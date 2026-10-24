package com.pmixay.ascent

import org.junit.Assert.*
import org.junit.Test

class SpeakerSpeechSupportTest {
    @Test fun contextsNeverCrossAnAsrSpeechPause() {
        val words = listOf(TimedWord("один", 100, 1500), TimedWord("два", 2500, 4000))
        val contexts = SpeakerSpeechSupport.contexts(words, 5000)
        assertEquals(2, contexts.size)
        assertEquals(20L, contexts.first().startMs)
        assertTrue(contexts.first().endMs < contexts.last().startMs)
        assertNotEquals(contexts.first().region, contexts.last().region)
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
}
