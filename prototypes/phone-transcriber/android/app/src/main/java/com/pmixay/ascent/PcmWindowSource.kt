package com.pmixay.ascent

import java.io.Closeable

/**
 * Bounded random-access view of 16 kHz mono PCM for the recognition engine.
 *
 * The engine asks for one window at a time, so an implementation never has to expose a whole
 * recording as one array. Implementations must not copy the audio into application storage or
 * caches, must keep sample positions in original-media coordinates, and must not retain returned
 * windows. The caller that creates a source owns it and closes it.
 */
interface PcmWindowSource : Closeable {
    val sampleCount: Long
    val durationMs: Long get() = sampleCount * 1000 / SAMPLE_RATE

    /** Short user-facing description of the decoded input format. */
    val formatDescription: String get() = "Формат не определён"

    /** Returns exactly [count] samples starting at sample [start]; both must lie inside the source. */
    fun read(start: Long, count: Int): FloatArray

    companion object { const val SAMPLE_RATE = 16000L }
}
