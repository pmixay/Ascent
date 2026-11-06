package com.pmixay.ascent

import org.junit.Assert.*
import org.junit.Test

class CtcChunkStitcherTest {
    @Test fun shiftedEstimatesOfOneBoundaryWordAreKeptOnceWithFullAcousticSupport() {
        val left = listOf(TimedWord("до", 18500, 18800), TimedWord("граница", 19800, 20100))
        val right = listOf(TimedWord("граница", 19950, 20250), TimedWord("после", 20500, 20800))
        val result = stitch(left, right)
        assertEquals(listOf(left[0], TimedWord("граница", 19800, 20250), right[1]), result)
        val unstitched = CtcDecoder.ownedBy(left, 0, 20000) + CtcDecoder.ownedBy(right, 20000, 40000)
        assertEquals(SpeakerSpeechSupport.contexts(unstitched, 40000), SpeakerSpeechSupport.contexts(result, 40000))
    }

    @Test fun consecutiveRepetitionsWithoutTimeOverlapRemainSeparate() {
        val left = listOf(TimedWord("да", 19700, 19900))
        val right = listOf(TimedWord("да", 20100, 20300))
        assertEquals(left + right, stitch(left, right))
    }

    @Test fun repeatedWordEvidenceInEitherContextPreventsAnAmbiguousMerge() {
        val first = TimedWord("да", 19500, 19700)
        val left = listOf(first, TimedWord("да", 19800, 20100))
        val right = listOf(TimedWord("да", 19950, 20250))
        assertEquals(left + right, stitch(left, right))
        val repeatedRight = listOf(TimedWord("да", 19200, 19400), right.single())
        val uniqueLeft = listOf(left.last())
        assertEquals(uniqueLeft + right, stitch(uniqueLeft, repeatedRight))
    }

    @Test fun differentTextAtTheSeamIsNeverRewrittenOrSuppressed() {
        val left = listOf(TimedWord("был", 19800, 20100))
        val right = listOf(TimedWord("будет", 19950, 20250))
        assertEquals(left + right, stitch(left, right))
    }

    @Test fun emptyAdjacentChunkDoesNotMergeAcrossARecognitionGap() {
        val stitcher = CtcChunkStitcher()
        val first = TimedWord("да", 500, 900)
        val last = TimedWord("да", 20500, 20900)
        stitcher.append(listOf(first), 0, 10000, 0, 11250)
        stitcher.append(emptyList(), 10000, 20000, 8750, 21250)
        stitcher.append(listOf(last), 20000, 30000, 18750, 30000)
        assertEquals(listOf(first, last), stitcher.finish())
    }

    @Test fun subMillisecondFinalCoreHasNoOwnedWordsAndDoesNotFail() {
        val stitcher = CtcChunkStitcher()
        val word = TimedWord("конец", 19500, 19900)
        stitcher.append(listOf(word), 0, 20000, 0, 20000)
        stitcher.append(listOf(word), 20000, 20000, 18750, 20000)
        assertEquals(listOf(word), stitcher.finish())
    }

    @Test fun inconsistentTimingCannotMoveAMergedWordBeforeItsPredecessor() {
        val left = listOf(TimedWord("до", 19900, 19920), TimedWord("граница", 19930, 20010))
        val right = listOf(TimedWord("граница", 19880, 20150))
        assertEquals((left + right).sortedBy { it.startMs }, stitch(left, right))
    }

    private fun stitch(left: List<TimedWord>, right: List<TimedWord>): List<TimedWord> {
        val stitcher = CtcChunkStitcher()
        stitcher.append(left, 0, 20000, 0, 21250)
        stitcher.append(right, 20000, 40000, 18750, 40000)
        return stitcher.finish()
    }
}
