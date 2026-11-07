package com.pmixay.ascent

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.abs
import kotlin.math.pow

class AsrGainTest {
    private val targetPeak = 10.0.pow(-1.0 / 20)

    @Test fun quietWindowIsRaisedToMinusOneDbfsPeak() {
        val samples = floatArrayOf(0f, 0.05f, -0.1f, 0.025f) // peak -20 dBFS: +19 dB, under the cap
        AsrGain.normalizeInPlace(samples)
        val peak = samples.maxOf { abs(it) }
        assertEquals(targetPeak, peak.toDouble(), 1.0 / 32768)
        // Shape is kept: the other samples scale by the same factor.
        assertEquals(-0.5, (samples[1] / samples[2]).toDouble(), 0.001)
        samples.forEach { assertEquals(0.0, it * 32768.0 - Math.rint(it * 32768.0), 1e-6) }
    }

    @Test fun gainIsCappedAtThirtyDecibels() {
        val samples = floatArrayOf(0.001f, -0.0005f)
        assertEquals(10.0.pow(1.5), AsrGain.gain(samples), 1e-9)
        AsrGain.normalizeInPlace(samples)
        assertEquals(0.001 * 10.0.pow(1.5), samples[0].toDouble(), 1.0 / 32768)
    }

    @Test fun loudWindowIsLoweredAndNothingClips() {
        val samples = floatArrayOf(0.999f, -0.999f, 0.5f)
        AsrGain.normalizeInPlace(samples)
        assertEquals(targetPeak, samples[0].toDouble(), 1.0 / 32768)
        assertEquals(-targetPeak, samples[1].toDouble(), 1.0 / 32768)
    }

    @Test fun silenceAndNonFiniteInputAreLeftUnchanged() {
        val silent = FloatArray(8)
        AsrGain.normalizeInPlace(silent)
        assertArrayEquals(FloatArray(8), silent, 0f)
        val broken = floatArrayOf(0.01f, Float.NaN)
        assertEquals(1.0, AsrGain.gain(broken), 0.0)
    }
}
