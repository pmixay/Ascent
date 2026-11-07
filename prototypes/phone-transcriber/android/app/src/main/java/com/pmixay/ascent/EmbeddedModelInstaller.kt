package com.pmixay.ascent

import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.UUID

internal data class EmbeddedInstallation(
    val acoustic: File, val text: File,
    val multilingual: File = File(acoustic.parentFile, "multilingual-${acoustic.name.removePrefix("gigaam-")}"),
)
internal const val EMBEDDED_MODEL_DISK_RESERVE_BYTES = 32L * 1024 * 1024

/** Separate envelope: the existing acoustic wire format and server validators do not change. */
internal class EmbeddedBundle(val digest: String, val files: List<ModelFileRecord>) {
    val totalBytes: Long get() = files.sumOf { it.bytes }

    companion object {
        val textNames = setOf("model_int8.onnx", "tokenizer.json", "config.json", "tokenizer_config.json", "LICENSE-Qwen.txt", "NOTICE.txt")
        val multilingualNames = ParakeetFiles.ALL.map { it.name }.toSet() + "NOTICE.txt"
        val names = (ModelManifest.payloadNames + "manifest.json").map { "acoustic/$it" }.toSet() +
            textNames.map { "text-model/$it" } + multilingualNames.map { "multilingual-asr/$it" }

        fun parse(bytes: ByteArray, expectedDigest: String): EmbeddedBundle {
            require(bytes.size in 1..MAX_MODEL_MANIFEST_BYTES && expectedDigest.matches(Regex("[0-9a-f]{64}")))
            require(bytes.inputStream().use(::sha256) == expectedDigest) { "Повреждён встроенный манифест моделей" }
            val json = JSONObject(bytes.decodeToString(throwOnInvalidSequence = true))
            require(json.getInt("schema_version") == 2 && json.getString("acoustic_model_id") == ModelSpec.MODEL_ID)
            require(json.getString("text_repository") == TextModelFiles.REPOSITORY && json.getString("text_revision") == TextModelFiles.REVISION)
            require(json.getString("multilingual_repository") == ParakeetFiles.REPOSITORY && json.getString("multilingual_revision") == ParakeetFiles.REVISION)
            require(json.getString("multilingual_upstream") == ParakeetFiles.UPSTREAM && json.getString("multilingual_license") == ParakeetFiles.LICENSE)
            val entries = json.getJSONObject("files")
            require(entries.keys().asSequence().toSet() == names) { "Неполный встроенный комплект моделей" }
            val files = names.sorted().map { name ->
                val item = entries.getJSONObject(name)
                val size = item.get("bytes")
                require(size is Int || size is Long)
                val length = (size as Number).toLong()
                val hash = item.getString("sha256")
                require(length in 1..1_600_000_000L && hash.matches(Regex("[0-9a-f]{64}")))
                if (!name.endsWith(".onnx")) require(length <= 8_388_608)
                ParakeetFiles.ALL.find { name == "multilingual-asr/${it.name}" }?.let {
                    require(length == it.bytes && hash == it.sha256) { "Изменён встроенный комплект Parakeet" }
                }
                ModelFileRecord(name, length, hash)
            }
            // Version 2 explicitly adds the pinned 670,576,784-byte Parakeet group.
            // Acoustic/server/download limits stay unchanged; individual files remain bounded above.
            require(files.sumOf { it.bytes } <= 2_200_000_000L)
            return EmbeddedBundle(expectedDigest, files)
        }
    }
}

/** No audio, network, ZIP extraction or whole-model byte arrays. Caller serializes installation. */
internal class EmbeddedModelInstaller(
    private val root: File,
    private val bundle: EmbeddedBundle,
    private val open: (String) -> InputStream,
    private val isLeased: (File) -> Boolean = { false },
    private val availableBytes: () -> Long = { root.usableSpace },
) {
    private fun interrupted() {
        if (Thread.currentThread().isInterrupted) throw InterruptedException("Подготовка моделей отменена")
    }

    fun valid(installation: EmbeddedInstallation, hashes: Boolean = true, staging: Boolean = false): Boolean = try {
        val parent = requireNotNull(installation.acoustic.canonicalFile.parentFile)
        require(parent == installation.text.canonicalFile.parentFile)
        require(parent == installation.multilingual.canonicalFile.parentFile)
        require(if (staging) parent.parentFile == root.canonicalFile && parent.name.startsWith(".embedded-stage-")
            else parent == root.canonicalFile)
        for (entry in bundle.files) {
            interrupted()
            val file = file(installation, entry.name)
            require(!Files.isSymbolicLink(file.toPath()) && file.isFile && file.length() == entry.bytes)
            if (hashes) require(file.inputStream().use(::sha256) == entry.sha256)
        }
        require(installation.text.list()?.toSet() == EmbeddedBundle.textNames)
        require(installation.multilingual.list()?.toSet() == EmbeddedBundle.multilingualNames)
        ModelSpec.load(installation.acoustic)
        true
    } catch (error: Exception) {
        if (error is InterruptedException) throw error
        false
    }

    fun install(existing: EmbeddedInstallation? = null, progress: (Long, Long) -> Unit = { _, _ -> }): EmbeddedInstallation {
        check(root.isDirectory || root.mkdirs()) { "Не удалось открыть хранилище моделей" }
        // Only our abandoned staging names; selected revisions and active leases are not touched.
        root.listFiles()?.filter { it.name.startsWith(".embedded-stage-") }?.forEach {
            check(if (Files.isSymbolicLink(it.toPath())) it.delete() else it.deleteRecursively()) {
                "Не удалось удалить незавершённую подготовку моделей"
            }
        }
        if (existing != null && valid(existing)) return existing
        // Process death may happen after either atomic rename but before selection.
        // Reuse a fully verified pair; reclaim only our unselected, unleased partial
        // promotions so a cold install does not permanently lose its disk budget.
        root.listFiles()?.filter { it.name.startsWith("gigaam-embedded-") }?.forEach { acoustic ->
            val candidate = EmbeddedInstallation(acoustic, File(root, "text-${acoustic.name.removePrefix("gigaam-")}"))
            if (valid(candidate)) return candidate
            if (acoustic != existing?.acoustic && !isLeased(acoustic)) {
                if (Files.isSymbolicLink(acoustic.toPath())) acoustic.delete() else acoustic.deleteRecursively()
                if (Files.isSymbolicLink(candidate.text.toPath())) candidate.text.delete() else candidate.text.deleteRecursively()
                if (Files.isSymbolicLink(candidate.multilingual.toPath())) candidate.multilingual.delete() else candidate.multilingual.deleteRecursively()
            }
        }
        root.listFiles()?.filter { it.name.startsWith("text-embedded-") }?.forEach { text ->
            val acoustic = File(root, "gigaam-${text.name.removePrefix("text-")}")
            if (!acoustic.exists() && !isLeased(acoustic)) {
                if (Files.isSymbolicLink(text.toPath())) text.delete() else text.deleteRecursively()
            }
        }
        root.listFiles()?.filter { it.name.startsWith("multilingual-embedded-") }?.forEach { multilingual ->
            val acoustic = File(root, "gigaam-${multilingual.name.removePrefix("multilingual-")}")
            if (!acoustic.exists() && !isLeased(acoustic)) {
                if (Files.isSymbolicLink(multilingual.toPath())) multilingual.delete() else multilingual.deleteRecursively()
            }
        }
        require(availableBytes() >= bundle.totalBytes + EMBEDDED_MODEL_DISK_RESERVE_BYTES) { "Недостаточно места для встроенных моделей" }
        val id = "embedded-${bundle.digest}-${UUID.randomUUID()}"
        val staging = File(root, ".embedded-stage-$id")
        val result = EmbeddedInstallation(File(root, "gigaam-$id"), File(root, "text-$id"))
        val staged = EmbeddedInstallation(File(staging, "acoustic"), File(staging, "text-model"), File(staging, "multilingual-asr"))
        check(staged.acoustic.mkdirs() && staged.text.mkdir() && staged.multilingual.mkdir())
        var promoted = false
        try {
            var completed = 0L
            for (entry in bundle.files) {
                interrupted()
                require(availableBytes() >= entry.bytes + EMBEDDED_MODEL_DISK_RESERVE_BYTES) { "Недостаточно места для встроенных моделей" }
                val target = file(staged, entry.name)
                val digest = MessageDigest.getInstance("SHA-256")
                var length = 0L
                open(entry.name).use { source ->
                    FileOutputStream(target).use { output ->
                        val buffer = ByteArray(128 * 1024)
                        while (true) {
                            interrupted()
                            val count = source.read(buffer)
                            if (count < 0) break
                            if (count == 0) continue
                            length += count
                            require(length <= entry.bytes) { "Неверный размер встроенного файла" }
                            digest.update(buffer, 0, count)
                            output.write(buffer, 0, count)
                            progress(completed + length, bundle.totalBytes)
                        }
                        output.fd.sync()
                    }
                }
                require(length == entry.bytes && digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) } == entry.sha256) {
                    "Повреждён встроенный файл модели"
                }
                completed += length
            }
            check(valid(staged, hashes = false, staging = true)) { "Встроенные модели несовместимы" }
            // Same filesystem; selection happens only after ALL THREE immutable directories exist.
            Files.move(staged.acoustic.toPath(), result.acoustic.toPath(), StandardCopyOption.ATOMIC_MOVE)
            Files.move(staged.text.toPath(), result.text.toPath(), StandardCopyOption.ATOMIC_MOVE)
            Files.move(staged.multilingual.toPath(), result.multilingual.toPath(), StandardCopyOption.ATOMIC_MOVE)
            promoted = true
            return result
        } finally {
            staging.deleteRecursively()
            if (!promoted) {
                result.acoustic.deleteRecursively()
                result.text.deleteRecursively()
                result.multilingual.deleteRecursively()
            }
        }
    }

    private fun file(installation: EmbeddedInstallation, name: String): File =
        if (name.startsWith("acoustic/")) File(installation.acoustic, name.removePrefix("acoustic/"))
        else if (name.startsWith("text-model/")) File(installation.text, name.removePrefix("text-model/"))
        else File(installation.multilingual, name.removePrefix("multilingual-asr/"))

}
