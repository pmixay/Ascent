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
                val parakeet = ParakeetFiles.ALL.find { name == "multilingual-asr/${it.name}" }
                files.put(name, JSONObject().put("bytes", parakeet?.bytes ?: bytes.size)
                    .put("sha256", parakeet?.sha256 ?: bytes.inputStream().use(::sha256)))
            }
            return JSONObject().put("schema_version", 2).put("acoustic_model_id", ModelSpec.MODEL_ID)
                .put("text_repository", TextModelFiles.REPOSITORY).put("text_revision", TextModelFiles.REVISION)
                .put("multilingual_repository", ParakeetFiles.REPOSITORY).put("multilingual_revision", ParakeetFiles.REVISION)
                .put("multilingual_upstream", ParakeetFiles.UPSTREAM).put("multilingual_license", ParakeetFiles.LICENSE)
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
        assertEquals(EmbeddedBundle.multilingualNames, installed.multilingual.list()!!.toSet())
        assertFalse(root.list()!!.any { it.startsWith(".") })
    }

    @Test fun warmInstallRechecksHashesWithoutReadingAssets() = inRoot { root ->
        val (bundle, data) = fixture()
        val installed = EmbeddedModelInstaller(root, bundle, { ByteArrayInputStream(data.getValue(it)) }) { Long.MAX_VALUE }.install()
        val next = EmbeddedModelInstaller(root, bundle, { error("Warm reuse must not read assets") }) { Long.MAX_VALUE }.install(installed)
        assertEquals(installed, next)
    }

    @Test fun deathAfterBothRenamesReusesVerifiedOrphanWithoutExtraDisk() = inRoot { root ->
        val (bundle, data) = fixture()
        val orphan = EmbeddedModelInstaller(root, bundle, { ByteArrayInputStream(data.getValue(it)) }) { Long.MAX_VALUE }.install()
        val recovered = EmbeddedModelInstaller(root, bundle, { error("Verified orphan needs no new copy") }) { 0 }.install()
        assertEquals(orphan, recovered)
    }

    @Test fun deathBetweenRenamesReclaimsOnlyUnleasedUnselectedPartialPromotion() = inRoot { root ->
        val (bundle, data) = fixture()
        val partial = EmbeddedModelInstaller(root, bundle, { ByteArrayInputStream(data.getValue(it)) }) { Long.MAX_VALUE }.install()
        partial.text.deleteRecursively()
        val recovered = EmbeddedModelInstaller(root, bundle, { ByteArrayInputStream(data.getValue(it)) }) {
            if (partial.acoustic.exists()) 0 else Long.MAX_VALUE
        }.install()
        assertFalse(partial.acoustic.exists())
        assertNotEquals(partial.acoustic, recovered.acoustic)
        assertTrue(recovered.text.isDirectory)
    }

    @Test fun incompleteLeasedPromotionIsNeverReclaimed() = inRoot { root ->
        val (bundle, data) = fixture()
        val leased = EmbeddedModelInstaller(root, bundle, { ByteArrayInputStream(data.getValue(it)) }) { Long.MAX_VALUE }.install()
        leased.text.deleteRecursively()
        assertThrows(IllegalArgumentException::class.java) {
            EmbeddedModelInstaller(root, bundle, { error("No space") }, isLeased = { it == leased.acoustic }) { 0 }.install()
        }
        assertTrue(leased.acoustic.exists())
    }

    @Test fun deathBeforeThirdRenameRecoversWithoutSelectingTwoGroupRevision() = inRoot { root ->
        val (bundle, data) = fixture()
        val partial = EmbeddedModelInstaller(root, bundle, { ByteArrayInputStream(data.getValue(it)) }) { Long.MAX_VALUE }.install()
        partial.multilingual.deleteRecursively()
        val installer = EmbeddedModelInstaller(root, bundle, { ByteArrayInputStream(data.getValue(it)) }) {
            if (partial.acoustic.exists() || partial.text.exists()) 0 else Long.MAX_VALUE
        }
        assertFalse(installer.valid(partial))
        val recovered = installer.install()
        assertFalse(partial.acoustic.exists())
        assertFalse(partial.text.exists())
        assertTrue(recovered.multilingual.isDirectory)
        assertTrue(installer.valid(recovered))
    }

    @Test fun corruptMultilingualGroupNeverReturnsReadinessAndKeepsLeasedTriple() = inRoot { root ->
        val (bundle, data) = fixture()
        val installer = EmbeddedModelInstaller(root, bundle, { ByteArrayInputStream(data.getValue(it)) }) { Long.MAX_VALUE }
        val first = installer.install()
        File(first.multilingual, "tokens.txt").writeText("corrupt")
        assertFalse(installer.valid(first))
        val second = EmbeddedModelInstaller(root, bundle, { ByteArrayInputStream(data.getValue(it)) },
            isLeased = { it == first.acoustic }) { Long.MAX_VALUE }.install(first)
        assertNotEquals(first.acoustic, second.acoustic)
        assertTrue(first.acoustic.isDirectory && first.text.isDirectory && first.multilingual.isDirectory)
        assertTrue(installer.valid(second))
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
