package com.pmixay.ascent

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
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
            require(manifestFile.length() in 1..1_048_576) { "Некорректный манифест моделей" }
            val manifest = JSONObject(manifestFile.readText())
            require(manifest.getInt("schema_version") == 1 && manifest.getString("model_id") == MODEL_ID) {
                "Нужен пакет GigaAM Multilingual 220M для Ascent"
            }
            require(manifest.getInt("sample_rate") == 16000 && manifest.getInt("channels") == 1)
            require(manifest.getInt("min_samples") == 4000 && manifest.getInt("max_samples") == 400000)
            require(manifest.getString("asr_file") == "asr.onnx" && manifest.getString("tokens_file") == "tokens.json")
            require(manifest.getString("speaker_file") == "speaker.onnx")
            require(manifest.getString("speaker_model_id") == "3dspeaker-eres2netv2-2024")
            require(manifest.getString("speaker_runtime") == "sherpa-onnx")
            val files = manifest.getJSONObject("files")
            val expected = mutableSetOf("manifest.json")
            files.keys().forEach { name ->
                require(isSafePackName(name)) { "Недопустимое имя файла модели" }
                expected += name
                val entry = files.getJSONObject(name)
                val file = File(directory, name)
                require(file.isFile && file.length() == entry.getLong("bytes")) { "Отсутствует или повреждён $name" }
                val hash = entry.getString("sha256")
                require(hash.matches(Regex("[0-9a-f]{64}")))
                if (verifyHashes) require(file.inputStream().use(::sha256) == hash) { "Не совпадает SHA-256: $name" }
            }
            require(expected == setOf("manifest.json", "asr.onnx", "speaker.onnx", "tokens.json", "LICENSE-asr.txt", "LICENSE-speaker.txt", "smoke-report.json"))
            require(directory.listFiles()?.map { it.name }?.toSet() == expected) { "Неожиданные файлы в пакете" }
            val tokenFile = File(directory, "tokens.json")
            require(tokenFile.length() in 1..1_048_576)
            val array = JSONArray(tokenFile.readText())
            require(array.length() in 2 until 65536)
            val tokens = (0 until array.length()).map { array.getString(it) }
            require(tokens.all { it.isNotEmpty() }) { "Пустой токен в словаре модели" }
            val blank = manifest.getInt("blank_id")
            require(blank == tokens.size && manifest.getInt("vocabulary_size") == tokens.size)
            return ModelSpec(directory, tokens, blank)
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
