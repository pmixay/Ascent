package com.pmixay.ascent

import kotlin.math.abs

/** Every recognized word has a participant; inferred assignments remain measurable internally. */
internal data class CompletedSpeakerAttribution(val speakerIds: List<String>, val inferred: List<Boolean>)

/**
 * Final best-effort assignment, separate from confidence-gated discovery and voice enrollment.
 * All reference vectors and temporal anchors are frozen before filling any uncertain word.
 * A fallback cannot corroborate another fallback or teach a remembered voice.
 */
internal object SpeakerAttribution {
    private const val DEFAULT_SPEAKER = "speaker_1"

    fun complete(words: List<TimedWord>, windows: List<SpeakerObservation>, clustered: SpeakerClustering): CompletedSpeakerAttribution {
        require(windows.size == clustered.assignments.size)
        if (words.isEmpty()) return CompletedSpeakerAttribution(emptyList(), emptyList())
        val strict = SpeakerSpeechSupport.speakersForWords(words, windows, clustered.assignments)
        val inferred = strict.map { it == SpeakerClusterer.UNKNOWN }
        if (inferred.none { it }) return CompletedSpeakerAttribution(strict, inferred)

        // Very short conversations may have usable embeddings but no strict seed. Reuse the
        // bounded discovery algorithm with an explicit short-evidence policy, never fabricated
        // duration/quality and never an enrollment signature. Every resulting word stays inferred.
        val original = if (clustered.assignments.any { it.speakerId != SpeakerClusterer.UNKNOWN })
            clustered.assignments else SpeakerClusterer().clusterShortEvidence(windows).assignments
        val vectors = windows.map { SpeakerClusterer.normalized(it.vector) }
        val centers = original.indices.filter { original[it].speakerId != SpeakerClusterer.UNKNOWN &&
            vectors[it] != null && windows[it].quality.isFinite() && windows[it].quality >= .20f }
            .groupBy { original[it].speakerId }.mapNotNull { (id, indices) ->
                val dimension = vectors[indices.first()]!!.size
                val sum = FloatArray(dimension)
                for (index in indices) vectors[index]?.takeIf { it.size == dimension }?.let { vector ->
                    for (component in sum.indices) sum[component] += vector[component]
                }
                SpeakerClusterer.normalized(sum)?.let { id to it }
            }.toMap()
        try {
            val resolved = original.mapIndexed { index, assignment ->
                if (assignment.speakerId != SpeakerClusterer.UNKNOWN) assignment else {
                    val vector = vectors[index]
                    val best = if (vector == null || !windows[index].quality.isFinite() || windows[index].quality < .20f) null
                        else centers.entries.filter { it.value.size == vector.size }
                            .map { it.key to SpeakerClusterer.dot(vector, it.value) }
                            .sortedWith(compareByDescending<Pair<String, Float>> { it.second }.thenBy { it.first }).firstOrNull()
                    // The small positive floor only permits a local vote. It is not a confidence
                    // claim: the word's inferred flag remains true and this score is never trained.
                    best?.let { SpeakerAssignment(it.first, it.second.coerceAtLeast(.01f)) } ?: assignment
                }
            }
            val knownWords = words.indices.filter { strict[it] != SpeakerClusterer.UNKNOWN }
                .map { Anchor(midpoint(words[it].startMs, words[it].endMs), strict[it]) }
            val anchors = NearestAnchor(knownWords.ifEmpty {
                original.indices.filter { original[it].speakerId != SpeakerClusterer.UNKNOWN }
                    .map { Anchor(midpoint(windows[it].startMs, windows[it].endMs), original[it].speakerId) }
            })
            val completed = words.mapIndexed { index, word ->
                if (!inferred[index]) strict[index] else {
                    val nearest = anchors.speaker(midpoint(word.startMs, word.endMs))
                    val candidates = SpeakerSpeechSupport.candidatesForWord(word, windows, resolved)
                    val best = candidates.firstOrNull()
                    if (best == null) nearest ?: DEFAULT_SPEAKER else {
                        val tied = candidates.takeWhile { abs(it.second - best.second) <= 1e-8 }
                        nearest?.takeIf { candidate -> tied.any { it.first == candidate } }
                            ?: tied.minBy { it.first }.first
                    }
                }
            }
            check(completed.none { it.isBlank() || it == SpeakerClusterer.UNKNOWN })
            return CompletedSpeakerAttribution(completed, inferred)
        } finally {
            vectors.forEach { it?.fill(0f) }
            centers.values.forEach { it.fill(0f) }
        }
    }

    /** Old text-only history has no retained audio; use nearest original attributed phrase. */
    fun completeStored(segments: List<TranscriptSegment>): List<TranscriptSegment> {
        if (segments.none { it.speakerId.isBlank() || it.speakerId == SpeakerClusterer.UNKNOWN }) return segments
        val assigned = segments.filter { it.speakerId.isNotBlank() && it.speakerId != SpeakerClusterer.UNKNOWN }
        val anchors = NearestAnchor(assigned.filter { !it.speakerInferred }
            .map { Anchor(midpoint(it.startMs, it.endMs), it.speakerId) })
        // Without any original attribution, preserve a stable existing anonymous identity
        // rather than using earlier guesses as temporal evidence or inventing another person.
        val defaultSpeaker = assigned.firstOrNull()?.speakerId ?: DEFAULT_SPEAKER
        return segments.map { segment ->
            if (segment.speakerId.isNotBlank() && segment.speakerId != SpeakerClusterer.UNKNOWN) segment else
                segment.copy(speakerId = anchors.speaker(midpoint(segment.startMs, segment.endMs)) ?: defaultSpeaker,
                    speakerInferred = true)
        }
    }

    private data class Anchor(val atMs: Long, val speaker: String)

    /** Binary search keeps migration of a long text history bounded; ties prefer earlier evidence. */
    private class NearestAnchor(source: List<Anchor>) {
        private val anchors = source.sortedWith(compareBy<Anchor> { it.atMs }.thenBy { it.speaker })

        fun speaker(atMs: Long): String? {
            if (anchors.isEmpty()) return null
            var low = 0
            var high = anchors.size
            while (low < high) {
                val middle = (low + high) ushr 1
                if (anchors[middle].atMs < atMs) low = middle + 1 else high = middle
            }
            val after = anchors.getOrNull(low)
            val before = anchors.getOrNull(low - 1)
            return if (before != null && (after == null || atMs - before.atMs <= after.atMs - atMs))
                before.speaker else after!!.speaker
        }
    }

    private fun midpoint(startMs: Long, endMs: Long) = startMs + (endMs - startMs) / 2
}
