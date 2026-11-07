package com.pmixay.ascent

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

/** In-memory stand-in for the picker's file descriptor. Positions past [bytes] read as zeros. */
internal class BytesInput(private val bytes: ByteArray, override val size: Long = bytes.size.toLong()) : RandomAccessInput {
    var closed = false
    override fun readAt(position: Long, target: ByteArray, offset: Int, length: Int): Int {
        if (position >= size) return 0
        val count = minOf(length.toLong(), size - position).toInt()
        for (i in 0 until count) {
            val at = position + i
            target[offset + i] = if (at < bytes.size) bytes[at.toInt()] else 0
        }
        return count
    }
    override fun close() { closed = true }
}

class WavWindowSourceTest {
    private fun le(value: Long, size: Int) = ByteArray(size) { ((value shr (8 * it)) and 0xFF).toByte() }

    private fun wav(
        rate: Int, channels: Int, bits: Int, code: Int, data: ByteArray,
        dataLength: Long = data.size.toLong(), extensible: Boolean = false, junkBefore: Boolean = false,
    ): ByteArray {
        val fmt = ByteArrayOutputStream()
        fmt.write(le((if (extensible) 0xFFFE else code).toLong(), 2))
        fmt.write(le(channels.toLong(), 2))
        fmt.write(le(rate.toLong(), 4))
        fmt.write(le((rate * channels * bits / 8).toLong(), 4))
        fmt.write(le((channels * bits / 8).toLong(), 2))
        fmt.write(le(bits.toLong(), 2))
        if (extensible) {
            fmt.write(le(22, 2)); fmt.write(le(bits.toLong(), 2)); fmt.write(le(0, 4))
            fmt.write(le(code.toLong(), 2)); fmt.write(ByteArray(14))
        }
        val body = ByteArrayOutputStream()
        body.write("WAVE".toByteArray())
        if (junkBefore) { body.write("JUNK".toByteArray()); body.write(le(1, 4)); body.write(byteArrayOf(42, 0)) }
        body.write("fmt ".toByteArray()); body.write(le(fmt.size().toLong(), 4)); body.write(fmt.toByteArray())
        body.write("data".toByteArray()); body.write(le(dataLength, 4)); body.write(data)
        return "RIFF".toByteArray() + le(body.size().toLong(), 4) + body.toByteArray()
    }

    private fun pcm16(vararg samples: Int) = ByteArray(samples.size * 2).also { out ->
        samples.forEachIndexed { i, s -> out[2 * i] = (s and 0xFF).toByte(); out[2 * i + 1] = ((s shr 8) and 0xFF).toByte() }
    }

    private fun open(bytes: ByteArray) = WavWindowSource.open(BytesInput(bytes))

    private fun sine(rate: Int, hertz: Double, seconds: Double, amplitude: Double): ByteArray {
        val count = (rate * seconds).toInt()
        return pcm16(*IntArray(count) { (amplitude * 32767 * sin(2 * PI * hertz * it / rate)).toInt() })
    }

    private fun assertSineMatches(rate: Int, hertz: Double) {
        val source = open(wav(rate, 1, 16, 1, sine(rate, hertz, 1.0, 0.5)))
        assertEquals(16_000L, source.sampleCount)
        val samples = source.read(0, 16_000)
        var worst = 0.0
        for (n in 100 until 15_900) {
            worst = maxOf(worst, abs(samples[n] - 0.5 * sin(2 * PI * hertz * n / 16_000)))
        }
        assertTrue("$rate Hz tone differs from the ideal 16 kHz tone by $worst", worst < 0.02)
    }

    @Test fun readsSixteenKilohertzMonoPcm16WithoutConversionAndOwnsItsInput() {
        val input = BytesInput(wav(16_000, 1, 16, 1, pcm16(-32768, 0, 32767, 16384)))
        val source = WavWindowSource.open(input)
        assertEquals(4L, source.sampleCount)
        assertArrayEquals(floatArrayOf(-1f, 0f, 32767f / 32768, 0.5f), source.read(0, 4), 0f)
        assertArrayEquals(floatArrayOf(0f, 32767f / 32768), source.read(1, 2), 0f)
        assertFalse(input.closed)
        source.close()
        assertTrue(input.closed)
    }

    @Test fun convertsEverySupportedSampleFormat() {
        fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }
        fun read(rate: Int, bits: Int, code: Int, data: ByteArray) = open(wav(rate, 1, bits, code, data)).read(0, data.size / (bits / 8))
        assertArrayEquals(floatArrayOf(-1f, 0f, 127f / 128), read(16_000, 8, 1, bytes(0, 128, 255)), 0f)
        val pcm24 = ByteArray(9).also { out ->
            listOf(-8_388_608, 0, 4_194_304).forEachIndexed { i, v -> for (b in 0..2) out[3 * i + b] = ((v shr (8 * b)) and 0xFF).toByte() }
        }
        assertArrayEquals(floatArrayOf(-1f, 0f, 0.5f), read(16_000, 24, 1, pcm24), 0f)
        val pcm32 = ByteArray(12).also { out ->
            listOf(Int.MIN_VALUE, 0, 1_073_741_824).forEachIndexed { i, v -> for (b in 0..3) out[4 * i + b] = ((v shr (8 * b)) and 0xFF).toByte() }
        }
        assertArrayEquals(floatArrayOf(-1f, 0f, 0.5f), read(16_000, 32, 1, pcm32), 0f)
        val floats = ByteArray(16).also { out ->
            listOf(-0.5f, 0.25f, 2f, Float.NaN).forEachIndexed { i, v ->
                val bits = v.toRawBits()
                for (b in 0..3) out[4 * i + b] = ((bits shr (8 * b)) and 0xFF).toByte()
            }
        }
        assertArrayEquals(floatArrayOf(-0.5f, 0.25f, 1f, 0f), read(16_000, 32, 3, floats), 0f)
        assertArrayEquals(floatArrayOf(0f, 32124f / 32768, -32124f / 32768), read(16_000, 8, 7, bytes(0xFF, 0x80, 0x00)), 0f)
        assertArrayEquals(floatArrayOf(8f / 32768, -8f / 32768, 32256f / 32768), read(16_000, 8, 6, bytes(0xD5, 0x55, 0xAA)), 0f)
    }

    @Test fun g711ExpansionMatchesTheStandardTables() {
        assertEquals(0, G711.ulaw(0xFF))
        assertEquals(0, G711.ulaw(0x7F))
        assertEquals(32124, G711.ulaw(0x80))
        assertEquals(-32124, G711.ulaw(0x00))
        assertEquals(8, G711.alaw(0xD5))
        assertEquals(-8, G711.alaw(0x55))
        assertEquals(32256, G711.alaw(0xAA))
        assertEquals(-32256, G711.alaw(0x2A))
    }

    @Test fun averagesAllChannelsIntoMono() {
        val frames = pcm16(16384, -8192, 16384, -8192, 0, 0)
        val source = open(wav(16_000, 2, 16, 1, frames))
        assertEquals(3L, source.sampleCount)
        assertArrayEquals(floatArrayOf(0.125f, 0.125f, 0f), source.read(0, 3), 1e-6f)
    }

    @Test fun acceptsExtensibleHeadersAndPaddedChunks() {
        val data = pcm16(100, -100)
        assertEquals(2L, open(wav(16_000, 1, 16, 1, data, extensible = true)).sampleCount)
        assertEquals(2L, open(wav(16_000, 1, 16, 1, data, junkBefore = true)).sampleCount)
    }

    @Test fun streamingHeaderWithUnknownDataLengthReadsToTheEndOfTheFile() {
        val source = open(wav(16_000, 1, 16, 1, pcm16(1, 2, 3, 4), dataLength = 0xFFFFFFFFL))
        assertEquals(4L, source.sampleCount)
    }

    @Test fun resamplesCommonRatesToSixteenKilohertzAtTheSameTimes() {
        assertSineMatches(48_000, 1_000.0)
        assertSineMatches(44_100, 1_000.0)
        assertSineMatches(22_050, 440.0)
        assertSineMatches(8_000, 1_000.0)
    }

    @Test fun aToneAboveTheNewNyquistFrequencyIsSuppressedNotAliased() {
        val source = open(wav(48_000, 1, 16, 1, sine(48_000, 12_000.0, 1.0, 0.5)))
        val samples = source.read(0, 16_000)
        val worst = (100 until 15_900).maxOf { abs(samples[it]) }
        assertTrue("aliased tone peak $worst", worst < 0.02)
    }

    @Test fun anyWindowEqualsTheSameSamplesOfAContinuousConversion() {
        val noise = java.util.Random(7)
        val source = open(wav(44_100, 1, 16, 1, pcm16(*IntArray(44_100) { noise.nextInt(20_000) - 10_000 })))
        val whole = source.read(0, 2_000)
        val parts = source.read(0, 1_000) + source.read(1_000, 1_000)
        assertArrayEquals(whole, parts, 0f)
        assertArrayEquals(whole.copyOfRange(777, 1_277), source.read(777, 500), 0f)
    }

    @Test fun windowsAtBothEdgesAreReadableAndOutOfRangeIsRejected() {
        val source = open(wav(48_000, 1, 16, 1, sine(48_000, 300.0, 0.5, 0.3)))
        assertEquals(8_000L, source.sampleCount)
        assertEquals(10, source.read(source.sampleCount - 10, 10).size)
        assertEquals(0, source.read(0, 0).size)
        assertThrows(IllegalArgumentException::class.java) { source.read(source.sampleCount - 5, 10) }
        assertThrows(IllegalArgumentException::class.java) { source.read(-1, 1) }
    }

    private fun newStream(rate: Int, channels: Int, limit: Long = 1_000_000) = Pcm16Blocks().let { it to Pcm16Stream(rate, channels, it, limit) }

    @Test fun streamingConversionEqualsTheRandomAccessConversionForAnyDecoderChunking() {
        val noise = java.util.Random(11)
        val samples = IntArray(44_100) { noise.nextInt(20_000) - 10_000 }
        val expected = open(wav(44_100, 1, 16, 1, pcm16(*samples))).read(0, 16_000)
        val (blocks, stream) = newStream(44_100, 1)
        val sizes = intArrayOf(1_000, 777, 4_096, 1, 13)
        var at = 0
        var turn = 0
        while (at < samples.size) {
            val count = minOf(sizes[turn++ % sizes.size], samples.size - at)
            stream.push(ShortArray(count) { samples[at + it].toShort() }, count)
            at += count
        }
        stream.finish()
        assertEquals(16_000L, blocks.size)
        for (n in 0 until 16_000) {
            assertEquals("sample $n", expected[n], blocks[n.toLong()] / 32768f, 2.5f / 32768)
        }
    }

    @Test fun streamingWithoutAResamplerAveragesInterleavedChannelsAndKeepsEverySample() {
        val (blocks, stream) = newStream(16_000, 2)
        stream.push(shortArrayOf(16384, -8192, 16384, -8192, 0, 0), 3)
        stream.finish()
        assertEquals(3L, blocks.size)
        assertEquals(listOf<Short>(4096, 4096, 0), listOf(blocks[0], blocks[1], blocks[2]))
    }

    @Test fun aStreamThatStartsLaterBeginsWithSilenceSoPositionsKeepOriginalTime() {
        val (blocks, stream) = newStream(16_000, 1)
        stream.pushSilence(1_600)
        stream.push(ShortArray(100) { 1_000 }, 100)
        stream.finish()
        assertEquals(1_700L, blocks.size)
        assertEquals(0.toShort(), blocks[1_599])
        assertEquals(1_000.toShort(), blocks[1_600])
    }

    @Test fun aDecodeLongerThanTheLimitFailsInsteadOfBeingTruncated() {
        val (_, stream) = newStream(16_000, 1, limit = 1_000)
        val error = assertThrows(AudioImportException::class.java) { stream.push(ShortArray(2_000), 2_000) }
        assertEquals(ImportFailure.TOO_LONG, error.failure)
    }

    private fun failure(bytes: ByteArray, size: Long = bytes.size.toLong()): ImportFailure =
        assertThrows(AudioImportException::class.java) { WavFormat.parse(BytesInput(bytes, size)) }.failure

    @Test fun reportsEachKindOfBadFileWithItsOwnCategory() {
        val good = wav(16_000, 1, 16, 1, pcm16(1, 2, 3, 4))
        assertEquals(ImportFailure.MALFORMED, failure(good.copyOf(good.size - 3)))
        assertEquals(ImportFailure.MALFORMED, failure("RIFF....WAVEjunk".toByteArray()))
        assertEquals(ImportFailure.MALFORMED, failure("not a wave file at all".toByteArray()))
        assertEquals(ImportFailure.UNSUPPORTED, failure(wav(16_000, 1, 16, 2, pcm16(1, 2))))
        assertEquals(ImportFailure.UNSUPPORTED, failure(wav(16_000, 1, 12, 1, pcm16(1, 2))))
        assertEquals(ImportFailure.UNSUPPORTED, failure(wav(16_000, 9, 16, 1, pcm16(1, 2))))
        assertEquals(ImportFailure.EMPTY, failure(wav(16_000, 1, 16, 1, ByteArray(0))))
    }

    @Test fun anEmptyFileIsRejectedBeforeAnyDecoderIsConsulted() {
        val error = assertThrows(AudioImportException::class.java) { AudioSources.openInput(BytesInput(ByteArray(0))) }
        assertEquals(ImportFailure.EMPTY, error.failure)
    }

    @Test fun aRecordingOverTheDeclaredLimitFailsInsteadOfBeingTruncated() {
        val seconds = AudioSources.MAX_DURATION_MS / 1000 + 1
        val header = wav(16_000, 1, 16, 1, ByteArray(0), dataLength = 16_000L * 2 * seconds)
        assertEquals(ImportFailure.TOO_LONG, failure(header, size = header.size + 16_000L * 2 * seconds))
    }
}
