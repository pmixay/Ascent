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
            if (previous != null && start - previous.endMs <= 300) {
                regions[regions.lastIndex] = previous.copy(endMs = maxOf(previous.endMs, end))
            } else regions += SpeechSpan(start, end)
        }
        val result = mutableListOf<SpeakerContext>()
        for ((index, region) in regions.withIndex()) {
            val start = (region.startMs - 80).coerceAtLeast(0)
            val end = (region.endMs + 80).coerceAtMost(durationMs)
            if (end - start < 640) continue
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

    /** Blend all covering contexts; do not borrow a voice from the other side of a pause. */
    fun speakerForWord(word: TimedWord, windows: List<SpeakerObservation>, labels: List<SpeakerAssignment>): String {
        require(windows.size == labels.size)
        val midpoint = (word.startMs + word.endMs) / 2
        val votes = mutableMapOf<String, Double>()
        windows.forEachIndexed { index, window ->
            val assignment = labels[index]
            if (assignment.speakerId == SpeakerClusterer.UNKNOWN || midpoint !in window.startMs..window.endMs) return@forEachIndexed
            val center = (window.startMs + window.endMs) / 2.0
            val radius = (window.endMs - window.startMs) / 2.0
            val proximity = (1.0 - abs(midpoint - center) / maxOf(radius, 1.0)).coerceAtLeast(0.05)
            val vote = proximity * assignment.similarity * window.quality
            votes[assignment.speakerId] = (votes[assignment.speakerId] ?: 0.0) + vote
        }
        val ranked = votes.entries.sortedByDescending { it.value }
        val best = ranked.firstOrNull() ?: return SpeakerClusterer.UNKNOWN
        val runner = ranked.getOrNull(1)?.value ?: 0.0
        return if (best.value <= 0 || (best.value - runner) / best.value < 0.20) SpeakerClusterer.UNKNOWN else best.key
    }
}
