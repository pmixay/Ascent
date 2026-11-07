package com.pmixay.ascent

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.IBinder
import android.os.SystemClock
import com.pmixay.ascent.companion.CompanionWire
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs

data class WatchPhoneState(
    val enabled: Boolean = false,
    val connected: Boolean = false,
    val listening: Boolean = false,
    val paused: Boolean = false,
    val pendingCommand: Boolean = false,
    val droppedFrames: Long = 0,
    val message: String = "Приём с часов выключен",
    val lastText: String = "",
    val transcriptRevision: Long = 0,
)

internal object WatchCompanion {
    private val mutableState = MutableStateFlow(WatchPhoneState())
    val state = mutableState.asStateFlow()
    @Volatile var service: WatchCompanionService? = null
    fun update(change: (WatchPhoneState) -> WatchPhoneState) = mutableState.update(change)
    fun enable(context: Context) { context.startForegroundService(Intent(context, WatchCompanionService::class.java)) }
    fun disable(context: Context) { context.stopService(Intent(context, WatchCompanionService::class.java)) }
    fun command(action: Int) { service?.requestCommand(action) }
}

/** A visible phone opt-in owns a direct secure Bluetooth listener; it never starts a microphone. */
class WatchCompanionService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var server: BluetoothServerSocket? = null
    @Volatile private var socket: BluetoothSocket? = null
    @Volatile private var commandId = 0L
    private val history by lazy { ConversationStore(filesDir) }

    @SuppressLint("MissingPermission") // Runtime permission is checked before any Bluetooth call.
    override fun onCreate() {
        super.onCreate()
        val notifications = getSystemService(NotificationManager::class.java)
        notifications.createNotificationChannel(NotificationChannel(CHANNEL, "Связь с часами", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        startForeground(NOTIFICATION_ID, Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now).setContentTitle("Ascent — часы")
            .setContentText("Приём с часов включён").setContentIntent(open).setOngoing(true).build())
        if (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            fail("Разрешите подключение к устройствам поблизости")
            return
        }
        val adapter = getSystemService(BluetoothManager::class.java)?.adapter
        if (adapter == null || !adapter.isEnabled) { fail("Включите Bluetooth на телефоне"); return }
        WatchCompanion.service = this
        WatchCompanion.update { it.copy(enabled = true, message = "Откройте Ascent на часах и начните слушать") }
        scope.launch {
            try {
                server = adapter.listenUsingRfcommWithServiceRecord("Ascent capture", CompanionWire.SERVICE_UUID)
                while (isActive) {
                    val client = server?.accept() ?: break
                    try {
                        if (client.remoteDevice.bondState != BluetoothDevice.BOND_BONDED) { client.close(); continue }
                        socket = client
                        receive(client)
                    } catch (error: Exception) {
                        if (error is CancellationException) throw error
                        WatchCompanion.update { it.copy(message = "Связь с часами прервана. Запустите слушание заново.") }
                    } finally {
                        runCatching { client.close() }
                        socket = null
                        WatchCompanion.update { it.copy(connected = false, listening = false, paused = false, pendingCommand = false) }
                    }
                }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                fail("Не удалось открыть связь с часами")
            }
        }
    }

    private suspend fun receive(client: BluetoothSocket) {
        val input = client.inputStream
        val output = client.outputStream
        val lastReceived = AtomicLong(SystemClock.elapsedRealtime())
        val watchdog = scope.launch {
            while (isActive) {
                delay(500)
                if (SystemClock.elapsedRealtime() - lastReceived.get() > 5_000) {
                    runCatching { client.close() }
                    break
                }
            }
        }
        var hello: CompanionWire.Hello? = null
        try {
            val greeting = CompanionWire.read(input) as? CompanionWire.Hello ?: throw IOException("Invalid watch handshake")
            hello = greeting
            val receivedAt = SystemClock.elapsedRealtime()
            require(greeting.epochMs > 0 && greeting.elapsedMs >= 0)
            require(abs(System.currentTimeMillis() - greeting.epochMs) <= 60_000) {
                "Проверьте время на телефоне и часах"
            }
            lastReceived.set(receivedAt)
            if (ModelSelection.current(this) == null) {
                CompanionWire.write(output, CompanionWire.Ready(false, "Дождитесь подготовки моделей на телефоне"))
                return
            }
            val offset = receivedAt - greeting.elapsedMs
            val session = Session(greeting, client, offset)
            CompanionWire.write(output, CompanionWire.Ready(true, "Телефон готов"))
            WatchCompanion.update { it.copy(connected = true, listening = false, droppedFrames = 0,
                message = "Часы подключены", lastText = "") }
            val maintenance = scope.launch {
                while (isActive) {
                    delay(250)
                    session.maintain()
                }
            }
            try {
                while (scope.isActive) {
                    val packet = CompanionWire.read(input)
                    lastReceived.set(SystemClock.elapsedRealtime())
                    when (packet) {
                        is CompanionWire.Frame -> session.accept(packet)
                        is CompanionWire.State -> {
                            require(packet.state in CompanionWire.STARTED..CompanionWire.GAP && packet.droppedFrames >= 0)
                            val acknowledged = packet.commandId == commandId
                            WatchCompanion.update { it.copy(
                                listening = if (packet.state == CompanionWire.GAP) it.listening else packet.state == CompanionWire.STARTED,
                                paused = if (packet.state == CompanionWire.GAP) it.paused else packet.state == CompanionWire.PAUSED,
                                pendingCommand = if (acknowledged) false else it.pendingCommand,
                                droppedFrames = maxOf(it.droppedFrames, packet.droppedFrames),
                                message = when (packet.state) {
                                    CompanionWire.STARTED -> "Источник: микрофон часов"
                                    CompanionWire.PAUSED -> "Слушание на паузе"
                                    CompanionWire.STOPPED -> "Микрофон часов остановлен"
                                    else -> "Часть аудио пропущена"
                                }) }
                            if (packet.state == CompanionWire.PAUSED) session.flush()
                            if (packet.state == CompanionWire.STOPPED) {
                                // Let the final bounded window finish before closing the socket.
                                // The explicit acknowledgement also tells the watch to stop reading.
                                watchdog.cancel()
                                session.finish()
                                runCatching {
                                    CompanionWire.write(output, CompanionWire.State(CompanionWire.STOPPED,
                                        WatchCompanion.state.value.droppedFrames, packet.commandId))
                                }
                                break
                            }
                        }
                        CompanionWire.Ping -> CompanionWire.write(output, CompanionWire.Pong)
                        CompanionWire.Pong -> Unit
                        else -> throw IOException("Unexpected watch packet")
                    }
                }
            } finally {
                maintenance.cancel()
                session.discard()
            }
        } finally { watchdog.cancel(); hello?.key?.fill(0) }
    }

    private inner class Session(
        val hello: CompanionWire.Hello,
        val client: BluetoothSocket,
        val clockOffset: Long,
    ) {
        private val frames = mutableListOf<CompanionWire.Frame>()
        private var samples = 0L
        private var lastSequence = -1L
        private var lastOffsetEnd = 0L
        @Volatile private var processing = false
        private var worker: Job? = null
        private var workerDeadline = 0L
        private var discarded = false

        @Synchronized
        fun accept(frame: CompanionWire.Frame) {
            var retained = false
            try {
                require(!discarded && frame.sequence > lastSequence && frame.sampleOffset >= lastOffsetEnd &&
                    frame.sampleOffset <= CompanionWire.SAMPLE_RATE * 86_400L)
                require(frame.capturedElapsedMs >= hello.elapsedMs)
                val captureOffset = (frame.capturedElapsedMs - hello.elapsedMs) * CompanionWire.SAMPLE_RATE / 1000
                require(abs(captureOffset - frame.sampleOffset) <= CompanionWire.SAMPLE_RATE) {
                    "Некорректное время аудиофрагмента"
                }
                lastSequence = frame.sequence
                lastOffsetEnd = frame.sampleOffset + frame.sampleCount
                val age = SystemClock.elapsedRealtime() - (frame.capturedElapsedMs + clockOffset)
                if (age < -500 || age > CompanionWire.MAX_FRAME_AGE_MS || processing) {
                    gap(); CompanionWire.write(client.outputStream, CompanionWire.Ack(frame.sequence, false)); return
                }
                CompanionWire.decrypt(hello, frame).fill(0) // Authenticate before retaining encrypted bytes.
                val previous = frames.lastOrNull()
                if (previous != null && abs(frame.sampleOffset - previous.sampleOffset - previous.sampleCount) > 160) {
                    gap(); flush()
                }
                if (processing) { gap(); CompanionWire.write(client.outputStream, CompanionWire.Ack(frame.sequence, false)); return }
                frames += frame
                samples += frame.sampleCount
                retained = true
                CompanionWire.write(client.outputStream, CompanionWire.Ack(frame.sequence, true))
                if (samples >= CompanionWire.WINDOW_SAMPLES) flush()
            } finally { if (!retained) frame.ciphertext.fill(0) }
        }

        @Synchronized
        fun flush() {
            if (discarded || frames.isEmpty()) return
            val batch = frames.toList()
            frames.clear(); samples = 0
            if (processing || !AudioWork.inference.tryLock()) {
                batch.forEach { it.ciphertext.fill(0) }; gap(batch.size.toLong()); return
            }
            processing = true
            val first = batch.first()
            val deadline = first.capturedElapsedMs + clockOffset + MAX_AUDIO_LIFETIME_MS
            workerDeadline = deadline
            val window = WatchPcmWindow(hello.copy(key = hello.key.copyOf()), batch, deadline)
            val offsetMs = first.sampleOffset * 1000 / CompanionWire.SAMPLE_RATE
            val released = AtomicBoolean(false)
            fun release() {
                if (released.compareAndSet(false, true)) {
                    window.close()
                    processing = false
                    AudioWork.inference.unlock()
                }
            }
            // Start after registering completion cleanup: cancellation before the body starts
            // must also erase the window and release the shared inference lock.
            worker = scope.launch(Dispatchers.Default, start = CoroutineStart.LAZY) {
                try {
                    val remaining = deadline - SystemClock.elapsedRealtime()
                    check(remaining > 0) { "Фрагмент с часов устарел" }
                    val result = withTimeout(remaining) {
                        runInterruptible {
                            ModelSelection.acquire(this@WatchCompanionService).use { lease ->
                                TranscriptionEngine(lease.directory).transcribeWithSpeakers(window) { }
                            }
                        }
                    }
                    val prefix = "watch_${first.sampleOffset}_"
                    val segments = result.segments.map { it.copy(speakerId = prefix + it.speakerId,
                        startMs = offsetMs + it.startMs, endMs = offsetMs + it.endMs) }
                    currentCoroutineContext().ensureActive()
                    withContext(NonCancellable + Dispatchers.IO) {
                        AudioWork.history.withLock {
                            val all = history.load()
                            val id = "watch-${hello.session}"
                            val old = all.firstOrNull { it.id == id }
                            val names = old?.speakerNames.orEmpty().toMutableMap()
                            segments.forEach { if (it.speakerId !in names) names[it.speakerId] = "Участник ${names.size + 1}" }
                            // A watch clock may be ahead; never store a future capture start that
                            // the retention filter would immediately discard.
                            val startedAtMs = old?.startedAtMs
                                ?: minOf(hello.epochMs, System.currentTimeMillis())
                            val updated = SavedConversation(id, startedAtMs,
                                maxOf(old?.durationMs ?: 0, offsetMs + window.durationMs), old?.segments.orEmpty() + segments, names)
                            val retained = history.save(all.filterNot { it.id == id } + updated)
                            check(retained.any { it.id == id }) { "Расшифровка с часов не сохранилась" }
                        }
                    }
                    val text = segments.joinToString(" ") { it.text }.take(600).ifEmpty { "Речь не обнаружена" }
                    WatchCompanion.update { it.copy(lastText = text, transcriptRevision = it.transcriptRevision + 1) }
                    runCatching { CompanionWire.write(client.outputStream, CompanionWire.Result(text)) }
                } catch (error: Exception) {
                    if (error !is CancellationException) WatchCompanion.update { it.copy(message = "Фрагмент с часов не обработан") }
                    gap(batch.size.toLong())
                } finally {
                    release()
                }
            }.apply { invokeOnCompletion { release() }; start() }
        }

        @Synchronized
        fun maintain() {
            val first = frames.firstOrNull() ?: return
            val age = SystemClock.elapsedRealtime() - (first.capturedElapsedMs + clockOffset)
            if (age >= MAX_AUDIO_LIFETIME_MS) {
                gap(frames.size.toLong())
                frames.forEach { it.ciphertext.fill(0) }; frames.clear(); samples = 0
            } else if (age >= 8_000) flush()
        }

        suspend fun finish() {
            flush()
            val activeWorker = synchronized(this) { if (discarded) null else worker }
            val remaining = workerDeadline - SystemClock.elapsedRealtime()
            if (activeWorker != null && remaining > 0) {
                withTimeoutOrNull(remaining) { activeWorker.join() }
            }
        }

        @Synchronized
        fun discard() {
            discarded = true
            worker?.cancel()
            if (frames.isNotEmpty()) gap(frames.size.toLong())
            frames.forEach { it.ciphertext.fill(0) }; frames.clear(); samples = 0
        }
        private fun gap(count: Long = 1) {
            WatchCompanion.update { it.copy(droppedFrames = it.droppedFrames + count) }
        }
    }

    internal fun requestCommand(action: Int) {
        val client = socket ?: return
        val id = SystemClock.elapsedRealtime()
        commandId = id
        WatchCompanion.update { it.copy(pendingCommand = true, message = "Ожидаю подтверждения часов…") }
        scope.launch {
            try { CompanionWire.write(client.outputStream, CompanionWire.Command(action, id)) }
            catch (_: IOException) { runCatching { client.close() } }
        }
    }

    private fun fail(message: String) { WatchCompanion.update { it.copy(message = message) }; stopSelf() }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int) = START_NOT_STICKY
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onDestroy() {
        if (WatchCompanion.service === this) WatchCompanion.service = null
        runCatching { server?.close() }; runCatching { socket?.close() }
        scope.cancel()
        WatchCompanion.update { it.copy(enabled = false, connected = false, listening = false, paused = false, pendingCommand = false) }
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL = "ascent-watch"
        private const val NOTIFICATION_ID = 82
        private const val MAX_AUDIO_LIFETIME_MS = 20_000L
    }
}
