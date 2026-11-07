package com.pmixay.ascent

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.WorkManager
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.FixMethodOrder
import org.junit.runners.MethodSorters
import org.junit.runner.RunWith
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID

/** Offline-only extraction/recovery and one synthetic text-stage invocation; no ARM acceptance. */
@RunWith(AndroidJUnit4::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class EmbeddedModelRuntimeTest {
    @Test fun coldBootstrapSelectsBothGroupsWithoutDownloadWork() = runBlocking {
        assertTrue(BuildConfig.EMBEDDED_MODELS)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertEquals(PackageManager.PERMISSION_DENIED, context.checkSelfPermission(Manifest.permission.INTERNET))
        assertNull(ModelSelection.current(context))
        ModelDownloads.schedule(context)
        assertTrue(ModelDownloads.startFromVisible(context))
        val state = withTimeout(240_000) {
            EmbeddedModels.states.first { it.phase == ModelTransferPhase.READY || it.phase == ModelTransferPhase.ERROR }
        }
        assertEquals(state.message, ModelTransferPhase.READY, state.phase)
        assertFalse(ModelDownloadService.running.value)
        val work = WorkManager.getInstance(context)
        assertTrue(work.getWorkInfosForUniqueWork("ascent-model-download").get().isEmpty())
        assertTrue(work.getWorkInfosForUniqueWork("ascent-model-updates").get().isEmpty())
        val storage = JSONObject(withTimeout(30_000) { ModelStorageDiagnostics.reports.first { it != null } }!!)
        assertEquals("measured", storage.getString("installed_stats_status"))
        assertEquals(BuildConfig.SOURCE_COMMIT, storage.getString("source_commit"))
        assertEquals(BuildConfig.NATIVE_ABI, storage.getString("apk_abi"))
        assertEquals(BuildConfig.EMBEDDED_BUNDLE_SHA256, storage.getString("bundle_sha256"))
        assertTrue(storage.getLong("installed_apk_files_bytes") > 0)
        assertTrue(storage.getLong("installed_app_bytes") > 0)
        assertTrue(storage.getLong("expanded_payload_bytes") > 0)
        assertEquals("extracted", storage.getString("preparation_kind"))
        assertEquals(storage.getLong("expanded_payload_bytes"), storage.getLong("copied_asset_bytes"))
        assertTrue(storage.getLong("extraction_and_validation_elapsed_ms") >= 0)
        assertTrue(storage.getLong("model_directory_logical_bytes_after_install") >= storage.getLong("expanded_payload_bytes"))
        assertTrue(storage.getInt("successful_samples") >= 2)
        assertEquals(0, storage.getInt("failed_samples"))
        println("Synthetic ${BuildConfig.NATIVE_ABI} bootstrap storage (not full-pipeline acceptance): $storage")
        ModelSelection.acquire(context).use { lease ->
            assertTrue(File(lease.directory, "asr.onnx").isFile)
            val text = requireNotNull(lease.textDirectory)
            assertEquals(TextModelFiles.MODEL_BYTES, File(text, TextModelFiles.MODEL).length())
            assertEquals(TextModelFiles.TOKENIZER_BYTES, File(text, TextModelFiles.TOKENIZER).length())
            assertEquals(ModelManifest.payloadNames + "manifest.json", lease.directory.list()!!.toSet())
            assertEquals(lease.directory, ModelSelection.current(context))

            // A bounded application integration check, not a quality/corpus benchmark.
            // Bundled bytes must be callable through the exact paired pipeline API.
            val input = listOf(TranscriptSegment("speaker_1", 100, 900, "привет мир", speakerInferred = true))
            val timings = ProcessingTimings()
            val presented = timings.measure(ProcessingMetricStage.TEXT) {
                TextStages.run(text, input, timings) { throw AssertionError("Bundled text runtime failed on synthetic input", it) }
            }
            val measured = timings.finish(ProcessingMeasurementOutcome.COMPLETED, null)
            assertNull(measured.realTimeFactor) // Synthetic text has no processed audio denominator.
            assertTrue(measured.stages.getValue(ProcessingMetricStage.TEXT_MODEL_LOAD).elapsedNs > 0)
            assertTrue(measured.stages.getValue(ProcessingMetricStage.TEXT).elapsedNs > 0)
            assertEquals(measured.totalNs, measured.stages.values.sumOf { it.elapsedNs })
            assertEquals(TextStageStatus.COMPLETED, presented.report.status)
            assertEquals(1, presented.report.phrases)
            assertEquals(0, presented.report.fallbacks)
            val phrase = presented.segments.single()
            assertEquals(input.single().recognizedText, phrase.recognizedText)
            assertEquals(input.single().speakerId, phrase.speakerId)
            assertEquals(input.single().speakerInferred, phrase.speakerInferred)
            assertEquals(input.single().startMs, phrase.startMs)
            assertEquals(input.single().endMs, phrase.endMs)
        }

        // Deliberate model-file corruption, never application audio. A replacement
        // must preserve BOTH old directories while a job still holds their lease.
        val old = ModelSelection.acquire(context)
        val oldText = requireNotNull(old.textDirectory)
        val oldMultilingual = requireNotNull(old.multilingualDirectory)
        try {
            File(oldText, "NOTICE.txt").writeText("corrupted test notice")
            assertNull(ModelSelection.current(context))
            ModelDownloads.schedule(context)
            val recovered = withTimeout(240_000) {
                EmbeddedModels.states.first { it.phase == ModelTransferPhase.READY || it.phase == ModelTransferPhase.ERROR }
            }
            assertEquals(recovered.message, ModelTransferPhase.READY, recovered.phase)
            assertNotEquals(old.directory, ModelSelection.current(context))
            assertTrue(old.directory.isDirectory)
            assertTrue(oldText.isDirectory)
            assertTrue(oldMultilingual.isDirectory)
        } finally { old.close() }
        assertFalse(old.directory.exists())
        assertFalse(oldText.exists())
        assertFalse(oldMultilingual.exists())
    }

    /** Creation traverses JNI config fields by original name in the actual R8-optimized APK. */
    @Test fun parakeetRecognizerLoadsPinnedFilesWithMinifiedJniConfiguration() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertTrue(BuildConfig.EMBEDDED_MODELS)
        assertEquals(PackageManager.PERMISSION_DENIED, context.checkSelfPermission(Manifest.permission.INTERNET))
        ModelDownloads.schedule(context)
        val ready = withTimeout(300_000) {
            EmbeddedModels.states.first { it.phase == ModelTransferPhase.READY || it.phase == ModelTransferPhase.ERROR }
        }
        assertEquals(ready.message, ModelTransferPhase.READY, ready.phase)
        ModelSelection.acquire(context).use { lease ->
            val directory = requireNotNull(lease.multilingualDirectory)
            assertTrue(ParakeetFiles.installed(directory))
            for (asset in ParakeetFiles.ALL) {
                val file = File(directory, asset.name)
                assertEquals(asset.bytes, file.length())
                assertEquals(asset.sha256, file.inputStream().use(::sha256))
            }
            assertTrue(File(directory, "NOTICE.txt").readText().contains("CC-BY-4.0"))
            // Existing Java ORT + isolated sherpa runtime coexist in this process.
            // No corpus, benchmark audio, transcript, quality claim or ARM acceptance.
            ParakeetRecognizer(directory).use { assertNotNull(it) }
        }
    }

    // ---- Real ConversationViewModel job path on the installed models. Silent synthetic clips only: no speech,
    // no application audio, no transcript content. Method names sort after the cold-start test on purpose
    // (NAME_ASCENDING), so that test still observes a process with no model selected. Not ARM acceptance. ----

    private val terminalStates = setOf(ProcessingState.Completed, ProcessingState.Failed, ProcessingState.Cancelled)

    private fun silentWav(seconds: Int): ByteArray {
        val rate = 16_000
        val data = ByteArray(rate * 2 * seconds)
        fun le(value: Long, size: Int) = ByteArray(size) { ((value shr (8 * it)) and 0xFF).toByte() }
        val out = ByteArrayOutputStream()
        out.write("RIFF".toByteArray()); out.write(le(36L + data.size, 4)); out.write("WAVEfmt ".toByteArray())
        out.write(le(16, 4)); out.write(le(1, 2)); out.write(le(1, 2)); out.write(le(rate.toLong(), 4))
        out.write(le(rate * 2L, 4)); out.write(le(2, 2)); out.write(le(16, 2))
        out.write("data".toByteArray()); out.write(le(data.size.toLong(), 4)); out.write(data)
        return out.toByteArray()
    }

    private fun importId(uri: Uri) = "import-${UUID.nameUUIDFromBytes(uri.toString().toByteArray(Charsets.UTF_8))}"

    private fun onMain(block: () -> Unit) = InstrumentationRegistry.getInstrumentation().runOnMainSync { block() }

    private fun awaitState(model: ConversationViewModel, what: String, timeoutMs: Long, predicate: (ConversationState) -> Boolean): ConversationState =
        try {
            runBlocking { withTimeout(timeoutMs) { model.state.first { predicate(it) } } }
        } catch (_: TimeoutCancellationException) {
            val last = model.state.value
            throw AssertionError("Timed out waiting for: $what (processingState=${last.processingState}, busy=${last.busy}, status=${last.status})")
        }

    private fun readyViewModel(): ConversationViewModel {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        runBlocking {
            ModelDownloads.schedule(context)
            val ready = withTimeout(300_000) {
                EmbeddedModels.states.first { it.phase == ModelTransferPhase.READY || it.phase == ModelTransferPhase.ERROR }
            }
            assertEquals(ready.message, ModelTransferPhase.READY, ready.phase)
        }
        var created: ConversationViewModel? = null
        onMain { created = ConversationViewModel(context.applicationContext as Application) }
        val model = requireNotNull(created)
        awaitState(model, "models and history are ready", 300_000) { it.modelDirectory != null && it.historyReady && !it.busy }
        return model
    }

    /** One import to completion, then the competition JSON of a recording without speech, the file untouched. */
    @Test fun viewModelSilentImportCompletesAndExportsAnEmptyTranscriptWithoutTouchingTheSource() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val folder = File(context.cacheDir, "lifecycle-${UUID.randomUUID()}").apply { mkdirs() }
        try {
            val model = readyViewModel()
            onMain { model.setRecognitionMode(RecognitionMode.AUTO) }
            val source = File(folder, "silent.wav").apply { writeBytes(silentWav(3)) }
            val before = source.readBytes()
            val uri = Uri.fromFile(source)
            val previousJob = model.state.value.jobId
            onMain { model.importAudio(uri) }
            val done = awaitState(model, "the silent import finishes", 240_000) {
                it.jobId != previousJob && it.processingState in terminalStates
            }
            assertEquals(done.status, ProcessingState.Completed, done.processingState)
            assertNull(done.jobError)
            assertFalse(done.busy)
            assertTrue(done.status, done.status.startsWith("Речь не обнаружена"))
            assertFalse("A finished job must not report a cleanup failure: ${done.status}", done.status.contains("очистку состояния"))
            assertEquals(TextStageStatus.SKIPPED, done.textStage?.status)
            // AUTO with Parakeet installed stays on GigaAM when no Latin letters were ever seen.
            assertEquals(RecognitionMode.RUSSIAN_CIS, done.recognizedMode)
            val conversation = done.conversations.single { it.id == importId(uri) }
            assertTrue(conversation.segments.isEmpty())

            val exported = File(folder, "export.json")
            onMain { model.exportTranscript(conversation.id, Uri.fromFile(exported), true) }
            awaitState(model, "the export finishes", 60_000) { it.status == "Расшифровка экспортирована" && !it.busy }
            val json = JSONObject(exported.readText(Charsets.UTF_8))
            assertEquals(setOf("transcript"), json.keys().asSequence().toSet())
            assertEquals(0, json.getJSONArray("transcript").length())
            assertArrayEquals("The picked file must stay byte-identical", before, source.readBytes())
        } finally { folder.deleteRecursively() }
    }

    /**
     * Repeated taps and a second file while a job runs start nothing, leaving and returning to the app does not
     * disturb it, cancelling ends it without saving, and a new job then starts and completes with a new job ID.
     * Kill/reopen and rotation are not covered here: the first needs the process to die, the second is Android's
     * ViewModel retention rather than this code.
     */
    @Test fun viewModelCancelDoubleTapAndBackgroundLeaveOneCoherentJobAndANewJobStartsAfterwards() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val folder = File(context.cacheDir, "lifecycle-${UUID.randomUUID()}").apply { mkdirs() }
        try {
            val model = readyViewModel()
            onMain { model.setRecognitionMode(RecognitionMode.RUSSIAN_CIS) }
            val longClip = File(folder, "long.wav").apply { writeBytes(silentWav(900)) }
            val shortClip = File(folder, "short.wav").apply { writeBytes(silentWav(3)) }
            val longUri = Uri.fromFile(longClip)
            val shortUri = Uri.fromFile(shortClip)
            val longBefore = longClip.length()

            onMain { model.importAudio(longUri) }
            val running = awaitState(model, "recognition of the long clip starts", 240_000) {
                it.processingState == ProcessingState.Recognizing
            }
            val firstJob = requireNotNull(running.jobId)

            onMain { model.importAudio(longUri); model.importAudio(shortUri); model.importAudio(longUri) }
            assertEquals("Repeated taps must not start another job", firstJob, model.state.value.jobId)

            onMain { model.setForeground(false) }
            onMain { model.setForeground(true) }
            val afterBackground = model.state.value
            assertEquals(firstJob, afterBackground.jobId)
            assertTrue("Leaving and returning must not stop an import", afterBackground.processingAudio)
            assertNotEquals(ProcessingState.Cancelled, afterBackground.processingState)

            onMain { model.cancelProcessing() }
            val cancelled = awaitState(model, "the running job is cancelled", 240_000) {
                it.processingState == ProcessingState.Cancelled && !it.busy
            }
            assertEquals(firstJob, cancelled.jobId)
            assertNull(cancelled.jobError)
            assertEquals("Обработка отменена", cancelled.status)
            assertTrue("A cancelled job must not save a conversation", cancelled.conversations.none { it.id == importId(longUri) })
            assertEquals("The picked file must stay untouched", longBefore, longClip.length())

            onMain { model.importAudio(shortUri) }
            val second = awaitState(model, "a new job completes after the cancelled one", 240_000) {
                it.jobId != firstJob && it.processingState in terminalStates
            }
            assertEquals(second.status, ProcessingState.Completed, second.processingState)
            assertNotEquals(firstJob, second.jobId)
            assertNull(second.jobError)
            assertFalse("A finished job must not report a cleanup failure: ${second.status}", second.status.contains("очистку состояния"))
            assertTrue(second.conversations.any { it.id == importId(shortUri) })
        } finally { folder.deleteRecursively() }
    }
}
