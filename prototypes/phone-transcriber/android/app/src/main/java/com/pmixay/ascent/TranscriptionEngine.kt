package com.pmixay.ascent

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractor
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractorConfig
import java.io.File
import java.nio.FloatBuffer
import java.nio.LongBuffer
import kotlin.math.abs

/** Both inference passes run locally, sequentially, to limit simultaneous model memory. */
class TranscriptionEngine(private val modelDirectory: File) {
    fun transcribe(audio: File, onProgress: (String) -> Unit): List<TranscriptSegment> {
        checkCancelled()
        val model = ModelSpec.load(modelDirectory)
        return PcmWave(audio).use { wave ->
            val words = recognize(model, wave, onProgress)
            checkCancelled()
            if (words.isEmpty()) emptyList() else attribute(model, wave, words, onProgress)
        }
    }

    private fun recognize(model: ModelSpec, wave: PcmWave, progress: (String) -> Unit): List<TimedWord> {
        progress("Загружаю GigaAM…")
        val environment = OrtEnvironment.getEnvironment()
        environment.setTelemetry(false)
        val allWords = mutableListOf<TimedWord>()
        OrtSession.SessionOptions().use { options ->
            options.setIntraOpNumThreads(2)
            options.setInterOpNumThreads(1)
            environment.createSession(model.asrFile.absolutePath, options).use { session ->
                require(session.inputNames == setOf("audio", "audio_lengths")) { "Несовместимая модель GigaAM" }
                var coreStart = 0L
                val coreSize = 20 * RATE
                while (coreStart < wave.sampleCount) {
                    checkCancelled()
                    val coreEnd = (coreStart + coreSize).coerceAtMost(wave.sampleCount)
                    val start = (coreStart - RATE / 2).coerceAtLeast(0)
                    val end = (coreEnd + RATE / 2).coerceAtMost(wave.sampleCount)
                    val input = wave.read(start, (end - start).toInt())
                    val samples = if (input.size < 4000) input.copyOf(4000) else input
                    progress("Распознаю речь: ${(100 * coreStart / wave.sampleCount).toInt()}%")
                    OnnxTensor.createTensor(environment, FloatBuffer.wrap(samples), longArrayOf(1, samples.size.toLong())).use { signal ->
                        OnnxTensor.createTensor(environment, LongBuffer.wrap(longArrayOf(samples.size.toLong())), longArrayOf(1)).use { lengths ->
                            session.run(mapOf("audio" to signal, "audio_lengths" to lengths)).use { result ->
                                checkCancelled()
                                val logits = result.get("log_probs").get() as OnnxTensor
                                val shape = logits.info.shape
                                require(shape.size == 3 && shape[0] == 1L && shape[2] == model.tokens.size.toLong() + 1)
                                val count = ((result.get("encoded_lengths").get().value as LongArray)[0]).toInt()
                                require(count in 1..shape[1].toInt())
                                val vocabulary = shape[2].toInt()
                                val buffer = logits.floatBuffer
                                val ids = IntArray(count) { frame ->
                                    var best = 0
                                    var value = Float.NEGATIVE_INFINITY
                                    for (token in 0 until vocabulary) {
                                        val score = buffer.get(frame * vocabulary + token)
                                        if (score > value) { value = score; best = token }
                                    }
                                    best
                                }
                                val decoded = CtcDecoder.decode(ids, model.tokens, model.blankId, start * 1000 / RATE, samples.size * 1000L / RATE)
                                    .filter { it.startMs < wave.durationMs }
                                    .map { it.copy(endMs = it.endMs.coerceAtMost(wave.durationMs)) }
                                allWords += CtcDecoder.ownedBy(decoded, coreStart * 1000 / RATE, coreEnd * 1000 / RATE)
                            }
                        }
                    }
                    coreStart = coreEnd
                }
            }
        }
        return allWords.sortedBy { it.startMs }
    }

    private data class SpeakerWindow(val centerMs: Long, val vector: FloatArray)

    private fun attribute(model: ModelSpec, wave: PcmWave, words: List<TimedWord>, progress: (String) -> Unit): List<TranscriptSegment> {
        progress("Определяю говорящих…")
        val extractor = SpeakerEmbeddingExtractor(config = SpeakerEmbeddingExtractorConfig(
            model = model.speakerFile.absolutePath, numThreads = 2, debug = false, provider = "cpu",
        ))
        val windows = mutableListOf<SpeakerWindow>()
        val clusters = SpeakerClusterer()
        try {
            // Each word selects a 750 ms cell; 1.5 s context captures voice identity.
            // Speaker labels are session-local and overlapping voices are not separated.
            val centers = words.map { ((it.startMs + it.endMs) / 2 / 750) * 750 + 375 }.distinct()
            for ((index, center) in centers.withIndex()) {
                checkCancelled()
                progress("Определяю говорящих: ${index * 100 / centers.size}%")
                val start = ((center - 750).coerceAtLeast(0) * RATE / 1000)
                val end = ((center + 750) * RATE / 1000).coerceAtMost(wave.sampleCount)
                if (end - start < RATE / 2) continue
                val samples = wave.read(start, (end - start).toInt())
                val stream = extractor.createStream()
                try {
                    stream.acceptWaveform(samples, RATE)
                    stream.inputFinished()
                    if (extractor.isReady(stream)) {
                        val embedding = extractor.compute(stream)
                        if (embedding.isNotEmpty() && embedding.all { it.isFinite() }) {
                            clusters.add(embedding)
                            windows += SpeakerWindow(center, embedding)
                        }
                    }
                } finally { stream.release() }
            }
        } finally { extractor.release() }
        checkCancelled()
        val labels = windows.map { clusters.classify(it.vector) }
        val result = mutableListOf<TranscriptSegment>()
        for (word in words) {
            val center = (word.startMs + word.endMs) / 2
            val nearest = windows.indices.minByOrNull { abs(windows[it].centerMs - center) }
            val label = if (nearest != null && abs(windows[nearest].centerMs - center) <= 1500) labels[nearest] else SpeakerClusterer.UNKNOWN
            val previous = result.lastOrNull()
            if (previous != null && previous.speakerId == label && word.startMs - previous.endMs <= 1500 && word.endMs - previous.startMs <= 20000) {
                result[result.lastIndex] = previous.copy(endMs = word.endMs.coerceAtLeast(previous.endMs), text = previous.text + " " + word.text)
            } else result += TranscriptSegment(label, word.startMs, word.endMs, word.text)
        }
        progress("Расшифровка готова")
        return result
    }

    private fun checkCancelled() {
        if (Thread.currentThread().isInterrupted) throw InterruptedException("Обработка отменена")
    }

    companion object { private const val RATE = 16000 }
}
