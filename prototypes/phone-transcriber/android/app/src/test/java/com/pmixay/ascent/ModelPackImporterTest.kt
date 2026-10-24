package com.pmixay.ascent

import org.junit.Assert.*
import org.junit.Test
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ModelPackImporterTest {
    private fun modelZip(corrupt: Boolean): ByteArray {
        val payloads = linkedMapOf(
            "asr.onnx" to byteArrayOf(1, 2, 3),
            "speaker.onnx" to byteArrayOf(4, 5, 6),
            "tokens.json" to JSONArray(listOf("а", " ")).toString().toByteArray(),
            "LICENSE-asr.txt" to "License".toByteArray(),
            "LICENSE-speaker.txt" to "License".toByteArray(),
            "smoke-report.json" to "{}".toByteArray(),
        )
        val files = JSONObject()
        payloads.forEach { (name, bytes) ->
            val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
            files.put(name, JSONObject().put("bytes", bytes.size).put("sha256", hash))
        }
        val manifest = JSONObject().put("schema_version", 1).put("model_id", ModelSpec.MODEL_ID)
            .put("sample_rate", 16000).put("channels", 1).put("min_samples", 4000).put("max_samples", 400000)
            .put("asr_file", "asr.onnx").put("tokens_file", "tokens.json").put("speaker_file", "speaker.onnx")
            .put("speaker_model_id", "3dspeaker-eres2netv2-2024").put("speaker_runtime", "sherpa-onnx")
            .put("blank_id", 2).put("vocabulary_size", 2).put("files", files)
        if (corrupt) payloads["asr.onnx"] = byteArrayOf(9, 9, 9)
        payloads["manifest.json"] = manifest.toString().toByteArray()
        val bytes = ByteArrayOutputStream()
        ZipOutputStream(bytes).use { zip ->
            payloads.forEach { (name, content) ->
                zip.putNextEntry(ZipEntry(name)); zip.write(content); zip.closeEntry()
            }
        }
        return bytes.toByteArray()
    }

    @Test fun onlyVerifiedPackIsPromotedFromStaging() {
        val root = Files.createTempDirectory("models").toFile()
        try {
            val installed = ModelPackImporter.extract(ByteArrayInputStream(modelZip(false)), root)
            assertFalse(installed.name.startsWith(".import"))
            assertEquals(listOf("а", " "), ModelSpec.load(installed).tokens)
            assertThrows(IllegalArgumentException::class.java) {
                ModelPackImporter.extract(ByteArrayInputStream(modelZip(true)), root)
            }
            assertEquals(listOf(installed.name), root.listFiles()!!.map { it.name })
        } finally { root.deleteRecursively() }
    }

    @Test fun rejectsTraversalAndCleansStagingWithoutTouchingInstalledModels() {
        val root = Files.createTempDirectory("models").toFile()
        val existing = root.resolve("installed").apply { mkdir(); resolve("keep.txt").writeText("keep") }
        val bytes = ByteArrayOutputStream()
        ZipOutputStream(bytes).use { zip ->
            zip.putNextEntry(ZipEntry("../escape.txt")); zip.write(byteArrayOf(1)); zip.closeEntry()
        }
        try {
            assertThrows(IllegalArgumentException::class.java) {
                ModelPackImporter.extract(ByteArrayInputStream(bytes.toByteArray()), root)
            }
            assertEquals(listOf("installed"), root.listFiles()!!.map { it.name })
            assertEquals("keep", existing.resolve("keep.txt").readText())
        } finally { root.deleteRecursively() }
    }

    @Test fun missingManifestDoesNotReplaceWorkingModels() {
        val root = Files.createTempDirectory("models").toFile()
        val bytes = ByteArrayOutputStream()
        ZipOutputStream(bytes).use { zip ->
            zip.putNextEntry(ZipEntry("asr.onnx")); zip.write(byteArrayOf(1)); zip.closeEntry()
        }
        try {
            assertThrows(IllegalArgumentException::class.java) {
                ModelPackImporter.extract(ByteArrayInputStream(bytes.toByteArray()), root)
            }
            assertTrue(root.listFiles()!!.isEmpty())
        } finally { root.deleteRecursively() }
    }
}
