package com.pmixay.ascent

import java.io.File
import java.util.Locale

enum class TextStageStatus {
    /** The model ran; [TextStageReport.fallbacks] phrases kept their recognized words. */
    COMPLETED,
    /** There was no speech to present. */
    SKIPPED,
    /** The model files are not installed, so the stage did not run. Never reported as success. */
    UNAVAILABLE,
    /** The model loaded or ran but produced nothing usable; every phrase kept its recognized words. */
    FAILED,
}

/**
 * What the text stage did for one job: counts and time only, never any text. [timedOut] counts phrases
 * left unedited because the stage's time budget was spent; they are also included in [fallbacks].
 */
data class TextStageReport(
    val status: TextStageStatus,
    val phrases: Int = 0,
    val fallbacks: Int = 0,
    val elapsedMs: Long = 0,
    val timedOut: Int = 0,
) {
    fun describe(): String = when (status) {
        TextStageStatus.COMPLETED -> "Текстовая модель: ${phrases - fallbacks} из $phrases фраз обработано, " +
            "оставлено без правки: $fallbacks, ${String.format(Locale.ROOT, "%.1f", elapsedMs / 1000.0)} с" +
            (if (timedOut > 0) ", из них по лимиту времени: $timedOut" else "")
        TextStageStatus.SKIPPED -> "Текстовая модель: текста нет"
        TextStageStatus.UNAVAILABLE -> "Текстовая модель не установлена, показан распознанный текст"
        TextStageStatus.FAILED ->
            if (timedOut > 0) "Текстовая модель не сработала или не уложилась в лимит времени, показан распознанный текст"
            else "Текстовая модель не сработала, показан распознанный текст"
    }
}

internal class TextStageOutcome(val segments: List<TranscriptSegment>, val report: TextStageReport)

/**
 * The local text model's role in the pipeline: punctuation and capitals over the recognized phrases.
 * It runs after the acoustic sessions are closed. Whatever goes wrong, a phrase keeps the words the
 * recognizer produced; the transcript is never lost to this stage.
 */
internal object TextStages {
    private const val NANOS_PER_MS = 1_000_000L

    /**
     * Time budget for presenting phrases, counted after the model has loaded so that a slow load can
     * never stop the model from running at all. The edit is presentation only (the export carries the
     * recognized words), so a spent budget costs punctuation, never words. These two numbers are a policy
     * default, not a measurement: no ARM timing exists yet. The floor lets a short clip be punctuated
     * completely even at ten times the measured host cost (added RTF 0.081); the share bounds the added
     * real-time factor on long recordings. Tune both from the device timings, not from this comment.
     */
    const val MIN_BUDGET_MS = 30_000L
    const val BUDGET_PERCENT_OF_MEDIA = 50L

    fun budgetMs(mediaDurationMs: Long): Long =
        maxOf(MIN_BUDGET_MS, mediaDurationMs.coerceAtLeast(0) * BUDGET_PERCENT_OF_MEDIA / 100)

    private class Files(val model: File, val tokenizer: File)

    private fun locate(directory: File?): Files? {
        if (directory == null) return null
        val model = File(directory, TextModelFiles.MODEL)
        val tokenizer = File(directory, TextModelFiles.TOKENIZER)
        // Hashes are checked when the files are extracted; a size mismatch means an interrupted extraction.
        return if (model.length() == TextModelFiles.MODEL_BYTES && tokenizer.length() == TextModelFiles.TOKENIZER_BYTES) {
            Files(model, tokenizer)
        } else null
    }

    /**
     * Blocking; call off the main thread while holding the paired [ModelLease]. A null text directory
     * is an acoustic-only revision, never permission to open an unversioned or newer text model.
     * A cancelled job surfaces as [InterruptedException]. [mediaDurationMs] sizes the time budget
     * ([budgetMs]); phrases reached after it is spent keep their recognized words and are reported.
     */
    // Tests may rethrow failures on synthetic input to expose native/runtime integration errors.
    // Production keeps the fallback and never logs exception messages or recognized text.
    fun run(
        directory: File?, segments: List<TranscriptSegment>, timings: ProcessingTimings? = null,
        mediaDurationMs: Long = 0, onFailure: (Throwable) -> Unit = {},
    ): TextStageOutcome {
        if (Thread.currentThread().isInterrupted) throw InterruptedException("Обработка отменена")
        if (segments.none { it.text.isNotBlank() }) return TextStageOutcome(segments, TextStageReport(TextStageStatus.SKIPPED))
        val files = locate(directory) ?: return TextStageOutcome(segments, TextStageReport(TextStageStatus.UNAVAILABLE))
        val started = System.nanoTime()
        return try {
            val tokenizer = measureProcessing(timings, ProcessingMetricStage.TEXT_MODEL_LOAD) { QwenTokenizer.load(files.tokenizer) }
            val outcome = measureProcessing(timings, ProcessingMetricStage.TEXT_MODEL_LOAD) {
                OnnxTextModel(files.model, TextPunctuator.instructionPrefix(tokenizer))
            }.use { model ->
                present(segments, TextPunctuator(tokenizer, model), budgetMs(mediaDurationMs), onFailure = onFailure)
            }
            // Include tokenizer/session initialization and teardown in the stage's reported time.
            TextStageOutcome(outcome.segments, outcome.report.copy(elapsedMs = (System.nanoTime() - started) / 1_000_000))
        } catch (error: InterruptedException) {
            throw error
        } catch (error: Throwable) {
            onFailure(error)
            TextStageOutcome(segments, TextStageReport(TextStageStatus.FAILED, elapsedMs = (System.nanoTime() - started) / 1_000_000))
        }
    }

    /**
     * Punctuates every phrase with [stage]. The edit is kept only when the independent lexical check
     * confirms the same words in the same order; the recognized text moves to [TranscriptSegment.rawText].
     * Speakers, times and attribution flags are copied untouched.
     *
     * Once [budgetMs] has elapsed the model is not asked again: every remaining phrase keeps its recognized
     * words and is counted in both `fallbacks` and `timedOut`. The limit is checked between phrases, so the
     * stage can exceed it by at most one phrase (a phrase is a few seconds of speech, at most 20 s). [onFailure]
     * stays the last parameter so that a trailing lambda still binds to it. [nowNanos] exists for tests.
     */
    fun present(
        segments: List<TranscriptSegment>, stage: TextPunctuator,
        budgetMs: Long = Long.MAX_VALUE, nowNanos: () -> Long = System::nanoTime,
        onFailure: (Throwable) -> Unit = {},
    ): TextStageOutcome {
        val started = nowNanos()
        val limitNanos = if (budgetMs >= Long.MAX_VALUE / NANOS_PER_MS) Long.MAX_VALUE else budgetMs * NANOS_PER_MS
        var attempted = 0
        var fallbacks = 0
        var timedOut = 0
        val shown = segments.map { segment ->
            val words = segment.text.split(' ').filter { it.isNotEmpty() }
            if (words.isEmpty()) return@map segment
            attempted++
            if (nowNanos() - started >= limitNanos) {
                fallbacks++
                timedOut++
                return@map segment
            }
            try {
                val edited = TextPunctuator.render(words, stage.styles(words))
                if (TextPunctuator.preservesWords(segment.text, edited)) {
                    segment.copy(text = edited, rawText = segment.text.takeIf { it != edited })
                } else {
                    fallbacks++
                    segment
                }
            } catch (error: InterruptedException) {
                throw error
            } catch (error: Exception) {
                onFailure(error)
                fallbacks++
                segment
            }
        }
        val status = if (attempted > 0 && fallbacks == attempted) TextStageStatus.FAILED else TextStageStatus.COMPLETED
        return TextStageOutcome(shown, TextStageReport(status, attempted, fallbacks, (nowNanos() - started) / NANOS_PER_MS, timedOut))
    }
}
