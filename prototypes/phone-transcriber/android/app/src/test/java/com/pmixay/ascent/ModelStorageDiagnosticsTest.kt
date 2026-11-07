package com.pmixay.ascent

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files

class ModelStorageDiagnosticsTest {
    @Test fun samplesAreThrottledAndRetainObservedPeakAcrossCleanup() {
        var now = 0L
        var current = ModelStorageSample(1000, 100)
        val probe = ModelStorageProbe({ current }, { now })
        current = ModelStorageSample(700, 400)
        probe.progress()
        now = 250_000_000L
        probe.progress()
        current = ModelStorageSample(900, 200)
        val observed = probe.finish()
        assertEquals(3, observed.samples)
        assertEquals(0, observed.failedSamples)
        assertEquals(100L, observed.before!!.modelDirectoryBytes)
        assertEquals(200L, observed.after!!.modelDirectoryBytes)
        assertEquals(400L, observed.largestModelDirectoryBytes)
        assertEquals(700L, observed.minimumFreeBytes)
    }

    @Test fun unavailableSamplesAreNotReplacedByFalseZeroMeasurements() {
        val probe = ModelStorageProbe({ throw java.io.IOException("unavailable") })
        val observed = probe.finish()
        assertNull(observed.before)
        assertNull(observed.after)
        assertNull(observed.largestModelDirectoryBytes)
        assertNull(observed.minimumFreeBytes)
        assertEquals(0, observed.samples)
        assertEquals(2, observed.failedSamples)
        val json = ModelStorageDiagnostics.record(50, observed, 0, 12)
        assertTrue(json.isNull("sampled_peak_model_directory_logical_bytes"))
        assertTrue(json.isNull("volume_free_bytes_before"))
        assertEquals(50 + EMBEDDED_MODEL_DISK_RESERVE_BYTES, json.getLong("fresh_extraction_payload_and_reserve_bytes"))
        assertEquals("verified-reuse", json.getString("preparation_kind"))
    }

    @Test fun modelLogicalBytesDoNotFollowSymlinksOrReadSiblingHistory() {
        val parent = Files.createTempDirectory("model-storage-test").toFile()
        try {
            val root = File(parent, "models").apply { mkdir() }
            File(root, "acoustic").mkdir()
            File(root, "acoustic/asr.onnx").writeBytes(ByteArray(7))
            File(root, "text-model").mkdir()
            File(root, "text-model/model.onnx").writeBytes(ByteArray(11))
            val other = File(parent, "unrelated").apply { mkdir() }
            File(other, "synthetic.enc").writeBytes(ByteArray(31))
            Files.createSymbolicLink(File(root, "directory-link").toPath(), other.toPath())
            Files.createSymbolicLink(File(root, "file-link").toPath(), File(other, "synthetic.enc").toPath())
            assertEquals(18L, modelDirectoryLogicalBytes(root))
        } finally { parent.deleteRecursively() }
    }

    @Test fun recordContainsCountsOnlyAndDistinguishesObservedPeakFromReserveBudget() {
        val observation = ModelStorageObservation(ModelStorageSample(1000, 100), ModelStorageSample(800, 300), 300, 800, 2, 0)
        val json = ModelStorageDiagnostics.record(200, observation, 200, 25)
        assertEquals("embedded_model_storage", json.getString("event"))
        assertEquals("sampled-model-logical-bytes-not-physical-allocation", json.getString("peak_scope"))
        assertEquals(200L, json.getLong("expanded_payload_bytes"))
        assertEquals(200L, json.getLong("copied_asset_bytes"))
        assertEquals("extracted", json.getString("preparation_kind"))
        assertEquals(25L, json.getLong("extraction_and_validation_elapsed_ms"))
        assertEquals(300L, json.getLong("sampled_peak_model_directory_logical_bytes"))
        assertEquals(200 + EMBEDDED_MODEL_DISK_RESERVE_BYTES, json.getLong("fresh_extraction_payload_and_reserve_bytes"))
        assertFalse(json.keys().asSequence().any { it in setOf("text", "transcript", "filename", "uri", "path", "audio") })
        assertFalse(json.toString().contains(JSONObject.NULL.toString()))
    }
}
