package com.pmixay.ascent

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import java.io.File
import java.nio.FloatBuffer
import java.nio.LongBuffer

/**
 * Pinned local text model: Qwen2.5 0.5B Instruct, int8 ONNX export
 * (`onnx-community/Qwen2.5-0.5B-Instruct`, Apache-2.0). Packaging copies these exact files
 * into [DIRECTORY] and checks the hashes at build time; this class does not download anything.
 */
object TextModelFiles {
    const val DIRECTORY = "text-model"
    const val REPOSITORY = "onnx-community/Qwen2.5-0.5B-Instruct"
    const val REVISION = "22942cb7d7ba4cc81bb4673549ca4d4614469b5e"
    const val MODEL = "model_int8.onnx"
    const val MODEL_SOURCE = "onnx/model_int8.onnx"
    const val MODEL_BYTES = 512_096_557L
    const val MODEL_SHA256 = "41834041ab1b29eff9fc592f1a29a1844133aea35832ea9fa91682be13016100"
    const val TOKENIZER = "tokenizer.json"
    const val TOKENIZER_BYTES = 7_031_673L
    const val TOKENIZER_SHA256 = "a8506e7111b80c6d8635951a02eab0f4e1a8e4e5772da83846579e97b16f61bf"
}

/**
 * Runs the text model with the same ONNX Runtime the recognizer uses, so no new native library
 * enters the APK. The instruction prefix is computed once and its key/value cache reused for
 * every phrase. Tokens go through the model in blocks of [BLOCK] with the cache carried over,
 * so logits memory per call stays bounded (about 15 MB) whatever the phrase length; only the
 * requested scores are copied out. Not thread-safe: one stage instance per processing job.
 */
class OnnxTextModel(modelFile: File, prefix: IntArray, threads: Int = 2) : TextModelScores {
    private val environment = OrtEnvironment.getEnvironment().also { it.setTelemetry(false) }
    private val options = OrtSession.SessionOptions().apply {
        setIntraOpNumThreads(threads)
        setInterOpNumThreads(1)
    }
    private val session: OrtSession = environment.createSession(modelFile.absolutePath, options)
    private val pastNames: List<String> = session.inputNames.filter { it.startsWith("past_key_values.") }.sorted()
    private val prefixLength = prefix.size
    private lateinit var prefixCache: Map<String, OnnxTensor>

    init {
        try {
            require(prefix.isNotEmpty())
            require(pastNames.isNotEmpty() && session.inputNames == pastNames.toSet() + BASE_INPUTS) { "Несовместимая текстовая модель" }
            require("logits" in session.outputNames && pastNames.all { present(it) in session.outputNames }) { "Несовместимая текстовая модель" }
            val shape = (session.inputInfo.getValue(pastNames[0]).info as TensorInfo).shape
            require(shape.size == 4 && shape[1] > 0 && shape[3] > 0) { "Несовместимая текстовая модель" }
            val empty = pastNames.associateWith {
                OnnxTensor.createTensor(environment, FloatBuffer.allocate(0), longArrayOf(1, shape[1], 0, shape[3]))
            }
            try {
                prefixCache = chained(prefix, empty, 0, emptyMap(), HashMap()) { cache ->
                    cache.mapValues { (_, tensor) -> OnnxTensor.createTensor(environment, tensor.floatBuffer, tensor.info.shape) }
                }
            } finally { empty.values.forEach(OnnxTensor::close) }
        } catch (error: Throwable) {
            session.close()
            options.close()
            throw error
        }
    }

    override fun nextTokenScores(tokens: IntArray, wanted: Map<Int, IntArray>): Map<Int, FloatArray> {
        require(tokens.isNotEmpty() && wanted.keys.all { it in tokens.indices })
        val scores = HashMap<Int, FloatArray>()
        chained(tokens, prefixCache, prefixLength, wanted, scores) { }
        return scores
    }

    /**
     * Feeds [tokens] after a cache of [startLength] positions, block by block. Each block's
     * `present` tensors become the next block's past; the previous result is closed only after
     * the next run has finished with it. [finish] sees the final cache before it is closed.
     */
    private fun <T> chained(
        tokens: IntArray, start: Map<String, OnnxTensor>, startLength: Int,
        wanted: Map<Int, IntArray>, scores: MutableMap<Int, FloatArray>,
        finish: (Map<String, OnnxTensor>) -> T,
    ): T {
        var past = start
        var length = startLength
        var previous: OrtSession.Result? = null
        try {
            var offset = 0
            while (offset < tokens.size) {
                if (Thread.currentThread().isInterrupted) throw InterruptedException("Обработка отменена")
                val count = minOf(BLOCK, tokens.size - offset)
                val result = forward(tokens.copyOfRange(offset, offset + count), past, length)
                previous?.close()
                previous = result
                val needed = (offset until offset + count).filter { it in wanted }
                if (needed.isNotEmpty()) {
                    val logits = result.get("logits").get() as OnnxTensor
                    val shape = logits.info.shape
                    require(shape.size == 3 && shape[0] == 1L && shape[1] == count.toLong()) { "Неожиданный ответ текстовой модели" }
                    val vocabulary = shape[2].toInt()
                    val buffer = logits.floatBuffer
                    for (position in needed) {
                        val ids = wanted.getValue(position)
                        require(ids.all { it in 0 until vocabulary })
                        scores[position] = FloatArray(ids.size) { buffer.get((position - offset) * vocabulary + ids[it]) }
                    }
                }
                past = pastNames.associateWith { result.get(present(it)).get() as OnnxTensor }
                length += count
                offset += count
            }
            return finish(past)
        } finally { previous?.close() }
    }

    private fun forward(ids: IntArray, past: Map<String, OnnxTensor>, pastLength: Int): OrtSession.Result {
        val count = ids.size
        val created = ArrayList<OnnxTensor>(3)
        fun longs(values: LongArray, vararg shape: Long) =
            OnnxTensor.createTensor(environment, LongBuffer.wrap(values), shape).also(created::add)
        try {
            val inputs = HashMap<String, OnnxTensor>(past)
            inputs["input_ids"] = longs(LongArray(count) { ids[it].toLong() }, 1, count.toLong())
            inputs["attention_mask"] = longs(LongArray(pastLength + count) { 1L }, 1, (pastLength + count).toLong())
            inputs["position_ids"] = longs(LongArray(count) { pastLength + it.toLong() }, 1, count.toLong())
            return session.run(inputs)
        } finally { created.forEach(OnnxTensor::close) }
    }

    override fun close() {
        prefixCache.values.forEach(OnnxTensor::close)
        session.close()
        options.close()
    }

    private companion object {
        const val BLOCK = 24
        val BASE_INPUTS = setOf("input_ids", "attention_mask", "position_ids")
        fun present(past: String) = "present." + past.removePrefix("past_key_values.")
    }
}
