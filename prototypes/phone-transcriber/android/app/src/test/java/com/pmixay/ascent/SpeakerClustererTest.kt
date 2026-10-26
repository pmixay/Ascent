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

    @Test fun defaultOperatingPointSeparatesVoicesDespiteRealisticWithinVoiceVariation() {
        // Same-voice cosine 0.64, different-voice cosine 0.28: representative of the broad
        // separation measured by CI with this exact ERes2NetV2 checkpoint, not ideal orthogonality.
        val observations = (0 until 8).map { index ->
            val vector = FloatArray(192)
            if (index % 2 == 0) vector[0] = 0.8f else {
                vector[0] = 0.35f
                vector[1] = sqrt(0.64f - 0.35f * 0.35f)
            }
            vector[index + 2] = 0.6f
            SpeakerObservation(index * 3000L, index * 3000L + 1800, vector)
        }
        val result = SpeakerClusterer().cluster(observations)
        assertEquals((0 until 8).map { if (it % 2 == 0) "speaker_1" else "speaker_2" },
            result.assignments.map { it.speakerId })
    }

    @Test fun aWeakAcousticMatchNeedsMoreThanPassingTheDiscoveryCutoff() {
        val weak = voice(5000, 0.57f, sqrt(1f - 0.57f * 0.57f), canSeed = false)
        val result = SpeakerClusterer().cluster(listOf(voice(0, 1f, 0f), weak))
        assertEquals(SpeakerClusterer.UNKNOWN, result.assignments.last().speakerId)
    }

    @Test fun clusteringDoesNotDependOnTheOrderOfEmbeddingExtraction() {
        val samples = listOf(voice(0, 1f, 0f), voice(4000, 0f, 1f), voice(8000, 0.98f, 0.02f))
        val clusterer = SpeakerClusterer()
        val forward = clusterer.cluster(samples).assignments.map { it.speakerId }
        val reversed = clusterer.cluster(samples.reversed()).assignments.reversed().map { it.speakerId }
        assertEquals(forward, reversed)
    }

    @Test fun shortMixedBoundaryDoesNotInventAThirdSpeakerOrPoisonBothVoices() {
        val samples = listOf(voice(0, 1f, 0f), voice(900, 1f, 1f), voice(1800, 0f, 1f))
        val result = SpeakerClusterer().cluster(samples)
        assertEquals(listOf("speaker_1", SpeakerClusterer.UNKNOWN, "speaker_2"), result.assignments.map { it.speakerId })
        assertEquals(2, result.signatures.size)
    }

    @Test fun separateShortUtteranceBetweenTwoOthersIsNotAssumedToBeTheirMixture() {
        val observations = listOf(
            voice(0, 1f, 0f).copy(vector = floatArrayOf(1f, 0f, 0f)),
            voice(2000, 0.5f, 0.5f).copy(vector = floatArrayOf(0.5f, 0.5f, sqrt(0.5f))),
            voice(4000, 0f, 1f).copy(vector = floatArrayOf(0f, 1f, 0f)),
        )
        assertEquals(listOf("speaker_1", "speaker_2", "speaker_3"),
            SpeakerClusterer().cluster(observations).assignments.map { it.speakerId })
    }

    @Test fun aThirdVoiceIsNotDiscardedForResemblingTwoDistantEarlierSpeakers() {
        // This synthetic geometry intentionally tests the temporal rejection rule with a
        // stricter discovery cutoff; 0.707 is same-voice-compatible for real ERes2NetV2.
        val result = SpeakerClusterer(threshold = 0.72f).cluster(listOf(
            voice(0, 1f, 0f), voice(4000, 0f, 1f), voice(10000, 1f, 1f),
        ))
        assertEquals(listOf("speaker_1", "speaker_2", "speaker_3"), result.assignments.map { it.speakerId })
    }

    @Test fun separateTurnsCannotBeRejectedAsAnOverlappingVoiceMixture() {
        val result = SpeakerClusterer(threshold = 0.72f).cluster(listOf(
            voice(0, 1f, 0f), voice(2000, 1f, 1f), voice(4000, 0f, 1f),
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
        val result = SpeakerClusterer(threshold = 0.72f).cluster(samples + listOf(
            SpeakerObservation(18000, 19800, unrelated), SpeakerObservation(21000, 22800, unrelated),
        ))
        assertEquals(List(8) { "speaker_1" }, result.assignments.take(8).map { it.speakerId })
        assertEquals(listOf("speaker_2", "speaker_2"), result.assignments.takeLast(2).map { it.speakerId })
        assertEquals(2, result.signatures.size)
    }

    @Test fun clearVoiceFragmentsCanConsolidateWithoutLoweringRawDiscovery() {
        // Within-fragment cosine .60, cross-fragment .50: raw discovery should form
        // two groups. Their normalized centroids agree at .50 / .70 = .714, exposing
        // the overly strict .75 centroid cutoff without relying on low-quality repair.
        val observations = (0 until 8).map { index ->
            val vector = FloatArray(192)
            vector[0] = sqrt(.55f)
            vector[1] = if (index < 4) sqrt(.05f) else -sqrt(.05f)
            vector[index + 2] = sqrt(.40f)
            SpeakerObservation(index * 2000L, index * 2000L + 1800, vector)
        }
        val other = FloatArray(192).also { it[100] = 1f }
        val samples = observations + listOf(SpeakerObservation(18000, 19800, other),
            SpeakerObservation(21000, 22800, other))
        val previous = SpeakerClusterer(centroidThreshold = .75f).cluster(samples)
        assertEquals(3, previous.assignments.map { it.speakerId }.distinct().size)
        val result = SpeakerClusterer().cluster(samples)
        assertEquals(List(8) { "speaker_1" }, result.assignments.take(8).map { it.speakerId })
        assertEquals(List(2) { "speaker_2" }, result.assignments.takeLast(2).map { it.speakerId })
        // A useful conversation label is still not sufficient evidence for enrollment.
        assertTrue(result.signatures.none { it.speakerId == "speaker_1" })
    }

    @Test fun similarDistinctEstablishedVoicesRemainSeparateAfterCentroidConsolidation() {
        // Distinct identity directions with cosine .72 plus independent per-window
        // variation. Raw across-voice cosine .5202 is below discovery, and centroid
        // cosine .657 must not merge the two well-supported voices.
        val observations = (0 until 8).map { index ->
            val vector = FloatArray(192)
            if (index % 2 == 0) vector[0] = .85f else {
                vector[0] = .85f * .72f
                vector[1] = .85f * sqrt(1f - .72f * .72f)
            }
            vector[index + 2] = sqrt(1f - .85f * .85f)
            SpeakerObservation(index * 3000L, index * 3000L + 1800, vector)
        }
        val result = SpeakerClusterer().cluster(observations)
        assertEquals((0 until 8).map { if (it % 2 == 0) "speaker_1" else "speaker_2" },
            result.assignments.map { it.speakerId })
        assertEquals(2, result.signatures.size)
    }

    private fun broadVoiceSamples(overlappingSupport: Boolean = false): List<SpeakerObservation> {
        // A and B are compatible modes (cosine .80). A brief F matches B at .75,
        // but A at only .35. Repeated A windows dominate the global centroid, so
        // ordinary AHC and centroid consolidation both leave F as a false person.
        val modeA = floatArrayOf(1f, 0f, 0f)
        val modeB = floatArrayOf(.8f, .6f, 0f)
        val secondStart = if (overlappingSupport) 900L else 1800L
        val mainStart = secondStart + 1800
        return listOf(SpeakerObservation(0, 1800, modeB),
            SpeakerObservation(secondStart, secondStart + 1800, modeB)) +
            (0 until 6).map { SpeakerObservation(mainStart + it * 1800L,
                mainStart + (it + 1) * 1800L, modeA) }
    }

    private fun returningMode(start: Long): SpeakerObservation {
        val y = (.75f - .35f * .8f) / .6f
        return SpeakerObservation(start, start + 1800, floatArrayOf(.35f, y,
            sqrt(1f - .35f * .35f - y * y)))
    }

    @Test fun independentVoiceModesRecoverABriefFragmentWithoutTeachingIt() {
        val samples = broadVoiceSamples() + returningMode(14400)
        val result = SpeakerClusterer().cluster(samples)
        assertEquals(List(9) { "speaker_1" }, result.assignments.map { it.speakerId })
        assertEquals(.75f, result.assignments.last().similarity, .001f)
        assertEquals(8, result.signatures.single().windowCount)
        assertEquals(14400L, result.signatures.single().speechMs)
        assertEquals(0f, result.signatures.single().vector[2], .001f)
    }

    @Test fun overlappingCopiesCannotCorroborateAnOtherwiseUnmatchedFragment() {
        val samples = broadVoiceSamples(overlappingSupport = true) + returningMode(13500)
        val result = SpeakerClusterer().cluster(samples)
        assertEquals("speaker_2", result.assignments.last().speakerId)
        assertEquals(2, result.assignments.map { it.speakerId }.distinct().size)
    }

    @Test fun independentlyCorroboratedModesKeepOneIdentityAcrossSeparateTurns() {
        val samples = broadVoiceSamples() + listOf(returningMode(14400),
            SpeakerObservation(16200, 18000, floatArrayOf(1f, 0f, 0f)), returningMode(18000))
        val result = SpeakerClusterer().cluster(samples)
        assertEquals(List(11) { "speaker_1" }, result.assignments.map { it.speakerId })
        assertEquals(9, result.signatures.single().windowCount)
        assertEquals(16200L, result.signatures.single().speechMs)
    }

    @Test fun everyFragmentWindowNeedsOriginalIndependentSupport() {
        // F and G agree strongly with each other. Only F has two original matches;
        // allowing F to corroborate G would recursively invent a broader identity.
        val unsupported = floatArrayOf(.2f, .73f, sqrt(1f - .2f * .2f - .73f * .73f))
        val samples = broadVoiceSamples() + listOf(returningMode(14400),
            SpeakerObservation(15300, 17100, unsupported))
        val result = SpeakerClusterer().cluster(samples)
        assertEquals(listOf("speaker_2", "speaker_2"), result.assignments.takeLast(2).map { it.speakerId })
    }

    @Test fun competingIndependentVoiceModesKeepAThirdFragmentSeparate() {
        val b = floatArrayOf(.75f, sqrt(1f - .75f * .75f), 0f, 0f, 0f)
        val aY = (.8f - .35f * .75f) / b[1]
        val a = floatArrayOf(.35f, aY, sqrt(1f - .35f * .35f - aY * aY), 0f, 0f)
        val d = floatArrayOf(.75f, 0f, 0f, b[1], 0f)
        val e = floatArrayOf(.35f, 0f, 0f, aY, a[2])
        val vectors = listOf(b, b) + List(6) { a } + listOf(d, d) + List(6) { e } +
            listOf(floatArrayOf(1f, 0f, 0f, 0f, 0f))
        val samples = vectors.mapIndexed { index, vector ->
            SpeakerObservation(index * 1800L, (index + 1) * 1800L, vector)
        }
        val result = SpeakerClusterer().cluster(samples)
        assertEquals(List(8) { "speaker_1" } + List(8) { "speaker_2" } + "speaker_3",
            result.assignments.map { it.speakerId })
    }

    @Test fun aShortSelfConfirmingComponentUsesIndependentEstablishedVoiceEvidence() {
        val observations = (0 until 4).map { index ->
            val vector = FloatArray(192).also { it[0] = .8f; it[index + 1] = .6f }
            SpeakerObservation(index * 2000L, index * 2000L + 1800, vector)
        }
        val fragment = FloatArray(192).also { it[0] = .67f; it[20] = sqrt(1f - .67f * .67f) }
        // Raw cross-similarity .536 misses discovery; the independent denoised centroid
        // matches at .627. Two copies of this short excerpt must not self-confirm a person.
        val samples = observations + listOf(SpeakerObservation(9000, 9900, fragment),
            SpeakerObservation(13000, 13900, fragment))
        val result = SpeakerClusterer().cluster(samples)
        assertEquals(List(6) { "speaker_1" }, result.assignments.map { it.speakerId })
        assertEquals(4, result.signatures.single().windowCount)
        assertEquals(7200L, result.signatures.single().speechMs)
        assertEquals(0f, result.signatures.single().vector[20], .001f)
    }

    @Test fun originalStrictSpeechCanEstablishAParentWithOnlyTwoIndependentSeeds() {
        val a = floatArrayOf(.75f, sqrt(1f - .75f * .75f), 0f, 0f)
        val b = floatArrayOf(.75f, 0f, a[1], 0f)
        val center = SpeakerClusterer.normalized(FloatArray(4) { a[it] + b[it] })!!
        val seeds = listOf(SpeakerObservation(0, 1800, a, quality = .6f,
            speech = listOf(SpeechSpan(0, 1060))),
            SpeakerObservation(2000, 3800, b, quality = .6f, speech = listOf(SpeechSpan(2000, 3060))))
        val corroboration = (0 until 3).map { index ->
            val start = 4000 + index * 2000L
            SpeakerObservation(start, start + 1800, center, quality = .44f, canSeed = false,
                speech = listOf(SpeechSpan(start, start + 800)))
        }
        val fragment = floatArrayOf(.72f, 0f, 0f, sqrt(1f - .72f * .72f))
        val samples = seeds + corroboration + listOf(
            SpeakerObservation(11000, 12200, fragment, quality = .75f,
                speech = listOf(SpeechSpan(11000, 11900))),
            SpeakerObservation(14000, 15200, fragment, quality = .75f,
                speech = listOf(SpeechSpan(14000, 14900))))
        val result = SpeakerClusterer().cluster(samples)
        assertEquals(List(7) { "speaker_1" }, result.assignments.map { it.speakerId })
        assertEquals(2, result.signatures.single().windowCount)
        assertEquals(2120L, result.signatures.single().speechMs)
    }

    @Test fun aSmallComponentCannotChooseBetweenSimilarEstablishedVoices() {
        val observations = (0 until 8).map { index ->
            val vector = FloatArray(192).also { it[if (index < 4) 0 else 1] = .8f; it[index + 2] = .6f }
            SpeakerObservation(index * 2000L, index * 2000L + 1800, vector)
        }
        val fragment = FloatArray(192).also {
            it[0] = .67f; it[1] = .67f; it[20] = sqrt(1f - 2 * .67f * .67f)
        }
        val samples = observations + listOf(SpeakerObservation(18000, 18900, fragment),
            SpeakerObservation(22000, 22900, fragment))
        val result = SpeakerClusterer().cluster(samples)
        assertEquals(List(4) { "speaker_1" } + List(4) { "speaker_2" } + List(2) { "speaker_3" },
            result.assignments.map { it.speakerId })
    }

    @Test fun isolatedVoiceExcursionDoesNotCreateAnotherPersonOrTeachItsVoiceprint() {
        val result = SpeakerClusterer(threshold = 0.72f).cluster(listOf(
            voice(0, 1f, 0f), voice(1800, 1f, 0f),
            voice(3600, 0.65f, sqrt(1f - 0.65f * 0.65f)).copy(quality = 0.6f),
            voice(5400, 1f, 0f), voice(7200, 1f, 0f),
        ))
        assertEquals(List(5) { "speaker_1" }, result.assignments.map { it.speakerId })
        assertEquals(4, result.signatures.single().windowCount)
        assertArrayEquals(floatArrayOf(1f, 0f), result.signatures.single().vector, 0.001f)
    }

    @Test fun overlappingDegradedContextsDoNotSelfConfirmAnotherPerson() {
        // One 2.7-second phonetic/channel excursion produces two correlated windows.
        // Its weak match falls below discovery, but both sides independently support A.
        val degraded = floatArrayOf(0.53f, sqrt(1f - 0.53f * 0.53f))
        val result = SpeakerClusterer().cluster(listOf(
            voice(0, 1f, 0f), voice(1800, 1f, 0f),
            SpeakerObservation(3600, 5400, degraded, quality = 0.6f),
            SpeakerObservation(4500, 6300, degraded, quality = 0.6f),
            voice(6300, 1f, 0f), voice(8100, 1f, 0f),
        ))
        assertEquals(List(6) { "speaker_1" }, result.assignments.map { it.speakerId })
        assertEquals(4, result.signatures.single().windowCount)
        assertEquals(7200L, result.signatures.single().speechMs)
        assertArrayEquals(floatArrayOf(1f, 0f), result.signatures.single().vector, 0.001f)
    }

    @Test fun recurringDegradedVoiceIsNotDeletedAsOneBriefExcursion() {
        val degraded = floatArrayOf(0.53f, sqrt(1f - 0.53f * 0.53f))
        val result = SpeakerClusterer().cluster(listOf(
            voice(0, 1f, 0f), voice(1800, 1f, 0f),
            SpeakerObservation(3600, 4400, degraded, quality = 0.6f),
            voice(4400, 1f, 0f), voice(6200, 1f, 0f),
            SpeakerObservation(8000, 8800, degraded, quality = 0.6f),
            voice(8800, 1f, 0f),
        ))
        assertEquals("speaker_2", result.assignments[2].speakerId)
        assertEquals("speaker_2", result.assignments[5].speakerId)
    }

    @Test fun overlappingClearContextsRemainADistinctBriefVoice() {
        val reply = floatArrayOf(0.53f, sqrt(1f - 0.53f * 0.53f))
        val result = SpeakerClusterer().cluster(listOf(
            voice(0, 1f, 0f), voice(1800, 1f, 0f),
            SpeakerObservation(3600, 5400, reply), SpeakerObservation(4500, 6300, reply),
            voice(6300, 1f, 0f), voice(8100, 1f, 0f),
        ))
        assertEquals(listOf("speaker_1", "speaker_1", "speaker_2", "speaker_2", "speaker_1", "speaker_1"),
            result.assignments.map { it.speakerId })
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
        // Exercise fragment cleanup independently of the checkpoint's discovery operating
        // point: this close synthetic pair is declared separate by the explicit cutoff.
        val result = SpeakerClusterer(threshold = 0.72f).cluster(listOf(
            voice(0, 1f, 0f), voice(1800, 1f, 0f),
            voice(3600, 0.65f, sqrt(1f - 0.65f * 0.65f)),
            voice(5400, 1f, 0f), voice(7200, 1f, 0f),
        ))
        assertEquals("speaker_2", result.assignments[2].speakerId)
    }

    @Test fun aOneSidedVoiceChangeIsNotAbsorbedAsAnIsolatedFragment() {
        val result = SpeakerClusterer(threshold = 0.72f).cluster(listOf(
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
        val weak = voice(1800, 0.53f, 0.53f, canSeed = false)
            .copy(vector = floatArrayOf(0.53f, 0.53f, sqrt(1f - 2f * 0.53f * 0.53f)))
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
        val weak = voice(5000, 0.53f, sqrt(1f - 0.53f * 0.53f), canSeed = false)
        val result = SpeakerClusterer().cluster(listOf(voice(0, 1f, 0f), weak, voice(10000, 1f, 0f)))
        assertEquals(SpeakerClusterer.UNKNOWN, result.assignments[1].speakerId)
    }

    @Test fun aBoundedRunOfWeakObservationsCanRecoverWithoutTeachingTheVoice() {
        val weak = floatArrayOf(0.53f, sqrt(1f - 0.53f * 0.53f))
        val result = SpeakerClusterer().cluster(listOf(
            voice(0, 1f, 0f),
            SpeakerObservation(1800, 3600, weak, canSeed = false),
            SpeakerObservation(2700, 4500, weak, canSeed = false),
            voice(4500, 1f, 0f),
        ))
        assertEquals(List(4) { "speaker_1" }, result.assignments.map { it.speakerId })
        assertEquals(2, result.signatures.single().windowCount)
    }

    @Test fun aLongWeakRunIsNotInferredFromItsNeighbors() {
        val weak = floatArrayOf(0.53f, sqrt(1f - 0.53f * 0.53f))
        val result = SpeakerClusterer().cluster(listOf(
            voice(0, 1f, 0f),
            SpeakerObservation(1800, 3600, weak, canSeed = false),
            SpeakerObservation(3600, 5400, weak, canSeed = false),
            voice(5400, 1f, 0f),
        ))
        assertEquals(listOf("speaker_1", SpeakerClusterer.UNKNOWN, SpeakerClusterer.UNKNOWN, "speaker_1"),
            result.assignments.map { it.speakerId })
    }

    @Test fun weakRunCannotCrossARealSpeakerChange() {
        val weak = floatArrayOf(0.53f, sqrt(1f - 0.53f * 0.53f), 0f)
        val result = SpeakerClusterer().cluster(listOf(
            SpeakerObservation(0, 1800, floatArrayOf(1f, 0f, 0f)),
            SpeakerObservation(1800, 3600, weak, canSeed = false),
            SpeakerObservation(2700, 4500, weak, canSeed = false),
            SpeakerObservation(4500, 6300, floatArrayOf(0f, 0f, 1f)),
        ))
        assertEquals(listOf("speaker_1", SpeakerClusterer.UNKNOWN, SpeakerClusterer.UNKNOWN, "speaker_2"),
            result.assignments.map { it.speakerId })
    }
}
