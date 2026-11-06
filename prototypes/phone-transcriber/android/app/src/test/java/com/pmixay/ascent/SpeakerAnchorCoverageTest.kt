package com.pmixay.ascent

import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/** A bounded discovery sample must preserve distinct recurring voices at either turn phase. */
@RunWith(Parameterized::class)
class SpeakerAnchorCoverageTest(private val startingIdentity: Int) {
    @Test fun anchorBudgetRetainsAlternatingParticipantsWithEqualQuality() {
        // Two clear voices alternate in disjoint turns. Equal quality is intentional:
        // whichever voice starts must not win every temporal sampling tie.
        val observations = (0 until 16).map { index ->
            val vector = FloatArray(192).also { it[(index + startingIdentity) % 2] = 1f }
            SpeakerObservation(index * 2000L, index * 2000L + 1800, vector)
        }

        val result = SpeakerClusterer(maxAnchors = 8).cluster(observations)

        assertEquals("Both recurring voices must survive the bounded discovery sample",
            observations.indices.map { if (it % 2 == 0) "speaker_1" else "speaker_2" },
            result.assignments.map { it.speakerId })
        assertEquals(2, result.signatures.size)
    }

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "starting identity {0}")
        fun phases(): Collection<Array<Any>> = listOf(arrayOf<Any>(0), arrayOf<Any>(1))
    }
}
