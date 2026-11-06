package com.pmixay.ascent

import org.junit.Assert.*
import org.junit.Test

class SpeakerSpeechSupportTest {
    @Test fun contextsNeverCrossAnAsrSpeechPause() {
        val words = listOf(TimedWord("один", 100, 1100), TimedWord("два", 2500, 3500))
        val contexts = SpeakerSpeechSupport.contexts(words, 5000)
        assertEquals(2, contexts.size)
        assertEquals(0L, contexts.first().startMs)
        assertTrue(contexts.first().endMs < contexts.last().startMs)
        assertNotEquals(contexts.first().region, contexts.last().region)
    }

    @Test fun sparseCtcPeaksKeepOneAcousticRegion() {
        val words = listOf(TimedWord("я", 100, 140), TimedWord("это", 520, 560), TimedWord("знаю", 940, 980))
        val contexts = SpeakerSpeechSupport.contexts(words, 2000)
        assertEquals(1, contexts.size)
        assertTrue(contexts.single().endMs - contexts.single().startMs >= 1200)
        assertTrue(words.all { it.startMs >= contexts.single().startMs && it.endMs <= contexts.single().endMs })
    }

    @Test fun aShortCtcEmissionGetsRealSurroundingAudio() {
        val context = SpeakerSpeechSupport.contexts(listOf(TimedWord("да", 2000, 2040)), 4000).single()
        assertEquals(1200L, context.endMs - context.startMs)
        assertTrue(context.startMs < 2000 && context.endMs > 2040)
    }

    @Test fun shortReplyContextsDoNotReachTheNextReply() {
        val contexts = SpeakerSpeechSupport.contexts(
            listOf(TimedWord("да", 1000, 1080), TimedWord("нет", 1800, 1880)), 3000)
        assertEquals(2, contexts.size)
        assertTrue(contexts.first().endMs <= contexts.last().startMs)
        assertTrue(contexts.first().endMs < 1800)
        assertTrue(contexts.last().startMs > 1080)
    }

    @Test fun shortRecordingEdgesRemainUsableAndBounded() {
        val contexts = SpeakerSpeechSupport.contexts(listOf(TimedWord("да", 20, 60)), 450)
        assertEquals(listOf(SpeakerContext(0, 450, 0)), contexts)
        assertTrue(SpeakerSpeechSupport.contexts(listOf(TimedWord("да", 20, 60)), 200).isEmpty())
    }

    @Test fun longSpeechUsesBoundedContextsAndCoversItsEnd() {
        val contexts = SpeakerSpeechSupport.contexts(listOf(TimedWord("речь", 0, 10000)), 10000)
        assertTrue(contexts.all { it.endMs - it.startMs == 1800L })
        assertEquals(0L, contexts.first().startMs)
        assertEquals(10000L, contexts.last().endMs)
        assertEquals(contexts.size, contexts.distinct().size)
    }

    @Test fun silenceAndClippingDoNotProvideVoiceEvidence() {
        assertEquals(0f, SpeakerSpeechSupport.activity(FloatArray(16000), 0).quality, 0f)
        assertEquals(0f, SpeakerSpeechSupport.activity(FloatArray(16000) { 1f }, 0).quality, 0f)
    }

    @Test fun activityCountsOnlyNonSilentFrames() {
        val samples = FloatArray(16000) { if (it < 8000) 0.1f else 0f }
        val activity = SpeakerSpeechSupport.activity(samples, 2000)
        assertEquals(500L, activity.spans.sumOf { it.endMs - it.startMs })
        assertEquals(0.5f, activity.quality, 0.001f)
        assertEquals(2000L, activity.spans.first().startMs)
    }

    @Test fun wordsCannotBorrowAVoiceFromAcrossAPause() {
        val windows = listOf(SpeakerObservation(0, 1000, floatArrayOf(1f)))
        val labels = listOf(SpeakerAssignment("speaker_1", 1f))
        assertEquals(SpeakerClusterer.UNKNOWN,
            SpeakerSpeechSupport.speakerForWord(TimedWord("далеко", 1500, 1800), windows, labels))
    }

    @Test fun equalCompetingContextVotesRemainUnknown() {
        val windows = listOf(SpeakerObservation(0, 2000, floatArrayOf(1f)),
            SpeakerObservation(0, 2000, floatArrayOf(1f)))
        val labels = listOf(SpeakerAssignment("speaker_1", 1f), SpeakerAssignment("speaker_2", 1f))
        assertEquals(SpeakerClusterer.UNKNOWN,
            SpeakerSpeechSupport.speakerForWord(TimedWord("да", 900, 1100), windows, labels))
    }

    @Test fun redundantOverlappingContextsCannotOutvoteTheClosestVoice() {
        val close = SpeakerObservation(0, 2000, floatArrayOf(1f))
        val farther = SpeakerObservation(500, 2500, floatArrayOf(1f))
        val word = TimedWord("слово", 900, 1100)
        val windows = listOf(close, farther, farther, farther)
        val labels = listOf(SpeakerAssignment("speaker_1", 1f)) + List(3) { SpeakerAssignment("speaker_2", 1f) }
        assertEquals("speaker_1", SpeakerSpeechSupport.speakerForWord(word, windows, labels))
    }

    @Test fun aBriefWeakVoteKeepsTheSameClearlySupportedVoice() {
        val words = listOf(TimedWord("я", 80, 120), TimedWord("уже", 480, 520), TimedWord("здесь", 880, 920))
        val windows = listOf(window(0, 200), window(400, 600), window(400, 600), window(800, 1000))
        val labels = listOf(label("speaker_1"), label("speaker_1"), label("speaker_2", 0.9f), label("speaker_1"))
        assertEquals(SpeakerClusterer.UNKNOWN, SpeakerSpeechSupport.speakerForWord(words[1], windows, labels))
        assertEquals(List(3) { "speaker_1" }, SpeakerSpeechSupport.speakersForWords(words, windows, labels))
    }

    @Test fun continuityCannotFillATieOrContradictoryLocalVoice() {
        val words = listOf(TimedWord("я", 80, 120), TimedWord("да", 480, 520), TimedWord("здесь", 880, 920))
        val windows = listOf(window(0, 200), window(400, 600), window(400, 600), window(800, 1000))
        for ((first, second) in listOf(1f to 1f, 0.9f to 1f)) {
            val labels = listOf(label("speaker_1"), label("speaker_1", first), label("speaker_2", second), label("speaker_1"))
            assertEquals(SpeakerClusterer.UNKNOWN, SpeakerSpeechSupport.speakersForWords(words, windows, labels)[1])
        }
    }

    @Test fun aClearlyDifferentShortReplyIsNeverSmoothedAway() {
        val words = listOf(TimedWord("я", 80, 120), TimedWord("нет", 480, 520), TimedWord("здесь", 880, 920))
        val windows = listOf(window(0, 200), window(400, 600), window(800, 1000))
        val labels = listOf(label("speaker_1"), label("speaker_2"), label("speaker_1"))
        assertEquals(listOf("speaker_1", "speaker_2", "speaker_1"), SpeakerSpeechSupport.speakersForWords(words, windows, labels))
    }

    @Test fun continuityCannotBorrowAcrossMissingEvidenceOrLongSilence() {
        val words = listOf(TimedWord("я", 80, 120), TimedWord("уже", 1480, 1520), TimedWord("здесь", 1880, 1920))
        val windows = listOf(window(0, 200), window(1400, 1600), window(1400, 1600), window(1800, 2000))
        val labels = listOf(label("speaker_1"), label("speaker_1"), label("speaker_2", 0.9f), label("speaker_1"))
        assertEquals(SpeakerClusterer.UNKNOWN, SpeakerSpeechSupport.speakersForWords(words, windows, labels)[1])
        assertEquals(SpeakerClusterer.UNKNOWN, SpeakerSpeechSupport.speakersForWords(
            words, listOf(windows.first(), windows.last()), listOf(labels.first(), labels.last()))[1])
    }

    @Test fun longUncertainPassageIsNotFilledFromItsNeighbors() {
        val words = listOf(TimedWord("до", 80, 120), TimedWord("неясно", 400, 1300), TimedWord("после", 1480, 1520))
        val windows = listOf(window(0, 200), window(300, 1400), window(300, 1400), window(1400, 1600))
        val labels = listOf(label("speaker_1"), label("speaker_1"), label("speaker_2", 0.9f), label("speaker_1"))
        assertEquals(SpeakerClusterer.UNKNOWN, SpeakerSpeechSupport.speakersForWords(words, windows, labels)[1])
    }

    private fun window(start: Long, end: Long) = SpeakerObservation(start, end, floatArrayOf(1f))
    private fun label(speaker: String, similarity: Float = 1f) = SpeakerAssignment(speaker, similarity)
}
