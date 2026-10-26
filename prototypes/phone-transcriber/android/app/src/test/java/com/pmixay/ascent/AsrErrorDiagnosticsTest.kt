package com.pmixay.ascent

import org.junit.Assert.assertEquals
import org.junit.Test

class AsrErrorDiagnosticsTest {
    @Test fun emptyRecognitionCountsEveryMissingReferenceWord() {
        assertEquals(AsrErrorDiagnostics.Counts(0, 3, 0),
            AsrErrorDiagnostics.counts(listOf("раз", "два", "три"), emptyList()))
    }

    @Test fun duplicatedSeamWordCountsAsAnInsertion() {
        assertEquals(AsrErrorDiagnostics.Counts(0, 0, 1),
            AsrErrorDiagnostics.counts(listOf("до", "граница", "после"), listOf("до", "граница", "граница", "после")))
    }

    @Test fun substitutionsAndDeletionsKeepTheSameMinimumErrorDenominator() {
        val reference = listOf("один", "два", "три", "четыре")
        val hypothesis = listOf("один", "другой", "четыре")
        val counts = AsrErrorDiagnostics.counts(reference, hypothesis)
        assertEquals(AsrErrorDiagnostics.Counts(1, 1, 0), counts)
        assertEquals(SpeakerRecognitionMetrics.distance(reference, hypothesis), counts.errors)
    }
}
