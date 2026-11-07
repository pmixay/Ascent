package com.pmixay.ascent

import java.io.Closeable
import java.util.Locale

/** Exclusive wall-time buckets, not CPU time. Uninstrumented work remains visible as overhead. */
enum class ProcessingMetricStage(val label: String) {
    QUEUE("Ожидание обработки"),
    MODEL_VALIDATION("Проверка моделей"),
    SOURCE_OPEN("Открытие источника"),
    INPUT_DECODE("Чтение и декодирование окон"),
    ASR_MODEL_LOAD("Загрузка модели речи"),
    ASR("Распознавание речи"),
    SPEAKER_MODEL_LOAD("Загрузка модели участников"),
    DIARIZATION("Разделение участников"),
    TEXT_MODEL_LOAD("Подготовка текстовой модели"),
    TEXT("Обработка текста"),
    SAVING("Сохранение результата"),
    CLEANUP("Завершение и очистка"),
    OVERHEAD("Остальные операции"),
}

enum class ProcessingMeasurementOutcome { COMPLETED, CANCELLED, FAILED }

data class ProcessingStageMeasurement(val elapsedNs: Long, val calls: Int)

/** In-memory performance counters only. Never attach source identifiers, text or audio. */
data class ProcessingMeasurementReport(
    val outcome: ProcessingMeasurementOutcome,
    val totalNs: Long,
    val mediaDurationMs: Long?,
    val stages: Map<ProcessingMetricStage, ProcessingStageMeasurement>,
) {
    /** Partial/failed jobs and unknown/empty media have no completed-processing RTF. */
    val realTimeFactor: Double? get() = mediaDurationMs?.takeIf {
        outcome == ProcessingMeasurementOutcome.COMPLETED && it > 0
    }?.let { totalNs.toDouble() / (it.toDouble() * 1_000_000.0) }

    fun summary(): String = buildString {
        append(when (outcome) {
            ProcessingMeasurementOutcome.COMPLETED -> "Время обработки: "
            ProcessingMeasurementOutcome.CANCELLED -> "Время до отмены: "
            ProcessingMeasurementOutcome.FAILED -> "Время до ошибки: "
        })
        append(seconds(totalNs)).append(" с")
        mediaDurationMs?.takeIf { it >= 0 }?.let {
            append("\nДлительность аудио: ").append(String.format(Locale.ROOT, "%.3f", it / 1000.0)).append(" с")
        }
        realTimeFactor?.let { append("\nRTF: ").append(String.format(Locale.ROOT, "%.3f", it)) }
    }

    fun stageDetails(): String = buildString {
        for (stage in ProcessingMetricStage.entries) {
            val measured = stages[stage] ?: continue
            append(stage.label).append(": ").append(seconds(measured.elapsedNs)).append(" с\n")
        }
        append("Этапы указаны без двойного счёта. Время включает ожидание, загрузку моделей, сохранение и очистку. ")
        append("Подготовка встроенных файлов моделей и запись микрофона в него не входят. ")
        append("Измерения хранятся только в памяти приложения.")
    }

    fun describe(): String = summary() + "\n" + stageDetails()

    private fun seconds(nanos: Long): String = String.format(Locale.ROOT, "%.3f", nanos / 1_000_000_000.0)
}

/**
 * One collector per serialized job. A child scope pauses its parent's clock, so time spent decoding
 * inside ASR/diarization is counted once. Scopes can span coroutine dispatches, but never parallel work.
 * No callbacks, logs, files, samples, source names or retained transcript references.
 */
class ProcessingTimings(private val nanoTime: () -> Long = System::nanoTime) {
    private val startedNs = nanoTime()
    private var checkpointNs = startedNs
    private val elapsed = LongArray(ProcessingMetricStage.entries.size)
    private val calls = IntArray(ProcessingMetricStage.entries.size).also { it[ProcessingMetricStage.OVERHEAD.ordinal] = 1 }
    private val stack = mutableListOf<Scope>()
    private var completed: ProcessingMeasurementReport? = null

    inner class Scope internal constructor(internal val stage: ProcessingMetricStage) : Closeable {
        internal var closed = false
        override fun close() = closeScope(this)
    }

    private fun checkpoint(now: Long) {
        val stage = stack.lastOrNull()?.stage ?: ProcessingMetricStage.OVERHEAD
        elapsed[stage.ordinal] += now - checkpointNs
        checkpointNs = now
    }

    @Synchronized fun enter(stage: ProcessingMetricStage): Scope {
        check(completed == null) { "Measurements are already complete" }
        checkpoint(nanoTime())
        calls[stage.ordinal]++
        return Scope(stage).also(stack::add)
    }

    @Synchronized private fun closeScope(scope: Scope) {
        if (scope.closed) return
        check(stack.lastOrNull() === scope) { "Measurement scopes must close in reverse order" }
        checkpoint(nanoTime())
        stack.removeAt(stack.lastIndex)
        scope.closed = true
    }

    fun <T> measure(stage: ProcessingMetricStage, block: () -> T): T = enter(stage).use { block() }

    @Synchronized fun finish(outcome: ProcessingMeasurementOutcome, mediaDurationMs: Long?): ProcessingMeasurementReport {
        completed?.let { return it }
        check(stack.isEmpty()) { "Cannot finish active measurement scopes" }
        val now = nanoTime()
        checkpoint(now)
        val measured = ProcessingMetricStage.entries.filter { calls[it.ordinal] > 0 }.associateWith {
            ProcessingStageMeasurement(elapsed[it.ordinal], calls[it.ordinal])
        }
        return ProcessingMeasurementReport(outcome, now - startedNs, mediaDurationMs, measured).also { completed = it }
    }
}

internal fun <T> measureProcessing(timings: ProcessingTimings?, stage: ProcessingMetricStage, block: () -> T): T =
    if (timings == null) block() else timings.measure(stage, block)

/** Transparent, bounded source adapter; every original read/offset/array/close is preserved. */
internal class MeasuredPcmWindowSource(
    private val source: PcmWindowSource,
    private val timings: ProcessingTimings,
) : PcmWindowSource {
    override val sampleCount: Long get() = source.sampleCount
    override val durationMs: Long get() = source.durationMs
    override val formatDescription: String get() = source.formatDescription
    override fun read(start: Long, count: Int): FloatArray = timings.measure(ProcessingMetricStage.INPUT_DECODE) {
        source.read(start, count)
    }
    override fun close() = source.close()
}
