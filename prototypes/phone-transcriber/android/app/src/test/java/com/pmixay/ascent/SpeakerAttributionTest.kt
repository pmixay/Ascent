package com.pmixay.ascent

import org.junit.Assert.*
import org.junit.Test

class SpeakerAttributionTest {
    private fun window(start: Long, end: Long, vector: FloatArray = floatArrayOf(1f, 0f)) =
        SpeakerObservation(start, end, vector)
    private fun assigned(id: String, similarity: Float = 1f) = SpeakerAssignment(id, similarity)
    private fun clustered(vararg labels: SpeakerAssignment) = SpeakerClustering(labels.toList(), emptyList())
    private val unknown = SpeakerAssignment(SpeakerClusterer.UNKNOWN, 0f)

    @Test fun strictWordsRemainUnchangedAndNeverBecomeInferred() {
        val words = listOf(TimedWord("Первый", 100, 200), TimedWord("Второй", 2100, 2200))
        val windows = listOf(window(0, 1000), window(2000, 3000, floatArrayOf(0f, 1f)))
        val result = SpeakerAttribution.complete(words, windows, clustered(assigned("speaker_1"), assigned("speaker_2")))
        assertEquals(listOf("speaker_1", "speaker_2"), result.speakerIds)
        assertEquals(listOf(false, false), result.inferred)
    }

    @Test fun unresolvedAcousticEvidenceWinsOverANearbyDifferentVoice() {
        val words = listOf(TimedWord("до", 80, 120), TimedWord("ответ", 480, 520), TimedWord("после", 880, 920))
        val windows = listOf(window(0, 200), window(400, 600, floatArrayOf(0f, 1f)),
            window(800, 1000, floatArrayOf(0f, 1f)))
        val result = SpeakerAttribution.complete(words, windows,
            clustered(assigned("speaker_1"), unknown, assigned("speaker_2")))
        assertEquals(listOf("speaker_1", "speaker_2", "speaker_2"), result.speakerIds)
        assertEquals(listOf(false, true, false), result.inferred)
    }

    @Test fun tiedLocalVoicesUseNearestOriginalPhraseAndRemainMarkedInferred() {
        val words = listOf(TimedWord("до", 80, 120), TimedWord("ответ", 480, 520), TimedWord("после", 880, 920))
        val windows = listOf(window(0, 200), window(400, 600), window(400, 600), window(800, 1000))
        val labels = clustered(assigned("speaker_1"), assigned("speaker_1"), assigned("speaker_2"), assigned("speaker_2"))
        val result = SpeakerAttribution.complete(words, windows, labels)
        assertEquals(listOf("speaker_1", "speaker_1", "speaker_2"), result.speakerIds)
        assertEquals(listOf(false, true, false), result.inferred)
        assertEquals(SpeakerClusterer.UNKNOWN, SpeakerSpeechSupport.speakersForWords(words, windows, labels.assignments)[1])
    }

    @Test fun uncoveredWordsUseFrozenOriginalEvidenceWithoutPropagatingGuesses() {
        val words = listOf(TimedWord("до", 80, 120), TimedWord("а", 3480, 3520),
            TimedWord("б", 6480, 6520), TimedWord("в", 8480, 8520), TimedWord("после", 9980, 10020))
        val windows = listOf(window(0, 200), window(9900, 10100, floatArrayOf(0f, 1f)))
        val result = SpeakerAttribution.complete(words, windows, clustered(assigned("speaker_1"), assigned("speaker_2")))
        assertEquals(listOf("speaker_1", "speaker_1", "speaker_2", "speaker_2", "speaker_2"), result.speakerIds)
        assertEquals(listOf(false, true, true, true, false), result.inferred)
    }

    @Test fun shortDistinctVoicesCanBeAssignedWithoutCreatingEnrollmentEvidence() {
        val windows = listOf(window(0, 400).copy(canSeed = false, quality = .5f),
            window(1000, 1400, floatArrayOf(0f, 1f)).copy(canSeed = false, quality = .5f))
        val strict = SpeakerClusterer().cluster(windows)
        assertTrue(strict.assignments.all { it.speakerId == SpeakerClusterer.UNKNOWN })
        val result = SpeakerAttribution.complete(listOf(TimedWord("да", 100, 200), TimedWord("нет", 1100, 1200)), windows, strict)
        assertEquals(listOf("speaker_1", "speaker_2"), result.speakerIds)
        assertEquals(listOf(true, true), result.inferred)
        assertTrue(SpeakerClusterer().clusterShortEvidence(windows).signatures.isEmpty())
        assertTrue(strict.signatures.isEmpty())
    }

    @Test fun absentOrInvalidEmbeddingsKeepOneStableParticipantAndEveryWord() {
        val words = listOf(TimedWord("раз", 100, 200), TimedWord("два", 1100, 1200), TimedWord("три", 5000, 5100))
        for (windows in listOf(emptyList(), listOf(window(0, 1000, floatArrayOf(0f, 0f)),
            window(1000, 2000, floatArrayOf(Float.NaN, 1f))))) {
            val result = SpeakerAttribution.complete(words, windows,
                SpeakerClustering(List(windows.size) { unknown }, emptyList()))
            assertEquals(List(3) { "speaker_1" }, result.speakerIds)
            assertEquals(List(3) { true }, result.inferred)
        }
    }

    @Test fun fallbackNeverMutatesSourceEmbeddingsOrExistingVoiceSignatures() {
        val known = window(0, 1800)
        val uncertain = window(3000, 4200, floatArrayOf(.8f, .2f)).copy(canSeed = false)
        val signature = VoiceSignature("speaker_1", floatArrayOf(1f, 0f), 6000, 4, .9f)
        val original = SpeakerClustering(listOf(assigned("speaker_1"), unknown), listOf(signature))
        val result = SpeakerAttribution.complete(listOf(TimedWord("слово", 3500, 3600)), listOf(known, uncertain), original)
        assertEquals(listOf("speaker_1"), result.speakerIds)
        assertEquals(listOf(true), result.inferred)
        assertArrayEquals(floatArrayOf(1f, 0f), known.vector, 0f)
        assertArrayEquals(floatArrayOf(.8f, .2f), uncertain.vector, 0f)
        assertArrayEquals(floatArrayOf(1f, 0f), signature.vector, 0f)
        assertSame(signature, original.signatures.single())
        assertEquals(unknown, original.assignments.last())
    }

    @Test fun tiedContextOrderDoesNotChangeTheFallbackSpeaker() {
        val windows = listOf(window(0, 2000), window(0, 2000, floatArrayOf(0f, 1f)))
        val labels = listOf(assigned("speaker_2"), assigned("speaker_1"))
        val words = listOf(TimedWord("ответ", 900, 1100))
        val first = SpeakerAttribution.complete(words, windows, SpeakerClustering(labels, emptyList()))
        val reverse = SpeakerAttribution.complete(words, windows.reversed(), SpeakerClustering(labels.reversed(), emptyList()))
        assertEquals(first, reverse)
        assertEquals(listOf("speaker_1"), first.speakerIds)
        assertEquals(listOf(true), first.inferred)
    }

    @Test fun legacyTextAssignmentsPreserveOriginalTextTimingAndKnownNames() {
        val segments = listOf(TranscriptSegment("speaker_1", 0, 1000, "Первый"),
            TranscriptSegment(SpeakerClusterer.UNKNOWN, 1200, 1500, "Ближе к первому"),
            TranscriptSegment(SpeakerClusterer.UNKNOWN, 8000, 8500, "Ближе ко второму"),
            TranscriptSegment("speaker_2", 9000, 10000, "Второй"))
        val completed = SpeakerAttribution.completeStored(segments)
        assertEquals(listOf("speaker_1", "speaker_1", "speaker_2", "speaker_2"), completed.map { it.speakerId })
        assertEquals(listOf(false, true, true, false), completed.map { it.speakerInferred })
        assertEquals(segments.map { Triple(it.text, it.startMs, it.endMs) },
            completed.map { Triple(it.text, it.startMs, it.endMs) })
        assertEquals(SpeakerClusterer.UNKNOWN, segments[1].speakerId)
        assertSame(completed, SpeakerAttribution.completeStored(completed))
    }

    @Test fun emptyTranscriptStaysEmpty() {
        assertEquals(CompletedSpeakerAttribution(emptyList(), emptyList()),
            SpeakerAttribution.complete(emptyList(), emptyList(), clustered()))
    }

    @Test fun legacyGuessesCannotBecomeTemporalEvidenceForLaterMissingAssignments() {
        val original = TranscriptSegment("speaker_1", 0, 1000, "Подтверждённая реплика")
        val guessed = TranscriptSegment("speaker_2", 9000, 9500, "Предполагаемая реплика", true)
        val missing = TranscriptSegment(SpeakerClusterer.UNKNOWN, 9600, 9800, "Ещё одна реплика")
        val completed = SpeakerAttribution.completeStored(listOf(original, guessed, missing))
        assertEquals(listOf("speaker_1", "speaker_2", "speaker_1"), completed.map { it.speakerId })
        assertTrue(completed.last().speakerInferred)
        // With no original anchor at all, use an existing anonymous identity consistently.
        val noOriginal = SpeakerAttribution.completeStored(listOf(guessed, missing))
        assertEquals(List(2) { "speaker_2" }, noOriginal.map { it.speakerId })
        assertTrue(noOriginal.all { it.speakerInferred })
    }
}
