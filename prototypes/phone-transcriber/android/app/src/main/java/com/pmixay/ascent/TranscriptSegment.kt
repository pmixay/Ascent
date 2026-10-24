package com.pmixay.ascent

data class TranscriptSegment(
    val speakerId: String,
    val startMs: Long,
    val endMs: Long,
    val text: String,
)

data class TimedWord(val text: String, val startMs: Long, val endMs: Long)
