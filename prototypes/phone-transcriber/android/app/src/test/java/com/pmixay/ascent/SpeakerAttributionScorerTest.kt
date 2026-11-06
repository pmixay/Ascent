package com.pmixay.ascent

import org.junit.Assert.*
import org.junit.Test

class SpeakerAttributionScorerTest {
    @Test fun labelNamesAreArbitraryAndOneGlobalPermutationRecoversThem() {
        val score = SpeakerAttributionScorer.score(listOf(
            AttributionCell("reader_a", 0, 100, "speaker_2"),
            AttributionCell("reader_b", 100, 200, "speaker_1"),
            AttributionCell("reader_a", 300, 400, "speaker_2"),
        ))
        assertEquals(300L, score.totalMs)
        assertEquals(300L, score.correctMs)
        assertEquals(1.0, score.accuracy, 0.0)
        assertEquals(1.0, score.macroAccuracy, 0.0)
        assertEquals(mapOf("reader_a" to "speaker_2", "reader_b" to "speaker_1"), score.matching)
    }

    @Test fun unknownAndMissingEvidenceCannotDisappearFromTheDenominator() {
        val score = SpeakerAttributionScorer.score(listOf(
            AttributionCell("a", 0, 40, "speaker_1"),
            AttributionCell("a", 40, 100, SpeakerClusterer.UNKNOWN),
        ))
        assertEquals(100L, score.totalMs)
        assertEquals(60L, score.unknownMs)
        assertEquals(0.4, score.accuracy, 0.0)
        assertEquals(0.6, score.unknownRate, 0.0)
    }

    @Test fun allUnknownIsZeroAccuracyRatherThanAnEmptyOrPerfectResult() {
        val score = SpeakerAttributionScorer.score(listOf(
            AttributionCell("a", 0, 100, SpeakerClusterer.UNKNOWN),
            AttributionCell("b", 100, 200, SpeakerClusterer.UNKNOWN),
        ))
        assertEquals(0L, score.correctMs)
        assertEquals(0.0, score.accuracy, 0.0)
        assertEquals(0.0, score.macroAccuracy, 0.0)
        assertEquals(1.0, score.unknownRate, 0.0)
        assertTrue(score.matching.isEmpty())
    }

    @Test fun severalFragmentsOfOneReaderDoNotAllCountAsCorrect() {
        val score = SpeakerAttributionScorer.score(listOf(
            AttributionCell("a", 0, 80, "speaker_1"),
            AttributionCell("a", 80, 100, "speaker_2"),
        ))
        assertEquals(80L, score.correctMs)
        assertEquals(0.8, score.accuracy, 0.0)
        assertEquals(mapOf("a" to "speaker_1"), score.matching)
    }

    @Test fun mergedReadersCannotShareOnePredictedIdentityInTheMapping() {
        val score = SpeakerAttributionScorer.score(listOf(
            AttributionCell("a", 0, 90, "speaker_1"),
            AttributionCell("b", 90, 100, "speaker_1"),
        ))
        assertEquals(0.9, score.accuracy, 0.0)
        assertEquals(mapOf("a" to 1.0, "b" to 0.0), score.perReaderAccuracy)
        assertEquals(0.5, score.macroAccuracy, 0.0)
    }

    @Test fun matchingFindsTheGlobalOptimumRatherThanGreedyReaderAssignments() {
        val score = SpeakerAttributionScorer.score(listOf(
            AttributionCell("a", 0, 60, "speaker_1"),
            AttributionCell("a", 60, 110, "speaker_2"),
            AttributionCell("b", 110, 165, "speaker_1"),
        ))
        assertEquals(105L, score.correctMs)
        assertEquals(mapOf("a" to "speaker_2", "b" to "speaker_1"), score.matching)
    }

    @Test fun aShortFinalCellCountsItsDurationInsteadOfAFullWindow() {
        val score = SpeakerAttributionScorer.score(listOf(
            AttributionCell("a", 0, 100, "speaker_1"),
            AttributionCell("a", 100, 101, SpeakerClusterer.UNKNOWN),
        ))
        assertEquals(100.0 / 101, score.accuracy, 0.0)
        assertEquals(101L, score.totalMs)
    }

    @Test fun exactlySeventyFivePercentDoesNotExceedTheRequestedTarget() {
        val score = SpeakerAttributionScorer.score(listOf(
            AttributionCell("a", 0, 75, "speaker_1"),
            AttributionCell("a", 75, 100, SpeakerClusterer.UNKNOWN),
        ))
        assertEquals(0.75, score.accuracy, 0.0)
        assertFalse(score.accuracy > 0.75)
    }

    @Test fun overlappingReferenceCellsAreRejectedInsteadOfDoubleCountingAudio() {
        assertThrows(IllegalArgumentException::class.java) {
            SpeakerAttributionScorer.score(listOf(
                AttributionCell("a", 0, 1800, "speaker_1"),
                AttributionCell("a", 900, 2700, "speaker_1"),
            ))
        }
    }

    @Test fun referenceOrderDoesNotChangeTheScoreAndGapsAreNotSpeechCredit() {
        val cells = listOf(
            AttributionCell("a", 1000, 1100, "speaker_1"),
            AttributionCell("b", 3000, 3100, "speaker_2"),
        )
        assertEquals(SpeakerAttributionScorer.score(cells), SpeakerAttributionScorer.score(cells.reversed()))
        assertEquals(200L, SpeakerAttributionScorer.score(cells).totalMs)
    }

    @Test fun emptyOrZeroDurationReferenceCannotProduceAnAccuracyClaim() {
        assertThrows(IllegalArgumentException::class.java) { SpeakerAttributionScorer.score(emptyList()) }
        assertThrows(IllegalArgumentException::class.java) {
            SpeakerAttributionScorer.score(listOf(AttributionCell("a", 100, 100, "speaker_1")))
        }
    }
}
