package com.pmixay.ascent

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

class PcmWaveTest {
    private fun fixture(): ByteArray {
        val chunks = ByteArrayOutputStream()
        fun int(value: Int, size: Int = 4) = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN).apply {
            if (size == 4) putInt(value) else putShort(value.toShort())
        }.array()
        chunks.write("JUNK".toByteArray()); chunks.write(int(1)); chunks.write(byteArrayOf(42, 0))
        chunks.write("fmt ".toByteArray()); chunks.write(int(16))
        chunks.write(int(1, 2)); chunks.write(int(1, 2)); chunks.write(int(16000)); chunks.write(int(32000))
        chunks.write(int(2, 2)); chunks.write(int(16, 2))
        chunks.write("data".toByteArray()); chunks.write(int(6))
        chunks.write(int(-32768, 2)); chunks.write(int(0, 2)); chunks.write(int(32767, 2))
        return "RIFF".toByteArray() + int(chunks.size() + 4) + "WAVE".toByteArray() + chunks.toByteArray()
    }

    @Test fun skipsPaddedChunksAndReadsSignedSamples() {
        val file = File.createTempFile("audio", ".wav")
        try {
            file.writeBytes(fixture())
            PcmWave(file).use { wave ->
                assertEquals(3L, wave.sampleCount)
                assertArrayEquals(floatArrayOf(-1f, 0f, 32767f / 32768), wave.read(0, 3), 0.00001f)
                assertArrayEquals(floatArrayOf(0f), wave.read(1, 1), 0f)
            }
        } finally { file.delete() }
    }

    @Test fun rejectsTruncatedPcmInsteadOfTranscribingGarbage() {
        val file = File.createTempFile("audio", ".wav")
        try {
            file.writeBytes(fixture().dropLast(1).toByteArray())
            assertThrows(IllegalArgumentException::class.java) { PcmWave(file).close() }
        } finally { file.delete() }
    }
}
