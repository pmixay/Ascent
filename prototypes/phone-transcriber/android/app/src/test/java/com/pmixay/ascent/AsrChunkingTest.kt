package com.pmixay.ascent

import org.junit.Assert.*
import org.junit.Test

class AsrChunkingTest {
    private val rate = AsrChunking.SAMPLE_RATE

    @Test fun movesSeamIntoSustainedQuietAndKeepsContext() {
        val window = AsrChunking.next(0, 40L * rate) { start, count ->
            assertEquals(18L * rate, start)
            FloatArray(count) { if (it in rate / 2 until rate) 0f else 0.2f }
        }
        assertEquals(rate * 75L / 4, window.coreEnd) // 18.75 seconds
        assertEquals(0L, window.start)
        assertEquals(20L * rate, window.end)
    }

    @Test fun briefDropoutDoesNotMoveBoundary() {
        val window = AsrChunking.next(0, 40L * rate) { _, count ->
            FloatArray(count) { if (it in rate / 2 until rate / 2 + rate / 10) 0f else 0.2f }
        }
        assertEquals(20L * rate, window.coreEnd)
    }

    @Test fun continuousSpeechRetainsFixedBoundaryWithMoreContext() {
        val window = AsrChunking.next(20L * rate, 50L * rate) { _, count -> FloatArray(count) { 0.2f } }
        assertEquals(40L * rate, window.coreEnd)
        assertEquals(rate * 75L / 4, window.start)
        assertEquals(rate * 165L / 4, window.end)
        assertTrue(window.end - window.start <= 25L * rate)
    }

    @Test fun lastChunkIncludesWholeTailWithoutLookingForAnotherSeam() {
        val window = AsrChunking.next(20L * rate, 21L * rate) { _, _ -> error("No search needed") }
        assertEquals(21L * rate, window.coreEnd)
        assertEquals(21L * rate, window.end)
    }

    @Test fun quietChunksAdvanceWithoutGapsAndClearSearchAudio() {
        var position = 0L
        val total = 61L * rate
        val windows = mutableListOf<AsrChunking.Window>()
        while (position < total) {
            var search: FloatArray? = null
            val window = AsrChunking.next(position, total) { _, count -> FloatArray(count) { 0.0001f }.also { search = it } }
            assertTrue(window.coreEnd > position)
            assertTrue(window.end - window.start <= 25L * rate)
            assertTrue(search?.all { it == 0f } ?: true)
            windows += window
            position = window.coreEnd
        }
        assertEquals(total, windows.last().coreEnd)
        windows.zipWithNext().forEach { (left, right) -> assertEquals(left.coreEnd, right.coreStart) }
    }
}
