package com.pmixay.ascent

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.file.Files

class EmbeddedModelInstallerTest {
    private fun fixture(): Pair<EmbeddedBundle, Map<String, ByteArray>> {
        val data = EmbeddedBundle.names.associateWith { "fixture".toByteArray() }.toMutableMap()
        data["acoustic/tokens.json"] = JSONArray(listOf("а", " ")).toString().toByteArray()
        val files = JSONObject()
        ModelManifest.payloadNames.forEach { name ->
            val bytes = data.getValue("acoustic/$name")
            files.put(name, JSONObject().put("bytes", bytes.size).put("sha256", bytes.inputStream().use(::sha256)))
        }
        data["acoustic/manifest.json"] = JSONObject().put("schema_version", 1).put("model_id", ModelSpec.MODEL_ID)
            .put("sample_rate", 16000).put("channels", 1).put("min_samples", 4000).put("max_samples", 400000)
            .put("asr_file", "asr.onnx").put("tokens_file", "tokens.json").put("speaker_file", "speaker.onnx")
            .put("speaker_model_id", "3dspeaker-eres2netv2-2024").put("speaker_runtime", "sherpa-onnx")
            .put("blank_id", 2).put("vocabulary_size", 2).put("files", files).toString().toByteArray()
        return EmbeddedBundle("a".repeat(64), data.map { (name, bytes) ->
            ModelFileRecord(name, bytes.size.toLong(), bytes.inputStream().use(::sha256))
        }) to data
    }

    private fun inRoot(block: (File) -> Unit) {
        val root = Files.createTempDirectory("embedded-test").toFile()
        try { block(root) } finally { root.deleteRecursively() }
    }

    @Test fun descriptorRequiresTheCompiledDigestAndCompleteFileSet() {
        val (_, data) = fixture()
        fun descriptor(payloads: Map<String, ByteArray>): ByteArray {
            val files = JSONObject()
            payloads.forEach { (name, bytes) ->
                files.put(name, JSONObject().put("bytes", bytes.size).put("sha256", bytes.inputStream().use(::sha256)))
            }
            return JSONObject().put("schema_version", 1).put("acoustic_model_id", ModelSpec.MODEL_ID)
                .put("text_repository", TextModelFiles.REPOSITORY).put("text_revision", TextModelFiles.REVISION)
                .put("files", files).toString().toByteArray()
        }
        val complete = descriptor(data)
        assertEquals(data.size, EmbeddedBundle.parse(complete, complete.inputStream().use(::sha256)).files.size)
        assertThrows(IllegalArgumentException::class.java) { EmbeddedBundle.parse(complete, "0".repeat(64)) }
        val missing = descriptor(data - "text-model/model_int8.onnx")
        assertThrows(IllegalArgumentException::class.java) { EmbeddedBundle.parse(missing, missing.inputStream().use(::sha256)) }
    }

    @Test fun streamsBothGroupsAndKeepsAcousticWireFormatUnchanged() = inRoot { root ->
        val (bundle, data) = fixture()
        val installer = EmbeddedModelInstaller(root, bundle, { ByteArrayInputStream(data.getValue(it)) }) { Long.MAX_VALUE }
        val installed = installer.install()
        assertTrue(installer.valid(installed))
        assertEquals(listOf("а", " "), ModelSpec.load(installed.acoustic).tokens)
        assertEquals(EmbeddedBundle.textNames, installed.text.list()!!.toSet())
        assertFalse(root.list()!!.any { it.startsWith(".") })
    }

    @Test fun warmInstallRechecksHashesWithoutReadingAssets() = inRoot { root ->
        val (bundle, data) = fixture()
        val installed = EmbeddedModelInstaller(root, bundle, { ByteArrayInputStream(data.getValue(it)) }) { Long.MAX_VALUE }.install()
        val next = EmbeddedModelInstaller(root, bundle, { error("Warm reuse must not read assets") }) { Long.MAX_VALUE }.install(installed)
        assertEquals(installed, next)
    }

    @Test fun sameSizeCorruptionNeverReturnsExistingReadiness() = inRoot { root ->
        val (bundle, data) = fixture()
        val installer = EmbeddedModelInstaller(root, bundle, { ByteArrayInputStream(data.getValue(it)) }) { Long.MAX_VALUE }
        val first = installer.install()
        File(first.text, "model_int8.onnx").writeText("corrupt")
        assertFalse(installer.valid(first))
        val second = installer.install(first)
        assertNotEquals(first.acoustic, second.acoustic)
        assertTrue(installer.valid(second))
        assertTrue("Old revisions belong to lease-aware cleanup", first.acoustic.isDirectory)
    }

    @Test fun shortageFailsBeforeReadingAssetsOrRemovingOldRevision() = inRoot { root ->
        val (bundle, _) = fixture()
        val old = File(root, "gigaam-old").apply { mkdir() }
        assertThrows(IllegalArgumentException::class.java) {
            EmbeddedModelInstaller(root, bundle, { error("Must check space first") }) { 0 }.install()
        }
        assertEquals(listOf(old.name), root.list()!!.toList())
    }

    @Test fun corruptPayloadCleansStagingAndPreservesOldModels() = inRoot { root ->
        val (bundle, data) = fixture()
        val old = File(root, "gigaam-old").apply { mkdir() }
        assertThrows(IllegalArgumentException::class.java) {
            EmbeddedModelInstaller(root, bundle, { ByteArrayInputStream(if (it.endsWith("asr.onnx")) byteArrayOf(1) else data.getValue(it)) }) { Long.MAX_VALUE }.install()
        }
        assertEquals(listOf(old.name), root.list()!!.toList())
    }

    @Test fun failedAssetReadCannotPromoteOrRemoveOldModels() = inRoot { root ->
        val (bundle, _) = fixture()
        val old = File(root, "gigaam-old").apply { mkdir() }
        assertThrows(java.io.IOException::class.java) {
            EmbeddedModelInstaller(root, bundle, { throw java.io.IOException("asset read failed") }) { Long.MAX_VALUE }.install()
        }
        assertEquals(listOf(old.name), root.list()!!.toList())
    }

    @Test fun interruptionAndAbandonedStageRecoverWithoutDeletingOtherFiles() = inRoot { root ->
        val (bundle, data) = fixture()
        val abandoned = File(root, ".embedded-stage-old").apply { mkdir(); resolve("partial").writeText("partial") }
        val unrelated = File(root, ".download-keep").apply { mkdir() }
        Thread.currentThread().interrupt()
        try {
            assertThrows(InterruptedException::class.java) {
                EmbeddedModelInstaller(root, bundle, { ByteArrayInputStream(data.getValue(it)) }) { Long.MAX_VALUE }.install()
            }
        } finally { Thread.interrupted() }
        assertFalse(abandoned.exists())
        assertTrue(unrelated.exists())
        val installed = EmbeddedModelInstaller(root, bundle, { ByteArrayInputStream(data.getValue(it)) }) { Long.MAX_VALUE }.install()
        assertTrue(installed.acoustic.isDirectory)
    }
}
