package com.pmixay.ascent

import org.junit.Assert.*
import org.junit.Test

class TextStageTest {
    private val tokenizer = QwenTokenizer.parse(
        requireNotNull(javaClass.getResource("/text-model/qwen-tokenizer-subset.json")).readText(),
    )
    private val period = tokenizer.encode(".").single()

    private class ScriptedModel(
        val behavior: (call: Int, tokens: IntArray, wanted: Map<Int, IntArray>) -> Map<Int, FloatArray>,
    ) : TextModelScores {
        var calls = 0
        override fun nextTokenScores(tokens: IntArray, wanted: Map<Int, IntArray>): Map<Int, FloatArray> = behavior(calls++, tokens, wanted)
        override fun close() = Unit
    }

    private fun neutral(wanted: Map<Int, IntArray>) = wanted.mapValues { (_, ids) -> FloatArray(ids.size) { 1f } }

    /** Prefers a full stop at the end of the phrase and nothing else. */
    private fun endsWithPeriod(tokens: IntArray, wanted: Map<Int, IntArray>) = wanted.mapValues { (position, ids) ->
        FloatArray(ids.size) { if (position == tokens.size - 1 && ids[it] == period) 5f else 1f }
    }

    private fun segment(text: String, speaker: String = "speaker_1", start: Long = 0, end: Long = 1_000, inferred: Boolean = false) =
        TranscriptSegment(speaker, start, end, text, inferred)

    private fun stage(model: TextModelScores) = TextPunctuator(tokenizer, model)

    @Test fun editsKeepTheRecognizedWordsBesideThePresentedText() {
        val input = listOf(segment("привет мир", "speaker_1", 100, 900), segment("как дела", "speaker_2", 1_000, 1_800, inferred = true))
        val outcome = TextStages.present(input, stage(ScriptedModel { _, tokens, wanted -> endsWithPeriod(tokens, wanted) }))
        assertEquals(listOf("привет мир.", "как дела."), outcome.segments.map { it.text })
        assertEquals(listOf("привет мир", "как дела"), outcome.segments.map { it.rawText })
        assertEquals(listOf("привет мир", "как дела"), outcome.segments.map { it.recognizedText })
        // Speakers, times and the inferred-attribution flag are copied, not recomputed.
        assertEquals(input.map { listOf(it.speakerId, it.startMs, it.endMs, it.speakerInferred) },
            outcome.segments.map { listOf(it.speakerId, it.startMs, it.endMs, it.speakerInferred) })
        assertEquals(TextStageReport(TextStageStatus.COMPLETED, 2, 0, outcome.report.elapsedMs), outcome.report)
    }

    @Test fun anUnchangedPhraseStoresNoDuplicateRawText() {
        val outcome = TextStages.present(listOf(segment("привет мир")), stage(ScriptedModel { _, _, wanted -> neutral(wanted) }))
        assertEquals("привет мир", outcome.segments.single().text)
        assertNull(outcome.segments.single().rawText)
        assertEquals(TextStageStatus.COMPLETED, outcome.report.status)
        assertEquals(0, outcome.report.fallbacks)
    }

    @Test fun aFailingPhraseKeepsItsWordsAndIsCountedWhileOthersAreStillPresented() {
        val model = ScriptedModel { call, tokens, wanted ->
            if (call == 1) throw IllegalStateException("model failed") else endsWithPeriod(tokens, wanted)
        }
        // The test tokenizer is a subset of the real vocabulary: every word and its capitalized form must
        // be encodable, or the phrase would fail before reaching the model and prove nothing.
        val outcome = TextStages.present(listOf(segment("привет мир"), segment("как дела")), stage(model))
        assertEquals(listOf("привет мир.", "как дела"), outcome.segments.map { it.text })
        assertNull(outcome.segments[1].rawText)
        assertEquals(2, outcome.report.phrases)
        assertEquals(1, outcome.report.fallbacks)
        assertEquals(TextStageStatus.COMPLETED, outcome.report.status)
    }

    @Test fun aStageThatFailsEveryPhraseIsReportedAsFailedNotAsSuccess() {
        val model = ScriptedModel { _, _, _ -> throw IllegalStateException("model failed") }
        val input = listOf(segment("раз два"), segment("как дела"))
        val outcome = TextStages.present(input, stage(model))
        assertEquals(input, outcome.segments)
        assertEquals(TextStageStatus.FAILED, outcome.report.status)
        assertEquals(2, outcome.report.fallbacks)
    }

    @Test fun anEditThatTheIndependentCheckRejectsIsDropped() {
        // Marks already present in the recognized text mean the word check cannot vouch for the edit.
        val outcome = TextStages.present(listOf(segment("привет, мир")), stage(ScriptedModel { _, _, wanted -> neutral(wanted) }))
        assertEquals("привет, мир", outcome.segments.single().text)
        assertNull(outcome.segments.single().rawText)
        assertEquals(1, outcome.report.fallbacks)
    }

    @Test fun cancellationIsNotSwallowedAsAFallback() {
        val model = ScriptedModel { _, _, _ -> throw InterruptedException("cancelled") }
        assertThrows(InterruptedException::class.java) { TextStages.present(listOf(segment("раз два")), stage(model)) }
    }

    @Test fun noSpeechMeansNothingToPresent() {
        val outcome = TextStages.present(emptyList(), stage(ScriptedModel { _, _, wanted -> neutral(wanted) }))
        assertTrue(outcome.segments.isEmpty())
        assertEquals(0, outcome.report.phrases)
    }


    @Test fun anAcousticOnlyLeaseReportsTheTextStageAsUnavailable() {
        val input = listOf(segment("привет мир"))
        val outcome = TextStages.run(null, input)
        assertSame(input, outcome.segments)
        assertEquals(TextStageStatus.UNAVAILABLE, outcome.report.status)
        assertEquals(0, outcome.report.phrases)
    }

    @Test fun anAbsentPairedDirectoryNeverLoadsAnotherRevision() {
        val root = java.nio.file.Files.createTempDirectory("text-pair").toFile()
        try {
            val input = listOf(segment("привет мир"))
            val outcome = TextStages.run(java.io.File(root, "text-embedded-missing"), input)
            assertSame(input, outcome.segments)
            assertEquals(TextStageStatus.UNAVAILABLE, outcome.report.status)
            assertEquals(TextStageStatus.SKIPPED, TextStages.run(null, emptyList()).report.status)
        } finally { root.deleteRecursively() }
    }

    @Test fun cancellationBeforeOpeningPairedModelsIsPropagated() {
        Thread.currentThread().interrupt()
        try {
            assertThrows(InterruptedException::class.java) { TextStages.run(null, listOf(segment("привет мир"))) }
        } finally { Thread.interrupted() }
    }

    @Test fun phrasesReachedAfterTheBudgetKeepTheirWordsAndTheModelIsNotAskedAgain() {
        var now = 0L
        // Each model call costs 10 s on the fake clock; the budget is 15 s.
        val model = ScriptedModel { _, tokens, wanted -> now += 10_000_000_000L; endsWithPeriod(tokens, wanted) }
        val input = listOf(segment("привет мир", "speaker_1", 100, 900), segment("как дела", "speaker_2", 1_000, 1_800),
            segment("раз два", "speaker_1", 2_000, 2_800))
        val outcome = TextStages.present(input, stage(model), budgetMs = 15_000, nowNanos = { now })
        // The check runs before each phrase: 0 s and 10 s are inside the budget, 20 s is not.
        assertEquals(2, model.calls)
        assertEquals(listOf("привет мир.", "как дела.", "раз два"), outcome.segments.map { it.text })
        assertNull(outcome.segments[2].rawText)
        assertEquals(input.map { listOf(it.speakerId, it.startMs, it.endMs) },
            outcome.segments.map { listOf(it.speakerId, it.startMs, it.endMs) })
        assertEquals(TextStageReport(TextStageStatus.COMPLETED, phrases = 3, fallbacks = 1, elapsedMs = 20_000, timedOut = 1), outcome.report)
        assertTrue(outcome.report.describe().contains("лимиту времени: 1"))
    }

    @Test fun aSpentBudgetOnTheFirstPhraseIsReportedAsFailedNotAsSuccess() {
        val model = ScriptedModel { _, tokens, wanted -> endsWithPeriod(tokens, wanted) }
        val input = listOf(segment("раз два"), segment("как дела"))
        val outcome = TextStages.present(input, stage(model), budgetMs = 0, nowNanos = { 0L })
        assertEquals(0, model.calls)
        assertEquals(input, outcome.segments)
        assertEquals(TextStageStatus.FAILED, outcome.report.status)
        assertEquals(2, outcome.report.timedOut)
        assertEquals(2, outcome.report.fallbacks)
        assertTrue(outcome.report.describe().contains("лимит времени"))
    }

    @Test fun withoutABudgetNothingTimesOutEvenOnAHugeClock() {
        var now = 0L
        val model = ScriptedModel { _, tokens, wanted -> now += 3_600_000_000_000L; endsWithPeriod(tokens, wanted) }
        val outcome = TextStages.present(listOf(segment("привет мир"), segment("как дела")), stage(model), nowNanos = { now })
        assertEquals(2, model.calls)
        assertEquals(0, outcome.report.timedOut)
    }

    @Test fun theBudgetGrowsWithTheRecordingButNeverDropsBelowTheFloor() {
        assertEquals(TextStages.MIN_BUDGET_MS, TextStages.budgetMs(0))
        assertEquals(TextStages.MIN_BUDGET_MS, TextStages.budgetMs(-5))
        assertEquals(TextStages.MIN_BUDGET_MS, TextStages.budgetMs(10_000))
        assertEquals(300_000L, TextStages.budgetMs(600_000))
        assertTrue(TextStages.budgetMs(7_200_000) > TextStages.budgetMs(600_000))
    }

    @Test fun reportsDescribeWhatHappenedWithoutAnyText() {
        assertTrue(TextStageReport(TextStageStatus.UNAVAILABLE).describe().contains("не установлена"))
        assertTrue(TextStageReport(TextStageStatus.FAILED).describe().contains("не сработала"))
        val done = TextStageReport(TextStageStatus.COMPLETED, phrases = 12, fallbacks = 2, elapsedMs = 3_140).describe()
        assertTrue(done, done.contains("10 из 12") && done.contains("3.1"))
    }
}
