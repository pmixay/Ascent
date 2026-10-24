package com.pmixay.ascent

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.sqrt

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

    @Test fun aThirdVoiceIsNotDiscardedForResemblingTwoDistantEarlierSpeakers() {
        val result = SpeakerClusterer().cluster(listOf(
            voice(0, 1f, 0f), voice(4000, 0f, 1f), voice(10000, 1f, 1f),
        ))
        assertEquals(listOf("speaker_1", "speaker_2", "speaker_3"), result.assignments.map { it.speakerId })
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

    @Test fun phoneticVariantsOfOneVoiceConsolidateAfterCentroidDenoising() {
        // 192-dimensional unit embeddings: stable identity, two phonetic directions and
        // independent per-window noise. Cross-fragment cosine is 0.7169, despite matching
        // identity; normalized fragment centroids exceed 0.8 after noise averaging.
        val samples = (0 until 8).map { index ->
            val vector = FloatArray(192)
            vector[0] = 0.87f
            vector[1] = if (index < 4) 0.20f else -0.20f
            vector[index + 2] = sqrt(1f - 0.87f * 0.87f - 0.20f * 0.20f)
            SpeakerObservation(index * 2000L, index * 2000L + 1800, vector)
        }
        val unrelated = FloatArray(192).also { it[100] = 1f }
        val result = SpeakerClusterer().cluster(samples + listOf(
            SpeakerObservation(18000, 19800, unrelated), SpeakerObservation(21000, 22800, unrelated),
        ))
        assertEquals(List(8) { "speaker_1" }, result.assignments.take(8).map { it.speakerId })
        assertEquals(listOf("speaker_2", "speaker_2"), result.assignments.takeLast(2).map { it.speakerId })
        assertEquals(2, result.signatures.size)
    }

    @Test fun isolatedVoiceExcursionDoesNotCreateAnotherPersonOrTeachItsVoiceprint() {
        val result = SpeakerClusterer().cluster(listOf(
            voice(0, 1f, 0f), voice(1800, 1f, 0f),
            voice(3600, 0.65f, sqrt(1f - 0.65f * 0.65f)).copy(quality = 0.6f),
            voice(5400, 1f, 0f), voice(7200, 1f, 0f),
        ))
        assertEquals(List(5) { "speaker_1" }, result.assignments.map { it.speakerId })
        assertEquals(4, result.signatures.single().windowCount)
        assertArrayEquals(floatArrayOf(1f, 0f), result.signatures.single().vector, 0.001f)
    }

    @Test fun aGenuinelyDifferentBriefVoiceIsPreservedInsideALongerTurn() {
        val result = SpeakerClusterer().cluster(listOf(
            voice(0, 1f, 0f), voice(1800, 1f, 0f), voice(3600, 0.4f, sqrt(0.84f)),
            voice(5400, 1f, 0f), voice(7200, 1f, 0f),
        ))
        assertEquals(listOf("speaker_1", "speaker_1", "speaker_2", "speaker_1", "speaker_1"),
            result.assignments.map { it.speakerId })
    }

    @Test fun aClearBriefVoiceIsNotAbsorbedEvenWhenItResemblesTheSurroundingVoice() {
        val result = SpeakerClusterer().cluster(listOf(
            voice(0, 1f, 0f), voice(1800, 1f, 0f),
            voice(3600, 0.65f, sqrt(1f - 0.65f * 0.65f)),
            voice(5400, 1f, 0f), voice(7200, 1f, 0f),
        ))
        assertEquals("speaker_2", result.assignments[2].speakerId)
    }

    @Test fun aOneSidedVoiceChangeIsNotAbsorbedAsAnIsolatedFragment() {
        val result = SpeakerClusterer().cluster(listOf(
            voice(0, 1f, 0f), voice(1800, 1f, 0f), voice(3600, 1f, 0f),
            voice(5400, 0.65f, sqrt(1f - 0.65f * 0.65f)),
        ))
        assertEquals("speaker_2", result.assignments.last().speakerId)
    }

    @Test fun briefReplyCanMatchWithoutBecomingAnAnchorOrTrainingSample() {
        val reply = SpeakerObservation(5000, 6200, floatArrayOf(0.98f, 0.02f),
            quality = 0.27f, canSeed = false, speech = listOf(SpeechSpan(5300, 5640)))
        val result = SpeakerClusterer().cluster(listOf(voice(0, 1f, 0f), voice(2000, 0f, 1f), reply))
        assertEquals("speaker_1", result.assignments.last().speakerId)
        assertEquals(1, result.signatures.first().windowCount)
        assertEquals(1800L, result.signatures.first().speechMs)
    }

    @Test fun insufficientSpeechCannotAcquireIdentityEvenWithAPerfectEmbeddingMatch() {
        val fragment = SpeakerObservation(5000, 6200, floatArrayOf(1f, 0f),
            quality = 0.27f, canSeed = false, speech = listOf(SpeechSpan(5300, 5500)))
        val result = SpeakerClusterer().cluster(listOf(voice(0, 1f, 0f), fragment))
        assertEquals(SpeakerClusterer.UNKNOWN, result.assignments.last().speakerId)
    }

    @Test fun weakNonSeedEvidenceCanRecoverOnlyBetweenMatchingNeighbors() {
        val first = voice(0, 1f, 0f).copy(vector = floatArrayOf(1f, 0f, 0f))
        val weak = voice(1800, 0.65f, 0.65f, canSeed = false)
            .copy(vector = floatArrayOf(0.65f, 0.65f, sqrt(1f - 2f * 0.65f * 0.65f)))
        val returning = voice(3600, 1f, 0f).copy(vector = floatArrayOf(1f, 0f, 0f))
        val supported = SpeakerClusterer().cluster(listOf(first, weak, returning))
        assertEquals("speaker_1", supported.assignments[1].speakerId)
        assertEquals(2, supported.signatures.single().windowCount)
        val boundary = SpeakerClusterer().cluster(listOf(first, weak,
            returning.copy(vector = floatArrayOf(0f, 1f, 0f))))
        // Equal acoustic evidence for two different neighbors must not be rescued by continuity.
        assertEquals(SpeakerClusterer.UNKNOWN, boundary.assignments[1].speakerId)
    }

    @Test fun continuityRecoveryNeverJumpsAcrossASilentGap() {
        val weak = voice(5000, 0.65f, sqrt(1f - 0.65f * 0.65f), canSeed = false)
        val result = SpeakerClusterer().cluster(listOf(voice(0, 1f, 0f), weak, voice(10000, 1f, 0f)))
        assertEquals(SpeakerClusterer.UNKNOWN, result.assignments[1].speakerId)
    }
}
