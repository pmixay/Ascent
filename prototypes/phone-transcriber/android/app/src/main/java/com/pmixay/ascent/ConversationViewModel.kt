package com.pmixay.ascent

import android.app.Application
import android.net.Uri
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
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
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import java.util.UUID

internal const val UNKNOWN_SPEAKER = "speaker_unknown"
internal const val PROCESSING_AUDIO_STATUS = "Обработка аудио…"

data class ConversationState(
    val busy: Boolean = false,
    val processingAudio: Boolean = false,
    val historyReady: Boolean = false,
    val recording: Boolean = false,
    val status: String = "Готово к разговору",
    val elapsedMs: Long = 0,
    val level: Float = 0f,
    val waveform: List<Float> = List(52) { 0f },
    val modelDirectory: File? = null,
    val modelsChecked: Boolean = false,
    val modelPhase: ModelTransferPhase = ModelTransferPhase.IDLE,
    val modelStatus: String = "Проверяю доступность…",
    val downloadingModels: Boolean = false,
    val modelDownloadBytes: Long = 0,
    val modelDownloadTotal: Long = 0,
    val modelVersionStatus: ModelVersionStatus = ModelVersionStatus.UNCHECKED,
    val modelLastCheckedAtMs: Long = 0,
    val conversations: List<SavedConversation> = emptyList(),
    val expandedConversationIds: Set<String> = emptySet(),
)

/** A stopped listening session is processed once; only encrypted text remains afterwards. */
class ConversationViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application
    private val mutableState = MutableStateFlow(ConversationState(busy = true, status = "Открываю разговоры…"))
    val state = mutableState.asStateFlow()
    private val history = ConversationStore(app.filesDir)
    private val voices = VoiceProfileStore(app.filesDir)
    private val historyMutex = Mutex()
    private var recorder: AudioRecorder? = null
    private var pendingAudio: File? = null
    private var captureStartedAtMs = 0L
    private var meterJob: Job? = null
    private var foreground = false
    private var historyReady = false

    init {
        ModelDownloads.schedule(app)
        viewModelScope.launch {
            ModelDownloads.observe(app).collect { models ->
                mutableState.update { it.copy(modelDirectory = models.directory, modelsChecked = true,
                    modelPhase = models.phase, modelStatus = models.message,
                    downloadingModels = models.downloading, modelDownloadBytes = models.completedBytes,
                    modelDownloadTotal = models.totalBytes, modelVersionStatus = models.versionStatus,
                    modelLastCheckedAtMs = models.lastCheckedAtMs) }
            }
        }
        viewModelScope.launch {
            try {
                val conversations = withContext(Dispatchers.IO) {
                    // A process death cannot run a finally block. Remove abandoned audio on the next launch.
                    clearAbandonedAudio()
                    historyMutex.withLock {
                        history.load().also { voices.prune(it.map { conversation -> conversation.id }.toSet()) }
                    }
                }
                historyReady = true
                mutableState.update { it.copy(busy = false, historyReady = true, conversations = conversations,
                    expandedConversationIds = conversations.firstOrNull()?.let { setOf(it.id) }.orEmpty(),
                    status = "Готово к разговору") }
            } catch (_: OutOfMemoryError) { reportMemoryError() }
            catch (error: Exception) {
                if (error is CancellationException) throw error
                reportError(error)
            } finally { mutableState.update { it.copy(busy = false) } }
        }
        viewModelScope.launch {
            while (isActive) {
                delay(60_000)
                expireHistory()
            }
        }
    }

    fun setForeground(visible: Boolean) {
        foreground = visible
        if (visible) {
            ModelDownloads.refresh()
            ModelDownloads.schedule(app)
            viewModelScope.launch { expireHistory() }
        } else stopRecording()
    }

    fun microphoneDenied() {
        mutableState.update { it.copy(status = "Разрешите доступ к микрофону, чтобы начать разговор.") }
    }

    fun startRecording() {
        if (mutableState.value.modelDirectory == null) return
        if (!foreground || !historyReady || mutableState.value.busy || recorder != null) return
        try {
            val folder = File(app.cacheDir, "pending-audio").apply { check(isDirectory || mkdirs()) }
            val output = File(folder, "${UUID.randomUUID()}.wav")
            val active = AudioRecorder(app, output) { viewModelScope.launch { stopRecording() } }
            active.start()
            recorder = active
            pendingAudio = output
            captureStartedAtMs = System.currentTimeMillis()
            val started = SystemClock.elapsedRealtime()
            mutableState.update { it.copy(recording = true, elapsedMs = 0, level = 0f,
                waveform = List(52) { 0f }, status = "Слушаю разговор") }
            meterJob = viewModelScope.launch {
                while (isActive) {
                    mutableState.update { it.copy(elapsedMs = SystemClock.elapsedRealtime() - started,
                        level = active.level, waveform = it.waveform.drop(1) + active.level) }
                    delay(100)
                }
            }
        } catch (_: OutOfMemoryError) { reportMemoryError() }
        catch (error: Exception) { reportError(error) }
    }

    fun stopRecording() {
        val active = recorder ?: return
        val audio = pendingAudio ?: return
        val startedAtMs = captureStartedAtMs
        val id = UUID.randomUUID().toString()
        recorder = null
        pendingAudio = null
        active.requestStop()
        meterJob?.cancel()
        mutableState.update { it.copy(recording = false, busy = true, processingAudio = true,
            level = 0f, status = PROCESSING_AUDIO_STATUS) }
        // Enter the try/finally immediately so clearing the ViewModel cannot skip recorder cleanup.
        viewModelScope.launch(start = CoroutineStart.UNDISPATCHED) {
            var saved = false
            try {
                val file = withContext(NonCancellable + Dispatchers.IO) { active.stop() }
                currentCoroutineContext().ensureActive()
                val result = runInterruptible(Dispatchers.Default) {
                    ModelSelection.acquire(app).use { lease ->
                        TranscriptionEngine(lease.directory).transcribeWithSpeakers(file) { }
                    }
                }
                val durationMs = (file.length() - 44).coerceAtLeast(0) * 1000 / (AudioRecorder.SAMPLE_RATE * 2)
                val names = result.segments.map { it.speakerId }.distinct().filter { it != UNKNOWN_SPEAKER }
                    .mapIndexed { index, speakerId -> speakerId to "Участник ${index + 1}" }.toMap()
                withContext(Dispatchers.IO) {
                    historyMutex.withLock {
                        val existing = mutableState.value.conversations
                        val remembered = voices.match(id, startedAtMs, result.signatures,
                            existing.map { it.id }.toSet())
                        val conversation = SavedConversation(id, startedAtMs, durationMs, result.segments, names + remembered)
                        history.save(existing + conversation).also { retained ->
                            saved = true
                            mutableState.update { it.copy(conversations = retained, expandedConversationIds = setOf(id),
                                status = if (result.segments.isEmpty()) "Речь не обнаружена" else "Разговор готов", elapsedMs = 0) }
                            voices.prune(retained.map { it.id }.toSet())
                        }
                    }
                }
            } catch (_: OutOfMemoryError) { reportMemoryError() }
            catch (error: Exception) {
                if (error is CancellationException) {
                    mutableState.update { it.copy(status = "Обработка отменена") }
                    throw error
                }
                reportError(error)
            } finally {
                try {
                    withContext(NonCancellable + Dispatchers.IO) {
                        if (!saved) voices.discard(id)
                        if (audio.exists() && !audio.delete()) {
                            mutableState.update { it.copy(status = "Не удалось удалить временное аудио") }
                        }
                    }
                } finally {
                    // Resuming from the IO dispatcher can deliver cancellation after cleanup.
                    mutableState.update { it.copy(busy = false, processingAudio = false, recording = false, level = 0f,
                        waveform = List(52) { 0f }) }
                }
            }
        }
    }

    fun toggleConversation(id: String) {
        mutableState.update { state -> state.copy(expandedConversationIds =
            if (id in state.expandedConversationIds) state.expandedConversationIds - id else state.expandedConversationIds + id) }
    }

    fun renameSpeaker(conversationId: String, speakerId: String, name: String) {
        val clean = name.trim().take(80)
        if (speakerId == UNKNOWN_SPEAKER || clean.isEmpty()) return
        work("Сохраняю имя участника…") {
            var learned = false
            val retained = withContext(Dispatchers.IO) {
                historyMutex.withLock {
                    val updated = mutableState.value.conversations.map { conversation ->
                        if (conversation.id == conversationId) conversation.copy(speakerNames = conversation.speakerNames + (speakerId to clean))
                        else conversation
                    }
                    history.save(updated).also { retained ->
                        mutableState.update { it.copy(conversations = retained) }
                        voices.prune(retained.map { conversation -> conversation.id }.toSet())
                        learned = voices.remember(conversationId, speakerId, clean)
                    }
                }
            }
            mutableState.update { it.copy(conversations = retained, status =
                if (learned) "Имя сохранено. Голос запомнен для следующих разговоров." else "Имя участника сохранено") }
        }
    }

    fun exportTranscript(conversationId: String, uri: Uri, json: Boolean) {
        val conversation = mutableState.value.conversations.firstOrNull { it.id == conversationId } ?: return
        if (conversation.segments.isEmpty() || conversation.startedAtMs <= System.currentTimeMillis() - HISTORY_RETENTION_MS) return
        work("Сохраняю расшифровку…") {
            runInterruptible(Dispatchers.IO) {
                val text = if (json) ConversationStore.conversationJson(conversation).put("schemaVersion", 1).toString(2)
                else conversation.segments.joinToString("\n\n") {
                    "[${formatTime(it.startMs)} — ${formatTime(it.endMs)}] ${conversation.speakerName(it.speakerId)}\n${it.text}"
                }
                app.contentResolver.openOutputStream(uri, "wt")?.bufferedWriter(Charsets.UTF_8)?.use { it.write(text) }
                    ?: error("Не удалось создать файл")
            }
            mutableState.update { it.copy(status = "Расшифровка экспортирована") }
        }
    }

    private suspend fun expireHistory() {
        if (!historyReady || mutableState.value.busy || mutableState.value.recording) return
        try {
            withContext(Dispatchers.IO) {
                historyMutex.withLock {
                    if (mutableState.value.busy || mutableState.value.recording) return@withLock
                    val existing = mutableState.value.conversations
                    val retained = ConversationStore.retained(existing, System.currentTimeMillis())
                    if (retained != existing) {
                        history.save(retained)
                        mutableState.update { it.copy(conversations = retained,
                            expandedConversationIds = it.expandedConversationIds.intersect(retained.map { conversation -> conversation.id }.toSet())) }
                        voices.prune(retained.map { it.id }.toSet())
                    }
                }
            }
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            reportError(error)
        }
    }

    private fun work(message: String, task: suspend () -> Unit) {
        if (!historyReady || mutableState.value.busy || mutableState.value.recording) return
        mutableState.update { it.copy(busy = true, status = message) }
        viewModelScope.launch {
            try { task() }
            catch (_: OutOfMemoryError) { reportMemoryError() }
            catch (error: Exception) {
                if (error is CancellationException) throw error
                reportError(error)
            } finally { mutableState.update { it.copy(busy = false) } }
        }
    }

    private fun clearAbandonedAudio() {
        listOf(File(app.filesDir, "recordings"), File(app.cacheDir, "pending-audio")).forEach { root ->
            root.listFiles()?.filter { it.isFile }?.forEach { check(it.delete()) { "Не удалось удалить временное аудио" } }
        }
        app.getSharedPreferences("ascent", Application.MODE_PRIVATE).edit().remove("audioPath").apply()
    }

    private fun reportError(error: Exception) {
        mutableState.update { it.copy(status = "Ошибка: ${error.message ?: "операция не завершена"}") }
    }
    private fun reportMemoryError() { mutableState.update { it.copy(status = MEMORY_MESSAGE) } }

    override fun onCleared() {
        recorder?.let { active ->
            val audio = pendingAudio
            active.requestStop()
            val cleanup = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            cleanup.launch {
                try { active.stop() }
                catch (_: Exception) { /* The screen has gone away; only cleanup remains. */ }
                finally { audio?.delete(); cleanup.cancel() }
            }
        }
        recorder = null
        pendingAudio = null
        super.onCleared()
    }

    companion object {
        private const val MEMORY_MESSAGE = "Недостаточно памяти. Закройте другие приложения и попробуйте более короткий разговор."
    }
}

internal fun formatTime(milliseconds: Long): String {
    val seconds = milliseconds.coerceAtLeast(0) / 1000
    return String.format(Locale.ROOT, "%02d:%02d:%02d", seconds / 3600, seconds / 60 % 60, seconds % 60)
}
