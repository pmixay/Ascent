package com.pmixay.ascent

import kotlin.math.sqrt
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/** More independent observations must not erase an already distinguishable participant. */
@RunWith(Parameterized::class)
class SpeakerMergeEvidenceTest(private val observationsPerSpeaker: Int) {
    @Test fun additionalIndependentSpeechKeepsEstablishedVoicesSeparate() {
        // Keep the same identity directions and per-window noise distribution as the
        // established similar-voices regression; vary only the amount of evidence.
        // Every turn is disjoint, and its nuisance direction occurs only once.
        val observations = (0 until observationsPerSpeaker * 2).map { index ->
            val vector = FloatArray(192)
            if (index % 2 == 0) vector[0] = .85f else {
                vector[0] = .85f * .72f
                vector[1] = .85f * sqrt(1f - .72f * .72f)
            }
            vector[index + 2] = sqrt(1f - .85f * .85f)
            SpeakerObservation(index * 3000L, index * 3000L + 1800, vector)
        }

        val result = SpeakerClusterer().cluster(observations)

        assertEquals("Independent turns must retain both participants",
            observations.indices.map { if (it % 2 == 0) "speaker_1" else "speaker_2" },
            result.assignments.map { it.speakerId })
        assertEquals("Independent speech must retain separate voice templates",
            2, result.signatures.size)
    }

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0} independent turns per speaker")
        fun evidenceSizes(): Collection<Array<Any>> = listOf(arrayOf<Any>(4), arrayOf<Any>(16))
    }
}
