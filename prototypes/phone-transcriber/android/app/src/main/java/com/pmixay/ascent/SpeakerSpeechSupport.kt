package com.pmixay.ascent

import kotlin.math.abs

internal data class SpeakerContext(val startMs: Long, val endMs: Long, val region: Int)
internal data class SpeechActivity(val spans: List<SpeechSpan>, val quality: Float)

/** ASR-supported speech boundaries with an energy quality gate; not neural VAD or overlap separation. */
internal object SpeakerSpeechSupport {
    fun contexts(words: List<TimedWord>, durationMs: Long): List<SpeakerContext> {
        if (durationMs <= 0) return emptyList()
        val regions = mutableListOf<SpeechSpan>()
        for (word in words.sortedBy { it.startMs }) {
            val start = word.startMs.coerceIn(0, durationMs)
            val end = word.endMs.coerceIn(start, durationMs)
            if (start == end) continue
            val previous = regions.lastOrNull()
            // CTC token emission peaks are not complete speech boundaries. A short gap
            // between them must not discard the surrounding acoustic context.
            if (previous != null && start - previous.endMs <= 500) {
                regions[regions.lastIndex] = previous.copy(endMs = maxOf(previous.endMs, end))
            } else regions += SpeechSpan(start, end)
        }
        val result = mutableListOf<SpeakerContext>()
        for ((index, region) in regions.withIndex()) {
            // Expansion is bounded by the midpoint of a real ASR gap and by 600ms
            // from an emission. It cannot reach the next isolated utterance.
            val lower = maxOf(0, region.startMs - 600,
                regions.getOrNull(index - 1)?.let { it.endMs + (region.startMs - it.endMs) / 2 } ?: 0)
            val upper = minOf(durationMs, region.endMs + 600,
                regions.getOrNull(index + 1)?.let { region.endMs + (it.startMs - region.endMs) / 2 } ?: durationMs)
            var start = maxOf(lower, region.startMs - 240)
            var end = minOf(upper, region.endMs + 240)
            if (end - start < 1200) {
                val target = minOf(1200, upper - lower)
                val center = region.startMs + (region.endMs - region.startMs) / 2
                start = (center - target / 2).coerceIn(lower, upper - target)
                end = start + target
            }
            // Short evidence can match an established voice without becoming a seed.
            if (end - start < 320) continue
            if (end - start <= 1800) result += SpeakerContext(start, end, index)
            else {
                var position = start
                while (position + 1800 < end) {
                    result += SpeakerContext(position, position + 1800, index)
                    position += 900
                }
                val finalStart = end - 1800
                if (result.lastOrNull()?.startMs != finalStart) result += SpeakerContext(finalStart, end, index)
            }
        }
        return result
    }

    fun activity(samples: FloatArray, startMs: Long, sampleRate: Int = 16000): SpeechActivity {
        if (samples.isEmpty() || samples.any { !it.isFinite() }) return SpeechActivity(emptyList(), 0f)
        val frameSize = sampleRate / 50
        val energies = (samples.indices step frameSize).map { from ->
            val end = (from + frameSize).coerceAtMost(samples.size)
            var total = 0.0
            for (i in from until end) total += samples[i].toDouble() * samples[i]
            total / (end - from)
        }
        val sorted = energies.sorted()
        val floor = sorted[(sorted.size - 1) / 5]
        val peak = sorted[(sorted.size - 1) * 9 / 10]
        if (peak < 1e-8) return SpeechActivity(emptyList(), 0f)
        val threshold = maxOf(1e-8, minOf(floor * 3, peak * 0.20))
        val active = energies.indices.filter { energies[it] >= threshold }
        val clipped = samples.count { abs(it) >= 0.999f }.toFloat() / samples.size
        val quality = (active.size.toFloat() / energies.size * (1f - clipped * 5)).coerceIn(0f, 1f)
        if (clipped >= 0.10f) return SpeechActivity(emptyList(), 0f)
        val spans = active.map { frame ->
            SpeechSpan(startMs + frame * frameSize * 1000L / sampleRate,
                startMs + minOf((frame + 1) * frameSize, samples.size) * 1000L / sampleRate)
        }
        return SpeechActivity(spans, quality)
    }

    /** Use local acoustic evidence; do not borrow a voice from the other side of a pause. */
    fun speakerForWord(word: TimedWord, windows: List<SpeakerObservation>, labels: List<SpeakerAssignment>): String {
        return voteForWord(word, windows, labels).certainSpeaker()
    }

    /**
     * Repair a brief confidence dip only when that same voice already leads locally
     * and clear speech on both sides agrees. No extrapolation, empty-evidence filling,
     * tied-voice filling, or replacement of another identified speaker is allowed.
     */
    fun speakersForWords(words: List<TimedWord>, windows: List<SpeakerObservation>, labels: List<SpeakerAssignment>): List<String> {
        require(windows.size == labels.size)
        val votes = words.map { voteForWord(it, windows, labels) }
        val result = votes.map { it.certainSpeaker() }.toMutableList()
        var index = 0
        while (index < words.size) {
            if (result[index] != SpeakerClusterer.UNKNOWN) { index++; continue }
            val first = index
            while (index < words.size && result[index] == SpeakerClusterer.UNKNOWN) index++
            val after = index
            if (first == 0 || after == words.size || words[after - 1].endMs - words[first].startMs > 800) continue
            val speaker = result[first - 1]
            if (speaker != result[after] || votes[first - 1].margin < 0.35 || votes[after].margin < 0.35) continue
            if ((first..after).any { words[it].startMs - words[it - 1].endMs > 500 }) continue
            if ((first until after).all { votes[it].speaker == speaker && votes[it].margin >= 0.05 }) {
                for (position in first until after) result[position] = speaker
            }
        }
        return result
    }

    private data class WordVote(val speaker: String, val margin: Double) {
        fun certainSpeaker(): String = if (margin >= 0.20) speaker else SpeakerClusterer.UNKNOWN
    }

    private fun voteForWord(word: TimedWord, windows: List<SpeakerObservation>, labels: List<SpeakerAssignment>): WordVote {
        require(windows.size == labels.size)
        val midpoint = (word.startMs + word.endMs) / 2
        val votes = mutableMapOf<String, Double>()
        windows.forEachIndexed { index, window ->
            val assignment = labels[index]
            if (assignment.speakerId == SpeakerClusterer.UNKNOWN || midpoint !in window.startMs..window.endMs) return@forEachIndexed
            if (!assignment.similarity.isFinite() || assignment.similarity <= 0 || !window.quality.isFinite() || window.quality <= 0) return@forEachIndexed
            val center = (window.startMs + window.endMs) / 2.0
            val radius = (window.endMs - window.startMs) / 2.0
            val proximity = (1.0 - abs(midpoint - center) / maxOf(radius, 1.0)).coerceAtLeast(0.05)
            val vote = proximity * assignment.similarity * window.quality
            // Overlapping windows share samples; duplicates and the end-anchored
            // window are not independent votes and must not overpower a closer voice.
            votes[assignment.speakerId] = maxOf(votes[assignment.speakerId] ?: 0.0, vote)
        }
        val ranked = votes.entries.sortedByDescending { it.value }
        val best = ranked.firstOrNull() ?: return WordVote(SpeakerClusterer.UNKNOWN, 0.0)
        val runner = ranked.getOrNull(1)?.value ?: 0.0
        return WordVote(best.key, if (best.value > 0) (best.value - runner) / best.value else 0.0)
    }
}
