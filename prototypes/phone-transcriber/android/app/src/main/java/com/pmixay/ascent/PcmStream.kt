package com.pmixay.ascent

/** Growing 16-bit PCM kept in fixed blocks, so a long decode never needs one huge contiguous array. */
internal class Pcm16Blocks {
    private val blocks = ArrayList<ShortArray>()
    var size = 0L
        private set

    fun add(value: Short) {
        val offset = (size % BLOCK).toInt()
        if (offset == 0) blocks.add(ShortArray(BLOCK))
        blocks[(size / BLOCK).toInt()][offset] = value
        size++
    }

    operator fun get(index: Long): Short {
        require(index in 0 until size)
        return blocks[(index / BLOCK).toInt()][(index % BLOCK).toInt()]
    }

    private companion object { const val BLOCK = 65_536 }
}

/** Decoded audio held in memory as 16 kHz mono. Nothing is written to storage. */
internal class Pcm16Source(private val blocks: Pcm16Blocks) : PcmWindowSource {
    override val sampleCount: Long = blocks.size

    override fun read(start: Long, count: Int): FloatArray {
        require(start >= 0 && count >= 0 && start + count <= sampleCount)
        return FloatArray(count) { blocks[start + it] / 32768f }
    }

    override fun close() = Unit
}

/**
 * Converts decoder output (interleaved 16-bit PCM at any rate) to 16 kHz mono as it arrives, with the
 * same windowed-sinc filter as [WavWindowSource], so both paths give the same samples for the same
 * signal. Only a short history of source samples is kept. Output beyond [limitSamples] fails with
 * [ImportFailure.TOO_LONG] instead of truncating.
 */
internal class Pcm16Stream(
    rate: Int,
    private val channels: Int,
    private val out: Pcm16Blocks,
    private val limitSamples: Long,
) {
    private val resampler = if (rate == 16_000) null else Resampler(rate)
    private var pending = FloatArray(maxOf(1, resampler?.lookback ?: 0) + CHUNK)
    private var pendingStart = -(resampler?.lookback ?: 0).toLong()
    private var pendingSize = resampler?.lookback ?: 0
    private var sourceFrames = 0L
    private var nextOutput = 0L

    /** [samples] holds [frames] interleaved frames. */
    fun push(samples: ShortArray, frames: Int) {
        require(frames >= 0 && frames * channels <= samples.size)
        val mono = FloatArray(frames)
        val scale = 1f / (channels * 32768f)
        var at = 0
        for (frame in 0 until frames) {
            var sum = 0
            for (channel in 0 until channels) sum += samples[at++]
            mono[frame] = sum * scale
        }
        append(mono, frames)
    }

    /** Silence for a source that starts after time zero, so positions keep original-media coordinates. */
    fun pushSilence(frames: Long) {
        var left = frames
        while (left > 0) {
            val count = minOf(left, CHUNK.toLong()).toInt()
            append(FloatArray(count), count)
            left -= count
        }
    }

    /** Emits the tail that needs the end of the signal. Call once, after the last [push]. */
    fun finish() {
        val resampler = resampler ?: return
        val total = resampler.outputsFor(sourceFrames)
        reserve(resampler.lookahead)
        java.util.Arrays.fill(pending, pendingSize, pendingSize + resampler.lookahead, 0f)
        pendingSize += resampler.lookahead
        drain(total)
    }

    private fun append(mono: FloatArray, count: Int) {
        if (resampler == null) {
            emit(mono, count)
            return
        }
        reserve(count)
        System.arraycopy(mono, 0, pending, pendingSize, count)
        pendingSize += count
        sourceFrames += count
        drain(resampler.completeOutputs(sourceFrames))
    }

    private fun reserve(extra: Int) {
        if (pendingSize + extra > pending.size) pending = pending.copyOf(maxOf(pending.size * 2, pendingSize + extra))
    }

    private fun emit(values: FloatArray, count: Int) {
        if (out.size + count > limitSamples) throw tooLong()
        for (i in 0 until count) out.add(toShort(values[i]))
    }

    private fun drain(upTo: Long) {
        val resampler = resampler ?: return
        while (nextOutput < upTo) {
            val count = minOf(upTo - nextOutput, CHUNK.toLong()).toInt()
            val range = resampler.sourceRange(nextOutput, count)
            val offset = (range.first - pendingStart).toInt()
            val window = pending.copyOfRange(offset, offset + (range.last - range.first + 1).toInt())
            emit(resampler.convert(window, nextOutput, count), count)
            nextOutput += count
        }
        // Keep only the history the next output still needs.
        val keepFrom = resampler.sourceRange(nextOutput, 1).first
        val drop = (keepFrom - pendingStart).toInt()
        if (drop > 0) {
            System.arraycopy(pending, drop, pending, 0, pendingSize - drop)
            pendingSize -= drop
            pendingStart = keepFrom
        }
    }

    private fun toShort(value: Float): Short = Math.round(value * 32768f).coerceIn(-32768, 32767).toShort()

    private fun tooLong() = AudioImportException(ImportFailure.TOO_LONG,
        "Для этого формата максимальная длина записи — ${limitSamples / 16_000 / 60} минут. Выберите файл короче или WAV.")

    private companion object { const val CHUNK = 4_096 }
}
