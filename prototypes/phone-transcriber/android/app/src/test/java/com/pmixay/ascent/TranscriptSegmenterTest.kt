package com.pmixay.ascent

import org.junit.Assert.*
import org.junit.Test

class TranscriptSegmenterTest {
    private fun word(text: String, start: Long, end: Long) = TimedWord(text, start, end)

    @Test fun shortPausesJoinWhileSpeakerChangesAndLongPausesSplit() {
        val words = listOf(word("а", 0, 500), word("б", 900, 1400), word("в", 2200, 2500), word("г", 2600, 3000))
        val segments = TranscriptSegmenter.segment(words, listOf("1", "1", "1", "2"), listOf(false, true, false, false))
        assertEquals(listOf(
            TranscriptSegment("1", 0, 1400, "а б", speakerInferred = true),
            TranscriptSegment("1", 2200, 2500, "в"),
            TranscriptSegment("2", 2600, 3000, "г"),
        ), segments)
    }

    @Test fun pauseIsMeasuredFromTheLatestEndInThePhrase() {
        val words = listOf(word("долго", 0, 3000), word("да", 1000, 1500), word("потом", 3500, 4000))
        val segment = TranscriptSegmenter.segment(words, List(3) { "1" }, List(3) { false }).single()
        assertEquals(TranscriptSegment("1", 0, 4000, "долго да потом"), segment)
    }

    @Test fun longMonologueSplitsAtItsLongestPauseInsteadOfAtTwentySeconds() {
        val words = (0 until 12).map { word("w$it", it * 1000L, it * 1000L + 900) } +
            (0 until 13).map { word("v$it", 12_500L + it * 1000L, 12_500L + it * 1000L + 900) }
        val segments = TranscriptSegmenter.segment(words, List(words.size) { "1" }, List(words.size) { false })
        assertEquals(listOf(0L, 12_500L), segments.map { it.startMs })
        assertEquals(listOf(11_900L, 25_400L), segments.map { it.endMs })
        assertTrue(segments.all { it.endMs - it.startMs <= TranscriptSegmenter.MAX_SPAN_MS })
    }

    @Test fun evenSpeechWithoutPausesUsesTheLatestCutWithinTheLimit() {
        val words = (0 until 30).map { word("w$it", it * 1000L, it * 1000L + 900) }
        val segments = TranscriptSegmenter.segment(words, List(30) { "1" }, List(30) { false })
        assertEquals(listOf(0L to 19_900L, 20_000L to 29_900L), segments.map { it.startMs to it.endMs })
    }

    @Test fun everyWordIsKeptOnceInOrderWithValidBounds() {
        val words = (0 until 40).map { word("w$it", it * 700L, it * 700L + 650) }
        val speakers = List(40) { if (it / 7 % 2 == 0) "1" else "2" }
        val segments = TranscriptSegmenter.segment(words, speakers, List(40) { false })
        assertEquals(words.joinToString(" ") { it.text }, segments.joinToString(" ") { it.text })
        assertTrue(segments.all { it.startMs < it.endMs })
        assertEquals(segments.sortedBy { it.startMs }, segments)
        assertTrue(segments.zipWithNext().all { (left, right) -> left.speakerId != right.speakerId || right.startMs - left.endMs > 0 })
    }

    @Test fun emptyInputAndMismatchedAttribution() {
        assertEquals(emptyList<TranscriptSegment>(), TranscriptSegmenter.segment(emptyList(), emptyList(), emptyList()))
        assertThrows(IllegalArgumentException::class.java) {
            TranscriptSegmenter.segment(listOf(word("а", 0, 100)), emptyList(), emptyList())
        }
    }
}
