package com.pmixay.ascent

/** Greedy character CTC. Times are frame estimates, not forced-alignment guarantees. */
object CtcDecoder {
    fun decode(
        ids: IntArray, tokens: List<String>, blankId: Int,
        offsetMs: Long, durationMs: Long,
    ): List<TimedWord> {
        if (ids.isEmpty()) return emptyList()
        require(blankId == tokens.size && durationMs >= 0)
        val words = mutableListOf<TimedWord>()
        val text = StringBuilder()
        var start = 0
        var end = 0
        fun time(frame: Int) = offsetMs + frame.toLong() * durationMs / ids.size
        fun flush() {
            if (text.isNotEmpty()) {
                words += TimedWord(text.toString(), time(start), time(end).coerceAtLeast(time(start) + 1))
                text.clear()
            }
        }
        var previous = blankId
        for ((frame, id) in ids.withIndex()) {
            require(id in 0..blankId) { "CTC token outside vocabulary" }
            if (id != blankId) {
                if (id != previous) {
                    for (char in tokens[id].replace('▁', ' ')) {
                        if (char.isWhitespace()) flush() else {
                            if (text.isEmpty()) start = frame
                            text.append(char)
                            end = frame + 1
                        }
                    }
                } else if (text.isNotEmpty()) end = frame + 1
            }
            previous = id
        }
        flush()
        return words
    }

    /** Adjacent chunks own disjoint word midpoints, avoiding duplicate overlap text. */
    fun ownedBy(words: List<TimedWord>, startMs: Long, endMs: Long): List<TimedWord> =
        words.filter { (it.startMs + it.endMs) / 2 in startMs until endMs }
}
