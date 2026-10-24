package com.pmixay.ascent

import org.junit.Assert.*
import org.junit.Test

class CtcDecoderTest {
    @Test fun repeatedLettersSeparatedByBlankArePreserved() {
        val words = CtcDecoder.decode(intArrayOf(0, 0, 3, 0, 2, 1), listOf("а", "б", " "), 3, 2000, 600)
        assertEquals(listOf("аа", "б"), words.map { it.text })
        assertEquals(2000L, words[0].startMs)
        assertEquals(2400L, words[0].endMs)
        assertEquals(2500L, words[1].startMs)
        assertEquals(2600L, words[1].endMs)
    }

    @Test fun blankRecordingHasNoFabricatedWords() {
        assertTrue(CtcDecoder.decode(intArrayOf(2, 2, 2), listOf("а", " "), 2, 0, 1000).isEmpty())
    }

    @Test fun overlapBoundaryBelongsToExactlyOneChunk() {
        val words = listOf(TimedWord("до", 19000, 19800), TimedWord("граница", 19900, 20100), TimedWord("после", 20100, 20600))
        val first = CtcDecoder.ownedBy(words, 0, 20000)
        val second = CtcDecoder.ownedBy(words, 20000, 40000)
        assertEquals(listOf("до"), first.map { it.text })
        assertEquals(listOf("граница", "после"), second.map { it.text })
        assertEquals(words, first + second)
    }
}
