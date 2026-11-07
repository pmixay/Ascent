package com.pmixay.ascent

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.sin
import kotlin.math.sqrt

class PcmWaveTest {
    private fun pcmFixture(
        rate: Int, channels: Int, bits: Int, frames: Int, value: Int = 0,
        signal: ((Int, Int) -> Int)? = null,
    ): ByteArray {
        val sampleBytes = bits / 8
        val pcm = ByteBuffer.allocate(frames * channels * sampleBytes).order(ByteOrder.LITTLE_ENDIAN)
        repeat(frames * channels) { index ->
            val sample = signal?.invoke(index / channels, index % channels) ?: value
            if (bits == 8) pcm.put(sample.toByte()) else pcm.putShort(sample.toShort())
        }
        val chunks = ByteArrayOutputStream()
        fun int(number: Int, size: Int = 4) = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN).apply {
            if (size == 4) putInt(number) else putShort(number.toShort())
        }.array()
        chunks.write("fmt ".toByteArray()); chunks.write(int(16))
        chunks.write(int(1, 2)); chunks.write(int(channels, 2)); chunks.write(int(rate))
        chunks.write(int(rate * channels * sampleBytes)); chunks.write(int(channels * sampleBytes, 2))
        chunks.write(int(bits, 2))
        chunks.write("data".toByteArray()); chunks.write(int(pcm.position())); chunks.write(pcm.array())
        return "RIFF".toByteArray() + int(chunks.size() + 4) + "WAVE".toByteArray() + chunks.toByteArray()
    }

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

    @Test fun fileReaderIsABoundedWindowSourceWithOriginalCoordinates() {
        val file = File.createTempFile("audio", ".wav")
        try {
            file.writeBytes(fixture())
            val source: PcmWindowSource = PcmWave(file)
            source.use {
                assertEquals(3L, it.sampleCount)
                assertEquals(0L, it.durationMs)
                assertArrayEquals(floatArrayOf(0f, 32767f / 32768), it.read(1, 2), 0.00001f)
                assertThrows(IllegalArgumentException::class.java) { it.read(2, 2) }
            }
        } finally { file.delete() }
    }

    @Test fun anInMemorySourceDerivesDurationFromItsSampleCount() {
        val samples = FloatArray(32000) { it / 32000f }
        val source = object : PcmWindowSource {
            override val sampleCount = samples.size.toLong()
            override fun read(start: Long, count: Int): FloatArray {
                require(start >= 0 && count >= 0 && start + count <= sampleCount)
                return samples.copyOfRange(start.toInt(), start.toInt() + count)
            }
            override fun close() = Unit
        }
        assertEquals(2000L, source.durationMs)
        assertEquals(0.5f, source.read(16000, 1)[0], 0f)
    }

    @Test fun rejectsTruncatedPcmInsteadOfTranscribingGarbage() {
        val file = File.createTempFile("audio", ".wav")
        try {
            file.writeBytes(fixture().dropLast(1).toByteArray())
            assertThrows(IllegalArgumentException::class.java) { PcmWave(file).close() }
        } finally { file.delete() }
    }

    @Test fun resamplesCommonStereoRatesWithoutChangingDurationOrWindowBoundary() {
        for (rate in listOf(44_100, 48_000)) {
            val file = File.createTempFile("audio", ".wav")
            try {
                file.writeBytes(pcmFixture(rate, 2, 16, rate, 8192))
                PcmWave(file).use { wave ->
                    assertEquals(16_000L, wave.sampleCount)
                    assertEquals(1_000L, wave.durationMs)
                    val all = wave.read(0, 16_000)
                    val split = wave.read(0, 8_000) + wave.read(8_000, 8_000)
                    assertArrayEquals(all, split, 0.00001f)
                    assertTrue(all.all { kotlin.math.abs(it - .25f) < .00001f })
                }
            } finally { file.delete() }
        }
    }

    @Test fun antiPhaseStereoKeepsOneAudibleChannelAcrossSplitReads() {
        val file = File.createTempFile("audio", ".wav")
        try {
            file.writeBytes(pcmFixture(16_000, 2, 16, 16_000,
                signal = { _, channel -> if (channel == 0) 24_576 else -24_576 }))
            PcmWave(file).use { wave ->
                val all = wave.read(0, 16_000)
                assertTrue(all.all { kotlin.math.abs(it - .75f) < .00001f })
                assertArrayEquals(all, wave.read(0, 8_000) + wave.read(8_000, 8_000), .00001f)
            }
        } finally { file.delete() }
    }

    @Test fun expandsEightBitMonoPcmWithoutTreatingUnsignedSilenceAsSpeech() {
        val file = File.createTempFile("audio", ".wav")
        try {
            file.writeBytes(pcmFixture(8_000, 1, 8, 8_000, 128))
            PcmWave(file).use { wave ->
                assertEquals(16_000L, wave.sampleCount)
                assertTrue(wave.read(0, 16_000).all { it == 0f })
            }
        } finally { file.delete() }
    }

    private fun tone(rate: Int, frequency: Int): ByteArray = pcmFixture(rate, 1, 16, rate,
        signal = { frame, _ -> (sin(2 * PI * frequency * frame / rate) * 24576).toInt() })

    private fun interiorRms(samples: FloatArray): Double {
        val interior = 512 until samples.size - 512
        return sqrt(interior.sumOf { samples[it].toDouble() * samples[it] } / interior.count())
    }

    @Test fun retainsSpeechBandToneAndSuppressesAliasedHighFrequencyTones() {
        for (rate in listOf(44_100, 48_000)) {
            val file = File.createTempFile("audio", ".wav")
            try {
                file.writeBytes(tone(rate, 1_000))
                PcmWave(file).use { wave ->
                    val all = wave.read(0, 16_000)
                    assertTrue("1 kHz tone was attenuated at $rate Hz", interiorRms(all) > .49)
                    assertArrayEquals(all, wave.read(0, 6_431) + wave.read(6_431, 9_569), .00001f)
                }
                for (frequency in listOf(11_000, 12_000)) {
                    file.writeBytes(tone(rate, frequency))
                    PcmWave(file).use { wave ->
                        assertTrue("$frequency Hz aliases into the speech band at $rate Hz",
                            interiorRms(wave.read(0, 16_000)) < .03)
                    }
                }
            } finally { file.delete() }
        }
    }

    @Test fun shortNonintegerDurationKeepsTheLastSourceSampleWithinReadableBounds() {
        val file = File.createTempFile("audio", ".wav")
        try {
            file.writeBytes(pcmFixture(44_100, 1, 16, 100, 8192))
            PcmWave(file).use { wave ->
                assertEquals(37L, wave.sampleCount)
                assertEquals(2L, wave.durationMs)
                val all = wave.read(0, 37)
                assertTrue(all.all { it.isFinite() })
                assertArrayEquals(floatArrayOf(all.last()), wave.read(36, 1), .00001f)
                assertTrue(wave.read(37, 0).isEmpty())
                assertThrows(IllegalArgumentException::class.java) { wave.read(37, 1) }
            }
        } finally { file.delete() }
    }
}
