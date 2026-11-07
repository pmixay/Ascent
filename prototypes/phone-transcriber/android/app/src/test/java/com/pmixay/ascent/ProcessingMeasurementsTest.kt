package com.pmixay.ascent

import org.junit.Assert.*
import org.junit.Test

class ProcessingMeasurementsTest {
    private class Clock {
        var now = 0L
        fun advance(ns: Long) { now += ns }
    }

    @Test fun childDecodeAndModelLoadAreNotDoubleCountedAsAsr() {
        val clock = Clock()
        val timings = ProcessingTimings { clock.now }
        clock.advance(2)
        timings.measure(ProcessingMetricStage.ASR) {
            clock.advance(3)
            timings.measure(ProcessingMetricStage.ASR_MODEL_LOAD) { clock.advance(5) }
            timings.measure(ProcessingMetricStage.INPUT_DECODE) { clock.advance(7) }
            clock.advance(11)
        }
        clock.advance(13)
        val report = timings.finish(ProcessingMeasurementOutcome.COMPLETED, 100)
        assertEquals(41L, report.totalNs)
        assertEquals(14L, report.stages.getValue(ProcessingMetricStage.ASR).elapsedNs)
        assertEquals(5L, report.stages.getValue(ProcessingMetricStage.ASR_MODEL_LOAD).elapsedNs)
        assertEquals(7L, report.stages.getValue(ProcessingMetricStage.INPUT_DECODE).elapsedNs)
        assertEquals(15L, report.stages.getValue(ProcessingMetricStage.OVERHEAD).elapsedNs)
        assertEquals(report.totalNs, report.stages.values.sumOf { it.elapsedNs })
        assertFalse(report.stages.containsKey(ProcessingMetricStage.DIARIZATION))
    }

    @Test fun repeatedNestedBucketsAccumulateAndRetainCallCounts() {
        val clock = Clock()
        val timings = ProcessingTimings { clock.now }
        timings.measure(ProcessingMetricStage.TEXT) {
            repeat(2) {
                timings.measure(ProcessingMetricStage.TEXT_MODEL_LOAD) { clock.advance(10) }
            }
            clock.advance(30)
        }
        val report = timings.finish(ProcessingMeasurementOutcome.COMPLETED, 1)
        assertEquals(20L, report.stages.getValue(ProcessingMetricStage.TEXT_MODEL_LOAD).elapsedNs)
        assertEquals(2, report.stages.getValue(ProcessingMetricStage.TEXT_MODEL_LOAD).calls)
        assertEquals(30L, report.stages.getValue(ProcessingMetricStage.TEXT).elapsedNs)
    }

    @Test fun failuresAndInterruptionsCloseScopesAndPreserveOriginalErrors() {
        for (failure in listOf(IllegalArgumentException("synthetic"), InterruptedException("cancelled"))) {
            val clock = Clock()
            val timings = ProcessingTimings { clock.now }
            try {
                timings.measure(ProcessingMetricStage.ASR) {
                    clock.advance(2)
                    timings.measure(ProcessingMetricStage.INPUT_DECODE) {
                        clock.advance(3)
                        throw failure
                    }
                }
                fail("Expected original error")
            } catch (error: Exception) { assertSame(failure, error) }
            timings.measure(ProcessingMetricStage.CLEANUP) { clock.advance(5) }
            val report = timings.finish(ProcessingMeasurementOutcome.CANCELLED, 1000)
            assertEquals(10L, report.totalNs)
            assertEquals(report.totalNs, report.stages.values.sumOf { it.elapsedNs })
            assertNull(report.realTimeFactor)
        }
    }

    @Test fun onlyCompletedNonemptyKnownMediaHasRtfIncludingCleanupAndOverhead() {
        for (outcome in ProcessingMeasurementOutcome.entries) {
            for (duration in listOf(null, 0L, -1L, 2000L)) {
                val clock = Clock()
                val timings = ProcessingTimings { clock.now }
                timings.measure(ProcessingMetricStage.QUEUE) { clock.advance(500_000_000) }
                timings.measure(ProcessingMetricStage.CLEANUP) { clock.advance(500_000_000) }
                val report = timings.finish(outcome, duration)
                if (outcome == ProcessingMeasurementOutcome.COMPLETED && duration == 2000L) {
                    assertEquals(0.5, report.realTimeFactor!!, 0.000001)
                } else assertNull(report.realTimeFactor)
            }
        }
    }

    @Test fun finishIsStableAndLaterWorkCannotChangeTheReport() {
        val clock = Clock()
        val timings = ProcessingTimings { clock.now }
        clock.advance(10)
        val first = timings.finish(ProcessingMeasurementOutcome.FAILED, null)
        clock.advance(50)
        assertSame(first, timings.finish(ProcessingMeasurementOutcome.COMPLETED, 1000))
        assertThrows(IllegalStateException::class.java) { timings.enter(ProcessingMetricStage.ASR) }
    }

    @Test fun scopesCannotBeClosedOutOfOrderButDoubleCloseIsSafe() {
        val timings = ProcessingTimings { 0 }
        val parent = timings.enter(ProcessingMetricStage.ASR)
        val child = timings.enter(ProcessingMetricStage.INPUT_DECODE)
        assertThrows(IllegalStateException::class.java) { parent.close() }
        assertThrows(IllegalStateException::class.java) { timings.finish(ProcessingMeasurementOutcome.FAILED, null) }
        child.close()
        parent.close()
        parent.close()
        assertEquals(0L, timings.finish(ProcessingMeasurementOutcome.COMPLETED, 1).totalNs)
    }

    @Test fun sourceAdapterKeepsOffsetsArraysPropertiesAndCloseOwnership() {
        val clock = Clock()
        val timings = ProcessingTimings { clock.now }
        val samples = floatArrayOf(.1f, .2f)
        var closed = 0
        val source = object : PcmWindowSource {
            override val sampleCount = 16000L
            override val formatDescription = "synthetic"
            override fun read(start: Long, count: Int): FloatArray {
                assertEquals(123L, start)
                assertEquals(2, count)
                clock.advance(10)
                return samples
            }
            override fun close() { closed++ }
        }
        MeasuredPcmWindowSource(source, timings).use { measured ->
            assertEquals(16000L, measured.sampleCount)
            assertEquals(1000L, measured.durationMs)
            assertEquals("synthetic", measured.formatDescription)
            assertSame(samples, measured.read(123, 2))
        }
        assertEquals(1, closed)
        assertEquals(10L, timings.finish(ProcessingMeasurementOutcome.COMPLETED, 1000)
            .stages.getValue(ProcessingMetricStage.INPUT_DECODE).elapsedNs)
        assertArrayEquals(floatArrayOf(.1f, .2f), samples, 0f)
    }

    @Test fun optionalInstrumentationDoesNotChangeUnmeasuredCallers() {
        val marker = Any()
        assertSame(marker, measureProcessing(null, ProcessingMetricStage.ASR) { marker })
    }

    @Test fun russianSummaryHasNoCompletedRtfForPartialJobsAndDoesNotHideShortStages() {
        val report = ProcessingMeasurementReport(ProcessingMeasurementOutcome.COMPLETED, 1_000_000_000,
            2000, mapOf(ProcessingMetricStage.INPUT_DECODE to ProcessingStageMeasurement(0, 1)))
        assertTrue(report.describe().contains("RTF: 0.500"))
        assertTrue(report.describe().contains("Чтение и декодирование окон: 0.000 с"))
        assertFalse(report.copy(outcome = ProcessingMeasurementOutcome.FAILED).describe().contains("RTF:"))
    }
}
