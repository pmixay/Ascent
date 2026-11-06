package com.pmixay.ascent

import org.junit.Assert.*
import org.junit.Test

class TranscriptTimelineTest {
    @Test fun sameSpeakerKeepsOneTurnAcrossPausesAndOldTwentySecondBoundaries() {
        val original = listOf(
            TranscriptSegment("a", 100, 20_000, "Первая фраза"),
            TranscriptSegment("a", 20_100, 22_000, "продолжение"),
            TranscriptSegment("a", 23_300, 25_000, "После паузы"),
        )
        val turn = TranscriptTimeline.turns(original).single()
        assertEquals("a", turn.speakerId)
        assertEquals(100L, turn.startMs)
        assertEquals(25_000L, turn.endMs)
        assertEquals(0L, turn.pauseBeforeMs)
        assertEquals(listOf(
            TimelinePhrase("Первая фраза продолжение", 100, 22_000),
            TimelinePhrase("После паузы", 23_300, 25_000, 1300),
        ), turn.phrases)
        // Rendering must not rewrite retained/exported timing evidence.
        assertEquals(3, original.size)
        assertEquals(20_000L, original[0].endMs)
    }

    @Test fun returningSpeakerRemainsASeparateTurnAfterAnotherVoice() {
        val turns = TranscriptTimeline.turns(listOf(
            TranscriptSegment("a", 0, 1000, "Первый"),
            TranscriptSegment("b", 2300, 3000, "Второй"),
            TranscriptSegment("a", 3200, 4000, "Ответ"),
        ))
        assertEquals(listOf("a", "b", "a"), turns.map { it.speakerId })
        assertEquals(listOf(0L, 1300L, 0L), turns.map { it.pauseBeforeMs })
    }

    @Test fun unknownAttributionNeverProvesThatPhrasesShareAVoice() {
        val turns = TranscriptTimeline.turns(listOf(
            TranscriptSegment("a", 0, 1000, "Да"),
            TranscriptSegment(UNKNOWN_SPEAKER, 1100, 1500, "Возможно"),
            TranscriptSegment(UNKNOWN_SPEAKER, 1600, 1900, "Нет"),
            TranscriptSegment("a", 2000, 3000, "Продолжение"),
        ))
        assertEquals(4, turns.size)
        assertEquals(listOf("Да", "Возможно", "Нет", "Продолжение"), turns.flatMap { it.phrases }.map { it.text })
    }

    @Test fun overlappingSpeechDoesNotInventAPauseFromANestedShortSegment() {
        val turns = TranscriptTimeline.turns(listOf(
            TranscriptSegment("a", 0, 5000, "Длинная реплика"),
            TranscriptSegment("b", 1000, 2000, "Короткая реплика"),
            TranscriptSegment("b", 3000, 4000, "Продолжение"),
            TranscriptSegment("c", 5100, 6000, "Ответ"),
        ))
        assertTrue(turns.all { it.pauseBeforeMs == 0L })
        assertEquals(1, turns[1].phrases.size)
        assertEquals("Короткая реплика Продолжение", turns[1].phrases.single().text)
    }

    @Test fun sameVoiceStillSharesOneElementAfterALongPause() {
        val turn = TranscriptTimeline.turns(listOf(
            TranscriptSegment("a", 0, 1000, "До"),
            TranscriptSegment("a", 61_000, 62_000, "После"),
        )).single()
        assertEquals(60_000L, turn.phrases.last().pauseBeforeMs)
        assertEquals("60,0", formatTimelineSeconds(turn.phrases.last().pauseBeforeMs))
    }

    @Test fun timelineUsesExplicitDecimalSecondsAndRoundsAtOneTenth() {
        assertEquals("1,3", formatTimelineSeconds(1300))
        assertEquals("1,3", formatTimelineSeconds(1349))
        assertEquals("1,4", formatTimelineSeconds(1350))
        assertEquals("0,0", formatTimelineSeconds(-1))
        assertEquals("59,9–60,1 с", formatTimelineRange(59_900, 60_100))
        assertEquals("3600,0", formatTimelineSeconds(3_600_000))
    }

    @Test fun shortEmissionGapsAndLeadingTimeAreNotPresentedAsPauses() {
        val turn = TranscriptTimeline.turns(listOf(
            TranscriptSegment("a", 3000, 4000, "Раз"),
            TranscriptSegment("a", 4700, 5000, "два"),
        )).single()
        assertEquals(0L, turn.pauseBeforeMs)
        assertEquals(listOf(TimelinePhrase("Раз два", 3000, 5000)), turn.phrases)
    }

    @Test fun emptyTranscriptHasNoTurnsOrFabricatedPauses() {
        assertTrue(TranscriptTimeline.turns(emptyList()).isEmpty())
    }
}
