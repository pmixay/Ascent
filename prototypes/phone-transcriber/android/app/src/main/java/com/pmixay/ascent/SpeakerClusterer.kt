package com.pmixay.ascent

import kotlin.math.abs
import kotlin.math.sqrt

/** One speech-constrained context. Boundary contexts can vote, but cannot train a cluster. */
data class SpeechSpan(val startMs: Long, val endMs: Long)

data class SpeakerObservation(
    val startMs: Long,
    val endMs: Long,
    val vector: FloatArray,
    val quality: Float = 1f,
    val canSeed: Boolean = true,
    val speech: List<SpeechSpan> = listOf(SpeechSpan(startMs, endMs)),
)

data class SpeakerAssignment(val speakerId: String, val similarity: Float)
data class SpeakerClustering(val assignments: List<SpeakerAssignment>, val signatures: List<VoiceSignature>)

/**
 * Bounded, deterministic average-linkage clustering followed by confidence-gated reassignment.
 * These cosine thresholds are initial operating points, not calibrated Russian accuracy claims.
 */
class SpeakerClusterer(
    private val threshold: Float = 0.72f,
    private val margin: Float = 0.08f,
    private val maxSpeakers: Int = 12,
    private val maxAnchors: Int = 256,
) {
    init {
        require(threshold in 0f..1f && margin in 0f..1f)
        require(maxSpeakers > 0 && maxAnchors in 1..512)
    }

    fun cluster(observations: List<SpeakerObservation>): SpeakerClustering {
        val vectors = observations.map { normalized(it.vector) }
        val dimension = vectors.firstOrNull { it != null }?.size
        val candidates = observations.indices.filter {
            val item = observations[it]
            vectors[it]?.size == dimension && vectors[it] != null && item.canSeed &&
                item.endMs - item.startMs >= 640 && item.quality.isFinite() && item.quality >= 0.55f
        }.sortedWith(compareBy({ observations[it].startMs }, { observations[it].endMs }, { it }))
        if (candidates.isEmpty()) return unknown(observations.size)

        // Cover the full session rather than only its beginning. At most 256 squared scores.
        val anchors = if (candidates.size <= maxAnchors) candidates else (0 until maxAnchors).map { bucket ->
            val from = bucket * candidates.size / maxAnchors
            val until = (bucket + 1) * candidates.size / maxAnchors
            candidates.subList(from, until).maxBy { observations[it].quality }
        }
        val count = anchors.size
        val scores = Array(count) { FloatArray(count) }
        val members = Array(count) { mutableListOf(anchors[it]) }
        val active = BooleanArray(count) { true }
        for (a in 0 until count) for (b in a + 1 until count) {
            val score = dot(vectors[anchors[a]]!!, vectors[anchors[b]]!!)
            scores[a][b] = score
            scores[b][a] = score
        }
        while (true) {
            checkCancelled()
            var first = -1
            var second = -1
            var best = threshold
            for (a in 0 until count) if (active[a]) for (b in a + 1 until count) if (active[b]) {
                if (scores[a][b] >= best && (first == -1 || scores[a][b] > best)) {
                    first = a; second = b; best = scores[a][b]
                }
            }
            if (first == -1) break
            val firstSize = members[first].size
            val secondSize = members[second].size
            for (other in 0 until count) if (active[other] && other != first && other != second) {
                val score = (scores[first][other] * firstSize + scores[second][other] * secondSize) /
                    (firstSize + secondSize)
                scores[first][other] = score
                scores[other][first] = score
            }
            members[first].addAll(members[second])
            active[second] = false
        }
        data class Group(val indices: List<Int>, val center: FloatArray, val supportMs: Long)
        val groups = members.indices.filter { active[it] }.map { index ->
            val indices = members[index]
            Group(indices, mean(indices.map { vectors[it]!! }), support(observations, indices))
        }
        // A short context between two distinct voices is not evidence for a third person.
        val cleanGroups = groups.filter { group ->
            if (group.supportMs >= 3000 || groups.size < 3) true else {
                val neighbors = groups.filter { it !== group }
                    .map { it to dot(group.center, it.center) }.sortedByDescending { it.second }
                !(neighbors[0].second >= 0.60f && neighbors[1].second >= 0.60f &&
                    abs(neighbors[0].second - neighbors[1].second) < margin &&
                    dot(neighbors[0].first.center, neighbors[1].first.center) < 0.60f)
            }
        }.sortedWith(compareByDescending<Group> { it.supportMs }.thenBy { observations[it.indices.first()].startMs })
            .take(maxSpeakers)
            .sortedBy { group -> group.indices.minOf { observations[it].startMs } }
        if (cleanGroups.isEmpty()) return unknown(observations.size)

        val margins = FloatArray(observations.size)
        val assignments = observations.indices.map { index ->
            val vector = vectors[index]
            if (vector == null || vector.size != dimension || !observations[index].quality.isFinite() ||
                observations[index].quality < 0.35f) SpeakerAssignment(UNKNOWN, 0f)
            else {
                val ranked = cleanGroups.indices.map { it to dot(vector, cleanGroups[it].center) }
                    .sortedByDescending { it.second }
                val best = ranked.first()
                val difference = best.second - (ranked.getOrNull(1)?.second ?: -1f)
                margins[index] = difference
                if (best.second < threshold || difference < margin) SpeakerAssignment(UNKNOWN, best.second)
                else SpeakerAssignment("speaker_${best.first + 1}", best.second)
            }
        }
        val signatures = cleanGroups.indices.mapNotNull { groupIndex ->
            val id = "speaker_${groupIndex + 1}"
            val trusted = observations.indices.filter {
                observations[it].canSeed && observations[it].quality >= 0.6f &&
                    assignments[it].speakerId == id && assignments[it].similarity >= 0.80f && margins[it] >= 0.12f
            }
            if (trusted.isEmpty()) null else {
                val center = mean(trusted.map { vectors[it]!! })
                VoiceSignature(id, center, support(observations, trusted), trusted.size,
                    trusted.map { dot(vectors[it]!!, center).toDouble() }.average().toFloat())
            }
        }
        return SpeakerClustering(assignments, signatures)
    }

    private fun unknown(count: Int) = SpeakerClustering(List(count) { SpeakerAssignment(UNKNOWN, 0f) }, emptyList())

    companion object {
        const val UNKNOWN = "speaker_unknown"

        internal fun normalized(vector: FloatArray): FloatArray? {
            if (vector.isEmpty() || vector.any { !it.isFinite() }) return null
            val norm = sqrt(vector.sumOf { it.toDouble() * it })
            if (norm < 1e-8 || !norm.isFinite()) return null
            return FloatArray(vector.size) { (vector[it] / norm).toFloat() }
        }

        internal fun dot(a: FloatArray, b: FloatArray): Float =
            a.indices.sumOf { a[it].toDouble() * b[it] }.toFloat().coerceIn(-1f, 1f)

        private fun mean(vectors: List<FloatArray>): FloatArray {
            val sum = FloatArray(vectors.first().size)
            vectors.forEach { vector -> vector.indices.forEach { sum[it] += vector[it] } }
            return normalized(sum) ?: vectors.first().copyOf()
        }

        /** Union duration prevents overlapping contexts from inventing extra enrollment evidence. */
        private fun support(observations: List<SpeakerObservation>, indices: List<Int>): Long {
            var end = Long.MIN_VALUE
            var total = 0L
            for (item in indices.flatMap { observations[it].speech }.sortedBy { it.startMs }) {
                total += (item.endMs - maxOf(item.startMs, end)).coerceAtLeast(0)
                end = maxOf(end, item.endMs)
            }
            return total
        }

        private fun checkCancelled() {
            if (Thread.currentThread().isInterrupted) throw InterruptedException("Обработка отменена")
        }
    }
}
