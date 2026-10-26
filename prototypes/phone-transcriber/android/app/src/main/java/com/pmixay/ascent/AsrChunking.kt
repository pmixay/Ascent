package com.pmixay.ascent

/** Bounded ASR windows that prefer a quiet seam instead of cutting a word at 20 seconds. */
object AsrChunking {
    const val SAMPLE_RATE = 16000
    const val CORE_SAMPLES = 20 * SAMPLE_RATE
    const val CONTEXT_SAMPLES = SAMPLE_RATE * 5 / 4
    const val SEARCH_SAMPLES = 2 * SAMPLE_RATE
    private const val FRAME_SAMPLES = SAMPLE_RATE / 50
    private const val MIN_QUIET_FRAMES = 10

    data class Window(val coreStart: Long, val coreEnd: Long, val start: Long, val end: Long)

    fun next(coreStart: Long, sampleCount: Long, read: (Long, Int) -> FloatArray): Window {
        require(coreStart >= 0 && coreStart < sampleCount)
        val nominalEnd = (coreStart + CORE_SAMPLES).coerceAtMost(sampleCount)
        val coreEnd = if (nominalEnd == sampleCount) nominalEnd else {
            val searchStart = nominalEnd - SEARCH_SAMPLES
            val samples = read(searchStart, SEARCH_SAMPLES)
            try {
                require(samples.size == SEARCH_SAMPLES)
                searchStart + quietSeam(samples)
            } finally { samples.fill(0f) }
        }
        return Window(coreStart, coreEnd,
            (coreStart - CONTEXT_SAMPLES).coerceAtLeast(0),
            (coreEnd + CONTEXT_SAMPLES).coerceAtMost(sampleCount))
    }

    /** Energy is used only to choose a seam; it never removes quiet speech. */
    private fun quietSeam(samples: FloatArray): Int {
        val energy = DoubleArray(samples.size / FRAME_SAMPLES) { frame ->
            var sum = 0.0
            for (index in frame * FRAME_SAMPLES until (frame + 1) * FRAME_SAMPLES) {
                val sample = samples[index].toDouble()
                sum += sample * sample
            }
            sum / FRAME_SAMPLES
        }
        if (energy.any { !it.isFinite() }) return samples.size
        val ordered = energy.sorted()
        // At least 200 ms at a low absolute level AND below the local speech level.
        // The small floor also permits a completely silent search interval.
        val threshold = (ordered[ordered.size * 9 / 10] * 0.02).coerceIn(0.00000025, 0.000064)
        var runStart = -1
        var seam = samples.size
        for (frame in 0..energy.size) {
            if (frame < energy.size && energy[frame] <= threshold) {
                if (runStart < 0) runStart = frame
            } else if (runStart >= 0) {
                if (frame - runStart >= MIN_QUIET_FRAMES) {
                    seam = (runStart + frame) * FRAME_SAMPLES / 2
                }
                runStart = -1
            }
        }
        return seam
    }
}
