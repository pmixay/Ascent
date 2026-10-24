package com.pmixay.ascent

import kotlin.math.abs

/** Keep a changing voice eligible; exclude likely mixed windows bridging two different voices. */
internal object SpeakerEvidence {
    fun stableSeeds(observations: List<SpeakerObservation>, regions: List<Int>): List<SpeakerObservation> {
        require(observations.size == regions.size)
        val vectors = observations.map { SpeakerClusterer.normalized(it.vector) }
        return observations.mapIndexed { index, current ->
            if (!current.canSeed || index == 0 || index == observations.lastIndex) return@mapIndexed current
            val left = observations[index - 1]
            val right = observations[index + 1]
            val a = vectors[index - 1]
            val b = vectors[index]
            val c = vectors[index + 1]
            val comparable = left.canSeed && right.canSeed && left.quality >= 0.55f && right.quality >= 0.55f &&
                regions[index - 1] == regions[index] && regions[index + 1] == regions[index] &&
                left.endMs >= current.startMs && right.startMs <= current.endMs &&
                a != null && b != null && c != null && a.size == b.size && b.size == c.size
            if (!comparable) return@mapIndexed current
            val leftMatch = SpeakerClusterer.dot(a!!, b!!)
            val rightMatch = SpeakerClusterer.dot(b, c!!)
            val mixed = SpeakerClusterer.dot(a, c) < 0.60f && leftMatch >= 0.55f && rightMatch >= 0.55f &&
                abs(leftMatch - rightMatch) < 0.10f
            if (mixed) current.copy(canSeed = false) else current
        }
    }
}
