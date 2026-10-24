package com.pmixay.ascent

import android.annotation.SuppressLint
import android.app.Application
import android.net.Uri
import android.os.SystemClock
import android.util.AtomicFile
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale

internal const val UNKNOWN_SPEAKER = "speaker_unknown"
internal const val UNKNOWN_SPEAKER_NAME = "Говорящий не определён"

data class ConversationState(
    val busy: Boolean = false,
    val recording: Boolean = false,
    val status: String = "Готово к записи",
    val elapsedMs: Long = 0,
    val level: Float = 0f,
    val waveform: List<Float> = List(52) { 0f },
    val audioFile: File? = null,
    val modelDirectory: File? = null,
    val downloadingModels: Boolean = false,
    val cancellingModelDownload: Boolean = false,
    val modelDownloadBytes: Long = 0,
    val modelDownloadTotal: Long = 0,
    val segments: List<TranscriptSegment> = emptyList(),
    val speakerNames: Map<String, String> = emptyMap(),
) {
    val participantCount: Int get() = speakerNames.keys.count { it != UNKNOWN_SPEAKER }
    fun speakerName(id: String): String = if (id == UNKNOWN_SPEAKER) UNKNOWN_SPEAKER_NAME else speakerNames[id] ?: id
}

/** Owns recordings and inference across recomposition/configuration changes. */
class ConversationViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application
    private val preferences = app.getSharedPreferences("ascent", Application.MODE_PRIVATE)
    private val mutableState = MutableStateFlow(ConversationState(busy = true, status = "Открываю записи…"))
    val state = mutableState.asStateFlow()
    private var recorder: AudioRecorder? = null
    private var meterJob: Job? = null
    private var modelDownloadJob: Job? = null
    private var foreground = false
    private val transcriptMutex = Mutex()
    private val transcriptFile get() = AtomicFile(File(app.filesDir, "latest-transcript.json"))

    init {
        viewModelScope.launch {
            try {
                mutableState.value = withContext(Dispatchers.IO) { restore() }
            } catch (_: OutOfMemoryError) {
                mutableState.value = ConversationState(status = MEMORY_MESSAGE)
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                mutableState.value = ConversationState(status = "Не удалось открыть сохранённую запись: ${error.message}")
            }
        }
    }

    fun setForeground(visible: Boolean) {
        foreground = visible
        if (!visible) {
            stopRecording(recognize = false)
            cancelModelDownload()
        }
    }

    fun microphoneDenied() { mutableState.update { it.copy(status = "Доступ к микрофону не разрешён. Можно открыть WAV.") } }

    fun startRecording() {
        if (!foreground || mutableState.value.busy || recorder != null) return
        try {
            val folder = File(app.filesDir, "recordings").apply { mkdirs() }
            val output = File(folder, "conversation-${System.currentTimeMillis()}.wav")
            val active = AudioRecorder(app, output) { viewModelScope.launch { stopRecording(recognize = false) } }
            active.start()
            recorder = active
            val started = SystemClock.elapsedRealtime()
            mutableState.update { it.copy(recording = true, elapsedMs = 0, level = 0f,
                waveform = List(52) { 0f }, status = "Идёт запись на этом устройстве") }
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

    fun stopRecording(recognize: Boolean = true) {
        val active = recorder ?: return
        recorder = null
        active.requestStop()
        meterJob?.cancel()
        mutableState.update { it.copy(recording = false, busy = true, level = 0f, status = "Сохраняю запись…") }
        viewModelScope.launch {
            var startRecognition = false
            try {
                // Finalize the WAV even if the Activity/ViewModel is destroyed during stop.
                val file = withContext(NonCancellable + Dispatchers.IO) {
                    active.stop().also { installSelection("audioPath", it, File(app.filesDir, "recordings")) }
                }
                replaceAudio(file)
                mutableState.update { it.copy(status = "Запись сохранена на устройстве") }
                startRecognition = recognize && foreground && isActive && mutableState.value.modelDirectory != null
                if (recognize && mutableState.value.modelDirectory == null) {
                    mutableState.update { it.copy(status = "Запись сохранена. Скачайте модели для расшифровки.") }
                }
            } catch (_: OutOfMemoryError) { reportMemoryError() }
            catch (error: Exception) {
                if (error is CancellationException) throw error
                reportError(error)
            } finally { mutableState.update { it.copy(busy = false) } }
            if (startRecognition) transcribe()
        }
    }

    fun importAudio(uri: Uri) = work("Проверяю WAV…", {
        runInterruptible(Dispatchers.IO) {
            AudioImporter.importWav(app, uri).also { installSelection("audioPath", it, File(app.filesDir, "recordings")) }
        }
    }) { file -> replaceAudio(file); mutableState.update { it.copy(status = "Запись импортирована") } }

    fun downloadModels() {
        if (!foreground || mutableState.value.busy || mutableState.value.recording) return
        mutableState.update { it.copy(busy = true, downloadingModels = true, cancellingModelDownload = false,
            modelDownloadBytes = 0, modelDownloadTotal = 0, status = "Проверяю доступность моделей…") }
        val root = File(app.filesDir, "models")
        val operation = ModelDownloader(root)
        modelDownloadJob = viewModelScope.launch {
            var installed = false
            try {
                val directory = runInterruptible(Dispatchers.IO) {
                    operation.download { progress ->
                        mutableState.update {
                            if (it.cancellingModelDownload) it else it.copy(
                                modelDownloadBytes = progress.completedBytes, modelDownloadTotal = progress.totalBytes,
                                status = if (progress.verifying) "Проверяю и устанавливаю модели…" else "Скачиваю модели…")
                        }
                    }
                }
                currentCoroutineContext().ensureActive()
                // Once installation begins, commit the selection and transaction together.
                withContext(NonCancellable + Dispatchers.IO) {
                    installSelection("modelPath", directory, root)
                    operation.commit()
                    installed = true
                    mutableState.update { it.copy(modelDirectory = directory, status = "Модели готовы. Распознавание работает без интернета.") }
                }
            } catch (error: CancellationException) {
                if (!installed) mutableState.update { it.copy(status = "Загрузка отменена. Её можно запустить снова.") }
                throw error
            } catch (_: OutOfMemoryError) { reportMemoryError() }
            catch (error: Exception) { reportError(error) }
            finally {
                withContext(NonCancellable + Dispatchers.IO) { operation.close() }
                modelDownloadJob = null
                mutableState.update { it.copy(busy = false, downloadingModels = false, cancellingModelDownload = false) }
            }
        }
    }

    fun cancelModelDownload() {
        modelDownloadJob?.let {
            mutableState.update { state -> state.copy(cancellingModelDownload = true, status = "Останавливаю загрузку…") }
            it.cancel()
        }
    }

    fun transcribe() {
        val snapshot = mutableState.value
        val audio = snapshot.audioFile ?: return
        val models = snapshot.modelDirectory ?: return
        if (!foreground) return
        work("Определяю участников…", {
            val result = runInterruptible(Dispatchers.Default) {
                TranscriptionEngine(models).transcribe(audio) { message -> mutableState.update { it.copy(status = message) } }
            }
            val names = result.map { it.speakerId }.distinct().filter { it != UNKNOWN_SPEAKER }
                .mapIndexed { index, id -> id to "Участник ${index + 1}" }.toMap()
            val completed = snapshot.copy(segments = result, speakerNames = names)
            withContext(Dispatchers.IO) { transcriptMutex.withLock { persistTranscript(completed) } }
            completed
        }) { completed ->
            mutableState.update { it.copy(segments = completed.segments, speakerNames = completed.speakerNames,
                status = if (completed.segments.isEmpty()) "Речь не обнаружена" else
                    "Готово — участников определено: ${completed.participantCount} — реплик: ${completed.segments.size}") }
        }
    }

    fun renameSpeaker(id: String, name: String) {
        val clean = name.trim().take(80)
        if (id == UNKNOWN_SPEAKER || clean.isEmpty() || mutableState.value.busy || mutableState.value.recording) return
        work("Сохраняю имя участника…", {
            val updated = mutableState.value.copy(speakerNames = mutableState.value.speakerNames + (id to clean))
            withContext(Dispatchers.IO) { transcriptMutex.withLock { persistTranscript(updated) } }
            updated.speakerNames
        }) { names -> mutableState.update { it.copy(speakerNames = names, status = "Имя участника сохранено") } }
    }

    fun exportTranscript(uri: Uri, json: Boolean) {
        val snapshot = mutableState.value
        if (snapshot.segments.isEmpty()) return
        work("Сохраняю расшифровку…", {
            runInterruptible(Dispatchers.IO) {
                val text = if (json) transcriptJson(snapshot).toString(2) else snapshot.segments.joinToString("\n\n") {
                    "[${formatTime(it.startMs)} — ${formatTime(it.endMs)}] ${snapshot.speakerName(it.speakerId)}\n${it.text}"
                }
                app.contentResolver.openOutputStream(uri, "wt")?.bufferedWriter(Charsets.UTF_8)?.use { it.write(text) }
                    ?: error("Не удалось создать файл")
            }
        }) { mutableState.update { it.copy(status = "Расшифровка экспортирована") } }
    }

    fun deleteRecording() {
        val audio = mutableState.value.audioFile ?: return
        work("Удаляю запись…", {
            withContext(Dispatchers.IO) {
                check(!audio.exists() || audio.delete()) { "Не удалось удалить запись" }
                check(preferences.edit().remove("audioPath").commit()) { "Не удалось обновить список записей" }
                transcriptMutex.withLock { transcriptFile.delete() }
            }
        }) { mutableState.update { it.copy(audioFile = null, segments = emptyList(), speakerNames = emptyMap(),
            elapsedMs = 0, waveform = List(52) { 0f }, status = "Запись удалена") } }
    }

    private suspend fun replaceAudio(file: File) {
        withContext(Dispatchers.IO) { transcriptMutex.withLock { transcriptFile.delete() } }
        mutableState.update { it.copy(audioFile = file, elapsedMs = duration(file), segments = emptyList(),
            speakerNames = emptyMap(), waveform = List(52) { 0f }) }
    }

    private fun <T> work(message: String, task: suspend () -> T, success: suspend (T) -> Unit) {
        if (mutableState.value.busy || mutableState.value.recording) return
        mutableState.update { it.copy(busy = true, status = message) }
        viewModelScope.launch {
            try { success(task()) }
            catch (_: OutOfMemoryError) { reportMemoryError() }
            catch (error: Exception) {
                if (error is CancellationException) throw error
                reportError(error)
            } finally { mutableState.update { it.copy(busy = false) } }
        }
    }

    /** Runs on IO. Commit the new selection before removing the previous private copy. */
    @SuppressLint("ApplySharedPref")
    private fun installSelection(key: String, file: File, root: File) {
        val old = preferences.getString(key, null)?.let(::File)
        check(file.canonicalFile.parentFile == root.canonicalFile) { "Некорректный путь файла" }
        if (!preferences.edit().putString(key, file.absolutePath).commit()) {
            // commit() can update the in-memory preference even when its disk write fails.
            val rollback = preferences.edit()
            if (old == null) rollback.remove(key) else rollback.putString(key, old.absolutePath)
            rollback.commit()
            error("Не удалось сохранить выбор файла")
        }
        if (old != null && old != file && old.canonicalFile.parentFile == root.canonicalFile) {
            if (old.isDirectory) old.deleteRecursively() else old.delete()
        }
    }

    private fun restore(): ConversationState {
        val audio = preferences.getString("audioPath", null)?.let(::File)?.takeIf { it.isFile }
        val modelRoot = File(app.filesDir, "models")
        val models = preferences.getString("modelPath", null)?.let(::File)?.takeIf { candidate ->
            candidate.isDirectory && candidate.canonicalFile.parentFile == modelRoot.canonicalFile &&
                runCatching { ModelSpec.load(candidate) }.isSuccess
        }
        // A killed process cannot finish its transaction; clear only app-owned unselected directories.
        modelRoot.listFiles()?.filter { child ->
            child != models && child.canonicalFile.parentFile == modelRoot.canonicalFile &&
                (child.name.startsWith(".download-") || child.name.startsWith(".import-") || child.name.startsWith("gigaam-"))
        }?.forEach { it.deleteRecursively() }
        val initial = ConversationState(audioFile = audio, modelDirectory = models, elapsedMs = audio?.let(::duration) ?: 0)
        if (audio == null || !transcriptFile.baseFile.isFile || transcriptFile.baseFile.length() > 10 * 1024 * 1024) return initial
        return try {
            val json = JSONObject(transcriptFile.openRead().bufferedReader().use { it.readText() })
            if (json.optString("audioFile") != audio.name) return initial
            val participants = json.getJSONArray("speakers")
            val names = buildMap {
                for (i in 0 until participants.length()) participants.getJSONObject(i).let {
                    if (it.getString("id") != UNKNOWN_SPEAKER) put(it.getString("id"), it.getString("name"))
                }
            }
            val items = json.getJSONArray("segments")
            val segments = List(items.length()) { index -> items.getJSONObject(index).let {
                TranscriptSegment(it.getString("speakerId"), it.getLong("startMs"), it.getLong("endMs"), it.getString("text"))
            } }
            initial.copy(segments = segments, speakerNames = names)
        } catch (_: Exception) { initial.copy(status = "Запись восстановлена. Расшифровку можно выполнить повторно.") }
    }

    private fun persistTranscript(snapshot: ConversationState) {
        val output = transcriptFile.startWrite()
        try {
            output.write(transcriptJson(snapshot).toString().toByteArray(Charsets.UTF_8))
            transcriptFile.finishWrite(output)
        } catch (error: Throwable) { transcriptFile.failWrite(output); throw error }
    }

    private fun transcriptJson(snapshot: ConversationState) = JSONObject().apply {
        put("schemaVersion", 1)
        put("languageHint", "ru")
        put("audioFile", snapshot.audioFile?.name)
        put("speakers", JSONArray().apply { snapshot.speakerNames.forEach { (id, name) ->
            if (id != UNKNOWN_SPEAKER) put(JSONObject().put("id", id).put("name", name))
        } })
        put("segments", JSONArray().apply { snapshot.segments.forEach {
            put(JSONObject().put("speakerId", it.speakerId).put("speakerName", snapshot.speakerName(it.speakerId))
                .put("speakerKnown", it.speakerId != UNKNOWN_SPEAKER)
                .put("startMs", it.startMs).put("endMs", it.endMs).put("text", it.text))
        } })
    }

    private fun reportError(error: Exception) { mutableState.update { it.copy(busy = false, status = "Ошибка: ${error.message ?: "операция не завершена"}") } }
    private fun reportMemoryError() { mutableState.update { it.copy(busy = false, status = MEMORY_MESSAGE) } }

    override fun onCleared() {
        // Normally onStop has already finalized recording. This is a bounded last-resort cleanup.
        recorder?.let { active ->
            active.requestStop()
            val cleanup = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            cleanup.launch {
                try { active.stop().also { installSelection("audioPath", it, File(app.filesDir, "recordings")) } }
                catch (_: Exception) { /* The screen is gone; the prior saved selection remains available. */ }
                finally { cleanup.cancel() }
            }
        }
        recorder = null
        super.onCleared()
    }

    companion object {
        private const val MEMORY_MESSAGE = "Недостаточно памяти. Закройте другие приложения или выберите более короткую запись."
        private fun duration(file: File): Long = (file.length() - 44).coerceAtLeast(0) * 1000 / (AudioRecorder.SAMPLE_RATE * 2)
    }
}

internal fun formatTime(milliseconds: Long): String {
    val seconds = milliseconds.coerceAtLeast(0) / 1000
    return String.format(Locale.ROOT, "%02d:%02d:%02d", seconds / 3600, seconds / 60 % 60, seconds % 60)
}
