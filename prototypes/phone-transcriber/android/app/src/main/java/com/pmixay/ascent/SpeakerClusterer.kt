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
 * P-pruned spectral discovery with bounded average-linkage fallback, centroid consolidation
 * and evidence-gated reassignment.
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
    private val centroidThreshold: Float = 0.70f,
) {
    init {
        require(threshold in 0f..1f && margin in 0f..1f &&
            assignmentThreshold in 0f..1f && centroidThreshold in 0f..1f)
        require(maxSpeakers > 0 && maxAnchors in 1..512)
    }

    fun cluster(observations: List<SpeakerObservation>): SpeakerClustering = cluster(observations, false)

    /** Display-only fallback when no strict voice exists. Never export enrollment signatures. */
    internal fun clusterShortEvidence(observations: List<SpeakerObservation>): SpeakerClustering =
        cluster(observations, true)

    private fun cluster(observations: List<SpeakerObservation>, shortEvidence: Boolean): SpeakerClustering {
        val vectors = observations.map { normalized(it.vector) }
        val dimension = vectors.firstOrNull { it != null }?.size
        val minimumSeedMs = if (shortEvidence) 320 else 640
        val minimumQuality = if (shortEvidence) .20f else .55f
        val usable = observations.indices.filter {
            val item = observations[it]
            vectors[it]?.size == dimension && vectors[it] != null && (item.canSeed || shortEvidence) &&
                item.endMs - item.startMs >= minimumSeedMs && item.quality.isFinite() && item.quality >= minimumQuality &&
                support(observations, listOf(it)) >= minimumSeedMs
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

        // Preserve different voices before filling the remaining budget across the full session.
        // Index buckets alone alias periodic turns: every [A, B] bucket can otherwise select A.
        val anchors = selectAnchors(candidates, observations, vectors)
        val count = anchors.size
        val scores = Array(count) { FloatArray(count) }
        for (a in 0 until count) for (b in a + 1 until count) {
            val score = dot(vectors[anchors[a]]!!, vectors[anchors[b]]!!)
            scores[a][b] = score
            scores[b][a] = score
        }
        val spectralMembers = spectralPartition(anchors, vectors)
        val members = spectralMembers?.map { it.toMutableList() }?.toMutableList()
            ?: MutableList(count) { mutableListOf(anchors[it]) }
        val active = BooleanArray(members.size) { true }
        while (spectralMembers == null) {
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
        data class Group(val indices: List<Int>, val center: FloatArray, val supportMs: Long,
            val independentAgreement: Double, val independentPairs: Int) {
            val independentCohesion: Float? get() = if (independentPairs == 0) null else
                (independentAgreement / independentPairs).toFloat()
        }
        fun independentScore(first: Int, second: Int): Float? =
            if (observations[first].endMs <= observations[second].startMs ||
                observations[second].endMs <= observations[first].startMs)
                dot(vectors[first]!!, vectors[second]!!) else null
        fun group(indices: List<Int>, pairEvidence: Pair<Double, Int>? = null): Group {
            var agreement = pairEvidence?.first ?: 0.0
            var independentPairs = pairEvidence?.second ?: 0
            if (pairEvidence == null) for (a in indices.indices) for (b in a + 1 until indices.size) {
                independentScore(indices[a], indices[b])?.let { score -> agreement += score; independentPairs++ }
            }
            return Group(indices, mean(indices.map { vectors[it]!! }), support(observations, indices),
                agreement, independentPairs)
        }
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
                val groupStart = group.indices.minOf { observations[it].startMs }
                val groupEnd = group.indices.maxOf { observations[it].endMs }
                val betweenNeighbors = before != null && after != null &&
                    ((before in neighbors[0].first.indices && after in neighbors[1].first.indices) ||
                        (before in neighbors[1].first.indices && after in neighbors[0].first.indices)) &&
                    observations[before].endMs > groupStart && observations[after].startMs < groupEnd &&
                    groupEnd - groupStart <= 3000
                // Similarity to two voices alone does not make a third voice a mixed boundary.
                // It must actually lie between those voices in time; distant replies are kept.
                !(betweenNeighbors && neighbors[0].second >= 0.60f && neighbors[1].second >= 0.60f &&
                    abs(neighbors[0].second - neighbors[1].second) < margin &&
                    dot(neighbors[0].first.center, neighbors[1].first.center) < 0.60f)
            }
        }.toMutableList()

        // Spectral partitions and average-linkage fallback groups can still fragment one voice.
        // Consolidate only when independently supported group centroids agree.
        while (consolidated.size > 1) {
            checkCancelled()
            var first = -1
            var second = -1
            var best = if (spectralMembers != null) maxOf(0.82f, centroidThreshold)
                else maxOf(threshold, centroidThreshold)
            for (a in consolidated.indices) for (b in a + 1 until consolidated.size) {
                if (consolidated[a].indices.size == 1 && consolidated[b].indices.size == 1) continue
                val score = dot(consolidated[a].center, consolidated[b].center)
                val firstCohesion = consolidated[a].independentCohesion
                val secondCohesion = consolidated[b].independentCohesion
                // More observations denoise both centroids and can raise their cosine even
                // for distinct voices. Two independently coherent groups must agree at least
                // as much across their centers as their own original contexts do internally.
                // Overlapping windows cannot establish that independent coherence.
                if (firstCohesion != null && secondCohesion != null &&
                    score < maxOf(firstCohesion, secondCohesion)) continue
                if (score >= best && (first == -1 || score > best)) {
                    first = a; second = b; best = score
                }
            }
            if (first == -1) break
            val left = consolidated[first]
            val right = consolidated[second]
            var agreement = left.independentAgreement + right.independentAgreement
            var independentPairs = left.independentPairs + right.independentPairs
            // Reuse each group's evidence. Only newly joined cross-pairs need a dot product,
            // bounding all cohesion work across successive merges by the anchor pair count.
            for (a in left.indices) for (b in right.indices) independentScore(a, b)?.let { score ->
                agreement += score
                independentPairs++
            }
            consolidated[first] = group(left.indices + right.indices, agreement to independentPairs)
            consolidated.removeAt(second)
        }

        val originalOwners = consolidated.flatMapIndexed { owner, item -> item.indices.map { it to owner } }.toMap()
        val anchorPositions = anchorOrder.withIndex().associate { it.value to it.index }
        val fragmentParents = mutableMapOf<Int, Int>()
        val prototypeAssignments = mutableMapOf<Int, Pair<Int, Float>>()
        val prototypeParents = mutableMapOf<Int, Int>()
        val proposedAssignments = mutableMapOf<Int, Pair<Int, Float>>()
        // Measure established voices before any recovery. Weak/non-seeding observations may
        // supply duration only when they already pass the ordinary acoustic assignment rules.
        // This does not retrain the original centroid or recursively add recovered evidence.
        val originalSupporters = List(consolidated.size) { mutableListOf<Int>() }
        observations.indices.forEach { index ->
            val item = observations[index]
            val vector = vectors[index]
            if (vector == null || vector.size != dimension || !item.quality.isFinite() ||
                item.quality < .20f || support(observations, listOf(index)) < 320 || index in mixedBoundaries)
                return@forEach
            val ranked = consolidated.indices.map { it to dot(vector, consolidated[it].center) }
                .sortedByDescending { it.second }
            val best = ranked.firstOrNull() ?: return@forEach
            if (originalOwners[index] != null && originalOwners[index] != best.first) return@forEach
            val minimum = if (item.quality < .35f) maxOf(assignmentThreshold, .80f) else assignmentThreshold
            val separation = if (item.quality < .35f) maxOf(margin, .12f) else margin
            if (best.second >= minimum && best.second - (ranked.getOrNull(1)?.second ?: -1f) >= separation)
                originalSupporters[best.first].add(index)
        }
        val originalSupportMs = originalSupporters.map { support(observations, it) }
        // Seed eligibility can leave only a few contexts in a voice's discovery centroid.
        // Independently assigned speech supplies a second, frozen verification template.
        // A candidate fragment's own observations never belong to this parent template, and
        // these extra samples never alter discovery centroids or remembered voice signatures.
        val verificationCenters = originalSupporters.mapIndexed { index, indices ->
            if (indices.isEmpty()) consolidated[index].center else mean(indices.map { vectors[it]!! })
        }
        fun verificationScore(vector: FloatArray, groupIndex: Int): Float = maxOf(
            dot(vector, consolidated[groupIndex].center), dot(vector, verificationCenters[groupIndex]))
        val independentSeeds = consolidated.map { item ->
            item.indices.any { left -> item.indices.any { right ->
                observations[left].endMs <= observations[right].startMs
            } }
        }
        // A broad voice can contain several phonetic modes. A returning mode may be
        // far from its global centroid, while matching independent, original observations of
        // that same voice. Require two nonoverlapping original contexts: overlapping windows
        // from one acoustic event must not corroborate one another. The established parent
        // must have at least twice the unique speech support. Recurrence alone does not prove
        // another person: a voice's phonetic/channel variation can recur throughout a session.
        fun independentSimilarity(vector: FloatArray, indices: List<Int>): Float {
            val ranked = indices.map { it to dot(vector, vectors[it]!!) }.sortedByDescending { it.second }
            for (position in ranked.indices) {
                val current = observations[ranked[position].first]
                if ((0 until position).any { earlier ->
                    val other = observations[ranked[earlier].first]
                    current.startMs >= other.endMs || other.startMs >= current.endMs
                }) return ranked[position].second
            }
            return -1f
        }
        for ((index, item) in consolidated.withIndex()) {
            checkCancelled()
            val matches = item.indices.map { observation ->
                val vector = vectors[observation]!!
                val independent = consolidated.map { independentSimilarity(vector, it.indices) }
                val eligible = consolidated.indices.filter { parent ->
                    parent != index && consolidated[parent].indices.size >= 3 &&
                        consolidated[parent].supportMs >= maxOf(3000, item.supportMs * 2)
                }
                val parent = eligible.maxByOrNull { independent[it] } ?: return@map null
                val score = independent[parent]
                val runnerUp = consolidated.indices.filter { it != index && it != parent }.maxOfOrNull {
                    maxOf(dot(vector, consolidated[it].center), independent[it])
                } ?: -1f
                if (score < maxOf(threshold, centroidThreshold) || score - runnerUp < margin) null
                else parent to score
            }
            val parent = matches.firstOrNull()?.first
            if (parent != null && matches.all { it != null && it.first == parent }) {
                prototypeParents[index] = parent
                item.indices.zip(matches).forEach { (observation, match) ->
                    proposedAssignments[observation] = match!!
                }
            }
        }
        val originalPrototypeParents = prototypeParents.toMap()
        val sourceIndicesByGroup = consolidated.mapIndexed { index, item ->
            (item.indices + originalSupporters[index]).distinct()
        }
        val sourceSupportByGroup = sourceIndicesByGroup.map { support(observations, it) }
        val establishedByGroup = consolidated.indices.map { index -> consolidated.indices.filter { candidate ->
            candidate != index && independentSeeds[candidate] &&
                originalSupportMs[candidate] >= maxOf(4000, sourceSupportByGroup[index] * 2)
        } }
        // Freeze each tiny group's independent preference against established voices first.
        // Two weak fragments must not become mutually convincing "new people" merely because
        // their nuisance features agree. Each must independently satisfy the unchanged
        // assignment and margin rules for EVERY original observation, without using the
        // other fragment, its proposed label, or any recovered template as parent evidence.
        val frozenPreferences = mutableMapOf<Int, Int>()
        for (index in consolidated.indices) {
            checkCancelled()
            if (sourceSupportByGroup[index] >= 3000) continue
            val established = establishedByGroup[index]
            val preferences = sourceIndicesByGroup[index].map { observation ->
                val vector = vectors[observation]!!
                val candidate = established.maxByOrNull { verificationScore(vector, it) } ?: return@map null
                val score = verificationScore(vector, candidate)
                val runnerUp = established.filter { it != candidate }.maxOfOrNull { verificationScore(vector, it) } ?: -1f
                val minimum = if (observations[observation].quality < .35f)
                    maxOf(assignmentThreshold, .80f) else assignmentThreshold
                candidate.takeIf { score >= minimum && score - runnerUp >= maxOf(margin, .12f) }
            }
            val candidate = preferences.firstOrNull()
            if (candidate != null && preferences.all { it == candidate } &&
                (originalPrototypeParents[index] == null || originalPrototypeParents[index] == candidate))
                frozenPreferences[index] = candidate
        }
        for ((index, _) in consolidated.withIndex()) {
            checkCancelled()
            if (index in originalPrototypeParents) continue
            // A tiny component can win only because it compares to its own seeds. Before
            // declaring another person, test whether ALL its observations independently pass
            // the existing assignment cutoff against an established voice, excluding itself.
            // This is asymmetric: a well-supported second person never enters this path.
            val sourceIndices = sourceIndicesByGroup[index]
            val sourceSupport = sourceSupportByGroup[index]
            if (sourceSupport >= 3000) continue
            val established = establishedByGroup[index]
            val centroidMatches = sourceIndices.map { observation ->
                val vector = vectors[observation]!!
                val candidate = established.maxByOrNull { verificationScore(vector, it) }
                    ?: return@map null
                val score = verificationScore(vector, candidate)
                val runnerUp = consolidated.indices.filter { rival ->
                    rival != index && rival != candidate && !(frozenPreferences[index] == candidate &&
                        frozenPreferences[rival] == candidate)
                }
                    .maxOfOrNull { verificationScore(vector, it) } ?: -1f
                val minimum = if (observations[observation].quality < .35f)
                    maxOf(assignmentThreshold, .80f) else assignmentThreshold
                if (score >= minimum && score - runnerUp >= maxOf(margin, .12f)) candidate to score
                else null
            }
            val centroidParent = centroidMatches.firstOrNull()?.first ?: continue
            if (centroidMatches.any { it == null || it.first != centroidParent }) continue
            prototypeParents[index] = centroidParent
            sourceIndices.zip(centroidMatches).forEach { (observation, match) ->
                proposedAssignments[observation] = match!!
            }
        }
        // Freeze all proposals before applying any. A proposed fragment cannot corroborate a
        // second recovery, even if it would otherwise be large enough to act as a parent.
        prototypeParents.filterValues { it !in prototypeParents }.forEach { (index, parent) ->
            fragmentParents[index] = parent
            proposedAssignments.filterKeys { it in consolidated[index].indices || it in originalSupporters[index] }
                .forEach { (observation, match) -> prototypeAssignments[observation] = match }
        }
        val establishedParents = fragmentParents.values.toSet()
        // Without the stronger independent-prototype evidence above, recover only a brief,
        // degraded contiguous passage with the same established voice on both sides. Clear,
        // recurring, distant and one-sided voices cannot be removed by temporal continuity.
        for ((index, item) in consolidated.withIndex()) {
            if (index in fragmentParents || index in establishedParents) continue
            if (item.supportMs >= 3000) continue
            val positions = item.indices.map { anchorPositions.getValue(it) }.sorted()
            if (positions.last() - positions.first() + 1 != positions.size) continue
            val first = item.indices.minBy { observations[it].startMs }
            val last = item.indices.maxBy { observations[it].endMs }
            if (observations[last].endMs - observations[first].startMs > 3000) continue
            val before = anchorOrder.getOrNull(positions.first() - 1) ?: continue
            val after = anchorOrder.getOrNull(positions.last() + 1) ?: continue
            val parent = originalOwners[before] ?: continue
            if (parent == index || originalOwners[after] != parent || parent in fragmentParents) continue
            val established = consolidated[parent]
            if (established.indices.size < 3 || established.supportMs < 3000) continue
            // A clear short reply is evidence for another voice, even inside someone else's
            // longer turn. Every constituent must be degraded and support that same parent;
            // averaging contradictory contexts must not manufacture evidence of continuity.
            if (item.indices.any { observations[it].quality >= 0.75f }) continue
            val passage = listOf(before) + anchorOrder.subList(positions.first(), positions.last() + 1) + after
            if (passage.zipWithNext().any { (left, right) ->
                observations[right].startMs - observations[left].endMs > 500
            }) continue
            val compatible = item.indices.all { observation ->
                val ranked = consolidated.indices.filter { it != index }
                    .map { it to dot(vectors[observation]!!, consolidated[it].center) }.sortedByDescending { it.second }
                ranked.first().first == parent &&
                    ranked.first().second >= (assignmentThreshold - 0.10f).coerceAtLeast(0.45f) &&
                    ranked.first().second - (ranked.getOrNull(1)?.second ?: -1f) >= maxOf(margin, 0.12f)
            }
            if (!compatible) continue
            fragmentParents[index] = parent
        }
        // A recovered fragment may be labeled, but must not influence the centroid or enrollment.
        val recoveredFragments = fragmentParents.keys.flatMap { consolidated[it].indices }.toSet() + prototypeAssignments.keys
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
        var position = 0
        while (position < order.size) {
            if (strictAssignments[order[position]].speakerId != UNKNOWN) { position++; continue }
            val start = position
            while (position < order.size && strictAssignments[order[position]].speakerId == UNKNOWN) position++
            val run = order.subList(start, position)
            val before = order.getOrNull(start - 1) ?: continue
            val after = order.getOrNull(position) ?: continue
            if (run.maxOf { observations[it].endMs } - run.minOf { observations[it].startMs } > 3000) continue
            val groupIndex = bestGroups[run.first()]
            if (groupIndex < 0 || run.any { bestGroups[it] != groupIndex }) continue
            val id = "speaker_${groupIndex + 1}"
            if (strictAssignments[before].speakerId != id || strictAssignments[after].speakerId != id) continue
            if ((listOf(before) + run + after).zipWithNext().any { (left, right) ->
                observations[right].startMs - observations[left].endMs > 500
            }) continue
            if (run.any { bestScores[it] < (assignmentThreshold - 0.12f).coerceAtLeast(0.45f) || margins[it] < margin }) continue
            if (cleanGroups[groupIndex].indices.size < 2 || cleanGroups[groupIndex].supportMs < 2400) continue
            // Work from the original strict labels so recovery cannot propagate into later runs.
            run.forEach { assignments[it] = SpeakerAssignment(id, bestScores[it]) }
        }
        // Removing a fragment alone would turn the false speaker into an unknown. Apply only
        // the independently corroborated attribution after temporal recovery, so it cannot
        // become evidence for recovering another interval. Preserve its acoustic score; it
        // never changes the parent's centroid or enrollment signature.
        prototypeAssignments.forEach { (observation, match) ->
            val parent = cleanGroups.indexOf(consolidated[match.first])
            if (parent >= 0) assignments[observation] = SpeakerAssignment("speaker_${parent + 1}", match.second)
        }
        if (shortEvidence) return SpeakerClustering(assignments, emptyList())
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

    /** P-pruned, symmetric cosine graph followed by an eigengap-selected spectral partition. */
    private fun spectralPartition(anchors: List<Int>, vectors: List<FloatArray?>): List<List<Int>>? {
        val size = anchors.size
        // Sparse and condition-heavy regression clips do not provide a stable global graph.
        // Keep those on the established local linkage path; spectral partitioning is for
        // sessions with enough independent anchors to expose recurring speaker modes.
        if (size < 160) return null
        val neighbors = minOf(6, size - 1)
        val affinity = Array(size) { DoubleArray(size) }
        for (row in 0 until size) {
            checkCancelled()
            val ranked = (0 until size).filter { it != row }
                .map { it to dot(vectors[anchors[row]]!!, vectors[anchors[it]]!!).coerceAtLeast(0f) }
                .sortedWith(compareByDescending<Pair<Int, Float>> { it.second }.thenBy { it.first })
            for ((column, score) in ranked.take(neighbors)) {
                val weight = score.toDouble()
                affinity[row][column] = maxOf(affinity[row][column], weight)
                affinity[column][row] = maxOf(affinity[column][row], weight)
            }
        }
        val degree = DoubleArray(size) { row -> affinity[row].sum() }
        if (degree.any { it <= 1e-9 }) return null
        val normalized = Array(size) { row -> DoubleArray(size) { column ->
            affinity[row][column] / sqrt(degree[row] * degree[column])
        } }
        val (eigenvalues, eigenvectors) = symmetricEigen(normalized) ?: return null
        val order = eigenvalues.indices.sortedByDescending { eigenvalues[it] }
        val sortedValues = order.map { eigenvalues[it] }
        val maxClusters = minOf(maxSpeakers, size / 3)
        if (maxClusters < 2) return null
        val candidates = (2..maxClusters).sortedByDescending { count ->
            sortedValues[count - 1] - sortedValues[count]
        }
        for (clusterCount in candidates) {
            checkCancelled()
            val gap = sortedValues[clusterCount - 1] - sortedValues[clusterCount]
            if (gap < 0.025) break
            val coordinates = Array(size) { row ->
                DoubleArray(clusterCount) { column -> eigenvectors[row][order[column]] }
            }
            for (row in 0 until size) {
                val norm = sqrt(coordinates[row].sumOf { it * it })
                if (norm <= 1e-9) continue
                for (column in coordinates[row].indices) coordinates[row][column] /= norm
            }
            val labels = spectralKMeans(coordinates, clusterCount) ?: continue
            val counts = IntArray(clusterCount)
            labels.forEach { counts[it]++ }
            if ((counts.minOrNull() ?: 0) < 3) continue
            return (0 until clusterCount).map { label ->
                labels.indices.filter { labels[it] == label }.map { anchors[it] }
            }
        }
        return null
    }

    /** Cyclic Jacobi diagonalization for the small symmetric anchor graph. */
    private fun symmetricEigen(matrix: Array<DoubleArray>): Pair<DoubleArray, Array<DoubleArray>>? {
        val size = matrix.size
        val values = Array(size) { matrix[it].copyOf() }
        val vectors = Array(size) { row -> DoubleArray(size) { column -> if (row == column) 1.0 else 0.0 } }
        for (sweep in 0 until 24) {
            checkCancelled()
            var largest = 0.0
            for (p in 0 until size - 1) for (q in p + 1 until size) {
                val cross = values[p][q]
                largest = maxOf(largest, abs(cross))
                if (abs(cross) < 1e-10) continue
                val diagonalP = values[p][p]
                val diagonalQ = values[q][q]
                val tau = (diagonalQ - diagonalP) / (2.0 * cross)
                val t = if (tau >= 0) 1.0 / (tau + sqrt(1 + tau * tau))
                    else -1.0 / (-tau + sqrt(1 + tau * tau))
                val cosine = 1.0 / sqrt(1 + t * t)
                val sine = t * cosine
                for (row in 0 until size) if (row != p && row != q) {
                    val oldP = values[row][p]
                    val oldQ = values[row][q]
                    values[row][p] = cosine * oldP - sine * oldQ
                    values[p][row] = values[row][p]
                    values[row][q] = sine * oldP + cosine * oldQ
                    values[q][row] = values[row][q]
                }
                values[p][p] = diagonalP - t * cross
                values[q][q] = diagonalQ + t * cross
                values[p][q] = 0.0
                values[q][p] = 0.0
                for (row in 0 until size) {
                    val oldP = vectors[row][p]
                    val oldQ = vectors[row][q]
                    vectors[row][p] = cosine * oldP - sine * oldQ
                    vectors[row][q] = sine * oldP + cosine * oldQ
                }
            }
            if (largest < 1e-8) break
        }
        val eigenvalues = DoubleArray(size) { values[it][it] }
        if (eigenvalues.any { !it.isFinite() } || vectors.any { row -> row.any { !it.isFinite() } }) return null
        return eigenvalues to vectors
    }

    /** Deterministic farthest-first initialization avoids random session labels. */
    private fun spectralKMeans(points: Array<DoubleArray>, clusters: Int): IntArray? {
        val selected = mutableListOf(0)
        while (selected.size < clusters) {
            val next = points.indices.filter { it !in selected }.maxByOrNull { candidate ->
                selected.minOf { prior -> squaredDistance(points[candidate], points[prior]) }
            } ?: return null
            selected += next
        }
        var centers = selected.map { points[it].copyOf() }.toMutableList()
        var labels = IntArray(points.size)
        repeat(80) {
            val updated = IntArray(points.size) { row -> centers.indices.minByOrNull { cluster ->
                squaredDistance(points[row], centers[cluster])
            } ?: 0 }
            val nextCenters = MutableList(clusters) { cluster ->
                val members = points.indices.filter { updated[it] == cluster }
                if (members.isEmpty()) centers[cluster].copyOf() else DoubleArray(points.first().size) { column ->
                    members.sumOf { points[it][column] } / members.size
                }
            }
            val stable = updated.contentEquals(labels)
            labels = updated
            centers = nextCenters
            if (stable) return labels
        }
        return labels
    }

    private fun squaredDistance(first: DoubleArray, second: DoubleArray): Double =
        first.indices.sumOf { index -> val difference = first[index] - second[index]; difference * difference }

    private fun selectAnchors(candidates: List<Int>, observations: List<SpeakerObservation>, vectors: List<FloatArray?>): List<Int> {
        if (candidates.size <= maxAnchors) return candidates
        val selected = linkedSetOf<Int>()
        val diversityBudget = minOf(maxAnchors, maxOf(2, maxAnchors / 4), maxSpeakers.coerceAtMost(maxAnchors) * 2)
        val nearest = FloatArray(candidates.size) { -1f }
        var next = candidates.indices.maxBy { observations[candidates[it]].quality }
        while (selected.size < diversityBudget) {
            checkCancelled()
            val anchor = candidates[next]
            selected += anchor
            for (position in candidates.indices) {
                nearest[position] = maxOf(nearest[position], dot(vectors[candidates[position]]!!, vectors[anchor]!!))
            }
            var leastAgreement = threshold
            next = -1
            for (position in candidates.indices) {
                if (candidates[position] in selected) continue
                val score = nearest[position]
                if (score < leastAgreement || (next >= 0 && score == leastAgreement &&
                    observations[candidates[position]].quality > observations[candidates[next]].quality)) {
                    leastAgreement = score
                    next = position
                }
            }
            // The rest already match a selected context at the unchanged discovery cutoff.
            if (next < 0) break
        }
        // Farthest-first work is O(N * diversityBudget * embeddingDimension), without a
        // full-session pair matrix. All candidates retain the ordinary quality/seed gate.
        val buckets = maxAnchors - selected.size
        for (bucket in 0 until buckets) {
            val from = bucket * candidates.size / buckets
            val until = (bucket + 1) * candidates.size / buckets
            candidates.subList(from, until).filter { it !in selected }
                .maxByOrNull { observations[it].quality }?.let { selected += it }
        }
        // A bucket may already be completely represented by diversity anchors.
        for (candidate in candidates) {
            if (selected.size >= maxAnchors) break
            selected += candidate
        }
        return selected.sortedWith(compareBy({ observations[it].startMs }, { observations[it].endMs }, { it }))
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
