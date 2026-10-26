package com.pmixay.ascent

/** Presentation only: keep stored phrase boundaries available for export and pause estimates. */
internal data class TimelinePhrase(
    val text: String,
    val startMs: Long,
    val endMs: Long,
    val pauseBeforeMs: Long = 0,
)

internal data class TimelineTurn(
    val speakerId: String,
    val startMs: Long,
    val endMs: Long,
    val pauseBeforeMs: Long,
    val phrases: List<TimelinePhrase>,
)

internal object TranscriptTimeline {
    // Stored segments already join same-speaker gaps up to 700 ms. Longer gaps
    // remain approximate: CTC emission times do not establish exact silence.
    private const val MIN_PAUSE_MS = 700L

    fun turns(segments: List<TranscriptSegment>): List<TimelineTurn> {
        val result = mutableListOf<TurnBuilder>()
        var coveredUntil = 0L
        for (segment in segments.sortedBy { it.startMs }) {
            val gap = if (result.isEmpty()) 0L else (segment.startMs - coveredUntil).coerceAtLeast(0)
            val pause = gap.takeIf { it > MIN_PAUSE_MS } ?: 0L
            val previous = result.lastOrNull()
            // Unknown is a lack of attribution, not evidence that two voices match.
            if (previous != null && segment.speakerId != UNKNOWN_SPEAKER && previous.speakerId == segment.speakerId) {
                previous.endMs = maxOf(previous.endMs, segment.endMs)
                val phrase = previous.phrases.last()
                if (pause == 0L) previous.phrases[previous.phrases.lastIndex] = phrase.copy(
                    endMs = maxOf(phrase.endMs, segment.endMs), text = phrase.text + " " + segment.text,
                ) else previous.phrases += TimelinePhrase(segment.text, segment.startMs, segment.endMs, pause)
            } else result += TurnBuilder(segment.speakerId, segment.startMs, segment.endMs, pause,
                mutableListOf(TimelinePhrase(segment.text, segment.startMs, segment.endMs)))
            coveredUntil = maxOf(coveredUntil, segment.endMs)
        }
        return result.map { TimelineTurn(it.speakerId, it.startMs, it.endMs, it.pauseBeforeMs, it.phrases.toList()) }
    }

    private data class TurnBuilder(
        val speakerId: String,
        val startMs: Long,
        var endMs: Long,
        val pauseBeforeMs: Long,
        val phrases: MutableList<TimelinePhrase>,
    )
}

/** Russian UI, explicit seconds and one decimal, independent of the phone's locale. */
internal fun formatTimelineSeconds(milliseconds: Long): String {
    val nonnegative = milliseconds.coerceAtLeast(0)
    val tenths = nonnegative / 100 + if (nonnegative % 100 >= 50) 1 else 0
    return "${tenths / 10},${tenths % 10}"
}

internal fun formatTimelineRange(startMs: Long, endMs: Long): String =
    "${formatTimelineSeconds(startMs)}–${formatTimelineSeconds(endMs)} с"
