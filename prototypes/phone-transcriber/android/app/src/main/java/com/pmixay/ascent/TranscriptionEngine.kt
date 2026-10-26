package com.pmixay.ascent

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractor
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractorConfig
import java.io.File
import java.nio.FloatBuffer
import java.nio.LongBuffer

data class TranscriptionResult(val segments: List<TranscriptSegment>, val signatures: List<VoiceSignature>)

/** Both inference passes run locally, sequentially, to limit simultaneous model memory. */
class TranscriptionEngine(private val modelDirectory: File) {
    fun transcribe(audio: File, onProgress: (String) -> Unit): List<TranscriptSegment> =
        transcribeWithSpeakers(audio, onProgress).segments

    fun transcribeWithSpeakers(audio: File, onProgress: (String) -> Unit): TranscriptionResult {
        checkCancelled()
        val model = ModelSpec.load(modelDirectory)
        return PcmWave(audio).use { wave ->
            val words = recognize(model, wave, onProgress)
            checkCancelled()
            if (words.isEmpty()) TranscriptionResult(emptyList(), emptyList()) else attribute(model, wave, words, onProgress)
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
                while (coreStart < wave.sampleCount) {
                    checkCancelled()
                    val window = AsrChunking.next(coreStart, wave.sampleCount, wave::read)
                    val coreEnd = window.coreEnd
                    val start = window.start
                    val end = window.end
                    val input = wave.read(start, (end - start).toInt())
                    val samples = if (input.size < 4000) input.copyOf(4000) else input
                    progress("Распознаю речь: ${(100 * coreStart / wave.sampleCount).toInt()}%")
                    try {
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
                    } finally {
                        samples.fill(0f)
                        if (input !== samples) input.fill(0f)
                    }
                    coreStart = coreEnd
                }
            }
        }
        return allWords.sortedBy { it.startMs }
    }

    private fun attribute(model: ModelSpec, wave: PcmWave, words: List<TimedWord>, progress: (String) -> Unit): TranscriptionResult {
        progress("Определяю говорящих…")
        val extractor = SpeakerEmbeddingExtractor(config = SpeakerEmbeddingExtractorConfig(
            model = model.speakerFile.absolutePath, numThreads = 2, debug = false, provider = "cpu",
        ))
        val windows = mutableListOf<SpeakerObservation>()
        val regions = mutableListOf<Int>()
        try {
            val contexts = SpeakerSpeechSupport.contexts(words, wave.durationMs)
            for ((index, context) in contexts.withIndex()) {
                checkCancelled()
                progress("Определяю говорящих: ${index * 100 / contexts.size}%")
                val start = context.startMs * RATE / 1000
                val end = (context.endMs * RATE / 1000).coerceAtMost(wave.sampleCount)
                val samples = wave.read(start, (end - start).toInt())
                try {
                    val activity = SpeakerSpeechSupport.activity(samples, context.startMs)
                    val speechMs = activity.spans.sumOf { it.endMs - it.startMs }
                    // A short reply can match an established voice without teaching a new one.
                    if (speechMs < 320 || activity.quality < 0.20f) continue
                    val stream = extractor.createStream()
                    try {
                        stream.acceptWaveform(samples, RATE)
                        stream.inputFinished()
                        if (extractor.isReady(stream)) {
                            val embedding = extractor.compute(stream)
                            if (embedding.size == 192 && embedding.all { it.isFinite() }) {
                                windows += SpeakerObservation(context.startMs, context.endMs, embedding,
                                    activity.quality, speechMs >= 800, activity.spans)
                                regions += context.region
                            }
                        }
                    } finally { stream.release() }
                } finally { samples.fill(0f) }
            }
        } finally { extractor.release() }
        checkCancelled()
        try {
            val stable = SpeakerEvidence.stableSeeds(windows, regions)
            val clustered = SpeakerClusterer().cluster(stable)
            val labels = SpeakerSpeechSupport.speakersForWords(words, stable, clustered.assignments)
            val result = mutableListOf<TranscriptSegment>()
            for ((index, word) in words.withIndex()) {
                checkCancelled()
                val label = labels[index]
                val previous = result.lastOrNull()
                if (previous != null && previous.speakerId == label && word.startMs - previous.endMs <= 700 &&
                    word.endMs - previous.startMs <= 20000) {
                    result[result.lastIndex] = previous.copy(endMs = word.endMs.coerceAtLeast(previous.endMs),
                        text = previous.text + " " + word.text)
                } else result += TranscriptSegment(label, word.startMs, word.endMs, word.text)
            }
            val used = result.map { it.speakerId }.toSet()
            progress("Расшифровка готова")
            return TranscriptionResult(result, clustered.signatures.filter { it.speakerId in used })
        } finally { windows.forEach { it.vector.fill(0f) } }
    }

    private fun checkCancelled() {
        if (Thread.currentThread().isInterrupted) throw InterruptedException("Обработка отменена")
    }

    companion object { private const val RATE = 16000 }
}
