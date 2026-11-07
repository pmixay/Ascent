package com.pmixay.ascent.wear

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.IBinder
import android.os.SystemClock
import com.pmixay.ascent.companion.CompanionWire
import java.io.IOException
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.max

/**
 * User-started microphone session over a secure RFCOMM link to one bonded phone.
 * Only encrypted frames enter the one-slot queue; neither audio nor keys reach disk.
 */
class WatchCaptureService : Service() {
    enum class Phase { IDLE, CONNECTING, LISTENING, PAUSED, ERROR }
    data class UiState(
        val phase: Phase = Phase.IDLE,
        val message: String = "Готово к разговору",
        val phoneName: String? = null,
        val startedElapsedMs: Long = 0,
        val stoppedElapsedMs: Long = 0,
        val droppedFrames: Long = 0,
        val latestResult: String = "",
    )

    private val active = AtomicBoolean(false)
    private val cleanupInProgress = AtomicBoolean(false)
    private val sessionGeneration = AtomicLong(0)
    private val stopAcknowledged = AtomicBoolean(false)
    private val paused = AtomicBoolean(false)
    private val dropped = AtomicLong(0)
    private val sequence = AtomicLong(0)
    private val captureGeneration = AtomicLong(0)
    private val pending = ArrayBlockingQueue<CompanionWire.Frame>(1)
    private val acknowledgements = ArrayBlockingQueue<CompanionWire.Ack>(1)
    private val controlLock = Any()
    @Volatile private var socket: BluetoothSocket? = null
    @Volatile private var recorder: AudioRecord? = null
    @Volatile private var hello: CompanionWire.Hello? = null
    @Volatile private var captureThread: Thread? = null
    @Volatile private var writerThread: Thread? = null
    @Volatile private var lastPhonePacketElapsedMs = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startSession(intent.getStringExtra(EXTRA_PHONE_ADDRESS))
            ACTION_PAUSE -> Thread { controlSafely(CompanionWire.PAUSE) }.start()
            ACTION_RESUME -> Thread { controlSafely(CompanionWire.RESUME) }.start()
            ACTION_STOP -> Thread { endSession(0, null) }.start()
        }
        return START_NOT_STICKY // A killed service cannot restart an old microphone session.
    }

    private fun startSession(address: String?) {
        if (!active.compareAndSet(false, true)) return
        // A previous session may still be joining timed-out reader threads. Do not let
        // those threads observe the new session as active.
        if (cleanupInProgress.get()) {
            active.set(false)
            return
        }
        val generation = sessionGeneration.incrementAndGet()
        stopAcknowledged.set(false)
        // startForegroundService() requires a visible foreground session even when the
        // start must fail, so become foreground before every validation and error path.
        try {
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(NotificationChannel(CHANNEL_ID, "Ascent: микрофон часов",
                NotificationManager.IMPORTANCE_LOW))
            startForeground(NOTIFICATION_ID, notification("Подключаюсь к телефону…"),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } catch (_: Exception) {
            active.set(false)
            showError("Не удалось запустить видимую сессию микрофона")
            stopSelf()
            return
        }
        val target = address?.takeIf { it.isNotBlank() }
        if (target == null ||
            checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED ||
            checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            active.set(false)
            showError("Нужны сопряжённый телефон и разрешения на микрофон и Bluetooth")
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }
        dropped.set(0)
        sequence.set(0)
        captureGeneration.set(0)
        paused.set(false)
        pending.clearAndZero()
        acknowledgements.clear()
        ui.set(UiState(phase = Phase.CONNECTING, message = "Подключаюсь к телефону…"))
        Thread({ connectAndListen(target, generation) }, "ascent-watch-link").start()
    }

    private fun sessionActive(generation: Long) = active.get() && sessionGeneration.get() == generation

    @SuppressLint("MissingPermission") // startSession checks BLUETOOTH_CONNECT before this thread starts.
    private fun connectAndListen(address: String, generation: Long) {
        val handshakeTimedOut = AtomicBoolean(false)
        try {
            val adapter = getSystemService(BluetoothManager::class.java).adapter
                ?: throw IOException("На часах нет Bluetooth")
            val phone = adapter.getRemoteDevice(address)
            if (phone.bondState != BluetoothDevice.BOND_BONDED) {
                throw IOException("Телефон больше не сопряжён")
            }
            ui.updateAndGet { it.copy(phoneName = phone.name ?: "Сопряжённый телефон") }
            val connection = phone.createRfcommSocketToServiceRecord(CompanionWire.SERVICE_UUID)
            socket = connection
            val handshakeFinished = AtomicBoolean(false)
            val breaker = Thread({
                try { Thread.sleep(HANDSHAKE_TIMEOUT_MS) }
                catch (_: InterruptedException) { return@Thread }
                if (handshakeFinished.compareAndSet(false, true)) {
                    handshakeTimedOut.set(true)
                    runCatching { connection.close() }
                }
            }, "ascent-watch-handshake-timeout").also { it.start() }
            val greeting: CompanionWire.Hello
            val reply: CompanionWire.Ready
            try {
                connection.connect()
                if (!sessionActive(generation)) return
                greeting = CompanionWire.hello(System.currentTimeMillis(), SystemClock.elapsedRealtime())
                hello = greeting
                CompanionWire.write(connection.outputStream, greeting)
                reply = CompanionWire.read(connection.inputStream) as? CompanionWire.Ready
                    ?: throw IOException("Телефон не подтвердил соединение")
                if (!handshakeFinished.compareAndSet(false, true)) {
                    throw IOException("Телефон не подтвердил соединение вовремя")
                }
            } finally {
                handshakeFinished.set(true)
                breaker.interrupt()
            }
            if (!reply.accepted) throw IOException(reply.message.ifBlank { "Телефон отклонил соединение" })
            if (!sessionActive(generation)) return
            lastPhonePacketElapsedMs = SystemClock.elapsedRealtime()
            val audio = newRecorder()
            recorder = audio
            audio.startRecording()
            if (audio.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                throw IOException("Микрофон часов не запустился")
            }
            ui.updateAndGet { it.copy(phase = Phase.LISTENING, message = "Слушаю с часов",
                startedElapsedMs = SystemClock.elapsedRealtime(), stoppedElapsedMs = 0) }
            updateNotification("Слушаю с часов")
            CompanionWire.write(connection.outputStream, CompanionWire.State(CompanionWire.STARTED, dropped.get()))
            writerThread = Thread({ writeFrames(connection, generation) }, "ascent-watch-writer").also { it.start() }
            captureThread = Thread({ captureFrames(audio, greeting, generation) }, "ascent-watch-capture").also { it.start() }
            readPhone(connection, generation)
            if (sessionActive(generation)) throw IOException("Соединение с телефоном прервано")
        } catch (error: Exception) {
            if (sessionActive(generation)) endSession(0, if (handshakeTimedOut.get())
                "Телефон не подтвердил соединение вовремя" else error.message ?: "Телефон недоступен", generation)
        } finally {
            if (sessionActive(generation)) endSession(0, "Связь с телефоном потеряна", generation)
        }
    }

    private fun newRecorder(): AudioRecord {
        val format = AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(CompanionWire.SAMPLE_RATE)
            .setChannelMask(AudioFormat.CHANNEL_IN_MONO).build()
        val minimum = AudioRecord.getMinBufferSize(CompanionWire.SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (minimum <= 0) throw IOException("Микрофон не поддерживает PCM 16 кГц")
        val audio = AudioRecord.Builder().setAudioSource(MediaRecorder.AudioSource.MIC)
            .setAudioFormat(format).setBufferSizeInBytes(max(minimum, CompanionWire.FRAME_SAMPLES * 4))
            .build()
        if (audio.state != AudioRecord.STATE_INITIALIZED) {
            audio.release()
            throw IOException("Не удалось подготовить микрофон")
        }
        return audio
    }

    private fun captureFrames(audio: AudioRecord, greeting: CompanionWire.Hello, generation: Long) {
        var nextOffset = 0L
        var runGeneration = -1L
        var runAnchorOffset = 0L
        var runSamples = 0L
        try {
            while (sessionActive(generation)) {
                if (paused.get()) {
                    Thread.sleep(50)
                    continue
                }
                val raw = ByteArray(CompanionWire.FRAME_SAMPLES * 2)
                try {
                    val captureRun = captureGeneration.get()
                    val capturedAt = SystemClock.elapsedRealtime()
                    var filled = 0
                    while (filled < raw.size && sessionActive(generation) && !paused.get()) {
                        val count = audio.read(raw, filled, raw.size - filled, AudioRecord.READ_BLOCKING)
                        if (count <= 0) {
                            if (!sessionActive(generation) || paused.get() || captureGeneration.get() != captureRun) break
                            throw IOException("Чтение с микрофона прервалось ($count)")
                        }
                        filled += count
                        if (captureGeneration.get() != captureRun) break
                    }
                    if (filled != raw.size || !sessionActive(generation) || paused.get() ||
                        captureGeneration.get() != captureRun) {
                        if (filled > 0 && sessionActive(generation)) markDropped()
                        continue
                    }
                    if (captureRun != runGeneration) {
                        runGeneration = captureRun
                        runAnchorOffset = max(nextOffset,
                            (capturedAt - greeting.elapsedMs).coerceAtLeast(0) *
                                CompanionWire.SAMPLE_RATE / 1000)
                        runSamples = 0
                    }
                    val offset = runAnchorOffset + runSamples
                    runSamples += CompanionWire.FRAME_SAMPLES
                    nextOffset = offset + CompanionWire.FRAME_SAMPLES
                    val frame = CompanionWire.encrypt(greeting, sequence.getAndIncrement(),
                        capturedAt, offset, raw, CompanionWire.FRAME_SAMPLES)
                    if (!pending.offer(frame)) {
                        frame.ciphertext.fill(0)
                        markDropped()
                    }
                } finally {
                    raw.fill(0) // No clear audio enters a queue, file or log.
                }
            }
        } catch (error: Exception) {
            if (sessionActive(generation)) endSession(0, error.message ?: "Микрофон остановился", generation)
        }
    }

    private fun writeFrames(connection: BluetoothSocket, generation: Long) {
        var lastReportedDrops = 0L
        var lastPing = 0L
        try {
            while (sessionActive(generation)) {
                val frame = pending.poll(100, TimeUnit.MILLISECONDS)
                val now = SystemClock.elapsedRealtime()
                if (now - lastPhonePacketElapsedMs > PHONE_TIMEOUT_MS) {
                    throw IOException("Телефон не отвечает")
                }
                val currentDrops = dropped.get()
                if (currentDrops != lastReportedDrops) {
                    CompanionWire.write(connection.outputStream, CompanionWire.State(CompanionWire.GAP, currentDrops))
                    if (!paused.get()) CompanionWire.write(connection.outputStream,
                        CompanionWire.State(CompanionWire.STARTED, currentDrops))
                    lastReportedDrops = currentDrops
                }
                if (frame == null) {
                    if (paused.get() && now - lastPing >= 1000) {
                        CompanionWire.write(connection.outputStream, CompanionWire.Ping)
                        lastPing = now
                    }
                    continue
                }
                try {
                    if (paused.get()) { markDropped(); continue }
                    val remaining = CompanionWire.MAX_FRAME_AGE_MS - (now - frame.capturedElapsedMs)
                    if (remaining <= 0) { markDropped(); continue }
                    CompanionWire.write(connection.outputStream, frame)
                    val deadline = frame.capturedElapsedMs + CompanionWire.MAX_FRAME_AGE_MS
                    var reply: CompanionWire.Ack? = null
                    while (sessionActive(generation) && reply == null) {
                        val waitMs = deadline - SystemClock.elapsedRealtime()
                        if (waitMs <= 0) break
                        val candidate = acknowledgements.poll(waitMs, TimeUnit.MILLISECONDS)
                        if (candidate?.sequence == frame.sequence) reply = candidate
                    }
                    if (!sessionActive(generation)) break
                    if (reply == null) throw IOException("Нет подтверждения аудиофрагмента")
                    if (!reply.accepted) markDropped()
                } finally { frame.ciphertext.fill(0) }
            }
        } catch (error: Exception) {
            if (sessionActive(generation)) endSession(0, error.message ?: "Соединение прервано", generation)
        }
    }

    private fun readPhone(connection: BluetoothSocket, generation: Long) {
        // After a normal stop, keep reading until the phone acknowledges the final
        // bounded window; the stopping thread closes the socket on timeout.
        while (sessionGeneration.get() == generation && (active.get() || !stopAcknowledged.get())) {
            val packet = CompanionWire.read(connection.inputStream)
            if (sessionGeneration.get() != generation) return
            lastPhonePacketElapsedMs = SystemClock.elapsedRealtime()
            when (packet) {
                is CompanionWire.Ack -> acknowledgements.offer(packet)
                is CompanionWire.Result -> ui.updateAndGet { it.copy(latestResult = packet.text) }
                is CompanionWire.Command -> if (packet.action == CompanionWire.STOP) {
                    Thread({ endSession(packet.id, null, generation) }, "ascent-watch-stop").start()
                } else applyCommand(packet.action, packet.id, generation)
                is CompanionWire.State -> {
                    if (packet.state != CompanionWire.STOPPED) throw IOException("Неожиданный пакет от телефона")
                    stopAcknowledged.set(true)
                    return
                }
                CompanionWire.Ping -> CompanionWire.write(connection.outputStream, CompanionWire.Pong)
                CompanionWire.Pong -> Unit
                else -> throw IOException("Неожиданный пакет от телефона")
            }
        }
    }

    private fun applyCommand(action: Int, commandId: Long, generation: Long = sessionGeneration.get()) {
        if (sessionGeneration.get() != generation) return
        if (action == CompanionWire.STOP) {
            Thread({ endSession(commandId, null, generation) }, "ascent-watch-stop").start()
            return
        }
        synchronized(controlLock) {
            if (!sessionActive(generation)) return
            val audio = recorder ?: return
            when (action) {
                CompanionWire.PAUSE -> if (!paused.get()) {
                    paused.set(true)
                    captureGeneration.incrementAndGet()
                    runCatching { audio.stop() }
                    while (true) {
                        val unsent = pending.poll() ?: break
                        unsent.ciphertext.fill(0)
                        markDropped()
                    }
                    ui.updateAndGet { it.copy(phase = Phase.PAUSED, message = "Пауза") }
                    updateNotification("Пауза")
                }
                CompanionWire.RESUME -> if (paused.get()) {
                    audio.startRecording()
                    captureGeneration.incrementAndGet()
                    paused.set(false)
                    ui.updateAndGet { it.copy(phase = Phase.LISTENING, message = "Слушаю с часов") }
                    updateNotification("Слушаю с часов")
                }
                else -> return
            }
            socket?.outputStream?.let { CompanionWire.write(it,
                CompanionWire.State(if (paused.get()) CompanionWire.PAUSED else CompanionWire.STARTED,
                    dropped.get(), commandId)) }
        }
    }

    private fun controlSafely(action: Int) {
        val generation = sessionGeneration.get()
        try { applyCommand(action, 0, generation) }
        catch (error: Exception) {
            if (sessionActive(generation)) endSession(0, error.message ?: "Команда не выполнена", generation)
        }
    }

    private fun markDropped() {
        val total = dropped.incrementAndGet()
        ui.updateAndGet { it.copy(droppedFrames = total) }
    }

    private fun endSession(
        commandId: Long,
        failure: String?,
        expectedGeneration: Long = sessionGeneration.get(),
    ) {
        if (expectedGeneration != sessionGeneration.get()) return
        if (!cleanupInProgress.compareAndSet(false, true)) return
        if (!active.compareAndSet(true, false)) {
            cleanupInProgress.set(false)
            return
        }
        try {
            paused.set(true)
            val audio = recorder
            runCatching { audio?.stop() }
            val connection = socket
            if (failure == null && connection != null) {
                // Keep the socket open long enough for the phone to finish the final
                // bounded window and acknowledge it. The breaker bounds every blocking call.
                val breaker = Thread {
                    val expired = try { Thread.sleep(STOP_DRAIN_TIMEOUT_MS); true }
                        catch (_: InterruptedException) { false }
                    if (expired) runCatching { connection.close() }
                }.also { it.start() }
                try {
                    runCatching { CompanionWire.write(connection.outputStream,
                        CompanionWire.State(CompanionWire.STOPPED, dropped.get(), commandId)) }
                    val deadline = SystemClock.elapsedRealtime() + STOP_DRAIN_TIMEOUT_MS
                    while (!stopAcknowledged.get() && SystemClock.elapsedRealtime() < deadline &&
                        sessionGeneration.get() == expectedGeneration) {
                        Thread.sleep(25)
                    }
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                } finally { breaker.interrupt() }
            }
            runCatching { connection?.close() }
            runCatching { captureThread?.takeUnless { it === Thread.currentThread() }?.join(800) }
            runCatching { writerThread?.takeUnless { it === Thread.currentThread() }?.join(800) }
            runCatching { audio?.release() }
            recorder = null
            socket = null
            pending.clearAndZero()
            acknowledgements.clear()
            hello?.key?.fill(0)
            hello = null
            val finished = SystemClock.elapsedRealtime()
            ui.updateAndGet { it.copy(phase = if (failure == null) Phase.IDLE else Phase.ERROR,
                message = failure ?: "Слушание остановлено", stoppedElapsedMs = finished) }
        } finally {
            cleanupInProgress.set(false)
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun ArrayBlockingQueue<CompanionWire.Frame>.clearAndZero() {
        while (true) {
            val frame = poll() ?: break
            frame.ciphertext.fill(0)
        }
    }

    private fun showError(message: String) {
        ui.updateAndGet { it.copy(phase = Phase.ERROR, message = message,
            stoppedElapsedMs = SystemClock.elapsedRealtime()) }
    }

    private fun notification(message: String): Notification {
        val destination = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_mic)
            .setContentTitle("Ascent. Микрофон часов")
            .setContentText(message)
            .setContentIntent(destination)
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .build()
    }

    private fun updateNotification(message: String) {
        runCatching { getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(message)) }
    }

    override fun onDestroy() {
        if (active.get()) endSession(0, "Сессия прервана системой", sessionGeneration.get())
        super.onDestroy()
    }

    companion object {
        const val ACTION_START = "com.pmixay.ascent.wear.START"
        const val ACTION_PAUSE = "com.pmixay.ascent.wear.PAUSE"
        const val ACTION_RESUME = "com.pmixay.ascent.wear.RESUME"
        const val ACTION_STOP = "com.pmixay.ascent.wear.STOP"
        const val EXTRA_PHONE_ADDRESS = "phoneAddress"
        private const val CHANNEL_ID = "ascent-watch-capture"
        private const val NOTIFICATION_ID = 117
        private const val PHONE_TIMEOUT_MS = 5_000L
        private const val HANDSHAKE_TIMEOUT_MS = 10_000L
        private const val STOP_DRAIN_TIMEOUT_MS = 22_000L
        private val ui = AtomicReference(UiState())
        fun snapshot(): UiState = ui.get()
    }
}
