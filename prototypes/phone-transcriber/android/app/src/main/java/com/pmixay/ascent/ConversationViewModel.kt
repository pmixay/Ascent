package com.pmixay.ascent

import android.app.Application
import android.Manifest
import android.content.pm.PackageManager
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
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import java.util.UUID

internal const val UNKNOWN_SPEAKER = "speaker_unknown"
internal const val PROCESSING_AUDIO_STATUS = "Обработка аудио…"
internal const val IMPORT_INTERRUPTED_STATUS =
    "Приложение закрылось во время обработки. Проверьте историю или выберите файл ещё раз."

data class ConversationState(
    val busy: Boolean = false,
    val processingAudio: Boolean = false,
    val historyReady: Boolean = false,
    val recording: Boolean = false,
    val status: String = "Готово к разговору",
    val sourceDisplayName: String? = null,
    val sourceDurationMs: Long? = null,
    val sourceFormat: String? = null,
    val cancellingProcessing: Boolean = false,
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
    val modelOnlineUpdates: Boolean = true,
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
    /** Last finished phone job only; volatile counters, never written into transcript/export/history. */
    val measurements: ProcessingMeasurementReport? = null,
    /** The language mode chosen for the next job; remembered across launches. */
    val recognitionMode: RecognitionMode = RecognitionSettings.DEFAULT,
    /**
     * The recognizer the last saved job actually used (for [RecognitionMode.AUTO], the probe's choice),
     * or null while a job runs and after a failed or cancelled one. Show this beside the result.
     */
    val recognizedMode: RecognitionMode? = null,
)

/** The data a job processes. The job closes [source] on every path. */
internal class OpenedAudio(val source: PcmWindowSource, val name: String?)

private class ProcessedAudio(
    val recognition: TranscriptionResult,
    val presentation: TextStageOutcome,
    val durationMs: Long,
    val name: String?,
)

/** A stopped listening session or imported file is processed once; only encrypted text remains afterwards. */
class ConversationViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application
    private val mutableState = MutableStateFlow(ConversationState(busy = true, status = "Открываю разговоры…",
        modelOnlineUpdates = app.checkSelfPermission(Manifest.permission.INTERNET) == PackageManager.PERMISSION_GRANTED,
        recognitionMode = RecognitionSettings.load(app)))
    val state = mutableState.asStateFlow()
    private val history = ConversationStore(app.filesDir)
    private val voices = VoiceProfileStore(app.filesDir)
    private val historyMutex = AudioWork.history
    private val pendingImports = PendingImportStore(app.filesDir)
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
            var revision = 0L
            WatchCompanion.state.collect { watch ->
                // Do not consume a watch revision before the initial history read. Publishing
                // readiness and the initial list under the same lock closes that startup race.
                if (!historyReady || watch.transcriptRevision == revision) return@collect
                try {
                    withContext(Dispatchers.IO) {
                        historyMutex.withLock {
                            val conversations = history.load()
                            revision = watch.transcriptRevision
                            mutableState.update { it.copy(conversations = conversations,
                                expandedConversationIds = it.expandedConversationIds.ifEmpty {
                                    conversations.firstOrNull()?.let { setOf(it.id) }.orEmpty()
                                }) }
                        }
                    }
                } catch (error: Exception) {
                    if (error is CancellationException) throw error
                    reportError(error)
                }
            }
        }
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    clearAbandonedAudio()
                    val interrupted = pendingImports.consumeInterrupted()
                    historyMutex.withLock {
                        val conversations = history.load()
                        voices.prune(conversations.map { it.id }.toSet())
                        historyReady = true
                        mutableState.update { it.copy(busy = false, historyReady = true, conversations = conversations,
                            expandedConversationIds = conversations.firstOrNull()?.let { setOf(it.id) }.orEmpty(),
                            status = if (interrupted) IMPORT_INTERRUPTED_STATUS else "Готово к разговору",
                            jobError = if (interrupted) JobError(JobErrorCategory.Interrupted, IMPORT_INTERRUPTED_STATUS) else null) }
                    }
                }
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
        if (!foreground || !historyReady || mutableState.value.busy || recorder != null || WatchCompanion.state.value.enabled) return
        // Refuse before listening starts, so a recording is never lost to a mode that cannot run.
        if (RecognitionSettings.blocked(mutableState.value.recognitionMode, multilingualDirectory(mutableState.value.modelDirectory))) {
            mutableState.update { it.copy(status = RecognitionSettings.MULTILINGUAL_MISSING) }
            return
        }
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
                waveform = List(52) { 0f }, status = "Слушаю разговор", jobError = null,
                sourceDisplayName = null, sourceDurationMs = null, sourceFormat = null, measurements = null, recognizedMode = null) }
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
                val file = active.stop()
                OpenedAudio(PcmWave(file), null)
            },
            cleanup = {
                // Cancellation can precede prepare(); always join/release the recorder before deleting its file.
                try { active.stop() } catch (_: Exception) { /* The main job reports capture failures. */ }
                if (audio.exists() && !audio.delete()) "Не удалось удалить временное аудио" else null
            },
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
            mutableState.update { it.copy(status = if (it.modelOnlineUpdates)
                "Модели ещё загружаются. Повторите импорт, когда они будут готовы."
                else "Встроенные модели ещё не готовы. Дождитесь подготовки в разделе «Модели».") }
            return
        }
        if (!historyReady || current.busy || current.recording || recorder != null || WatchCompanion.state.value.enabled) return
        if (RecognitionSettings.blocked(current.recognitionMode, multilingualDirectory(current.modelDirectory))) {
            mutableState.update { it.copy(status = RecognitionSettings.MULTILINGUAL_MISSING) }
            return
        }
        val conversationId = "import-${UUID.nameUUIDFromBytes(uri.toString().toByteArray(Charsets.UTF_8))}"
        val importedAtMs = System.currentTimeMillis()
        launchJob(
            id = UUID.randomUUID().toString(),
            conversationId = conversationId,
            imported = true,
            capturedAtMs = importRetentionStart(current.conversations.firstOrNull { it.id == conversationId }, importedAtMs),
            prepare = {
                val name = AudioSources.displayName(app, uri)
                OpenedAudio(AudioSources.open(app, uri), name)
            },
        )
    }

    /**
     * Chooses the language mode for the next job and remembers it. A running job keeps the mode it
     * started with, so changing it never affects work already in progress.
     */
    fun setRecognitionMode(mode: RecognitionMode) {
        if (mutableState.value.recognitionMode == mode) return
        RecognitionSettings.save(app, mode)
        mutableState.update { it.copy(recognitionMode = mode) }
    }

    /**
     * The installed Parakeet directory for the selected acoustic revision, or null. Only the offline build
     * installs it (the compact network build has none), and the job itself reads it from the model lease
     * instead, which protects the files from cleanup. This lookup only lets a start-time check refuse early.
     */
    private fun multilingualDirectory(acoustic: File?): File? =
        if (BuildConfig.EMBEDDED_MODELS && acoustic != null) EmbeddedModels.multilingualDirectory(acoustic) else null

    /** Requests cancellation of the running job. A native recognition call finishes before it takes effect. */
    fun cancelProcessing() {
        val job = activeJob ?: return
        if (!job.isActive) return
        mutableState.update { it.copy(status = "Отменяю обработку…", cancellingProcessing = true) }
        job.cancel()
    }

    /**
     * Runs one job to completion: open the source, recognize and attribute speakers, release the acoustic
     * models, apply the text stage, save the encrypted text. Handles are closed and the temporary state is
     * cleaned on success, failure and cancellation; a finished transcript is never lost to a later stage.
     */
    private fun launchJob(
        id: String,
        conversationId: String = id,
        imported: Boolean = false,
        capturedAtMs: Long,
        prepare: () -> OpenedAudio,
        cleanup: () -> String? = { null },
    ) {
        check(jobs.begin(id)) { "Обработка уже идёт" }
        val timings = ProcessingTimings()
        var measuredMediaDurationMs: Long? = null
        mutableState.update { it.copy(busy = true, processingAudio = true, recording = false, level = 0f,
            status = PROCESSING_AUDIO_STATUS, jobId = id, jobSource = null, processingState = ProcessingState.ReadingInput,
            jobProgress = null, jobError = null, textStage = null, recognizedMode = null, cancellingProcessing = false,
            sourceDisplayName = null, sourceDurationMs = null, sourceFormat = null, measurements = null) }
        // Freeze the selected mode for this job.
        val mode = mutableState.value.recognitionMode
        // Enter the try/finally immediately so clearing the ViewModel cannot skip cleanup.
        activeJob = viewModelScope.launch(start = CoroutineStart.UNDISPATCHED) {
            var saved = false
            var stage = ProcessingState.ReadingInput
            var finalState = ProcessingState.Failed
            var failure: JobError? = null
            var report: TextStageReport? = null
            var usedMode: RecognitionMode? = null
            var spoke = false
            try {
                withContext(NonCancellable + Dispatchers.IO) { pendingImports.begin(id) }
                // Acquire, use and close native/source handles in the same worker. No open descriptor
                // crosses a cancellable dispatcher return, where its value could otherwise be discarded.
                val queued = timings.enter(ProcessingMetricStage.QUEUE)
                val processed = try { AudioWork.inference.withLock {
                    queued.close()
                    runInterruptible(Dispatchers.Default) {
                        // Pin all three model groups across both stages, including cancellation.
                        timings.measure(ProcessingMetricStage.MODEL_VALIDATION) { ModelSelection.acquire(app) }.use { lease ->
                            val multilingual = lease.multilingualDirectory
                            // Refuse before opening the source, using files protected for the whole job.
                            check(!RecognitionSettings.blocked(mode, multilingual)) { RecognitionSettings.MULTILINGUAL_MISSING }
                            val opened = timings.measure(ProcessingMetricStage.SOURCE_OPEN) { prepare() }
                            val (recognized, mediaDurationMs) = MeasuredPcmWindowSource(opened.source, timings).use { source ->
                                if (Thread.currentThread().isInterrupted) throw InterruptedException("Обработка отменена")
                                val duration = source.durationMs
                                measuredMediaDurationMs = duration
                                jobs.whileCurrent(id) { mutableState.update { it.copy(jobSource = opened.name,
                                    sourceDisplayName = opened.name, sourceDurationMs = duration,
                                    sourceFormat = source.formatDescription) } }
                                val result = TranscriptionEngine(lease.directory, mode, multilingual, timings = timings).transcribeWithSpeakers(source) { message ->
                                    reportProgress(id, message)
                                }
                                result to duration
                            }
                            // Native acoustic sessions and the source are closed before the LLM opens.
                            if (Thread.currentThread().isInterrupted) throw InterruptedException("Обработка отменена")
                            stage = ProcessingState.Formatting
                            jobs.whileCurrent(id) { mutableState.update { it.copy(processingState = ProcessingState.Formatting, jobProgress = null) } }
                            val presented = timings.measure(ProcessingMetricStage.TEXT) {
                                TextStages.run(lease.textDirectory, recognized.segments, timings, mediaDurationMs)
                            }
                            ProcessedAudio(recognized, presented, mediaDurationMs, opened.name)
                        }
                    }
                } } finally { queued.close() }
                val result = processed.recognition
                val presented = processed.presentation
                val durationMs = processed.durationMs
                val sourceName = processed.name
                report = presented.report
                usedMode = result.recognitionMode
                spoke = presented.segments.isNotEmpty()
                currentCoroutineContext().ensureActive()
                stage = ProcessingState.Saving
                jobs.whileCurrent(id) { mutableState.update { it.copy(processingState = ProcessingState.Saving, jobProgress = null) } }
                // Once saving starts it finishes: the atomic file replacement is never cut in half.
                timings.enter(ProcessingMetricStage.SAVING).use { withContext(NonCancellable + Dispatchers.IO) {
                    val names = presented.segments.map { it.speakerId }.distinct().filter { it != UNKNOWN_SPEAKER }
                        .mapIndexed { index, speakerId -> speakerId to "Участник ${index + 1}" }.toMap()
                    historyMutex.withLock {
                        val existing = history.load()
                        val remembered = if (imported) emptyMap() else voices.match(conversationId, capturedAtMs,
                            result.signatures, existing.map { it.id }.toSet())
                        val conversation = SavedConversation(conversationId, capturedAtMs, durationMs, presented.segments,
                            names + remembered, sourceName)
                        history.save(existing.filterNot { it.id == conversationId } + conversation).also { retained ->
                            saved = true
                            mutableState.update { it.copy(conversations = retained, expandedConversationIds = setOf(conversationId), elapsedMs = 0) }
                            voices.prune(retained.map { it.id }.toSet())
                        }
                    }
                } }
                finalState = ProcessingState.Completed
            } catch (_: OutOfMemoryError) {
                if (!saved) failure = JobError(JobErrorCategory.OutOfMemory, MEMORY_MESSAGE)
            } catch (error: Throwable) {
                // A native-library Error fails this job with a message instead of ending the app. Anything
                // raised after cancellation (for example a read cut off by the interrupt) is a cancel.
                if (saved) finalState = ProcessingState.Completed
                else if (error is CancellationException) {
                    finalState = ProcessingState.Cancelled
                    throw error
                } else if (!currentCoroutineContext().isActive) finalState = ProcessingState.Cancelled
                else failure = describe(error, stage)
            } finally {
                var warning: String? = null
                try {
                    // Return from IO into a protected caller context. Returning directly into the
                    // cancelled job would turn successful cleanup into a CancellationException.
                    withContext(NonCancellable) {
                        timings.enter(ProcessingMetricStage.CLEANUP).use { withContext(Dispatchers.IO) {
                            try {
                                if (!saved && !imported) voices.discard(conversationId)
                                warning = cleanup()
                            } finally { pendingImports.complete(id) }
                        } }
                    }
                } catch (_: Exception) {
                    warning = "Не удалось завершить очистку состояния. Проверьте историю после перезапуска."
                } finally {
                    if (saved) finalState = ProcessingState.Completed
                    // Resuming from the IO dispatcher can deliver cancellation after cleanup.
                    val message = when (finalState) {
                        ProcessingState.Completed ->
                            (if (spoke) "Разговор готов" else "Речь не обнаружена") + (report?.let { ". ${it.describe()}" } ?: "")
                        ProcessingState.Cancelled -> "Обработка отменена"
                        else -> failure?.message ?: "Ошибка: операция не завершена"
                    }
                    val measurement = timings.finish(when (finalState) {
                        ProcessingState.Completed -> ProcessingMeasurementOutcome.COMPLETED
                        ProcessingState.Cancelled -> ProcessingMeasurementOutcome.CANCELLED
                        else -> ProcessingMeasurementOutcome.FAILED
                    }, measuredMediaDurationMs)
                    jobs.finish(id) {
                        mutableState.update { it.copy(busy = false, processingAudio = false, recording = false, level = 0f,
                            waveform = List(52) { 0f }, processingState = finalState, jobProgress = null,
                            jobError = failure, textStage = report, measurements = measurement,
                            recognizedMode = if (saved) usedMode else null,
                            cancellingProcessing = false, status = warning?.let { w -> "$message. $w" } ?: message) }
                    }
                }
            }
        }
    }

    private fun reportProgress(id: String, message: String) {
        val parsed = EngineProgress.parse(message) ?: return
        jobs.whileCurrent(id) {
            mutableState.update {
                if (it.cancellingProcessing) it
                else it.copy(processingState = parsed.first, jobProgress = parsed.second, status = message)
            }
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
                    val updated = history.load().map { conversation ->
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
        work(if (json) "Экспортирую JSON…" else "Экспортирую текст…",
            failure = "Не удалось экспортировать ${if (json) "JSON" else "текст"}. Расшифровка сохранена") {
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
                    val existing = history.load()
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

    private fun work(message: String, failure: String = "Ошибка", task: suspend () -> Unit) {
        if (!historyReady || mutableState.value.busy || mutableState.value.recording) return
        mutableState.update { it.copy(busy = true, status = message) }
        viewModelScope.launch {
            try { task() }
            catch (_: OutOfMemoryError) { reportMemoryError() }
            catch (error: Exception) {
                if (error is CancellationException) throw error
                reportError(error, failure)
            } finally { mutableState.update { it.copy(busy = false) } }
        }
    }

    private fun clearAbandonedAudio() {
        listOf(File(app.filesDir, "recordings"), File(app.cacheDir, "pending-audio")).forEach { root ->
            root.listFiles()?.filter { it.isFile }?.forEach { check(it.delete()) { "Не удалось удалить временное аудио" } }
        }
        app.getSharedPreferences("ascent", Application.MODE_PRIVATE).edit().remove("audioPath").apply()
    }

    private fun reportError(error: Exception, prefix: String = "Ошибка") {
        mutableState.update { it.copy(status = "$prefix: ${error.message ?: "операция не завершена"}") }
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
