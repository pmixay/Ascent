package com.pmixay.ascent

import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineTransducerModelConfig
import java.io.Closeable
import java.io.File
import java.util.Locale

/**
 * Recognition modes chosen by the user; there is no automatic language identification.
 * See docs/interfaces/multilingual-recognizer.md for the measurements behind the choice.
 */
enum class RecognitionMode {
    /** GigaAM Multilingual CTC: Russian and CIS languages. The default. */
    RUSSIAN_CIS,

    /** NVIDIA Parakeet TDT 0.6B v3 through sherpa-onnx: English and other European languages. */
    MULTILINGUAL,
}

/** Pinned Parakeet TDT 0.6B v3 int8 files for the multilingual mode (CC-BY-4.0). */
object ParakeetFiles {
    const val DIRECTORY = "multilingual-asr"
    const val REPOSITORY = "csukuangfj/sherpa-onnx-nemo-parakeet-tdt-0.6b-v3-int8"
    const val REVISION = "ed5d745623472f97cfc2e6c2916ee248798fc5a8"
    const val UPSTREAM = "nvidia/parakeet-tdt-0.6b-v3"
    const val LICENSE = "CC-BY-4.0"

    data class Asset(val name: String, val bytes: Long, val sha256: String)

    val ENCODER = Asset("encoder.int8.onnx", 652_282_294L, "1afbe69c1ffdabee94755f42e4daf1d93549131a3da11356bda84fcef0f2c479")
    val DECODER = Asset("decoder.int8.onnx", 11_845_274L, "0222a86147299d4dee90edf95b6d13827edb42ad8b7c9cf0b2582f030937c0b5")
    val JOINER = Asset("joiner.int8.onnx", 6_355_277L, "cbd02f3696e9944970111fb439d9c3fd365a80374565f13f2b9c7007d253fc98")
    val TOKENS = Asset("tokens.txt", 93_939L, "d58544679ea4bc6ac563d1f545eb7d474bd6cfa467f0a6e2c1dc1c7d37e3c35d")
    val ALL = listOf(ENCODER, DECODER, JOINER, TOKENS)

    /**
     * Every pinned file is present with its pinned size. Hashes are checked when the files are
     * extracted or installed; a size mismatch here means a missing or interrupted install.
     */
    fun installed(directory: File?): Boolean =
        directory != null && ALL.all { File(directory, it.name).let { file -> file.isFile && file.length() == it.bytes } }
}

/**
 * Timed words from sherpa-onnx token output, in the same form the GigaAM path produces:
 * lowercase, without punctuation, so the stitcher, speaker attribution, segmenter and the
 * text stage treat both modes alike. Parakeet's own punctuation and capitals are dropped;
 * the text stage adds marks and capitals for both modes.
 */
object ParakeetWords {
    private const val FALLBACK_TOKEN_MS = 80L

    /**
     * [starts] and [durations] are seconds from the window start ([durations] may be empty).
     * A token that begins with a space or `▁` starts a word; other tokens continue it.
     */
    fun words(tokens: Array<String>, starts: FloatArray, durations: FloatArray, offsetMs: Long, limitMs: Long): List<TimedWord> {
        require(starts.size == tokens.size && (durations.isEmpty() || durations.size == tokens.size))
        val words = mutableListOf<TimedWord>()
        val text = StringBuilder()
        var startMs = -1L
        var endMs = -1L
        fun flush() {
            val word = normalize(text.toString())
            // Like the GigaAM path, a word that starts after the recording ends is dropped.
            if (word.isNotEmpty() && startMs in 0 until limitMs) {
                words += TimedWord(word, startMs, endMs.coerceIn(startMs + 1, limitMs))
            }
            text.clear()
            startMs = -1L
            endMs = -1L
        }
        for (index in tokens.indices) {
            val token = tokens[index]
            if (token.startsWith(' ') || token.startsWith('▁')) flush()
            text.append(token)
            if (normalize(token).isEmpty()) continue  // punctuation does not move word times
            val tokenStart = offsetMs + Math.round(starts[index] * 1000.0)
            val tokenEnd = if (durations.isNotEmpty()) offsetMs + Math.round((starts[index] + durations[index]) * 1000.0)
            else starts.getOrNull(index + 1)?.let { offsetMs + Math.round(it * 1000.0) } ?: (tokenStart + FALLBACK_TOKEN_MS)
            if (startMs < 0) startMs = tokenStart
            endMs = maxOf(endMs, tokenEnd, tokenStart + 1)
        }
        flush()
        return words
    }

    /** Lowercase letters and digits; an inner apostrophe or hyphen stays ("don't", "кто-то"). */
    internal fun normalize(raw: String): String {
        val kept = raw.replace('▁', ' ').lowercase(Locale.ROOT)
            .filter { it.isLetterOrDigit() || it == '\'' || it == '’' || it == '-' }
            .replace('’', '\'')
        return kept.trim('\'', '-')
    }
}

/** Parakeet TDT 0.6B v3 over one bounded window at a time. Not thread-safe; one per job. */
internal class ParakeetRecognizer(directory: File, threads: Int = 2) : Closeable {
    private val recognizer = OfflineRecognizer(config = OfflineRecognizerConfig(
        featConfig = FeatureConfig(sampleRate = AsrChunking.SAMPLE_RATE, featureDim = 80),
        modelConfig = OfflineModelConfig(
            transducer = OfflineTransducerModelConfig(
                encoder = File(directory, ParakeetFiles.ENCODER.name).absolutePath,
                decoder = File(directory, ParakeetFiles.DECODER.name).absolutePath,
                joiner = File(directory, ParakeetFiles.JOINER.name).absolutePath,
            ),
            tokens = File(directory, ParakeetFiles.TOKENS.name).absolutePath,
            modelType = "nemo_transducer",
            numThreads = threads,
            provider = "cpu",
            debug = false,
        ),
        decodingMethod = "greedy_search",
    ))

    /** Words of [samples] with times offset by [offsetMs] and clamped to [limitMs]. */
    fun recognize(samples: FloatArray, offsetMs: Long, limitMs: Long): List<TimedWord> {
        val stream = recognizer.createStream()
        try {
            stream.acceptWaveform(samples, AsrChunking.SAMPLE_RATE)
            recognizer.decode(stream)
            val result = recognizer.getResult(stream)
            return ParakeetWords.words(result.tokens, result.timestamps, result.durations, offsetMs, limitMs)
        } finally {
            stream.release()
        }
    }

    override fun close() = recognizer.release()
}
