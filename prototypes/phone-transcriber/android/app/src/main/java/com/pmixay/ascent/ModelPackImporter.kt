package com.pmixay.ascent

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.ZipInputStream

data class ModelSpec(val directory: File, val tokens: List<String>, val blankId: Int) {
    val asrFile: File get() = File(directory, "asr.onnx")
    val speakerFile: File get() = File(directory, "speaker.onnx")

    companion object {
        const val MODEL_ID = "gigaam-multilingual-ctc-220m-2026-06"
        fun load(directory: File, verifyHashes: Boolean = false): ModelSpec {
            val manifestFile = File(directory, "manifest.json")
            require(manifestFile.length() in 1..MAX_MODEL_MANIFEST_BYTES.toLong()) { "Некорректный манифест моделей" }
            val manifest = ModelManifest.parse(manifestFile.readBytes())
            val expected = ModelManifest.payloadNames + "manifest.json"
            manifest.files.forEach { entry ->
                val file = File(directory, entry.name)
                require(file.isFile && file.length() == entry.bytes) { "Отсутствует или повреждён ${entry.name}" }
                if (verifyHashes) require(file.inputStream().use(::sha256) == entry.sha256) { "Не совпадает SHA-256: ${entry.name}" }
            }
            require(directory.listFiles()?.map { it.name }?.toSet() == expected) { "Неожиданные файлы в пакете" }
            val tokenFile = File(directory, "tokens.json")
            require(tokenFile.length() in 1..1_048_576)
            val array = JSONArray(tokenFile.readText())
            require(array.length() in 2 until 65536)
            val tokens = (0 until array.length()).map { array.getString(it) }
            require(tokens.all { it.isNotEmpty() }) { "Пустой токен в словаре модели" }
            require(manifest.blankId == tokens.size && manifest.vocabularySize == tokens.size)
            return ModelSpec(directory, tokens, manifest.blankId)
        }
    }
}

internal fun isSafePackName(name: String): Boolean =
    name.matches(Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")) && name != ".."

internal fun sha256(input: InputStream): String {
    val digest = MessageDigest.getInstance("SHA-256")
    val buffer = ByteArray(128 * 1024)
    while (true) {
        if (Thread.currentThread().isInterrupted) throw InterruptedException()
        val count = input.read(buffer)
        if (count < 0) break
        digest.update(buffer, 0, count)
    }
    return digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
}

object ModelPackImporter {
    fun importZip(context: Context, uri: Uri): File {
        val root = File(context.filesDir, "models").apply { mkdirs() }
        return context.contentResolver.openInputStream(uri)?.use { extract(it, root) }
            ?: throw IllegalStateException("Не удалось открыть пакет моделей")
    }

    /** Flat, bounded extraction; previous models remain intact until a new pack passes validation. */
    internal fun extract(input: InputStream, root: File): File {
        root.mkdirs()
        val temporary = File(root, ".import-${UUID.randomUUID()}")
        check(temporary.mkdir())
        try {
            val seen = mutableSetOf<String>()
            var total = 0L
            ZipInputStream(input.buffered()).use { zip ->
                val buffer = ByteArray(128 * 1024)
                while (true) {
                    val entry = zip.nextEntry ?: break
                    require(!entry.isDirectory && isSafePackName(entry.name) && entry.name !in seen) { "Неверная структура ZIP" }
                    seen += entry.name
                    require(seen.size <= 20) { "Слишком много файлов в ZIP" }
                    var size = 0L
                    File(temporary, entry.name).outputStream().use { output ->
                        while (true) {
                            if (Thread.currentThread().isInterrupted) throw InterruptedException()
                            val count = zip.read(buffer)
                            if (count < 0) break
                            size += count
                            total += count
                            val limit = if (entry.name.endsWith(".onnx")) 1_600_000_000L else 1_048_576L
                            require(size <= limit && total <= 2_000_000_000L) { "Пакет моделей слишком большой" }
                            output.write(buffer, 0, count)
                        }
                    }
                    zip.closeEntry()
                }
            }
            ModelSpec.load(temporary, verifyHashes = true)
            val installed = File(root, "gigaam-${UUID.randomUUID()}")
            check(temporary.renameTo(installed)) { "Не удалось сохранить модели" }
            return installed
        } catch (error: Throwable) {
            temporary.deleteRecursively()
            throw error
        }
    }
}
