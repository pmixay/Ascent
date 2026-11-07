package com.pmixay.ascent

import org.junit.Assert.*
import org.junit.Test

class ProcessingJobTest {
    @Test fun onlyOneJobRunsAtATimeAndARepeatedTapIsRefused() {
        val tracker = JobTracker()
        assertTrue(tracker.begin("first"))
        assertFalse(tracker.begin("second"))
        assertFalse(tracker.begin("first"))
        assertTrue(tracker.isCurrent("first"))
        assertFalse(tracker.isCurrent("second"))
    }

    @Test fun aStaleJobCannotPublishAfterALaterJobStarted() {
        val tracker = JobTracker()
        val published = mutableListOf<String>()
        assertTrue(tracker.begin("old"))
        assertTrue(tracker.finish("old") { published += "old done" })
        assertTrue(tracker.begin("new"))
        // A late callback of the finished job: ignored, and it must not release the new job's slot.
        tracker.whileCurrent("old") { published += "old progress" }
        assertFalse(tracker.finish("old") { published += "old overwrote new" })
        assertTrue(tracker.isCurrent("new"))
        tracker.whileCurrent("new") { published += "new progress" }
        assertTrue(tracker.finish("new") { published += "new done" })
        assertEquals(listOf("old done", "new progress", "new done"), published)
    }

    @Test fun afterFinishingTheSlotIsFreeAgainEvenWhenThePublishStepRan() {
        val tracker = JobTracker()
        assertTrue(tracker.begin("a"))
        assertTrue(tracker.finish("a") { })
        assertFalse(tracker.isCurrent("a"))
        assertTrue(tracker.begin("b"))
    }

    @Test fun engineMessagesBecomeStagesWithPerStageFractions() {
        assertEquals(ProcessingState.PreparingModels to null, EngineProgress.parse("Загружаю GigaAM…"))
        assertEquals(ProcessingState.Recognizing, EngineProgress.parse("Распознаю речь: 45%")!!.first)
        assertEquals(0.45f, EngineProgress.parse("Распознаю речь: 45%")!!.second!!, 1e-6f)
        assertEquals(ProcessingState.Diarizing to null, EngineProgress.parse("Определяю говорящих…"))
        assertEquals(1f, EngineProgress.parse("Определяю говорящих: 100%")!!.second!!, 1e-6f)
        assertEquals(1f, EngineProgress.parse("Распознаю речь: 250%")!!.second!!, 1e-6f)
        assertEquals(ProcessingState.Formatting to null, EngineProgress.parse("Расшифровка готова"))
        assertNull(EngineProgress.parse("что-то другое"))
    }

    @Test fun importFailuresMapToJobErrorCategories() {
        assertEquals(JobErrorCategory.SourceUnreadable, importFailureCategory(ImportFailure.UNREADABLE))
        assertEquals(JobErrorCategory.SourceUnreadable, importFailureCategory(ImportFailure.NOT_SEEKABLE))
        assertEquals(JobErrorCategory.FormatUnsupported, importFailureCategory(ImportFailure.UNSUPPORTED))
        assertEquals(JobErrorCategory.SourceMalformed, importFailureCategory(ImportFailure.MALFORMED))
        assertEquals(JobErrorCategory.SourceMalformed, importFailureCategory(ImportFailure.EMPTY))
        assertEquals(JobErrorCategory.SourceTooLong, importFailureCategory(ImportFailure.TOO_LONG))
    }
}
