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
internal const val INTERRUPTED_JOB_STATUS = "Предыдущая обработка была прервана. Выберите файл ещё раз."

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
    /** The latest or running job. [jobId] changes with every job; [jobError] is set only for a failed one. */
    val jobId: String? = null,
    val jobSource: String? = null,
    val processingState: ProcessingState = ProcessingState.Idle,
    /** Completed share of the current stage's media, or null while the amount of work is unknown. */
    val jobProgress: Float? = null,
    val jobError: JobError? = null,
    val textStage: TextStageReport? = null,
)

/** The data a job processes. The job closes [source] on every path. */
internal class OpenedAudio(val source: PcmWindowSource, val name: String?)

/** A stopped listening session or imported file is processed once; only encrypted text remains afterwards. */
class ConversationViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application
    private val mutableState = MutableStateFlow(ConversationState(busy = true, status = "Открываю разговоры…"))
    val state = mutableState.asStateFlow()
    private val history = ConversationStore(app.filesDir)
    private val voices = VoiceProfileStore(app.filesDir)
    private val historyMutex = Mutex()
    private val jobs = JobTracker()
    @Volatile private var activeJob: Job? = null
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
                var interrupted = false
                val conversations = withContext(Dispatchers.IO) {
                    // A process death cannot run a finally block. Remove abandoned audio on the next launch.
                    clearAbandonedAudio()
                    interrupted = JobMarker.takeInterrupted(app)
                    historyMutex.withLock {
                        history.load().also { voices.prune(it.map { conversation -> conversation.id }.toSet()) }
                    }
                }
                historyReady = true
                mutableState.update { it.copy(busy = false, historyReady = true, conversations = conversations,
                    expandedConversationIds = conversations.firstOrNull()?.let { setOf(it.id) }.orEmpty(),
                    status = if (interrupted) INTERRUPTED_JOB_STATUS else "Готово к разговору",
                    jobError = if (interrupted) JobError(JobErrorCategory.Interrupted, INTERRUPTED_JOB_STATUS) else null) }
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
                waveform = List(52) { 0f }, status = "Слушаю разговор", jobError = null) }
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
        recorder = null
        pendingAudio = null
        active.requestStop()
        meterJob?.cancel()
        launchJob(
            id = UUID.randomUUID().toString(),
            capturedAtMs = startedAtMs,
            prepare = {
                val file = withContext(NonCancellable + Dispatchers.IO) { active.stop() }
                currentCoroutineContext().ensureActive()
                OpenedAudio(runInterruptible(Dispatchers.IO) { PcmWave(file) }, null)
            },
            cleanup = { if (audio.exists() && !audio.delete()) "Не удалось удалить временное аудио" else null },
        )
    }

    /**
     * Imports a recording the user picked. The file is read in place through its content URI and never
     * copied; the original is neither modified nor deleted. [uri] is null when the picker was cancelled,
     * which changes nothing. Ignored while a job runs, so repeated taps cannot start two jobs.
     */
    fun importAudio(uri: Uri?) {
        if (uri == null) return
        val current = mutableState.value
        if (current.modelDirectory == null) {
            mutableState.update { it.copy(status = "Модели ещё загружаются. Повторите импорт, когда они будут готовы.") }
            return
        }
        if (!historyReady || current.busy || current.recording || recorder != null) return
        launchJob(
            id = UUID.randomUUID().toString(),
            // The import time starts retention: the original recording date is unknown and is not invented.
            capturedAtMs = System.currentTimeMillis(),
            prepare = {
                val name = runInterruptible(Dispatchers.IO) { AudioSources.displayName(app, uri) }
                OpenedAudio(runInterruptible(Dispatchers.IO) { AudioSources.open(app, uri) }, name)
            },
        )
    }

    /** Requests cancellation of the running job. A native recognition call finishes before it takes effect. */
    fun cancelProcessing() {
        val job = activeJob ?: return
        if (!job.isActive) return
        mutableState.update { it.copy(status = "Отменяю обработку…") }
        job.cancel()
    }

    /**
     * Runs one job to completion: open the source, recognize and attribute speakers, release the acoustic
     * models, apply the text stage, save the encrypted text. Handles are closed and the temporary state is
     * cleaned on success, failure and cancellation; a finished transcript is never lost to a later stage.
     */
    private fun launchJob(
        id: String,
        capturedAtMs: Long,
        prepare: suspend () -> OpenedAudio,
        cleanup: () -> String? = { null },
    ) {
        check(jobs.begin(id)) { "Обработка уже идёт" }
        mutableState.update { it.copy(busy = true, processingAudio = true, recording = false, level = 0f,
            status = PROCESSING_AUDIO_STATUS, jobId = id, jobSource = null, processingState = ProcessingState.ReadingInput,
            jobProgress = null, jobError = null, textStage = null) }
        // Enter the try/finally immediately so clearing the ViewModel cannot skip cleanup.
        activeJob = viewModelScope.launch(start = CoroutineStart.UNDISPATCHED) {
            var saved = false
            var stage = ProcessingState.ReadingInput
            var finalState = ProcessingState.Failed
            var failure: JobError? = null
            var report: TextStageReport? = null
            var spoke = false
            try {
                withContext(NonCancellable + Dispatchers.IO) { JobMarker.begin(app, id) }
                val opened = prepare()
                val acoustic: Pair<TranscriptionResult, Long> = try {
                    currentCoroutineContext().ensureActive()
                    val mediaDurationMs = opened.source.durationMs
                    jobs.whileCurrent(id) { mutableState.update { it.copy(jobSource = opened.name) } }
                    val recognized = runInterruptible(Dispatchers.Default) {
                        ModelSelection.acquire(app).use { lease ->
                            TranscriptionEngine(lease.directory).transcribeWithSpeakers(opened.source) { message ->
                                reportProgress(id, message)
                            }
                        }
                    }
                    Pair(recognized, mediaDurationMs)
                } finally {
                    // The audio is not needed after the acoustic passes; release the file at once.
                    runCatching { opened.source.close() }
                }
                val result = acoustic.first
                val durationMs = acoustic.second
                currentCoroutineContext().ensureActive()
                stage = ProcessingState.Formatting
                jobs.whileCurrent(id) { mutableState.update { it.copy(processingState = ProcessingState.Formatting, jobProgress = null) } }
                val presented = runInterruptible(Dispatchers.Default) { TextStages.run(app, result.segments) }
                report = presented.report
                spoke = presented.segments.isNotEmpty()
                currentCoroutineContext().ensureActive()
                stage = ProcessingState.Saving
                jobs.whileCurrent(id) { mutableState.update { it.copy(processingState = ProcessingState.Saving, jobProgress = null) } }
                // Once saving starts it finishes: the atomic file replacement is never cut in half.
                withContext(NonCancellable + Dispatchers.IO) {
                    val names = presented.segments.map { it.speakerId }.distinct().filter { it != UNKNOWN_SPEAKER }
                        .mapIndexed { index, speakerId -> speakerId to "Участник ${index + 1}" }.toMap()
                    historyMutex.withLock {
                        val existing = mutableState.value.conversations
                        val remembered = voices.match(id, capturedAtMs, result.signatures, existing.map { it.id }.toSet())
                        val conversation = SavedConversation(id, capturedAtMs, durationMs, presented.segments,
                            names + remembered, opened.name)
                        history.save(existing + conversation).also { retained ->
                            saved = true
                            mutableState.update { it.copy(conversations = retained, expandedConversationIds = setOf(id), elapsedMs = 0) }
                            voices.prune(retained.map { it.id }.toSet())
                        }
                    }
                }
                finalState = ProcessingState.Completed
            } catch (_: OutOfMemoryError) {
                failure = JobError(JobErrorCategory.OutOfMemory, MEMORY_MESSAGE)
            } catch (error: Throwable) {
                // A native-library Error fails this job with a message instead of ending the app. Anything
                // raised after cancellation (for example a read cut off by the interrupt) is a cancel.
                if (error is CancellationException) {
                    finalState = ProcessingState.Cancelled
                    throw error
                }
                if (!currentCoroutineContext().isActive) finalState = ProcessingState.Cancelled
                else failure = describe(error, stage)
            } finally {
                var warning: String? = null
                try {
                    withContext(NonCancellable + Dispatchers.IO) {
                        if (!saved) voices.discard(id)
                        warning = cleanup()
                        JobMarker.end(app, id)
                    }
                } finally {
                    // Resuming from the IO dispatcher can deliver cancellation after cleanup.
                    val message = when (finalState) {
                        ProcessingState.Completed ->
                            (if (spoke) "Разговор готов" else "Речь не обнаружена") + (report?.let { ". ${it.describe()}" } ?: "")
                        ProcessingState.Cancelled -> "Обработка отменена"
                        else -> failure?.message ?: "Ошибка: операция не завершена"
                    }
                    jobs.finish(id) {
                        mutableState.update { it.copy(busy = false, processingAudio = false, recording = false, level = 0f,
                            waveform = List(52) { 0f }, processingState = finalState, jobProgress = null,
                            jobError = failure, textStage = report, status = warning?.let { w -> "$message. $w" } ?: message) }
                    }
                }
            }
        }
    }

    private fun reportProgress(id: String, message: String) {
        val parsed = EngineProgress.parse(message) ?: return
        jobs.whileCurrent(id) {
            mutableState.update { it.copy(processingState = parsed.first, jobProgress = parsed.second) }
        }
    }

    private fun describe(error: Throwable, stage: ProcessingState): JobError = when {
        error is AudioImportException -> JobError(importFailureCategory(error.failure), error.message ?: "Не удалось прочитать файл")
        stage == ProcessingState.Saving ->
            JobError(JobErrorCategory.Storage, "Не удалось сохранить расшифровку. Освободите место на телефоне и повторите. " +
                "Сохранённые разговоры не изменены.")
        error is IllegalStateException && error.message?.startsWith("Модели") == true ->
            JobError(JobErrorCategory.ModelsUnavailable, error.message ?: "Модели ещё загружаются")
        else -> JobError(JobErrorCategory.Internal, "Ошибка: ${error.message ?: "операция не завершена"}")
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

    /**
     * Writes a user-chosen file. [json] selects the competition format (`transcript` with `speaker_id`,
     * `start_ms`, `end_ms`, `text`), which is also valid for a recording without speech. [uri] is null
     * when the picker was cancelled, which changes nothing. The whole document is built in memory first,
     * so a failure leaves the stored transcript untouched and the export can simply be repeated.
     */
    fun exportTranscript(conversationId: String, uri: Uri?, json: Boolean) {
        if (uri == null) return
        val conversation = mutableState.value.conversations.firstOrNull { it.id == conversationId } ?: return
        if (conversation.startedAtMs <= System.currentTimeMillis() - HISTORY_RETENTION_MS) return
        if (!json && conversation.segments.isEmpty()) return
        work("Сохраняю расшифровку…") {
            runInterruptible(Dispatchers.IO) {
                val text = if (json) CompetitionJsonExporter.toJson(conversation)
                else conversation.segments.joinToString("\n\n") {
                    "[${formatTime(it.startMs)} — ${formatTime(it.endMs)}] ${conversation.speakerName(it.speakerId)}\n${it.text}"
                }
                val output = app.contentResolver.openOutputStream(uri, "wt") ?: error("Не удалось создать файл")
                output.use { it.write(text.toByteArray(Charsets.UTF_8)); it.flush() }
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
