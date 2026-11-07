package com.pmixay.ascent

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** Read-only phase table shared by bounded file and streaming PCM converters. */
internal class PcmResamplingKernel private constructor(
    val radius: Int,
    private val phases: Array<FloatArray>,
) {
    fun weights(fraction: Double): FloatArray =
        phases[(fraction * PHASE_COUNT).toInt().coerceIn(0, PHASE_COUNT - 1)]

    companion object {
        private const val PHASE_COUNT = 256

        fun forRate(sourceRate: Int, outputRate: Int = 16000): PcmResamplingKernel {
            require(sourceRate > 0 && outputRate > 0)
            if (sourceRate == outputRate) return PcmResamplingKernel(0, Array(PHASE_COUNT) { floatArrayOf(1f) })
            val ratio = sourceRate.toDouble() / outputRate
            val radius = ceil(12 * max(1.0, ratio)).toInt()
            val cutoff = .90 * min(1.0, 1.0 / ratio)
            val phases = Array(PHASE_COUNT) { phase ->
                val fraction = phase.toDouble() / PHASE_COUNT
                val weights = FloatArray(radius * 2 + 1) { tap ->
                    val distance = tap - radius - fraction
                    if (abs(distance) > radius) 0f else {
                        val x = PI * cutoff * distance
                        val sinc = if (x == 0.0) 1.0 else sin(x) / x
                        val window = .42 + .5 * cos(PI * distance / radius) +
                            .08 * cos(2 * PI * distance / radius)
                        (cutoff * sinc * window).toFloat()
                    }
                }
                val total = weights.sum()
                for (index in weights.indices) weights[index] /= total
                weights
            }
            return PcmResamplingKernel(radius, phases)
        }
    }
}
