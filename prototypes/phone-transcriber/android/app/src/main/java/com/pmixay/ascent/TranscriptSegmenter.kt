package com.pmixay.ascent

/**
 * Groups attributed words into stored phrases, the unit later exported as a reply.
 *
 * Consecutive words stay together while the participant label is unchanged and the
 * pause between them is at most [MAX_GAP_MS]. A run longer than [MAX_SPAN_MS] is split
 * at its longest internal pause rather than at whichever word happens to cross the
 * limit, so a long monologue breaks where the speaker actually paused. Word text,
 * order and times are never changed, and a speaker change always starts a phrase.
 */
internal object TranscriptSegmenter {
    const val MAX_GAP_MS = 700L
    const val MAX_SPAN_MS = 20_000L

    fun segment(
        words: List<TimedWord>,
        speakerIds: List<String>,
        inferred: List<Boolean>,
        maxGapMs: Long = MAX_GAP_MS,
        maxSpanMs: Long = MAX_SPAN_MS,
    ): List<TranscriptSegment> {
        require(words.size == speakerIds.size && words.size == inferred.size) { "Attribution must cover every word" }
        require(maxGapMs >= 0 && maxSpanMs > 0)
        val result = mutableListOf<TranscriptSegment>()
        var runStart = 0
        var runEnd = words.firstOrNull()?.endMs ?: 0L
        for (index in 1..words.size) {
            // The pause is measured from the latest end in the run, as stored phrases always did.
            val boundary = index == words.size || speakerIds[index] != speakerIds[index - 1] ||
                words[index].startMs - runEnd > maxGapMs
            if (!boundary) {
                runEnd = maxOf(runEnd, words[index].endMs)
                continue
            }
            splitRun(words, runStart, index, maxSpanMs).forEach { (from, to) ->
                result += TranscriptSegment(
                    speakerId = speakerIds[from],
                    startMs = words[from].startMs,
                    endMs = (from until to).maxOf { words[it].endMs },
                    text = (from until to).joinToString(" ") { words[it].text },
                    speakerInferred = (from until to).any { inferred[it] },
                )
            }
            runStart = index
            if (index < words.size) runEnd = words[index].endMs
        }
        return result
    }

    /** Index ranges [from, to) covering one same-speaker run, each within [maxSpanMs] when possible. */
    internal fun splitRun(words: List<TimedWord>, from: Int, to: Int, maxSpanMs: Long): List<Pair<Int, Int>> {
        val pieces = mutableListOf<Pair<Int, Int>>()
        var start = from
        while (start < to) {
            var end = to
            if (spanEnd(words, start, to) - words[start].startMs > maxSpanMs) {
                // Candidate cuts keep the first piece within the limit; prefer the longest pause,
                // and among equal pauses the latest cut so pieces stay as long as allowed.
                var best = -1
                var bestGap = Long.MIN_VALUE
                for (cut in start + 1 until to) {
                    if (spanEnd(words, start, cut) - words[start].startMs > maxSpanMs) break
                    val gap = words[cut].startMs - words[cut - 1].endMs
                    if (gap >= bestGap) {
                        bestGap = gap
                        best = cut
                    }
                }
                end = if (best > start) best else start + 1
            }
            pieces += start to end
            start = end
        }
        return pieces
    }

    private fun spanEnd(words: List<TimedWord>, from: Int, to: Int): Long = (from until to).maxOf { words[it].endMs }
}
