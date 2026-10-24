package com.pmixay.ascent

import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.File
import java.io.InputStream
import java.io.RandomAccessFile
import java.net.URI
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.GZIPInputStream
import javax.net.ssl.HttpsURLConnection

internal const val MODEL_CATALOG_URL = "https://ascent.arbuz.lol/models/catalog.json"
internal const val MAX_MODEL_MANIFEST_BYTES = 128 * 1024
private const val MAX_CATALOG_BYTES = 16 * 1024
private const val FREE_SPACE_RESERVE = 32L * 1024 * 1024
private const val CHUNK_BYTES = 4 * 1024 * 1024
private const val MAX_DOWNLOAD_INDEX_BYTES = 512 * 1024

internal data class ModelFileRecord(val name: String, val bytes: Long, val sha256: String)

/** The catalog selects an immutable directory; it cannot supply another server or arbitrary paths. */
internal data class ModelCatalog(val manifestSha256: String, val manifestBytes: Long,
    val downloadSha256: String? = null, val downloadBytes: Long = 0) {
    val directory: String get() = "${ModelSpec.MODEL_ID}/$manifestSha256/"
    val downloadDirectory: String get() = "${ModelSpec.MODEL_ID}/downloads/$downloadSha256/"

    companion object {
        fun parse(bytes: ByteArray): ModelCatalog {
            require(bytes.size in 1..MAX_CATALOG_BYTES) { "Некорректный каталог моделей" }
            val json = JSONObject(bytes.decodeToString(throwOnInvalidSequence = true))
            require(json.integer("schema_version") == 1L && json.getString("model_id") == ModelSpec.MODEL_ID) {
                "Каталог моделей не подходит этой версии приложения"
            }
            val hash = json.getString("manifest_sha256")
            require(hash.matches(Regex("[0-9a-f]{64}"))) { "Некорректная контрольная сумма манифеста" }
            val length = json.integer("manifest_bytes")
            require(length in 1..MAX_MODEL_MANIFEST_BYTES.toLong()) { "Манифест моделей слишком большой" }
            val downloadHash = if (json.has("download_sha256")) json.getString("download_sha256") else null
            val downloadLength = if (downloadHash != null) json.integer("download_bytes") else 0L
            require(downloadHash == null || (downloadHash.matches(Regex("[0-9a-f]{64}")) &&
                downloadLength in 1..MAX_DOWNLOAD_INDEX_BYTES.toLong())) { "Некорректный индекс загрузки" }
            return ModelCatalog(hash, length, downloadHash, downloadLength)
        }
    }
}

internal data class ModelChunk(val bytes: Long, val sha256: String, val rawBytes: Long, val rawSha256: String)

internal fun parseDownloadIndex(bytes: ByteArray, catalog: ModelCatalog, manifest: ModelManifest): Map<String, List<ModelChunk>> {
    require(bytes.size in 1..MAX_DOWNLOAD_INDEX_BYTES)
    val json = JSONObject(bytes.decodeToString(throwOnInvalidSequence = true))
    require(json.integer("schema_version") == 1L && json.integer("chunk_bytes") == CHUNK_BYTES.toLong())
    require(json.getString("manifest_sha256") == catalog.manifestSha256)
    val files = json.getJSONObject("files")
    require(files.keys().asSequence().toSet() == ModelManifest.payloadNames)
    return manifest.files.associate { file ->
        val records = files.getJSONArray(file.name)
        require(records.length().toLong() == (file.bytes + CHUNK_BYTES - 1) / CHUNK_BYTES)
        file.name to List(records.length()) { index ->
            val record = records.getJSONObject(index)
            val rawSize = minOf(CHUNK_BYTES.toLong(), file.bytes - index.toLong() * CHUNK_BYTES)
            val chunk = ModelChunk(record.integer("bytes"), record.getString("sha256"),
                record.integer("raw_bytes"), record.getString("raw_sha256"))
            require(chunk.bytes in 1..(CHUNK_BYTES + 65536).toLong() && chunk.rawBytes == rawSize)
            require(chunk.sha256.matches(Regex("[0-9a-f]{64}")) && chunk.rawSha256.matches(Regex("[0-9a-f]{64}")))
            chunk
        }
    }
}

internal data class ModelManifest(val files: List<ModelFileRecord>, val vocabularySize: Int, val blankId: Int) {
    val totalBytes: Long get() = files.sumOf { it.bytes }

    companion object {
        val payloadNames = setOf("asr.onnx", "speaker.onnx", "tokens.json", "LICENSE-asr.txt", "LICENSE-speaker.txt", "smoke-report.json")

        fun parse(bytes: ByteArray): ModelManifest {
            require(bytes.size in 1..MAX_MODEL_MANIFEST_BYTES) { "Некорректный манифест моделей" }
            val json = JSONObject(bytes.decodeToString(throwOnInvalidSequence = true))
            require(json.integer("schema_version") == 1L && json.getString("model_id") == ModelSpec.MODEL_ID) {
                "Нужны модели GigaAM Multilingual 220M для Ascent"
            }
            require(json.integer("sample_rate") == 16000L && json.integer("channels") == 1L)
            require(json.integer("min_samples") == 4000L && json.integer("max_samples") == 400000L)
            require(json.getString("asr_file") == "asr.onnx" && json.getString("tokens_file") == "tokens.json")
            require(json.getString("speaker_file") == "speaker.onnx")
            require(json.getString("speaker_model_id") == "3dspeaker-eres2netv2-2024" && json.getString("speaker_runtime") == "sherpa-onnx")
            val vocabulary = json.integer("vocabulary_size")
            require(vocabulary in 2L..65535L && json.integer("blank_id") == vocabulary)
            val entries = json.getJSONObject("files")
            require(entries.keys().asSequence().toSet() == payloadNames) { "Неверный список файлов моделей" }
            val records = payloadNames.sorted().map { name ->
                val item = entries.getJSONObject(name)
                val size = item.integer("bytes")
                val limit = if (name.endsWith(".onnx")) 1_600_000_000L else 1_048_576L
                require(size in 1..limit) { "Недопустимый размер файла модели" }
                val hash = item.getString("sha256")
                require(hash.matches(Regex("[0-9a-f]{64}"))) { "Некорректная контрольная сумма модели" }
                ModelFileRecord(name, size, hash)
            }
            require(records.sumOf { it.bytes } <= 2_000_000_000L) { "Модели слишком большие" }
            return ModelManifest(records, vocabulary.toInt(), vocabulary.toInt())
        }
    }
}

private fun JSONObject.integer(name: String): Long {
    val value = get(name)
    require(value is Int || value is Long) { "Некорректное значение в манифесте: $name" }
    return (value as Number).toLong()
}

internal class ModelResponse(val input: InputStream, val length: Long, private val disconnect: () -> Unit = {}) : Closeable {
    override fun close() {
        try { input.close() } finally { disconnect() }
    }
}

internal fun interface ModelSource {
    fun open(relativePath: String): ModelResponse
}

/** GET-only access to the fixed model host. No audio, transcript, credentials or identifiers are sent. */
internal class HttpsModelSource : ModelSource {
    override fun open(relativePath: String): ModelResponse {
        val base = URI(MODEL_CATALOG_URL).resolve(".")
        val target = base.resolve(relativePath)
        require(target.scheme == "https" && target.host == base.host && target.port == base.port &&
            target.rawUserInfo == null && target.rawQuery == null && target.rawFragment == null &&
            target.path.startsWith(base.path) && target.normalize() == target) { "Некорректный адрес модели" }
        val connection = target.toURL().openConnection() as HttpsURLConnection
        connection.requestMethod = "GET"
        connection.instanceFollowRedirects = false
        connection.connectTimeout = 15_000
        connection.readTimeout = 15_000
        connection.useCaches = false
        connection.setRequestProperty("Accept-Encoding", "identity")
        try {
            check(connection.responseCode == 200) {
                if (connection.responseCode == 404 || connection.responseCode == 503)
                    "Модели пока недоступны. Попробуйте позже."
                else "Не удалось скачать модели (HTTP ${connection.responseCode})"
            }
            val encoding = connection.contentEncoding
            check(encoding == null || encoding.equals("identity", ignoreCase = true)) { "Неподдерживаемый ответ сервера моделей" }
            return ModelResponse(connection.inputStream, connection.contentLengthLong, connection::disconnect)
        } catch (error: Throwable) {
            connection.disconnect()
            throw error
        }
    }
}

internal data class ModelDownloadProgress(val completedBytes: Long, val totalBytes: Long, val verifying: Boolean = false)

internal class DownloadYieldException : java.io.IOException("Загрузка продолжится в фоне")

/** Verified chunks survive cancellation, process death and retries; activation remains atomic. */
internal class ModelDownloader(
    private val root: File,
    private val source: ModelSource = HttpsModelSource(),
    private val availableBytes: () -> Long = { root.usableSpace },
    private val currentDirectory: File? = null,
    private val shouldYield: () -> Boolean = { false },
) : Closeable {
    private var temporary: File? = null
    private var installed: File? = null
    private var committed = false

    fun download(progress: (ModelDownloadProgress) -> Unit): File {
        check(temporary == null && installed == null) { "Загрузка уже запущена" }
        check(root.isDirectory || root.mkdirs()) { "Не удалось подготовить хранилище моделей" }
        val catalog = ModelCatalog.parse(readSmall("catalog.json", MAX_CATALOG_BYTES))
        if (currentDirectory?.isDirectory == true &&
            fileHash(File(currentDirectory, "manifest.json")) == catalog.manifestSha256) {
            val valid = try { ModelSpec.load(currentDirectory, verifyHashes = true); true }
                catch (error: InterruptedException) { throw error }
                catch (_: Exception) { false }
            if (valid) {
                installed = currentDirectory
                committed = true
                return currentDirectory
            }
        }
        val manifestBytes = readSmall(catalog.directory + "manifest.json", MAX_MODEL_MANIFEST_BYTES, catalog.manifestBytes, catalog.manifestSha256)
        val manifest = ModelManifest.parse(manifestBytes)
        val chunks = if (catalog.downloadSha256 != null) parseDownloadIndex(readSmall(
            catalog.downloadDirectory + "download.json", MAX_DOWNLOAD_INDEX_BYTES, catalog.downloadBytes,
            catalog.downloadSha256), catalog, manifest) else null
        val staging = File(root, ".download-${catalog.manifestSha256}")
        root.listFiles()?.filter { it.name.startsWith(".download-") && it != staging }?.forEach { it.deleteRecursively() }
        val reusableBytes = staging.listFiles()?.filter { it.isFile }?.sumOf { it.length() } ?: 0
        check(availableBytes() >= (manifest.totalBytes - reusableBytes).coerceAtLeast(0) + manifestBytes.size + FREE_SPACE_RESERVE) {
            "Недостаточно места. Освободите не менее ${((manifest.totalBytes + FREE_SPACE_RESERVE) / 1_048_576) + 1} МБ."
        }
        check(staging.isDirectory || staging.mkdir()) { "Не удалось подготовить загрузку моделей" }
        temporary = staging
        File(staging, "manifest.json").writeBytes(manifestBytes)
        if (chunks != null) {
            downloadChunks(staging, catalog, manifest, chunks, progress)
            return promote(staging, progress, chunks.values.flatten().sumOf { it.bytes })
        }
        var completed = 0L
        progress(ModelDownloadProgress(0, manifest.totalBytes))
        val buffer = ByteArray(128 * 1024)
        for (entry in manifest.files) {
            interrupted()
            val existing = File(staging, entry.name)
            if (existing.length() == entry.bytes && fileHash(existing) == entry.sha256) {
                completed += entry.bytes
                progress(ModelDownloadProgress(completed, manifest.totalBytes))
                continue
            }
            source.open(catalog.directory + entry.name).use { response ->
                check(response.length < 0 || response.length == entry.bytes) { "Размер модели изменился. Повторите загрузку." }
                val digest = MessageDigest.getInstance("SHA-256")
                var count = 0L
                var lastProgress = 0L
                File(staging, entry.name).outputStream().use { output ->
                    while (true) {
                        interrupted()
                        val read = response.input.read(buffer)
                        interrupted()
                        if (read < 0) break
                        count += read
                        check(count <= entry.bytes) { "Файл модели больше заявленного размера" }
                        output.write(buffer, 0, read)
                        digest.update(buffer, 0, read)
                        val now = System.nanoTime()
                        if (now - lastProgress >= 150_000_000L) {
                            progress(ModelDownloadProgress(completed + count, manifest.totalBytes))
                            lastProgress = now
                        }
                    }
                }
                check(count == entry.bytes && digest.digest().hex() == entry.sha256) {
                    "Проверка модели не пройдена. Повторите загрузку."
                }
                completed += count
                progress(ModelDownloadProgress(completed, manifest.totalBytes))
            }
        }
        return promote(staging, progress, completed)
    }

    private fun promote(staging: File, progress: (ModelDownloadProgress) -> Unit, completed: Long): File {
        interrupted()
        progress(ModelDownloadProgress(completed, completed, verifying = true))
        // Every payload was already hash-verified while streaming; validate metadata and vocabulary too.
        ModelSpec.load(staging)
        interrupted()
        val destination = File(root, "gigaam-${UUID.randomUUID()}")
        check(staging.renameTo(destination)) { "Не удалось установить модели" }
        installed = destination
        temporary = null
        return destination
    }

    fun commit() { check(installed != null); committed = true }

    override fun close() {
        // Keep verified file prefixes so WorkManager can resume after an interruption.
        temporary?.listFiles()?.filter { it.name.endsWith(".chunk") }?.forEach { it.delete() }
        if (!committed) installed?.deleteRecursively()
    }

    private fun downloadChunks(staging: File, catalog: ModelCatalog, manifest: ModelManifest,
        chunks: Map<String, List<ModelChunk>>, progress: (ModelDownloadProgress) -> Unit) {
        val total = chunks.values.flatten().sumOf { it.bytes }
        var completed = 0L
        val buffer = ByteArray(128 * 1024)
        for (entry in manifest.files) {
            interrupted()
            val records = chunks.getValue(entry.name)
            val target = File(staging, entry.name)
            if (target.length() == entry.bytes && fileHash(target) == entry.sha256) {
                completed += records.sumOf { it.bytes }
                progress(ModelDownloadProgress(completed, total))
                continue
            }
            target.delete()
            val partial = File(staging, entry.name + ".part")
            RandomAccessFile(partial, "rw").use { file ->
                var offset = 0L
                for ((number, chunk) in records.withIndex()) {
                    interrupted()
                    if (shouldYield()) throw DownloadYieldException()
                    // Rehash persisted data instead of trusting a completion marker after a crash.
                    var reusable = false
                    if (file.length() >= offset + chunk.rawBytes) {
                        file.seek(offset)
                        val digest = MessageDigest.getInstance("SHA-256")
                        var remaining = chunk.rawBytes
                        while (remaining > 0) {
                            interrupted()
                            val count = file.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                            check(count > 0)
                            digest.update(buffer, 0, count)
                            remaining -= count
                        }
                        reusable = digest.digest().hex() == chunk.rawSha256
                    }
                    if (!reusable) {
                        file.setLength(offset)
                        val compressed = readSmall(catalog.downloadDirectory + "${entry.name}.$number.gz",
                            CHUNK_BYTES + 65536, chunk.bytes, chunk.sha256)
                        val temp = File(staging, entry.name + ".chunk")
                        try {
                            val digest = MessageDigest.getInstance("SHA-256")
                            var count = 0L
                            GZIPInputStream(compressed.inputStream()).use { input ->
                                temp.outputStream().use { output ->
                                    while (true) {
                                        interrupted()
                                        val size = input.read(buffer)
                                        if (size < 0) break
                                        count += size
                                        check(count <= chunk.rawBytes) { "Некорректный размер распакованной части" }
                                        output.write(buffer, 0, size)
                                        digest.update(buffer, 0, size)
                                    }
                                    check(count == chunk.rawBytes && digest.digest().hex() == chunk.rawSha256) { "Проверка части модели не пройдена" }
                                    output.fd.sync()
                                }
                            }
                            file.seek(offset)
                            temp.inputStream().use { input ->
                                while (true) {
                                    interrupted()
                                    val size = input.read(buffer)
                                    if (size < 0) break
                                    file.write(buffer, 0, size)
                                }
                            }
                            file.fd.sync()
                        } finally { temp.delete() }
                    }
                    offset += chunk.rawBytes
                    completed += chunk.bytes
                    progress(ModelDownloadProgress(completed, total))
                }
                file.setLength(entry.bytes)
            }
            check(fileHash(partial) == entry.sha256) { "Проверка модели не пройдена" }
            check(partial.renameTo(target)) { "Не удалось сохранить модель" }
        }
    }

    private fun readSmall(path: String, maximum: Int, expectedSize: Long? = null, expectedHash: String? = null): ByteArray {
        interrupted()
        source.open(path).use { response ->
            check(response.length < 0 || response.length <= maximum) { "Ответ сервера моделей слишком большой" }
            if (expectedSize != null) check(response.length < 0 || response.length == expectedSize) { "Манифест моделей изменился" }
            val bytes = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                interrupted()
                if (path.endsWith(".gz") && shouldYield()) throw DownloadYieldException()
                val read = response.input.read(buffer)
                interrupted()
                if (read < 0) break
                check(bytes.size() + read <= maximum) { "Ответ сервера моделей слишком большой" }
                bytes.write(buffer, 0, read)
            }
            val result = bytes.toByteArray()
            if (expectedSize != null) check(result.size.toLong() == expectedSize) { "Манифест загружен не полностью" }
            if (expectedHash != null) check(MessageDigest.getInstance("SHA-256").digest(result).hex() == expectedHash) { "Проверка манифеста моделей не пройдена" }
            return result
        }
    }
}

private fun fileHash(file: File): String? {
    if (!file.isFile) return null
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { input ->
        val buffer = ByteArray(128 * 1024)
        while (true) {
            interrupted()
            val size = input.read(buffer)
            if (size < 0) break
            digest.update(buffer, 0, size)
        }
    }
    return digest.digest().hex()
}

private fun ByteArray.hex(): String = joinToString("") { "%02x".format(it.toInt() and 255) }
private fun interrupted() { if (Thread.currentThread().isInterrupted) throw InterruptedException("Загрузка отменена") }
