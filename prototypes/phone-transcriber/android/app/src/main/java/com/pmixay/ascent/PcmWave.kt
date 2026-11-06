package com.pmixay.ascent

import java.io.Closeable
import java.io.File
import java.io.RandomAccessFile

/** Reads windows from disk so a long recording never becomes one giant FloatArray. */
class PcmWave(file: File) : Closeable {
    private val input = RandomAccessFile(file, "r")
    private var dataOffset = 0L
    val sampleCount: Long
    val durationMs: Long get() = sampleCount * 1000 / 16000

    init {
        try {
            require(input.length() >= 44) { "Повреждённый WAV-файл" }
            require(tag() == "RIFF") { "Ожидается WAV PCM16" }
            val riffEnd = uint32() + 8
            require(riffEnd <= input.length() && riffEnd >= 12) { "Неполный WAV-файл" }
            require(tag() == "WAVE") { "Ожидается WAV" }
            var formatOk = false
            var bytes = -1L
            while (input.filePointer + 8 <= riffEnd) {
                val kind = tag()
                val size = uint32()
                val next = input.filePointer + size + (size % 2)
                require(next <= riffEnd) { "Неполный блок WAV" }
                when (kind) {
                    "fmt " -> {
                        require(size >= 16)
                        val encoding = uint16()
                        val channels = uint16()
                        val rate = uint32()
                        uint32()
                        val align = uint16()
                        val bits = uint16()
                        require(encoding == 1 && channels == 1 && rate == 16000L && bits == 16 && align == 2) {
                            "Требуется WAV: PCM16, моно, 16000 Гц"
                        }
                        formatOk = true
                    }
                    "data" -> if (bytes < 0) { dataOffset = input.filePointer; bytes = size }
                }
                input.seek(next)
            }
            require(formatOk && bytes > 0 && bytes % 2 == 0L) { "Нет аудиоданных WAV" }
            sampleCount = bytes / 2
            require(sampleCount <= 16000L * 60 * 30) { "Максимальная длительность — 30 минут" }
        } catch (error: Throwable) {
            input.close()
            throw error
        }
    }

    fun read(start: Long, count: Int): FloatArray {
        require(start >= 0 && count >= 0 && start + count <= sampleCount)
        val bytes = ByteArray(count * 2)
        input.seek(dataOffset + start * 2)
        input.readFully(bytes)
        return FloatArray(count) { i ->
            (((bytes[i * 2].toInt() and 255) or (bytes[i * 2 + 1].toInt() shl 8)).toShort().toInt()) / 32768f
        }
    }

    private fun tag(): String = ByteArray(4).also(input::readFully).toString(Charsets.US_ASCII)
    private fun uint16(): Int = input.readUnsignedByte() or (input.readUnsignedByte() shl 8)
    private fun uint32(): Long = uint16().toLong() or (uint16().toLong() shl 16)
    override fun close() = input.close()
}
