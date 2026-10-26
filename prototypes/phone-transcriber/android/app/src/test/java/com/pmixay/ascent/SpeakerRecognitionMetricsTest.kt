package com.pmixay.ascent

import org.junit.Assert.*
import org.junit.Test

class SpeakerRecognitionMetricsTest {
    @Test fun anonymousLabelPermutationDoesNotChangeJointAccuracy() {
        val result = SpeakerRecognitionMetrics.cpwer(listOf(AttributedText("a", "один два"), AttributedText("b", "три")),
            listOf(AttributedText("second", "один два"), AttributedText("first", "три")))
        assertEquals(0, result.errors)
        assertEquals(3, result.referenceWords)
    }

    @Test fun extraSpeakerFragmentCannotReuseTheSameReferenceIdentity() {
        val result = SpeakerRecognitionMetrics.cpwer(listOf(AttributedText("a", "один два три четыре")),
            listOf(AttributedText("one", "один два"), AttributedText("two", "три четыре")))
        assertEquals(4, result.errors) // Two deletions plus two insertions in an unmatched stream.
    }

    @Test fun missingSpeakersAndExtraWordsStayInTheScore() {
        val reference = listOf(AttributedText("a", "один два"), AttributedText("b", "три"))
        assertEquals(3, SpeakerRecognitionMetrics.cpwer(reference, emptyList()).errors)
        assertEquals(2, SpeakerRecognitionMetrics.cpwer(reference,
            listOf(AttributedText("x", "один два"), AttributedText("y", "три четыре пять"))).errors)
    }

    @Test fun normalizationMatchesTheRussianAsrMeasurementPolicy() {
        assertEquals(listOf("елка", "25", "да", "нет"), SpeakerRecognitionMetrics.words("Ёлка, 25! Да—нет."))
    }
}
