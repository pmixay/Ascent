package com.pmixay.ascent

import org.junit.Assert.*
import org.junit.Test

class SpeakerEvidenceTest {
    private fun windows(vararg vectors: FloatArray) = vectors.mapIndexed { index, vector ->
        SpeakerObservation(index * 900L, index * 900L + 1800, vector)
    }

    @Test fun varyingSingleVoiceDoesNotLoseItsTrainingWindows() {
        val observations = windows(floatArrayOf(1f, 0f), floatArrayOf(.9f, .4f), floatArrayOf(.65f, .76f))
        assertTrue(SpeakerEvidence.stableSeeds(observations, listOf(0, 0, 0)).all { it.canSeed })
    }

    @Test fun clearSideOfSpeakerChangeRemainsEligible() {
        val observations = windows(floatArrayOf(1f, 0f), floatArrayOf(.98f, .2f), floatArrayOf(0f, 1f))
        assertTrue(SpeakerEvidence.stableSeeds(observations, listOf(0, 0, 0))[1].canSeed)
    }

    @Test fun mixedWindowCanVoteButCannotTeachEitherVoice() {
        val observations = windows(floatArrayOf(1f, 0f), floatArrayOf(1f, 1f), floatArrayOf(0f, 1f))
        val result = SpeakerEvidence.stableSeeds(observations, listOf(0, 0, 0))
        assertFalse(result[1].canSeed)
        assertTrue(result[0].canSeed)
        assertTrue(result[2].canSeed)
        assertSame(observations[1].vector, result[1].vector)
    }

    @Test fun genuinelyDistinctThirdVoiceIsNotAssumedToBeAMixture() {
        val observations = windows(floatArrayOf(1f, 0f, 0f), floatArrayOf(0f, 0f, 1f), floatArrayOf(0f, 1f, 0f))
        assertTrue(SpeakerEvidence.stableSeeds(observations, listOf(0, 0, 0))[1].canSeed)
    }

    @Test fun unrelatedSpeechRegionsDoNotVetoEachOther() {
        val observations = windows(floatArrayOf(1f, 0f), floatArrayOf(1f, 1f), floatArrayOf(0f, 1f))
        assertTrue(SpeakerEvidence.stableSeeds(observations, listOf(0, 1, 2))[1].canSeed)
    }
}
