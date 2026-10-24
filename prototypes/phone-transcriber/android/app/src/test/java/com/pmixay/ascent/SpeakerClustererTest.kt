package com.pmixay.ascent

import org.junit.Assert.*
import org.junit.Test

class SpeakerClustererTest {
    private fun voice(start: Long, x: Float, y: Float, canSeed: Boolean = true) =
        SpeakerObservation(start, start + 1800, floatArrayOf(x, y), canSeed = canSeed)

    @Test fun returningVoicesStaySeparateAcrossTheWholeConversation() {
        val samples = listOf(voice(0, 1f, 0f), voice(4000, 0f, 1f),
            voice(8000, 0.98f, 0.02f), voice(12000, 0.03f, 0.97f))
        val result = SpeakerClusterer().cluster(samples)
        assertEquals(listOf("speaker_1", "speaker_2", "speaker_1", "speaker_2"), result.assignments.map { it.speakerId })
    }

    @Test fun clusteringDoesNotDependOnTheOrderOfEmbeddingExtraction() {
        val samples = listOf(voice(0, 1f, 0f), voice(4000, 0f, 1f), voice(8000, 0.98f, 0.02f))
        val clusterer = SpeakerClusterer()
        val forward = clusterer.cluster(samples).assignments.map { it.speakerId }
        val reversed = clusterer.cluster(samples.reversed()).assignments.reversed().map { it.speakerId }
        assertEquals(forward, reversed)
    }

    @Test fun shortMixedBoundaryDoesNotInventAThirdSpeakerOrPoisonBothVoices() {
        val samples = listOf(voice(0, 1f, 0f), voice(2000, 1f, 1f), voice(4000, 0f, 1f))
        val result = SpeakerClusterer().cluster(samples)
        assertEquals(listOf("speaker_1", SpeakerClusterer.UNKNOWN, "speaker_2"), result.assignments.map { it.speakerId })
        assertEquals(2, result.signatures.size)
    }

    @Test fun ambiguousBoundaryCannotEnrollOrModifyACentroid() {
        val samples = listOf(voice(0, 1f, 0f), voice(2000, 1f, 1f, canSeed = false), voice(4000, 0f, 1f))
        val result = SpeakerClusterer().cluster(samples)
        assertEquals(SpeakerClusterer.UNKNOWN, result.assignments[1].speakerId)
        assertEquals(1, result.signatures[0].windowCount)
        assertArrayEquals(floatArrayOf(1f, 0f), result.signatures[0].vector, 0.001f)
    }

    @Test fun silentInvalidAndClippedContextsHaveNoIdentity() {
        val samples = listOf(voice(0, 0f, 0f), voice(2000, Float.NaN, 0f),
            voice(4000, 1f, 0f).copy(quality = 0.2f))
        val result = SpeakerClusterer().cluster(samples)
        assertTrue(result.assignments.all { it.speakerId == SpeakerClusterer.UNKNOWN })
        assertTrue(result.signatures.isEmpty())
    }

    @Test fun overlappingContextsDoNotInflateVoiceLearningDuration() {
        val samples = listOf(voice(0, 1f, 0f), voice(900, 1f, 0f), voice(1800, 1f, 0f))
        val signature = SpeakerClusterer().cluster(samples).signatures.single()
        assertEquals(3600L, signature.speechMs)
        assertEquals(3, signature.windowCount)
        assertEquals(1f, signature.cohesion, 0.001f)
    }

    @Test fun enrollmentCountsOnlyActiveSpeechFrames() {
        val sample = voice(0, 1f, 0f).copy(speech = listOf(SpeechSpan(100, 500), SpeechSpan(1000, 1400)))
        assertEquals(800L, SpeakerClusterer().cluster(listOf(sample)).signatures.single().speechMs)
    }

    @Test fun anchorBudgetStillCoversVoicesNearTheEnd() {
        val samples = (0 until 100).map { index -> voice(index * 2000L,
            if (index < 50) 1f else 0f, if (index < 50) 0f else 1f) }
        val result = SpeakerClusterer(maxAnchors = 8).cluster(samples)
        assertEquals("speaker_1", result.assignments.first().speakerId)
        assertEquals("speaker_2", result.assignments.last().speakerId)
        assertEquals(2, result.signatures.size)
    }

    @Test fun exceedingSpeakerBudgetDoesNotForceAnUnrelatedVoiceIntoAnotherSpeaker() {
        val result = SpeakerClusterer(maxSpeakers = 1).cluster(listOf(voice(0, 1f, 0f), voice(4000, 0f, 1f)))
        assertEquals("speaker_1", result.assignments.first().speakerId)
        assertEquals(SpeakerClusterer.UNKNOWN, result.assignments.last().speakerId)
    }
}
