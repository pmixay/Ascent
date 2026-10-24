package com.pmixay.ascent

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.zip.GZIPOutputStream
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest

class ModelDownloaderTest {
    private data class Fixture(val files: MutableMap<String, ByteArray>, val directory: String, val totalBytes: Long)

    private fun fixture(asr: ByteArray = byteArrayOf(1, 2, 3)): Fixture {
        val payloads = linkedMapOf(
            "asr.onnx" to asr,
            "speaker.onnx" to byteArrayOf(4, 5, 6),
            "tokens.json" to JSONArray(listOf("а", " ")).toString().toByteArray(),
            "LICENSE-asr.txt" to "ASR license".toByteArray(),
            "LICENSE-speaker.txt" to "Speaker license".toByteArray(),
            "smoke-report.json" to "{}".toByteArray(),
        )
        val entries = JSONObject()
        payloads.forEach { (name, bytes) -> entries.put(name, JSONObject().put("bytes", bytes.size).put("sha256", hash(bytes))) }
        val manifest = JSONObject().put("schema_version", 1).put("model_id", ModelSpec.MODEL_ID)
            .put("sample_rate", 16000).put("channels", 1).put("min_samples", 4000).put("max_samples", 400000)
            .put("asr_file", "asr.onnx").put("tokens_file", "tokens.json").put("speaker_file", "speaker.onnx")
            .put("speaker_model_id", "3dspeaker-eres2netv2-2024").put("speaker_runtime", "sherpa-onnx")
            .put("blank_id", 2).put("vocabulary_size", 2).put("files", entries).toString().toByteArray()
        val catalog = JSONObject().put("schema_version", 1).put("model_id", ModelSpec.MODEL_ID)
            .put("manifest_sha256", hash(manifest)).put("manifest_bytes", manifest.size).toString().toByteArray()
        val directory = "${ModelSpec.MODEL_ID}/${hash(manifest)}/"
        val files = mutableMapOf("catalog.json" to catalog, (directory + "manifest.json") to manifest)
        payloads.forEach { (name, bytes) -> files[directory + name] = bytes }
        return Fixture(files, directory, payloads.values.sumOf { it.size.toLong() })
    }

    private fun source(fixture: Fixture, requested: MutableList<String> = mutableListOf()) = ModelSource { path ->
        requested += path
        val bytes = fixture.files.getValue(path)
        ModelResponse(ByteArrayInputStream(bytes), bytes.size.toLong())
    }

    @Test fun installsVerifiedSiblingFilesWithoutAnArchiveAndKeepsCommittedModels() {
        val root = Files.createTempDirectory("model-download").toFile()
        try {
            val fixture = fixture()
            val requested = mutableListOf<String>()
            val progress = mutableListOf<ModelDownloadProgress>()
            lateinit var installed: File
            ModelDownloader(root, source(fixture, requested), { Long.MAX_VALUE }).use { operation ->
                installed = operation.download { progress += it }
                operation.commit()
            }
            assertTrue(installed.isDirectory)
            assertEquals(listOf("а", " "), ModelSpec.load(installed, verifyHashes = true).tokens)
            assertEquals(8, requested.size)
            assertTrue(requested.none { it.endsWith(".zip") })
            assertEquals(fixture.totalBytes, progress.last().completedBytes)
            assertTrue(progress.zipWithNext().all { (before, after) -> before.completedBytes <= after.completedBytes })
            assertEquals(ModelManifest.payloadNames + "manifest.json", installed.listFiles()!!.map { it.name }.toSet())
        } finally { root.deleteRecursively() }
    }

    @Test fun aCancelledOrUncommittedPromotionCannotLeaveAnOrphanModelDirectory() {
        val root = Files.createTempDirectory("model-download").toFile()
        val previous = root.resolve("previous").apply { mkdir(); resolve("keep").writeText("old") }
        try {
            ModelDownloader(root, source(fixture()), { Long.MAX_VALUE }).use { it.download {} }
            assertEquals(listOf(previous), root.listFiles()!!.toList())
            assertEquals("old", previous.resolve("keep").readText())
        } finally { root.deleteRecursively() }
    }

    @Test fun corruptedPayloadIsRejectedAndPreviousModelsSurvive() {
        val root = Files.createTempDirectory("model-download").toFile()
        val previous = root.resolve("previous").apply { mkdir() }
        val fixture = fixture().apply { files[directory + "asr.onnx"] = byteArrayOf(8, 8, 8) }
        try {
            ModelDownloader(root, source(fixture), { Long.MAX_VALUE }).use { operation ->
                assertThrows(IllegalStateException::class.java) { operation.download {} }
            }
            assertTrue(previous.isDirectory)
            assertTrue(root.listFiles()!!.none { it.name.startsWith("gigaam-") })
        } finally { root.deleteRecursively() }
    }

    @Test fun wrongManifestHashFailsBeforeAnyPayloadIsDownloaded() {
        val root = Files.createTempDirectory("model-download").toFile()
        val fixture = fixture()
        val manifestPath = fixture.directory + "manifest.json"
        fixture.files[manifestPath] = fixture.files.getValue(manifestPath).copyOf().also { it[0] = '['.code.toByte() }
        val requested = mutableListOf<String>()
        try {
            ModelDownloader(root, source(fixture, requested), { Long.MAX_VALUE }).use { operation ->
                assertThrows(IllegalStateException::class.java) { operation.download {} }
            }
            assertEquals(listOf("catalog.json", manifestPath), requested)
            assertTrue(root.listFiles()!!.isEmpty())
        } finally { root.deleteRecursively() }
    }

    @Test fun spaceCheckRunsBeforeCreatingFilesOrDownloadingLargePayloads() {
        val root = Files.createTempDirectory("model-download").toFile()
        val fixture = fixture()
        val requested = mutableListOf<String>()
        try {
            ModelDownloader(root, source(fixture, requested), { 0 }).use { operation ->
                assertThrows(IllegalStateException::class.java) { operation.download {} }
            }
            assertEquals(2, requested.size)
            assertTrue(root.listFiles()!!.isEmpty())
        } finally { root.deleteRecursively() }
    }

    @Test fun rejectsTruncatedBodiesEvenWithoutContentLength() {
        val root = Files.createTempDirectory("model-download").toFile()
        val fixture = fixture()
        val source = ModelSource { path ->
            val bytes = fixture.files.getValue(path).let { if (path.endsWith("asr.onnx")) it.copyOf(1) else it }
            ModelResponse(ByteArrayInputStream(bytes), -1)
        }
        try {
            ModelDownloader(root, source, { Long.MAX_VALUE }).use { operation ->
                assertThrows(IllegalStateException::class.java) { operation.download {} }
            }
            assertTrue(root.listFiles()!!.none { it.name.startsWith("gigaam-") })
        } finally { root.deleteRecursively() }
    }

    @Test fun interruptionDuringPayloadReadDoesNotActivatePartialFiles() {
        val root = Files.createTempDirectory("model-download").toFile()
        val fixture = fixture()
        val source = ModelSource { path ->
            val bytes = fixture.files.getValue(path)
            val stream = if (path.endsWith("asr.onnx")) object : ByteArrayInputStream(bytes) {
                override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                    return super.read(buffer, offset, length).also { Thread.currentThread().interrupt() }
                }
            } else ByteArrayInputStream(bytes)
            ModelResponse(stream, bytes.size.toLong())
        }
        try {
            ModelDownloader(root, source, { Long.MAX_VALUE }).use { operation ->
                assertThrows(InterruptedException::class.java) { operation.download {} }
            }
            assertTrue(root.listFiles()!!.none { it.name.startsWith("gigaam-") })
        } finally { Thread.interrupted(); root.deleteRecursively() }
    }

    @Test fun catalogCannotSelectAnUnreviewedModelOrPath() {
        val invalid = JSONObject().put("schema_version", 1).put("model_id", ModelSpec.MODEL_ID)
            .put("manifest_sha256", "../outside").put("manifest_bytes", 1)
        assertThrows(IllegalArgumentException::class.java) { ModelCatalog.parse(invalid.toString().toByteArray()) }
        invalid.put("manifest_sha256", "0".repeat(64)).put("model_id", "another-model")
        assertThrows(IllegalArgumentException::class.java) { ModelCatalog.parse(invalid.toString().toByteArray()) }
    }


    private fun compressedFixture(): Fixture {
        val fixture = fixture(ByteArray(4 * 1024 * 1024 + 1024) { (it % 251).toByte() })
        val indexFiles = JSONObject()
        val compressedFiles = mutableMapOf<String, ByteArray>()
        for (name in ModelManifest.payloadNames) {
            val raw = fixture.files.getValue(fixture.directory + name)
            val records = JSONArray()
            var start = 0
            while (start < raw.size) {
                val chunk = raw.copyOfRange(start, minOf(start + 4 * 1024 * 1024, raw.size))
                val bytes = ByteArrayOutputStream().also { output -> GZIPOutputStream(output).use { it.write(chunk) } }.toByteArray()
                compressedFiles["$name.${records.length()}.gz"] = bytes
                records.put(JSONObject().put("bytes", bytes.size).put("sha256", hash(bytes))
                    .put("raw_bytes", chunk.size).put("raw_sha256", hash(chunk)))
                start += chunk.size
            }
            indexFiles.put(name, records)
        }
        val catalog = JSONObject(fixture.files.getValue("catalog.json").decodeToString())
        val index = JSONObject().put("schema_version", 1).put("manifest_sha256", catalog.getString("manifest_sha256"))
            .put("chunk_bytes", 4 * 1024 * 1024).put("files", indexFiles).toString().toByteArray()
        val base = "${ModelSpec.MODEL_ID}/downloads/${hash(index)}/"
        fixture.files[base + "download.json"] = index
        compressedFiles.forEach { (name, bytes) -> fixture.files[base + name] = bytes }
        catalog.put("download_sha256", hash(index)).put("download_bytes", index.size)
        fixture.files["catalog.json"] = catalog.toString().toByteArray()
        return fixture
    }

    @Test fun compressedChunksResumeAfterNetworkFailureWithoutFetchingCompletedChunks() {
        val root = Files.createTempDirectory("model-chunks").toFile()
        try {
            val fixture = compressedFixture()
            val failing = ModelSource { path ->
                if (path.endsWith("asr.onnx.1.gz")) throw IOException("Network interrupted")
                val bytes = fixture.files.getValue(path)
                ModelResponse(ByteArrayInputStream(bytes), bytes.size.toLong())
            }
            ModelDownloader(root, failing, { Long.MAX_VALUE }).use { operation ->
                assertThrows(IOException::class.java) { operation.download {} }
            }
            val partial = root.listFiles()!!.single().resolve("asr.onnx.part")
            assertEquals(4L * 1024 * 1024, partial.length())
            val requested = mutableListOf<String>()
            ModelDownloader(root, source(fixture, requested), { Long.MAX_VALUE }).use { operation ->
                val installed = operation.download {}
                assertEquals(fixture.files.getValue(fixture.directory + "asr.onnx").size.toLong(), installed.resolve("asr.onnx").length())
                ModelSpec.load(installed, verifyHashes = true)
                operation.commit()
            }
            assertTrue(requested.none { it.endsWith("asr.onnx.0.gz") })
            assertTrue(requested.any { it.endsWith("asr.onnx.1.gz") })
            assertTrue(requested.none { it.endsWith("asr.onnx") || it.endsWith("speaker.onnx") })
        } finally { root.deleteRecursively() }
    }

    @Test fun corruptPersistedChunkIsReplacedAndTruncatedTailCannotBeTrusted() {
        val root = Files.createTempDirectory("model-chunks").toFile()
        try {
            val fixture = compressedFixture()
            val failing = ModelSource { path ->
                if (path.endsWith("asr.onnx.1.gz")) throw IOException("Disconnected")
                val bytes = fixture.files.getValue(path)
                ModelResponse(ByteArrayInputStream(bytes), bytes.size.toLong())
            }
            ModelDownloader(root, failing, { Long.MAX_VALUE }).use { operation ->
                assertThrows(IOException::class.java) { operation.download {} }
            }
            val partial = root.listFiles()!!.single().resolve("asr.onnx.part")
            java.io.RandomAccessFile(partial, "rw").use { it.seek(99); it.writeByte(19); it.seek(it.length()); it.writeByte(42) }
            val requested = mutableListOf<String>()
            ModelDownloader(root, source(fixture, requested), { Long.MAX_VALUE }).use { operation ->
                ModelSpec.load(operation.download {}, verifyHashes = true)
                operation.commit()
            }
            assertTrue(requested.any { it.endsWith("asr.onnx.0.gz") })
            assertTrue(requested.any { it.endsWith("asr.onnx.1.gz") })
        } finally { root.deleteRecursively() }
    }

    @Test fun corruptedCompressedChunkCannotBeInstalled() {
        val root = Files.createTempDirectory("model-chunks").toFile()
        try {
            val fixture = compressedFixture()
            val key = fixture.files.keys.single { it.endsWith("asr.onnx.0.gz") }
            fixture.files[key] = fixture.files.getValue(key).copyOf().also { it[10] = (it[10].toInt() xor 255).toByte() }
            ModelDownloader(root, source(fixture), { Long.MAX_VALUE }).use { operation ->
                assertThrows(IllegalStateException::class.java) { operation.download {} }
            }
            assertTrue(root.listFiles()!!.none { it.name.startsWith("gigaam-") })
        } finally { root.deleteRecursively() }
    }

    @Test fun matchingInstalledRevisionChecksCatalogWithoutRedownloadingWeights() {
        val root = Files.createTempDirectory("model-update").toFile()
        try {
            val fixture = compressedFixture()
            lateinit var installed: File
            ModelDownloader(root, source(fixture), { Long.MAX_VALUE }).use {
                installed = it.download {}; it.commit()
            }
            val requested = mutableListOf<String>()
            ModelDownloader(root, source(fixture, requested), { Long.MAX_VALUE }, installed).use {
                assertEquals(installed, it.download {}); it.commit()
            }
            assertEquals(listOf("catalog.json"), requested)
        } finally { root.deleteRecursively() }
    }


    @Test fun newServerRevisionIsVerifiedBeforeReplacingSelection() {
        val root = Files.createTempDirectory("model-update").toFile()
        try {
            lateinit var previous: File
            ModelDownloader(root, source(fixture()), { Long.MAX_VALUE }).use {
                previous = it.download {}; it.commit()
            }
            val replacement = fixture(byteArrayOf(9, 8, 7, 6))
            ModelDownloader(root, source(replacement), { Long.MAX_VALUE }, previous).use {
                val newer = it.download {}
                assertNotEquals(previous, newer)
                assertTrue(previous.isDirectory)
                ModelSpec.load(newer, verifyHashes = true)
                // Simulate a crash before committing activation: old models remain usable.
            }
            assertEquals(listOf(previous), root.listFiles()!!.toList())
            ModelSpec.load(previous, verifyHashes = true)
        } finally { root.deleteRecursively() }
    }

    private fun hash(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it.toInt() and 255) }
}
