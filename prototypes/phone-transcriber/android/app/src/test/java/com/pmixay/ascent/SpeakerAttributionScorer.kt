package com.pmixay.ascent

/** A nonoverlapping, half-open interval from an independently fixed reference timeline. */
internal data class AttributionCell(
    val reader: String,
    val startMs: Long,
    val endMs: Long,
    val predictedSpeakerId: String,
)

internal data class SpeakerAttributionScore(
    val correctMs: Long,
    val totalMs: Long,
    val unknownMs: Long,
    val perReaderAccuracy: Map<String, Double>,
    val matching: Map<String, String>,
) {
    val accuracy: Double get() = correctMs.toDouble() / totalMs
    val macroAccuracy: Double get() = perReaderAccuracy.values.average()
    val unknownRate: Double get() = unknownMs.toDouble() / totalMs
}

/**
 * Duration-weighted clip-time attribution, not DER. The caller freezes reference coverage
 * independently of activity/quality gates and supplies every cell, including missing predictions.
 * Each interval is counted once; unknowns and extra fragments stay in the error denominator.
 */
internal object SpeakerAttributionScorer {
    fun score(cells: List<AttributionCell>): SpeakerAttributionScore {
        require(cells.isNotEmpty()) { "Reference coverage must not be empty" }
        require(cells.all {
            it.reader.isNotBlank() && it.reader != SpeakerClusterer.UNKNOWN &&
                it.predictedSpeakerId.isNotBlank() && it.startMs >= 0 && it.endMs > it.startMs
        }) { "Invalid reference attribution cell" }
        val ordered = cells.sortedBy { it.startMs }
        require(ordered.zipWithNext().all { (left, right) -> left.endMs <= right.startMs }) {
            "Reference attribution cells must not overlap"
        }
        val readers = cells.map { it.reader }.distinct().sorted()
        val labels = cells.map { it.predictedSpeakerId }.filter { it != SpeakerClusterer.UNKNOWN }.distinct().sorted()
        // The production clusterer caps speaker discovery at twelve; keep subset matching bounded.
        require(labels.size <= 12) { "Too many predicted labels for bounded attribution matching" }
        val readerIndices = readers.withIndex().associate { it.value to it.index }
        val labelIndices = labels.withIndex().associate { it.value to it.index }
        val durations = LongArray(readers.size)
        val matches = Array(readers.size) { LongArray(labels.size) }
        var unknownMs = 0L
        for (cell in cells) {
            val reader = readerIndices.getValue(cell.reader)
            val duration = cell.endMs - cell.startMs
            durations[reader] += duration
            val label = labelIndices[cell.predictedSpeakerId]
            if (label == null) unknownMs += duration else matches[reader][label] += duration
        }

        // One global, one-to-one permutation prevents several fragments of one reader, or one
        // merged label shared by many readers, from all being counted as correct.
        val memo = mutableMapOf<Pair<Int, Int>, Long>()
        fun best(reader: Int, used: Int): Long {
            if (reader == readers.size) return 0L
            return memo.getOrPut(reader to used) {
                var maximum = best(reader + 1, used)
                for (label in labels.indices) if (used and (1 shl label) == 0) {
                    maximum = maxOf(maximum, matches[reader][label] + best(reader + 1, used or (1 shl label)))
                }
                maximum
            }
        }
        val correctMs = best(0, 0)
        val mapping = mutableMapOf<String, String>()
        val perReaderAccuracy = mutableMapOf<String, Double>()
        var used = 0
        for (reader in readers.indices) {
            val optimum = best(reader, used)
            val label = labels.indices.firstOrNull {
                used and (1 shl it) == 0 && matches[reader][it] > 0 &&
                    matches[reader][it] + best(reader + 1, used or (1 shl it)) == optimum
            }
            val correct = if (label == null) 0L else matches[reader][label]
            perReaderAccuracy[readers[reader]] = correct.toDouble() / durations[reader]
            if (label != null) {
                mapping[readers[reader]] = labels[label]
                used = used or (1 shl label)
            }
        }
        return SpeakerAttributionScore(correctMs, durations.sum(), unknownMs, perReaderAccuracy, mapping)
    }
}
