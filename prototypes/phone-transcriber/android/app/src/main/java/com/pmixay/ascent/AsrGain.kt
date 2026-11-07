package com.pmixay.ascent

import kotlin.math.abs
import kotlin.math.min
import kotlin.math.pow

/**
 * Level normalization of one ASR window before GigaAM: scale the window so its peak reaches
 * -1 dBFS, with at most +30 dB of gain, and keep the samples on the 16-bit PCM grid.
 *
 * The rule was chosen on an AMI development meeting and then checked once on a separate AMI
 * test meeting and on the Russian Dialogs development and validation sets
 * (docs/hackathon/model-report.md). It changes only the recognizer input; speaker embeddings
 * and word timing read the original audio.
 */
object AsrGain {
    private val TARGET_PEAK = 10.0.pow(-1.0 / 20)
    private val MAX_GAIN = 10.0.pow(30.0 / 20)

    /** The gain the rule applies to [samples]; 1 for silence or non-finite input. */
    fun gain(samples: FloatArray): Double {
        var peak = 0f
        for (sample in samples) {
            val level = abs(sample)
            if (!level.isFinite()) return 1.0
            if (level > peak) peak = level
        }
        return if (peak > 0f) min(TARGET_PEAK / peak, MAX_GAIN) else 1.0
    }

    /** Applies the rule in place, so no further copy of the audio is made. */
    fun normalizeInPlace(samples: FloatArray) {
        val gain = gain(samples)
        if (gain == 1.0) return
        for (index in samples.indices) {
            samples[index] = (Math.rint(samples[index] * gain * 32768.0).coerceIn(-32768.0, 32767.0) / 32768.0).toFloat()
        }
    }
}
