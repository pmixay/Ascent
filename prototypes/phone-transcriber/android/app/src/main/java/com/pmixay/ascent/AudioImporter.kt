package com.pmixay.ascent

import android.content.Context
import android.net.Uri
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.util.UUID

/** Deliberately accepts PCM16 / mono / 16 kHz WAV; no lossy or silently incorrect conversion. */
object AudioImporter {
    fun importWav(context: Context, uri: Uri): File {
        val folder = File(context.filesDir, "recordings").apply { mkdirs() }
        val raw = File.createTempFile("import-", ".part", context.cacheDir)
        val output = File(folder, "conversation-${UUID.randomUUID()}.wav")
        try {
            val input = context.contentResolver.openInputStream(uri) ?: throw IOException("Не удалось открыть файл")
            input.use { source ->
                raw.outputStream().use { target ->
                    val buffer = ByteArray(64 * 1024)
                    var total = 0L
                    while (true) {
                        checkInterrupted()
                        val count = source.read(buffer)
                        if (count < 0) break
                        total += count
                        require(total <= AudioRecorder.MAX_PCM_BYTES + 1024 * 1024) { "Максимальная длина записи — 30 минут" }
                        target.write(buffer, 0, count)
                    }
                }
            }
            RandomAccessFile(raw, "r").use { source ->
                require(source.length() >= 44 && readTag(source) == "RIFF") { "Выберите WAV-файл" }
                val riffEnd = readUnsignedInt(source) + 8
                require(readTag(source) == "WAVE" && riffEnd <= source.length()) { "Повреждённый WAV-файл" }
                var validFormat = false
                var dataStart = -1L
                var dataSize = 0L
                while (source.filePointer + 8 <= riffEnd) {
                    checkInterrupted()
                    val tag = readTag(source)
                    val size = readUnsignedInt(source)
                    val start = source.filePointer
                    val end = start + size
                    require(end <= riffEnd) { "Повреждённый WAV-файл" }
                    if (tag == "fmt ") {
                        require(size >= 16) { "Повреждённый формат WAV" }
                        val encoding = readUnsignedShort(source)
                        val channels = readUnsignedShort(source)
                        val rate = readUnsignedInt(source)
                        val byteRate = readUnsignedInt(source)
                        val blockAlign = readUnsignedShort(source)
                        val bits = readUnsignedShort(source)
                        require(encoding == 1 && channels == 1 && rate == 16000L && bits == 16 &&
                            byteRate == 32000L && blockAlign == 2) {
                            "Нужен WAV: PCM 16 бит, моно, 16 кГц. Запишите разговор в приложении или преобразуйте файл."
                        }
                        validFormat = true
                    } else if (tag == "data") {
                        require(dataStart < 0) { "WAV с несколькими блоками data не поддерживается" }
                        dataStart = start
                        dataSize = size
                    }
                    source.seek(end + (size and 1L))
                }
                require(validFormat && dataStart >= 0 && dataSize > 0 && dataSize % 2 == 0L) { "В WAV отсутствуют корректные аудиоданные" }
                require(dataSize <= AudioRecorder.MAX_PCM_BYTES) { "Максимальная длина записи — 30 минут" }
                source.seek(dataStart)
                RandomAccessFile(output, "rw").use { target ->
                    AudioRecorder.writeWavHeader(target, dataSize)
                    val buffer = ByteArray(64 * 1024)
                    var remaining = dataSize
                    while (remaining > 0) {
                        checkInterrupted()
                        val count = minOf(buffer.size.toLong(), remaining).toInt()
                        source.readFully(buffer, 0, count)
                        target.write(buffer, 0, count)
                        remaining -= count
                    }
                }
            }
            return output
        } catch (error: Throwable) {
            output.delete()
            throw error
        } finally {
            raw.delete()
        }
    }

    private fun readTag(file: RandomAccessFile): String = ByteArray(4).also { file.readFully(it) }.toString(Charsets.US_ASCII)
    private fun readUnsignedInt(file: RandomAccessFile): Long = Integer.reverseBytes(file.readInt()).toLong() and 0xffffffffL
    private fun readUnsignedShort(file: RandomAccessFile): Int = java.lang.Short.reverseBytes(file.readShort()).toInt() and 0xffff
    private fun checkInterrupted() { if (Thread.currentThread().isInterrupted) throw InterruptedException("Операция отменена") }
}
