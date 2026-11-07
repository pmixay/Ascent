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

/** What the text stage did for one job: counts and time only, never any text. */
data class TextStageReport(
    val status: TextStageStatus,
    val phrases: Int = 0,
    val fallbacks: Int = 0,
    val elapsedMs: Long = 0,
) {
    fun describe(): String = when (status) {
        TextStageStatus.COMPLETED -> "Текстовая модель: ${phrases - fallbacks} из $phrases фраз обработано, " +
            "оставлено без правки: $fallbacks, ${String.format(Locale.ROOT, "%.1f", elapsedMs / 1000.0)} с"
        TextStageStatus.SKIPPED -> "Текстовая модель: текста нет"
        TextStageStatus.UNAVAILABLE -> "Текстовая модель не установлена, показан распознанный текст"
        TextStageStatus.FAILED -> "Текстовая модель не сработала, показан распознанный текст"
    }
}

internal class TextStageOutcome(val segments: List<TranscriptSegment>, val report: TextStageReport)

/**
 * The local text model's role in the pipeline: punctuation and capitals over the recognized phrases.
 * It runs after the acoustic sessions are closed. Whatever goes wrong, a phrase keeps the words the
 * recognizer produced; the transcript is never lost to this stage.
 */
internal object TextStages {
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
     * A cancelled job surfaces as [InterruptedException].
     */
    fun run(directory: File?, segments: List<TranscriptSegment>): TextStageOutcome {
        if (Thread.currentThread().isInterrupted) throw InterruptedException("Обработка отменена")
        if (segments.none { it.text.isNotBlank() }) return TextStageOutcome(segments, TextStageReport(TextStageStatus.SKIPPED))
        val files = locate(directory) ?: return TextStageOutcome(segments, TextStageReport(TextStageStatus.UNAVAILABLE))
        val started = System.nanoTime()
        return try {
            val tokenizer = QwenTokenizer.load(files.tokenizer)
            OnnxTextModel(files.model, TextPunctuator.instructionPrefix(tokenizer)).use { model ->
                present(segments, TextPunctuator(tokenizer, model))
            }
        } catch (error: InterruptedException) {
            throw error
        } catch (error: Throwable) {
            TextStageOutcome(segments, TextStageReport(TextStageStatus.FAILED, elapsedMs = (System.nanoTime() - started) / 1_000_000))
        }
    }

    /**
     * Punctuates every phrase with [stage]. The edit is kept only when the independent lexical check
     * confirms the same words in the same order; the recognized text moves to [TranscriptSegment.rawText].
     * Speakers, times and attribution flags are copied untouched.
     */
    fun present(segments: List<TranscriptSegment>, stage: TextPunctuator): TextStageOutcome {
        val started = System.nanoTime()
        var attempted = 0
        var fallbacks = 0
        val shown = segments.map { segment ->
            val words = segment.text.split(' ').filter { it.isNotEmpty() }
            if (words.isEmpty()) return@map segment
            attempted++
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
                fallbacks++
                segment
            }
        }
        val status = if (attempted > 0 && fallbacks == attempted) TextStageStatus.FAILED else TextStageStatus.COMPLETED
        return TextStageOutcome(shown, TextStageReport(status, attempted, fallbacks, (System.nanoTime() - started) / 1_000_000))
    }
}
