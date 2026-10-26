package com.pmixay.ascent

import org.json.JSONObject
import kotlin.math.abs

/** Development-only error accounting over already computed public CTC predictions. */
internal object AsrErrorDiagnostics {
    data class Counts(val substitutions: Int, val deletions: Int, val insertions: Int) {
        val errors get() = substitutions + deletions + insertions
    }
    private data class Reference(val token: String, val speaker: String, val emotion: String, val startMs: Long, val endMs: Long)
    private data class Predicted(val token: String, val midpointMs: Long)

    /** Deterministic minimum-edit alignment; S/D/I composition may have equally optimal alternatives. */
    fun counts(reference: List<String>, hypothesis: List<String>): Counts {
        var substitutions = 0
        var deletions = 0
        var insertions = 0
        align(reference, hypothesis) { r, h, same ->
            when {
                r < 0 -> insertions++
                h < 0 -> deletions++
                !same -> substitutions++
            }
        }
        return Counts(substitutions, deletions, insertions)
    }

    fun printDevelopment(document: JSONObject, tokens: List<String>) {
        val id = document.getString("id")
        require(id.startsWith("masha_dima_part1-")) { "Detailed diagnostics are restricted to development audio" }
        val references = document.getJSONArray("references")
        val expected = (0 until references.length()).flatMap { index ->
            val turn = references.getJSONObject(index)
            SpeakerRecognitionMetrics.words(turn.getString("text")).map {
                Reference(it, turn.getString("speaker"), turn.getString("emotion"),
                    turn.getLong("start_ms"), turn.getLong("end_ms"))
            }
        }
        val words = document.getJSONArray("words")
        val actual = (0 until words.length()).flatMap { index ->
            val word = words.getJSONObject(index)
            val midpoint = word.getLong("start_ms") + (word.getLong("end_ms") - word.getLong("start_ms")) / 2
            SpeakerRecognitionMetrics.words(word.getString("text")).map { Predicted(it, midpoint) }
        }
        val raw = document.getJSONArray("asr_windows")
        val seams = (0 until raw.length() - 1).map { raw.getJSONObject(it).getLong("core_end") / 16 }
        val actors = sortedMapOf<String, IntArray>()
        val emotions = sortedMapOf<String, IntArray>()
        var substitutions = 0
        var deletions = 0
        var insertions = 0
        var boundarySubstitutions = 0
        var boundaryInsertions = 0
        var potentiallyBoundaryDeletions = 0
        align(expected.map { it.token }, actual.map { it.token }) { r, h, same ->
            val reference = expected.getOrNull(r)
            val hypothesis = actual.getOrNull(h)
            val actor = reference?.let { actors.getOrPut(it.speaker) { IntArray(3) } }
            val emotion = reference?.let { emotions.getOrPut(it.emotion) { IntArray(3) } }
            if (actor != null) actor[0]++
            if (emotion != null) emotion[0]++
            val nearSeam = hypothesis != null && seams.any { abs(it - hypothesis.midpointMs) <= 1000 }
            when {
                reference == null -> { insertions++; if (nearSeam) boundaryInsertions++ }
                hypothesis == null -> {
                    deletions++; actor!![2]++; emotion!![2]++
                    if (seams.any { it >= reference.startMs && it <= reference.endMs }) potentiallyBoundaryDeletions++
                }
                !same -> { substitutions++; actor!![1]++; emotion!![1]++; if (nearSeam) boundarySubstitutions++ }
            }
        }
        check(substitutions + deletions + insertions == document.getJSONObject("asr_metrics").getInt("word_errors"))
        val nearSeamWords = actual.count { word -> seams.any { abs(it - word.midpointMs) <= 1000 } }
        var duplicateSeamPairs = 0
        var ownerlessSeamPairs = 0
        for (i in 0 until raw.length() - 1) {
            val left = raw.getJSONObject(i)
            val right = raw.getJSONObject(i + 1)
            val seam = left.getLong("core_end") / 16
            val leftWords = decodeWindow(left, tokens, document.getLong("duration_ms"))
            val rightWords = decodeWindow(right, tokens, document.getLong("duration_ms"))
            val usedRight = mutableSetOf<Int>()
            for (word in leftWords) {
                val normalized = SpeakerRecognitionMetrics.words(word.text)
                if (normalized.isEmpty() || word.endMs < seam - 1000 || word.startMs > seam + 1000) continue
                val candidate = rightWords.indices.filter { index ->
                    index !in usedRight && SpeakerRecognitionMetrics.words(rightWords[index].text) == normalized &&
                        maxOf(word.startMs, rightWords[index].startMs) < minOf(word.endMs, rightWords[index].endMs)
                }.minByOrNull { abs(midpoint(word) - midpoint(rightWords[it])) } ?: continue
                usedRight += candidate
                val keptLeft = midpoint(word) < seam
                val keptRight = midpoint(rightWords[candidate]) >= seam
                if (keptLeft && keptRight) duplicateSeamPairs++
                if (!keptLeft && !keptRight) ownerlessSeamPairs++
            }
        }
        println("ASR DEVELOPMENT $id: referenceWords=${expected.size}, substitutions=$substitutions, " +
            "deletions=$deletions, insertions=$insertions, predictedTokens=${actual.size}, " +
            "tokensWithinOneSecondOfSeam=$nearSeamWords, substitutionsNearSeam=$boundarySubstitutions, " +
            "insertionsNearSeam=$boundaryInsertions, deletionsInReferenceClipsCrossingSeam=$potentiallyBoundaryDeletions, " +
            "duplicateOverlapCandidates=$duplicateSeamPairs, ownerlessOverlapCandidates=$ownerlessSeamPairs")
        actors.forEach { (actor, counts) -> println("ASR DEVELOPMENT $id actor=$actor: " +
            "referenceWords=${counts[0]}, substitutions=${counts[1]}, deletions=${counts[2]}") }
        emotions.forEach { (emotion, counts) -> println("ASR DEVELOPMENT $id emotion=$emotion: " +
            "referenceWords=${counts[0]}, substitutions=${counts[1]}, deletions=${counts[2]}") }
        println("ASR diagnostic scope: one optimal alignment; seam counts use approximate CTC times. " +
            "Reference clips have no word timings, so crossing-clip deletions are only candidates. " +
            "Matching overlap candidates are not proof of duplicate speech.")
    }

    private fun midpoint(word: TimedWord) = word.startMs + (word.endMs - word.startMs) / 2

    private fun decodeWindow(window: JSONObject, tokens: List<String>, durationMs: Long): List<TimedWord> {
        val ids = window.getJSONArray("ids")
        return CtcDecoder.decode(IntArray(ids.length()) { ids.getInt(it) }, tokens, tokens.size,
            window.getLong("start") / 16, maxOf(window.getLong("end") - window.getLong("start"), 4000) / 16)
            .filter { it.startMs < durationMs }.map { it.copy(endMs = minOf(it.endMs, durationMs)) }
    }

    private fun align(reference: List<String>, hypothesis: List<String>, visit: (Int, Int, Boolean) -> Unit) {
        // Five-minute development blocks bound this diagnostic matrix; it never enters the APK.
        val cost = Array(reference.size + 1) { IntArray(hypothesis.size + 1) }
        for (r in reference.indices) cost[r + 1][0] = r + 1
        for (h in hypothesis.indices) cost[0][h + 1] = h + 1
        for (r in reference.indices) for (h in hypothesis.indices) {
            cost[r + 1][h + 1] = minOf(cost[r][h] + if (reference[r] == hypothesis[h]) 0 else 1,
                cost[r][h + 1] + 1, cost[r + 1][h] + 1)
        }
        var r = reference.size
        var h = hypothesis.size
        while (r > 0 || h > 0) {
            val same = r > 0 && h > 0 && reference[r - 1] == hypothesis[h - 1]
            when {
                r > 0 && h > 0 && cost[r][h] == cost[r - 1][h - 1] + if (same) 0 else 1 -> {
                    visit(--r, --h, same)
                }
                r > 0 && cost[r][h] == cost[r - 1][h] + 1 -> visit(--r, -1, false)
                else -> visit(-1, --h, false)
            }
        }
    }
}
