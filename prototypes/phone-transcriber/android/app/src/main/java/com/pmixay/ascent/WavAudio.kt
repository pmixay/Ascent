package com.pmixay.ascent

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.sin

internal enum class WavEncoding { PCM, FLOAT, ALAW, ULAW }

internal data class WavFormat(
    val encoding: WavEncoding,
    val channels: Int,
    val sampleRate: Int,
    val bitsPerSample: Int,
    val dataOffset: Long,
    val dataBytes: Long,
) {
    val bytesPerFrame: Int get() = channels * (bitsPerSample / 8)
    val frames: Long get() = dataBytes / bytesPerFrame

    companion object {
        private const val MAX_CHUNKS = 4096

        /** Reads the RIFF chunk list with positional reads; the audio itself is not touched. */
        fun parse(input: RandomAccessInput): WavFormat {
            val size = input.size
            val header = input.readFully(0, 12)
            if (text(header, 0) != "RIFF" || text(header, 8) != "WAVE") {
                throw AudioImportException(ImportFailure.MALFORMED, "Файл не является WAV")
            }
            var encoding: WavEncoding? = null
            var channels = 0
            var rate = 0
            var bits = 0
            var dataOffset = -1L
            var dataBytes = 0L
            var position = 12L
            var chunks = 0
            while (position + 8 <= size && chunks++ < MAX_CHUNKS) {
                val head = input.readFully(position, 8)
                val kind = text(head, 0)
                val length = uint32(head, 4)
                val body = position + 8
                if (kind == "fmt ") {
                    if (length < 16) throw AudioImportException(ImportFailure.MALFORMED, "Повреждённый заголовок WAV")
                    val fmt = input.readFully(body, minOf(length, 40L).toInt().coerceAtLeast(16))
                    var code = uint16(fmt, 0)
                    if (code == EXTENSIBLE) {
                        if (fmt.size < 26) throw AudioImportException(ImportFailure.MALFORMED, "Повреждённый заголовок WAV")
                        code = uint16(fmt, 24)
                    }
                    channels = uint16(fmt, 2)
                    val declaredRate = uint32(fmt, 4)
                    bits = uint16(fmt, 14)
                    val parsed: WavEncoding = when (code) {
                        1 -> WavEncoding.PCM
                        3 -> WavEncoding.FLOAT
                        6 -> WavEncoding.ALAW
                        7 -> WavEncoding.ULAW
                        else -> throw AudioImportException(ImportFailure.UNSUPPORTED,
                            "Этот вариант WAV (код кодирования $code) не поддерживается. Нужен PCM, float, A-law или μ-law.")
                    }
                    encoding = parsed
                    val bitsOk = when (parsed) {
                        WavEncoding.PCM -> bits == 8 || bits == 16 || bits == 24 || bits == 32
                        WavEncoding.FLOAT -> bits == 32
                        WavEncoding.ALAW, WavEncoding.ULAW -> bits == 8
                    }
                    if (!bitsOk || channels !in 1..8 || declaredRate !in MIN_RATE..MAX_RATE) {
                        throw AudioImportException(ImportFailure.UNSUPPORTED,
                            "Параметры WAV не поддерживаются: $channels кан., $declaredRate Гц, $bits бит")
                    }
                    rate = declaredRate.toInt()
                } else if (kind == "data" && dataOffset < 0) {
                    dataOffset = body
                    // Streaming writers leave 0xFFFFFFFF: the data then runs to the end of the file.
                    dataBytes = if (length == UNKNOWN_LENGTH) size - body else length
                    if (length == UNKNOWN_LENGTH) break
                }
                position = body + length + (length and 1L)
            }
            if (encoding == null || dataOffset < 0) {
                throw AudioImportException(ImportFailure.MALFORMED, "В WAV нет описания формата или аудиоданных")
            }
            val format = WavFormat(encoding, channels, rate, bits, dataOffset, dataBytes)
            if (format.frames <= 0L) throw AudioImportException(ImportFailure.EMPTY, "В файле нет звука")
            if (format.frames * 1000 / rate > AudioSources.MAX_DURATION_MS) {
                throw AudioImportException(ImportFailure.TOO_LONG, "Максимальная длина записи — 2 часа")
            }
            if (dataOffset + dataBytes > size) {
                throw AudioImportException(ImportFailure.MALFORMED, "Файл WAV неполный: данных меньше, чем заявлено в заголовке")
            }
            return format
        }

        private const val EXTENSIBLE = 0xFFFE
        private const val UNKNOWN_LENGTH = 0xFFFFFFFFL
        private const val MIN_RATE = 4_000L
        private const val MAX_RATE = 192_000L
        private fun text(bytes: ByteArray, at: Int) = String(bytes, at, 4, Charsets.US_ASCII)
        private fun uint16(bytes: ByteArray, at: Int) = (bytes[at].toInt() and 0xFF) or ((bytes[at + 1].toInt() and 0xFF) shl 8)
        private fun uint32(bytes: ByteArray, at: Int) = uint16(bytes, at).toLong() or (uint16(bytes, at + 2).toLong() shl 16)
    }
}

/** ITU-T G.711 expansion to 16-bit linear values. */
internal object G711 {
    fun ulaw(code: Int): Int {
        val u = code.inv() and 0xFF
        var t = ((u and 0x0F) shl 3) + 0x84
        t = t shl ((u and 0x70) shr 4)
        return if (u and 0x80 != 0) 0x84 - t else t - 0x84
    }

    fun alaw(code: Int): Int {
        val a = code xor 0x55
        var t = (a and 0x0F) shl 4
        val segment = (a and 0x70) shr 4
        t += when (segment) {
            0 -> 8
            1 -> 0x108
            else -> 0x108
        }
        if (segment > 1) t = t shl (segment - 1)
        return if (a and 0x80 != 0) t else -t
    }
}

/** Decodes frames of a WAV data chunk to mono floats; all channels are averaged. */
internal object WavFrames {
    private const val CHUNK_FRAMES = 32_768

    fun readMono(input: RandomAccessInput, format: WavFormat, first: Long, count: Int, out: FloatArray, outOffset: Int) {
        require(first >= 0 && count >= 0 && first + count <= format.frames)
        val buffer = ByteArray(minOf(count, CHUNK_FRAMES).coerceAtLeast(1) * format.bytesPerFrame)
        var done = 0
        while (done < count) {
            val frames = minOf(count - done, CHUNK_FRAMES)
            val bytes = frames * format.bytesPerFrame
            val read = input.readAt(format.dataOffset + (first + done) * format.bytesPerFrame, buffer, 0, bytes)
            if (read < bytes) throw AudioImportException(ImportFailure.MALFORMED, "Файл WAV оборван во время чтения")
            decode(buffer, frames, format, out, outOffset + done)
            done += frames
        }
    }

    private fun decode(bytes: ByteArray, frames: Int, format: WavFormat, out: FloatArray, outOffset: Int) {
        val width = format.bitsPerSample / 8
        val scale = 1f / format.channels
        var at = 0
        for (frame in 0 until frames) {
            var sum = 0f
            for (channel in 0 until format.channels) {
                sum += sample(bytes, at, format)
                at += width
            }
            out[outOffset + frame] = sum * scale
        }
    }

    private fun sample(b: ByteArray, p: Int, format: WavFormat): Float = when (format.encoding) {
        WavEncoding.PCM -> when (format.bitsPerSample) {
            8 -> ((b[p].toInt() and 0xFF) - 128) / 128f
            16 -> ((b[p].toInt() and 0xFF) or (b[p + 1].toInt() shl 8)).toShort().toInt() / 32768f
            24 -> ((b[p].toInt() and 0xFF) or ((b[p + 1].toInt() and 0xFF) shl 8) or (b[p + 2].toInt() shl 16)) / 8388608f
            else -> int32(b, p) / 2147483648f
        }
        WavEncoding.FLOAT -> Float.fromBits(int32(b, p)).let { if (it.isFinite()) it.coerceIn(-1f, 1f) else 0f }
        WavEncoding.ALAW -> G711.alaw(b[p].toInt() and 0xFF) / 32768f
        WavEncoding.ULAW -> G711.ulaw(b[p].toInt() and 0xFF) / 32768f
    }

    private fun int32(b: ByteArray, p: Int): Int = (b[p].toInt() and 0xFF) or ((b[p + 1].toInt() and 0xFF) shl 8) or
        ((b[p + 2].toInt() and 0xFF) shl 16) or (b[p + 3].toInt() shl 24)
}

/**
 * Windowed-sinc conversion to 16 kHz. Every output sample is computed from its own source
 * neighbourhood, so there is no filter state: any window can be read at any offset and equals the
 * same samples of a continuous conversion. Output sample `n` is the signal at `n / 16000` seconds
 * of the original media, so positions keep their original coordinates.
 */
internal class Resampler(sourceRate: Int) {
    private val divisor = gcd(sourceRate, TARGET)
    private val up = TARGET / divisor
    private val down = sourceRate / divisor
    private val cutoff = 0.45 / maxOf(sourceRate.toDouble() / TARGET, 1.0)
    private val halfWidth = ZERO_CROSSINGS / (2 * cutoff)
    private val reach = ceil(halfWidth).toInt() + 1
    private val taps = 2 * reach
    private val phases = arrayOfNulls<FloatArray>(up)

    /** Source frames needed before the first output (virtual zeros at the start of a stream). */
    val lookback: Int get() = reach - 1

    /** Source frames needed after the last output's position (virtual zeros at the end of a stream). */
    val lookahead: Int get() = reach

    /** Output samples that are fully determined once source frames `[0, sourceFrames)` are known. */
    fun completeOutputs(sourceFrames: Long): Long {
        val limit = (sourceFrames - reach) * up
        return if (limit <= 0) 0 else (limit - 1) / down + 1
    }

    /** Total 16 kHz samples of a recording with [sourceFrames] frames, as [WavWindowSource] counts them. */
    fun outputsFor(sourceFrames: Long): Long = sourceFrames * up / down

    /** Source frames `[first, last]` that outputs `[start, start + count)` depend on. */
    fun sourceRange(start: Long, count: Int): LongRange {
        require(count > 0)
        val firstBase = start * down / up
        val lastBase = (start + count - 1) * down / up
        return (firstBase - reach + 1)..(lastBase + reach)
    }

    /** [mono] holds source frames from `sourceRange(start, count).first` on, zero outside the file. */
    fun convert(mono: FloatArray, start: Long, count: Int): FloatArray {
        val firstSource = sourceRange(start, count).first
        return FloatArray(count) { i ->
            val product = (start + i) * down
            val kernel = weights((product % up).toInt())
            val from = (product / up - reach + 1 - firstSource).toInt()
            var sum = 0f
            for (j in 0 until taps) sum += mono[from + j] * kernel[j]
            sum
        }
    }

    private fun weights(phase: Int): FloatArray = phases[phase] ?: computeWeights(phase).also { phases[phase] = it }

    private fun computeWeights(phase: Int): FloatArray {
        val fraction = phase.toDouble() / up
        val values = DoubleArray(taps) { j ->
            val t = (j - reach + 1) - fraction
            if (abs(t) >= halfWidth) 0.0 else {
                val x = 2 * cutoff * t
                val sinc = if (abs(x) < 1e-12) 1.0 else sin(PI * x) / (PI * x)
                2 * cutoff * sinc * 0.5 * (1 + cos(PI * t / halfWidth))
            }
        }
        val total = values.sum()
        return FloatArray(taps) { (values[it] / total).toFloat() }
    }

    private companion object {
        const val TARGET = 16_000
        const val ZERO_CROSSINGS = 8.0
        fun gcd(a: Int, b: Int): Int = if (b == 0) a else gcd(b, a % b)
    }
}

/**
 * The user's WAV as bounded 16 kHz mono windows. Rate, channel count and sample format are
 * converted on read from the original file, which stays untouched; no converted audio is stored.
 */
internal class WavWindowSource private constructor(
    private val input: RandomAccessInput,
    private val format: WavFormat,
) : PcmWindowSource {
    private val resampler = if (format.sampleRate == 16_000) null else Resampler(format.sampleRate)
    override val sampleCount: Long = format.frames * 16_000 / format.sampleRate

    override fun read(start: Long, count: Int): FloatArray {
        require(start >= 0 && count >= 0 && start + count <= sampleCount)
        if (count == 0) return FloatArray(0)
        if (resampler == null) {
            return FloatArray(count).also { WavFrames.readMono(input, format, start, count, it, 0) }
        }
        val range = resampler.sourceRange(start, count)
        val mono = FloatArray((range.last - range.first + 1).toInt())
        val from = maxOf(range.first, 0L)
        val to = minOf(range.last + 1, format.frames)
        if (to > from) WavFrames.readMono(input, format, from, (to - from).toInt(), mono, (from - range.first).toInt())
        return resampler.convert(mono, start, count)
    }

    override fun close() = input.close()

    companion object {
        /** Takes ownership of [input]: it is closed with the source, not by the caller. */
        fun open(input: RandomAccessInput): WavWindowSource = WavWindowSource(input, WavFormat.parse(input))
    }
}
