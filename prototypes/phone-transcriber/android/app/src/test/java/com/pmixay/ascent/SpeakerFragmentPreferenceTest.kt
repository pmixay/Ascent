package com.pmixay.ascent

import kotlin.math.sqrt
import org.junit.Assert.*
import org.junit.Test

/** Weak fragments cannot create extra evidence by competing with, or teaching, one another. */
class SpeakerFragmentPreferenceTest {
    @Test fun independentlySupportedFragmentsDoNotMutuallyBlockTheSameParent() {
        val parent = broadParent(0, 10, 0)
        val first = mode(.64f)
        val second = relatedMode(.64f, first, .53f)
        val result = SpeakerClusterer().cluster(parent + listOf(short(50000, first), short(54000, second)))

        assertEquals(List(12) { "speaker_1" }, result.assignments.map { it.speakerId })
        val signature = result.signatures.single()
        assertEquals(10, signature.windowCount)
        assertEquals(18000L, signature.speechMs)
        assertEquals(0f, signature.vector[2], .00001f)
        assertEquals(0f, signature.vector[3], .00001f)
    }

    @Test fun aRivalBelowTheExistingParentCutoffRemainsACompetitor() {
        val parent = broadParent(0, 10, 0)
        val first = mode(.64f)
        val unsupportedRival = relatedMode(.57f, first, .53f)
        val result = SpeakerClusterer().cluster(parent + listOf(short(50000, first), short(54000, unsupportedRival)))

        assertNotEquals(result.assignments.first().speakerId, result.assignments[10].speakerId)
        assertNotEquals(result.assignments.first().speakerId, result.assignments[11].speakerId)
        assertNotEquals(result.assignments[10].speakerId, result.assignments[11].speakerId)
    }

    @Test fun fragmentsPreferringDifferentEstablishedParentsRemainCompetitors() {
        val firstParent = broadParent(0, 10, 0)
        val secondParent = broadParent(1, 30, 22000)
        val first = mode(.64f)
        val second = FloatArray(192).also {
            it[1] = .64f
            it[2] = .53f / first[2]
            it[3] = sqrt(1f - it[1] * it[1] - it[2] * it[2])
        }
        val result = SpeakerClusterer().cluster(firstParent + secondParent + listOf(short(50000, first), short(54000, second)))

        assertNotEquals(result.assignments[0].speakerId, result.assignments[20].speakerId)
        assertNotEquals(result.assignments[10].speakerId, result.assignments[21].speakerId)
        assertNotEquals(result.assignments[20].speakerId, result.assignments[21].speakerId)
    }

    @Test fun overlappingParentContextsCannotSupplyIndependentCorroboration() {
        val parent = broadParent(0, 10, 0).map { it.copy(startMs = 0, endMs = 6000,
            speech = listOf(SpeechSpan(0, 6000))) }
        val first = mode(.64f)
        val second = relatedMode(.64f, first, .53f)
        val result = SpeakerClusterer().cluster(parent + listOf(short(50000, first), short(54000, second)))

        assertNotEquals(result.assignments.first().speakerId, result.assignments[10].speakerId)
        assertNotEquals(result.assignments.first().speakerId, result.assignments[11].speakerId)
    }

    @Test fun subthresholdModeCannotLearnFromTwoRecoveredFragments() {
        val parent = broadParent(0, 10, 0)
        val first = mode(.64f)
        val second = relatedMode(.64f, first, .53f)
        val unsupported = FloatArray(192).also {
            it[0] = .61f
            it[2] = (.49f - it[0] * first[0]) / first[2]
            it[3] = (.49f - it[0] * second[0] - it[2] * second[2]) / second[3]
            it[4] = sqrt(1f - it[0] * it[0] - it[2] * it[2] - it[3] * it[3])
        }
        val result = SpeakerClusterer().cluster(parent + listOf(short(50000, first), short(54000, second),
            short(58000, unsupported)))

        assertEquals("speaker_1", result.assignments[10].speakerId)
        assertEquals("speaker_1", result.assignments[11].speakerId)
        assertNotEquals("speaker_1", result.assignments[12].speakerId)
        assertEquals(10, result.signatures.first { it.speakerId == "speaker_1" }.windowCount)
    }

    private fun broadParent(identity: Int, noiseStart: Int, startMs: Long) = (0 until 10).map { index ->
        val vector = FloatArray(192).also { it[identity] = .8f; it[noiseStart + index] = .6f }
        SpeakerObservation(startMs + index * 2000, startMs + index * 2000 + 1800, vector)
    }

    private fun mode(identity: Float) = FloatArray(192).also {
        it[0] = identity
        it[2] = sqrt(1f - identity * identity)
    }

    private fun relatedMode(identity: Float, first: FloatArray, cross: Float) = FloatArray(192).also {
        it[0] = identity
        it[2] = (cross - identity * first[0]) / first[2]
        it[3] = sqrt(1f - it[0] * it[0] - it[2] * it[2])
    }

    private fun short(startMs: Long, vector: FloatArray) = SpeakerObservation(startMs, startMs + 900, vector)
}
