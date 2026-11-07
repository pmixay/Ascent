package com.pmixay.ascent

import android.content.Context
import android.net.Uri
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

private class WaveHandle(
    val channel: FileChannel,
    val offset: Long,
    val length: Long?,
    val close: () -> Unit,
)

/** Bounded, seekable PCM WAVE reader and mono 16 kHz converter. */
class PcmWave private constructor(private val handle: WaveHandle) : AudioWindowSource {
    constructor(file: File) : this(openFile(file))
    constructor(context: Context, uri: Uri) : this(openDocument(context, uri))

    private val input = handle.channel
    private var dataOffset = 0L
    private var sourceFrames = 0L
    private var sampleRate = 16000
    private var channels = 1
    private var bitsPerSample = 16
    private var blockAlign = 2
    private var resamplingKernel: PcmResamplingKernel? = null
    override val sampleCount: Long
    // Round the source duration up to its containing millisecond, like the codec source.
    override val durationMs: Long get() = (sourceFrames * 1000 + sampleRate - 1) / sampleRate
    override val formatDescription: String get() =
        "WAV PCM$bitsPerSample, $sampleRate Гц, ${if (channels == 1) "моно" else "стерео"}"

    init {
        try {
            val length = try {
                seek(0)
                handle.length ?: input.size() - handle.offset
            } catch (error: IOException) {
                throw IllegalArgumentException("Источник аудио не поддерживает чтение по позициям", error)
            }
            require(length >= 44) { "Повреждённый WAV-файл" }
            require(tag() == "RIFF") { "Ожидается WAV PCM" }
            val riffEnd = uint32() + 8
            require(riffEnd <= length && riffEnd >= 12) { "Неполный WAV-файл" }
            require(tag() == "WAVE") { "Ожидается WAV" }
            var formatOk = false
            var bytes = -1L
            while (position() + 8 <= riffEnd) {
                val kind = tag()
                val size = uint32()
                val next = position() + size + (size % 2)
                require(next <= riffEnd) { "Неполный блок WAV" }
                when (kind) {
                    "fmt " -> {
                        require(size >= 16) { "Повреждённый формат WAV" }
                        val encoding = uint16()
                        val channelCount = uint16()
                        val rate = uint32()
                        val byteRate = uint32()
                        val alignment = uint16()
                        val bits = uint16()
                        require(encoding == 1 && channelCount in 1..2 && rate in 8000L..96000L &&
                            bits in listOf(8, 16) && alignment == channelCount * bits / 8 &&
                            byteRate == rate * alignment) {
                            "Неподдерживаемый формат WAV PCM"
                        }
                        sampleRate = rate.toInt()
                        channels = channelCount
                        bitsPerSample = bits
                        blockAlign = alignment
                        formatOk = true
                    }
                    "data" -> if (bytes < 0) { dataOffset = position(); bytes = size }
                }
                seek(next)
            }
            require(formatOk && bytes > 0 && bytes % blockAlign == 0L) { "Нет аудиоданных WAV" }
            sourceFrames = bytes / blockAlign
            require(sourceFrames <= sampleRate * 30L * 60) {
                "Максимальная длительность — 30 минут"
            }
            sampleCount = (sourceFrames * 16000L + sampleRate - 1) / sampleRate
            if (sampleRate > OUTPUT_RATE) {
                resamplingKernel = PcmResamplingKernel.forRate(sampleRate, OUTPUT_RATE)
            }
        } catch (error: Throwable) {
            try { close() } catch (closeError: Throwable) { error.addSuppressed(closeError) }
            throw error
        }
    }

    override fun read(start: Long, count: Int): FloatArray {
        require(start >= 0 && start <= sampleCount && count in 0..MAX_WINDOW_SAMPLES &&
            count.toLong() <= sampleCount - start)
        if (count == 0) return FloatArray(0)
        val ratio = sampleRate.toDouble() / OUTPUT_RATE
        val kernel = resamplingKernel
        val halo = (kernel?.radius ?: 0).toLong() + 2
        val first = max(0L, floor(start * ratio).toLong() - halo)
        val last = min(sourceFrames - 1, ceil((start + count - 1) * ratio).toLong() + halo)
        val frameCount = (last - first + 1).toInt()
        val bytes = ByteArray(frameCount * blockAlign)
        var mono: FloatArray? = null
        var unfinishedOutput: FloatArray? = null
        try {
            seek(dataOffset + first * blockAlign)
            readFully(bytes)
            val decoded = FloatArray(frameCount)
            mono = decoded
            val channelEnergy = DoubleArray(channels)
            var mixedEnergy = 0.0
            for (frame in decoded.indices) {
                val offset = frame * blockAlign
                if (channels == 1) {
                    decoded[frame] = sample(bytes, offset)
                    channelEnergy[0] += decoded[frame] * decoded[frame]
                } else {
                    val left = sample(bytes, offset)
                    val right = sample(bytes, offset + bitsPerSample / 8)
                    decoded[frame] = (left + right) / 2f
                    channelEnergy[0] += left * left
                    channelEnergy[1] += right * right
                    mixedEnergy += decoded[frame] * decoded[frame]
                }
            }
            // A stereo recording can put the same speech in opposite phase. Select the
            // strongest channel for the whole window instead of erasing it by averaging.
            if (channels == 2 && mixedEnergy < channelEnergy.max() * 0.02) {
                val selected = channelEnergy.indices.maxBy { channelEnergy[it] }
                for (frame in decoded.indices) {
                    decoded[frame] = sample(bytes, frame * blockAlign + selected * bitsPerSample / 8)
                }
            }
            val result = FloatArray(count)
            unfinishedOutput = result
            for (output in result.indices) {
                if (output % 4096 == 0 && Thread.currentThread().isInterrupted)
                    throw InterruptedException("Операция отменена")
                val position = (start + output) * ratio
                result[output] = if (ratio <= 1.0) {
                    val base = floor(position).toLong()
                    val left = decoded[(base - first).toInt()]
                    val right = decoded[(min(base + 1, sourceFrames - 1) - first).toInt()]
                    (left + (right - left) * (position - base)).toFloat()
                } else {
                    // A symmetric low-pass kernel preserves source time while rejecting frequencies
                    // above the output Nyquist limit. Absolute phase keeps split reads identical.
                    val base = floor(position).toLong()
                    val filter = checkNotNull(kernel)
                    val weights = filter.weights(position - base)
                    var weighted = 0.0
                    var weight = 0.0
                    for (tap in weights.indices) {
                        val frame = base + tap - filter.radius
                        if (frame >= 0 && frame < sourceFrames) {
                            val coefficient = weights[tap]
                            weighted += decoded[(frame - first).toInt()] * coefficient
                            weight += coefficient
                        }
                    }
                    (weighted / weight).toFloat()
                }
            }
            unfinishedOutput = null
            return result
        } finally {
            bytes.fill(0)
            mono?.fill(0f)
            unfinishedOutput?.fill(0f)
        }
    }

    private fun sample(bytes: ByteArray, offset: Int): Float = if (bitsPerSample == 8) {
        ((bytes[offset].toInt() and 255) - 128) / 128f
    } else {
        (((bytes[offset].toInt() and 255) or (bytes[offset + 1].toInt() shl 8)).toShort().toInt()) / 32768f
    }

    private fun readFully(bytes: ByteArray) {
        val buffer = ByteBuffer.wrap(bytes)
        while (buffer.hasRemaining()) {
            if (input.read(buffer) <= 0) throw IOException("Неполный WAV-файл")
        }
    }

    private fun position(): Long = input.position() - handle.offset
    private fun seek(position: Long) { input.position(handle.offset + position) }
    private fun tag(): String = ByteArray(4).also(::readFully).toString(Charsets.US_ASCII)
    private fun uint16(): Int = ByteArray(2).also(::readFully).let {
        (it[0].toInt() and 255) or ((it[1].toInt() and 255) shl 8)
    }
    private fun uint32(): Long = uint16().toLong() or (uint16().toLong() shl 16)
    override fun close() = handle.close()

    companion object {
        private const val OUTPUT_RATE = 16000
        private const val MAX_WINDOW_SAMPLES = 512_000
        private fun openFile(file: File): WaveHandle {
            val source = RandomAccessFile(file, "r")
            return WaveHandle(source.channel, 0, null) { source.close() }
        }

        private fun openDocument(context: Context, uri: Uri): WaveHandle {
            val descriptor = context.contentResolver.openAssetFileDescriptor(uri, "r")
                ?: throw IOException("Не удалось открыть выбранный файл")
            val offset = descriptor.startOffset
            val length = descriptor.declaredLength.takeIf { it >= 0 }
            val source = try {
                descriptor.createInputStream()
            } catch (error: Throwable) {
                descriptor.close()
                throw error
            }
            return try {
                WaveHandle(source.channel, offset, length) { source.close() }
            } catch (error: Throwable) {
                source.close()
                throw error
            }
        }
    }
}
