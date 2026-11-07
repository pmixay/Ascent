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

/**
 * Both inference passes run locally, sequentially, to limit simultaneous model memory.
 * [mode] selects the recognizer; speaker attribution always uses the pack in [modelDirectory].
 * [MULTILINGUAL][RecognitionMode.MULTILINGUAL] needs the Parakeet files in [multilingualDirectory].
 */
class TranscriptionEngine(
    private val modelDirectory: File,
    private val mode: RecognitionMode = RecognitionMode.RUSSIAN_CIS,
    private val multilingualDirectory: File? = null,
) {
    fun transcribe(audio: File, onProgress: (String) -> Unit): List<TranscriptSegment> =
        transcribeWithSpeakers(audio, onProgress).segments

    fun transcribeWithSpeakers(audio: File, onProgress: (String) -> Unit): TranscriptionResult {
        return PcmWave(audio).use { wave ->
            transcribeWithSpeakers(wave, onProgress)
        }
    }

    fun transcribeWithSpeakers(source: PcmWindowSource, onProgress: (String) -> Unit): TranscriptionResult {
        checkCancelled()
        val model = ModelSpec.load(modelDirectory)
        val words = when (mode) {
            RecognitionMode.RUSSIAN_CIS -> recognize(model, source, onProgress)
            RecognitionMode.MULTILINGUAL -> recognizeMultilingual(source, onProgress)
        }
        checkCancelled()
        return if (words.isEmpty()) TranscriptionResult(emptyList(), emptyList())
        else attribute(model, source, words, onProgress)
    }

    private fun recognize(model: ModelSpec, wave: PcmWindowSource, progress: (String) -> Unit): List<TimedWord> {
        progress("Загружаю GigaAM…")
        val environment = OrtEnvironment.getEnvironment()
        environment.setTelemetry(false)
        val stitcher = CtcChunkStitcher()
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
                    try {
                        // Recognizer input only: speaker embeddings read the original source again.
                        AsrGain.normalizeInPlace(samples)
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
                                    stitcher.append(decoded, coreStart * 1000 / RATE, coreEnd * 1000 / RATE,
                                        start * 1000 / RATE, end * 1000 / RATE)
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
        return stitcher.finish()
    }

    /**
     * Same bounded windows and stitching as the GigaAM path. No level normalization: Parakeet was
     * measured without it, and its effect on this model is unmeasured.
     */
    private fun recognizeMultilingual(wave: PcmWindowSource, progress: (String) -> Unit): List<TimedWord> {
        val directory = multilingualDirectory
        require(ParakeetFiles.installed(directory)) { "Мультиязычная модель не установлена" }
        progress("Загружаю мультиязычную модель…")
        val stitcher = CtcChunkStitcher()
        ParakeetRecognizer(requireNotNull(directory)).use { recognizer ->
            var coreStart = 0L
            while (coreStart < wave.sampleCount) {
                checkCancelled()
                val window = AsrChunking.next(coreStart, wave.sampleCount, wave::read)
                val samples = wave.read(window.start, (window.end - window.start).toInt())
                try {
                    progress("Распознаю речь: ${(100 * coreStart / wave.sampleCount).toInt()}%")
                    val decoded = recognizer.recognize(samples, window.start * 1000 / RATE, wave.durationMs)
                    checkCancelled()
                    stitcher.append(decoded, window.coreStart * 1000 / RATE, window.coreEnd * 1000 / RATE,
                        window.start * 1000 / RATE, window.end * 1000 / RATE)
                } finally {
                    samples.fill(0f)
                }
                coreStart = window.coreEnd
            }
        }
        return stitcher.finish()
    }

    private fun attribute(model: ModelSpec, wave: PcmWindowSource, words: List<TimedWord>, progress: (String) -> Unit): TranscriptionResult {
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
            val attribution = SpeakerAttribution.complete(words, stable, clustered)
            checkCancelled()
            val result = TranscriptSegmenter.segment(words, attribution.speakerIds, attribution.inferred)
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
