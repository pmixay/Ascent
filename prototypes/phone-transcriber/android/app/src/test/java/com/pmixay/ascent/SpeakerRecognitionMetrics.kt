package com.pmixay.ascent

import java.text.Normalizer
import java.util.Locale

internal data class AttributedText(val speaker: String, val text: String)
internal data class PermutationWordError(val errors: Int, val referenceWords: Int) {
    val rate: Double get() = errors.toDouble() / referenceWords
}

/** Test-only joint transcription/diarization score; anonymous labels map one-to-one. */
internal object SpeakerRecognitionMetrics {
    fun words(text: String): List<String> = Normalizer.normalize(text, Normalizer.Form.NFKC)
        .lowercase(Locale.ROOT).replace('ё', 'е')
        .map { if (it.isLetterOrDigit()) it else ' ' }.joinToString("")
        .split(Regex("\\s+")).filter { it.isNotEmpty() }

    fun distance(reference: List<String>, hypothesis: List<String>): Int {
        var previous = IntArray(hypothesis.size + 1) { it }
        for ((row, expected) in reference.withIndex()) {
            val current = IntArray(hypothesis.size + 1)
            current[0] = row + 1
            for ((column, actual) in hypothesis.withIndex()) {
                current[column + 1] = minOf(current[column] + 1, previous[column + 1] + 1,
                    previous[column] + if (expected == actual) 0 else 1)
            }
            previous = current
        }
        return previous.last()
    }

    /** cpWER: concatenate each speaker's words in time order, then minimize word errors. */
    fun cpwer(reference: List<AttributedText>, hypothesis: List<AttributedText>): PermutationWordError {
        val expected = reference.groupBy { it.speaker }.toSortedMap().values.map { turns -> turns.flatMap { words(it.text) } }
        val actual = hypothesis.groupBy { it.speaker }.toSortedMap().values.map { turns -> turns.flatMap { words(it.text) } }
        require(expected.isNotEmpty() && expected.sumOf { it.size } > 0)
        require(actual.size <= 12)
        val costs = Array(expected.size) { r -> IntArray(actual.size) { h -> distance(expected[r], actual[h]) } }
        val memo = mutableMapOf<Pair<Int, Int>, Int>()
        fun best(reader: Int, used: Int): Int {
            if (reader == expected.size) return 0
            return memo.getOrPut(reader to used) {
                // Hypothesis words begin as insertions. A matched stream replaces that
                // insertion cost with its edit distance; an absent speaker contributes deletions.
                var minimum = expected[reader].size + best(reader + 1, used)
                for (label in actual.indices) if (used and (1 shl label) == 0) {
                    minimum = minOf(minimum, costs[reader][label] - actual[label].size +
                        best(reader + 1, used or (1 shl label)))
                }
                minimum
            }
        }
        return PermutationWordError(actual.sumOf { it.size } + best(0, 0), expected.sumOf { it.size })
    }
}
