package com.pmixay.ascent

data class TranscriptSegment(
    val speakerId: String,
    val startMs: Long,
    val endMs: Long,
    /** What the interface shows. With the local text stage this carries capitals and marks. */
    val text: String,
    /** A best-effort participant assignment; never enrollment evidence by itself. */
    val speakerInferred: Boolean = false,
    /**
     * The recognizer's own words when [text] was edited by the text stage; null when the two are
     * identical. The competition export writes this value so punctuation cannot count as WER errors.
     */
    val rawText: String? = null,
) {
    /** The recognizer output, which is what the competition JSON carries. */
    val recognizedText: String get() = rawText ?: text
}

data class TimedWord(val text: String, val startMs: Long, val endMs: Long)
