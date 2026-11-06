package com.pmixay.ascent

data class TranscriptSegment(
    val speakerId: String,
    val startMs: Long,
    val endMs: Long,
    val text: String,
    /** A best-effort participant assignment; never enrollment evidence by itself. */
    val speakerInferred: Boolean = false,
)

data class TimedWord(val text: String, val startMs: Long, val endMs: Long)
