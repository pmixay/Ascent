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
 * Bounded average-linkage discovery, centroid consolidation and evidence-gated reassignment.
 * Discovery and labeling are different decisions: a short phrase can match an established voice
 * without being allowed to establish a new voice or teach a remembered profile.
 * These cosine thresholds are initial operating points, not calibrated Russian accuracy claims.
 */
class SpeakerClusterer(
    private val threshold: Float = 0.55f,
    private val margin: Float = 0.08f,
    private val maxSpeakers: Int = 12,
    private val maxAnchors: Int = 256,
    private val assignmentThreshold: Float = maxOf(threshold, 0.60f),
    private val centroidThreshold: Float = 0.75f,
) {
    init {
        require(threshold in 0f..1f && margin in 0f..1f &&
            assignmentThreshold in 0f..1f && centroidThreshold in 0f..1f)
        require(maxSpeakers > 0 && maxAnchors in 1..512)
    }

    fun cluster(observations: List<SpeakerObservation>): SpeakerClustering {
        val vectors = observations.map { normalized(it.vector) }
        val dimension = vectors.firstOrNull { it != null }?.size
        val usable = observations.indices.filter {
            val item = observations[it]
            vectors[it]?.size == dimension && vectors[it] != null && item.canSeed &&
                item.endMs - item.startMs >= 640 && item.quality.isFinite() && item.quality >= 0.55f &&
                support(observations, listOf(it)) >= 640
        }.sortedWith(compareBy({ observations[it].startMs }, { observations[it].endMs }, { it }))
        // Reject a genuinely mixed temporal boundary before agglomeration: after merging it
        // into a voice, no centroid-only pass can remove its contamination. The neighboring
        // voices must be substantially more separated than ordinary same-voice variation.
        val candidates = usable.filterIndexed { position, index ->
            val before = usable.getOrNull(position - 1)
            val after = usable.getOrNull(position + 1)
            if (before == null || after == null ||
                observations[before].endMs <= observations[index].startMs ||
                observations[after].startMs >= observations[index].endMs) true
            else {
                val left = dot(vectors[index]!!, vectors[before]!!)
                val right = dot(vectors[index]!!, vectors[after]!!)
                !(dot(vectors[before]!!, vectors[after]!!) < 0.45f &&
                    left >= 0.55f && right >= 0.55f && abs(left - right) < 0.10f)
            }
        }
        val mixedBoundaries = usable.toSet() - candidates.toSet()
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
        fun group(indices: List<Int>) = Group(indices, mean(indices.map { vectors[it]!! }), support(observations, indices))
        val groups = members.indices.filter { active[it] }.map { index ->
            group(members[index])
        }
        val anchorOrder = anchors.sortedBy { observations[it].startMs }
        // A short context between two distinct voices is not evidence for a third person.
        val consolidated = groups.filter { group ->
            if (group.supportMs >= 3000 || groups.size < 3) true else {
                val neighbors = groups.filter { it !== group }
                    .map { it to dot(group.center, it.center) }.sortedByDescending { it.second }
                val firstPosition = anchorOrder.indexOfFirst { it in group.indices }
                val lastPosition = anchorOrder.indexOfLast { it in group.indices }
                val before = anchorOrder.getOrNull(firstPosition - 1)
                val after = anchorOrder.getOrNull(lastPosition + 1)
                val betweenNeighbors = before != null && after != null &&
                    ((before in neighbors[0].first.indices && after in neighbors[1].first.indices) ||
                        (before in neighbors[1].first.indices && after in neighbors[0].first.indices)) &&
                    group.indices.minOf { observations[it].startMs } - observations[before].endMs <= 500 &&
                    observations[after].startMs - group.indices.maxOf { observations[it].endMs } <= 500 &&
                    group.indices.maxOf { observations[it].endMs } -
                        group.indices.minOf { observations[it].startMs } <= 3000
                // Similarity to two voices alone does not make a third voice a mixed boundary.
                // It must actually lie between those voices in time; distant replies are kept.
                !(betweenNeighbors && neighbors[0].second >= 0.60f && neighbors[1].second >= 0.60f &&
                    abs(neighbors[0].second - neighbors[1].second) < margin &&
                    dot(neighbors[0].first.center, neighbors[1].first.center) < 0.60f)
            }
        }.toMutableList()

        // Average linkage compares individual noisy embeddings. Two fragments of the same voice
        // can fail that comparison even when their denoised, normalized centroids agree. This is
        // a separate centroid pass, as in the upstream 3D-Speaker clustering backend; it does not
        // lower the discovery threshold or force small clusters into the largest speaker.
        while (consolidated.size > 1) {
            checkCancelled()
            var first = -1
            var second = -1
            var best = maxOf(threshold, centroidThreshold)
            for (a in consolidated.indices) for (b in a + 1 until consolidated.size) {
                if (consolidated[a].indices.size == 1 && consolidated[b].indices.size == 1) continue
                val score = dot(consolidated[a].center, consolidated[b].center)
                if (score >= best && (first == -1 || score > best)) {
                    first = a; second = b; best = score
                }
            }
            if (first == -1) break
            consolidated[first] = group(consolidated[first].indices + consolidated[second].indices)
            consolidated.removeAt(second)
        }

        // A single poor context inside a stable voice must not create a new person solely because
        // of a phonetic/channel excursion. Require acoustic agreement AND that same established
        // cluster immediately on both sides. A different short voice and a one-sided boundary
        // remain independent; no duration-only minor-cluster deletion is used.
        val originalOwners = consolidated.flatMapIndexed { owner, item -> item.indices.map { it to owner } }.toMap()
        val fragmentParents = mutableMapOf<Int, Int>()
        for ((index, item) in consolidated.withIndex()) {
            if (item.indices.size != 1 || item.supportMs >= 2400) continue
            val position = anchorOrder.indexOf(item.indices.single())
            val before = anchorOrder.getOrNull(position - 1) ?: continue
            val after = anchorOrder.getOrNull(position + 1) ?: continue
            val parent = originalOwners[before] ?: continue
            if (parent == index || originalOwners[after] != parent) continue
            val established = consolidated[parent]
            if (established.indices.size < 3 || established.supportMs < 3000) continue
            val sample = observations[item.indices.single()]
            // A clear short reply is evidence for another voice, even inside someone else's
            // longer turn. Only a degraded context is eligible for fragment cleanup.
            if (sample.quality >= 0.75f) continue
            if (sample.startMs - observations[before].endMs > 500 ||
                observations[after].startMs - sample.endMs > 500) continue
            val ranked = consolidated.indices.filter { it != index }
                .map { it to dot(item.center, consolidated[it].center) }.sortedByDescending { it.second }
            if (ranked.first().first != parent || ranked.first().second < (assignmentThreshold - 0.10f).coerceAtLeast(0.45f)) continue
            if (ranked.first().second - (ranked.getOrNull(1)?.second ?: -1f) < maxOf(margin, 0.12f)) continue
            fragmentParents[index] = parent
        }
        // A recovered fragment may be labeled, but must not influence the centroid or enrollment.
        val recoveredFragments = fragmentParents.keys.flatMap { consolidated[it].indices }.toSet()
        val cleanGroups = consolidated.filterIndexed { index, _ -> index !in fragmentParents }
            .sortedWith(compareByDescending<Group> { it.supportMs }.thenBy { observations[it.indices.first()].startMs })
            .take(maxSpeakers)
            .sortedBy { group -> group.indices.minOf { observations[it].startMs } }
        if (cleanGroups.isEmpty()) return unknown(observations.size)

        val margins = FloatArray(observations.size)
        val bestGroups = IntArray(observations.size) { -1 }
        val bestScores = FloatArray(observations.size)
        val assignments = observations.indices.map { index ->
            val vector = vectors[index]
            if (vector == null || vector.size != dimension || !observations[index].quality.isFinite() ||
                observations[index].quality < 0.20f || support(observations, listOf(index)) < 320)
                SpeakerAssignment(UNKNOWN, 0f)
            else {
                val ranked = cleanGroups.indices.map { it to dot(vector, cleanGroups[it].center) }
                    .sortedByDescending { it.second }
                val best = ranked.first()
                val difference = best.second - (ranked.getOrNull(1)?.second ?: -1f)
                margins[index] = difference
                bestGroups[index] = best.first
                bestScores[index] = best.second
                // Low speech occupancy is acceptable for an acoustically clear short reply, but
                // it needs stronger evidence and still cannot become a training observation.
                val minimum = if (observations[index].quality < 0.35f) maxOf(assignmentThreshold, 0.80f) else assignmentThreshold
                val separation = if (observations[index].quality < 0.35f) maxOf(margin, 0.12f) else margin
                if (best.second < minimum || difference < separation) SpeakerAssignment(UNKNOWN, best.second)
                else SpeakerAssignment("speaker_${best.first + 1}", best.second)
            }
        }.toMutableList()
        val strictAssignments = assignments.toList()
        val order = observations.indices.sortedWith(compareBy({ observations[it].startMs }, { observations[it].endMs }))
        for ((position, index) in order.withIndex()) {
            if (strictAssignments[index].speakerId != UNKNOWN || bestGroups[index] < 0) continue
            val groupIndex = bestGroups[index]
            val id = "speaker_${groupIndex + 1}"
            val before = order.getOrNull(position - 1) ?: continue
            val after = order.getOrNull(position + 1) ?: continue
            if (strictAssignments[before].speakerId != id || strictAssignments[after].speakerId != id) continue
            val item = observations[index]
            if (item.startMs - observations[before].endMs > 500 ||
                observations[after].startMs - item.endMs > 500) continue
            if (bestScores[index] < (assignmentThreshold - 0.12f).coerceAtLeast(0.45f) || margins[index] < margin) continue
            if (cleanGroups[groupIndex].indices.size < 2 || cleanGroups[groupIndex].supportMs < 2400) continue
            assignments[index] = SpeakerAssignment(id, bestScores[index])
        }
        val signatures = cleanGroups.indices.mapNotNull { groupIndex ->
            val id = "speaker_${groupIndex + 1}"
            val trusted = observations.indices.filter {
                it !in recoveredFragments && it !in mixedBoundaries &&
                    observations[it].canSeed && observations[it].quality >= 0.6f &&
                    strictAssignments[it].speakerId == id &&
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
