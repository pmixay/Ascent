package com.pmixay.ascent

import android.Manifest
import android.content.pm.PackageManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.WorkManager
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.json.JSONObject
import java.io.File

/** Offline-only extraction/recovery and one synthetic text-stage invocation; no ARM acceptance. */
@RunWith(AndroidJUnit4::class)
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
        } finally { old.close() }
        assertFalse(old.directory.exists())
        assertFalse(oldText.exists())
    }
}
