package com.pmixay.ascent

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import java.io.IOException
import java.nio.ByteOrder
import kotlin.math.floor
import kotlin.math.roundToLong

/**
 * Decodes Android-supported audio formats from the selected document. A small PCM ring serves
 * overlapping ASR reads; the decoder starts over when a read goes behind that ring. Decoder PTS
 * places samples on the original file's timeline, so leading silence and gaps are retained.
 */
internal class CodecAudioWindowSource(context: Context, uri: Uri) : PcmWindowSource {
    private val extractor = MediaExtractor()
    private lateinit var decoder: MediaCodec
    private var decoderStarted = false
    private var closed = false
    private var inputEos = false
    private var outputEos = false
    private var sawFrames = false
    private var sourceRate = 0
    private var channelCount = 0
    private var pcmEncoding = AudioFormat.ENCODING_PCM_16BIT
    private var mime = ""
    private var nextOutputIndex = 0L
    private var lastSourceIndex = -1L
    private val outputRing = FloatArray(OUTPUT_RING_SIZE)
    private val sourceRing = FloatArray(SOURCE_RING_SIZE)
    private lateinit var resamplingKernel: PcmResamplingKernel
    private val bufferInfo = MediaCodec.BufferInfo()

    override val sampleCount: Long
    override val durationMs: Long
    override var formatDescription: String = ""
        private set

    init {
        try {
            extractor.setDataSource(context, uri, null)
            val track = (0 until extractor.trackCount).firstOrNull { index ->
                extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: throw IOException("В файле нет поддерживаемой аудиодорожки")
            val trackFormat = extractor.getTrackFormat(track)
            mime = trackFormat.getString(MediaFormat.KEY_MIME)
                ?: throw IOException("Не удалось определить формат аудио")
            require(mime != "audio/raw") { "Этот PCM-файл не поддерживается системой" }
            val durationUs = if (trackFormat.containsKey(MediaFormat.KEY_DURATION))
                trackFormat.getLong(MediaFormat.KEY_DURATION) else -1L
            require(durationUs in 1..MAX_DURATION_US) {
                if (durationUs > MAX_DURATION_US) "Максимальная длительность — 30 минут"
                else "Не удалось определить длительность аудиофайла"
            }
            durationMs = (durationUs + 999) / 1000
            sampleCount = (durationUs * OUTPUT_RATE + 999_999) / 1_000_000
            extractor.selectTrack(track)
            decoder = MediaCodec.createDecoderByType(mime)
            decoder.configure(trackFormat, null, null, 0)
            decoder.start()
            decoderStarted = true
            var stalls = 0
            while ((sourceRate == 0 || !sawFrames) && !outputEos) {
                if (Thread.currentThread().isInterrupted) throw InterruptedException("Обработка отменена")
                if (pump()) stalls = 0 else if (++stalls > MAX_STALLS) {
                    throw IOException("Декодер аудио не отвечает")
                }
            }
            require(sourceRate > 0 && sawFrames) { "В аудиофайле нет звука" }
        } catch (error: Throwable) {
            runCatching { close() }
            throw error
        }
    }

    override fun read(start: Long, count: Int): FloatArray {
        check(!closed) { "Источник аудио закрыт" }
        require(start >= 0 && count >= 0 && start <= sampleCount && count.toLong() <= sampleCount - start)
        require(count <= MAX_WINDOW_SAMPLES) { "Запрошено слишком длинное окно аудио" }
        if (count == 0) return FloatArray(0)
        if (start < (nextOutputIndex - OUTPUT_RING_SIZE).coerceAtLeast(0)) rewind()
        val target = start + count
        var stalls = 0
        while (nextOutputIndex < target && !outputEos) {
            if (Thread.currentThread().isInterrupted) throw InterruptedException("Обработка отменена")
            if (pump()) stalls = 0 else if (++stalls > MAX_STALLS) {
                throw IOException("Декодер аудио не отвечает")
            }
        }
        if (nextOutputIndex < target) throw IOException("Аудиоданные закончились раньше заявленной длительности")
        if (start < nextOutputIndex - OUTPUT_RING_SIZE) {
            // A malformed source with a large timestamp jump could evict the requested window.
            throw IOException("Невозможно прочитать запрошенный фрагмент аудио")
        }
        return FloatArray(count) { index -> outputRing[((start + index) % OUTPUT_RING_SIZE).toInt()] }
    }

    private fun rewind() {
        extractor.seekTo(0, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
        decoder.flush()
        inputEos = false
        outputEos = false
        sawFrames = false
        nextOutputIndex = 0
        lastSourceIndex = -1
        outputRing.fill(0f)
        sourceRing.fill(0f)
    }

    /** True when a packet, decoded buffer, or format transition made progress. */
    private fun pump(): Boolean {
        var progressed = false
        if (!inputEos) {
            val inputIndex = decoder.dequeueInputBuffer(DEQUEUE_TIMEOUT_US)
            if (inputIndex >= 0) {
                val input = decoder.getInputBuffer(inputIndex) ?: throw IOException("Декодер не выдал входной буфер")
                input.clear()
                val size = extractor.readSampleData(input, 0)
                if (size < 0) {
                    decoder.queueInputBuffer(inputIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                    inputEos = true
                } else {
                    val timeUs = extractor.sampleTime
                    require(timeUs >= 0) { "Некорректное время аудиодорожки" }
                    decoder.queueInputBuffer(inputIndex, 0, size, timeUs, 0)
                    extractor.advance()
                }
                progressed = true
            }
        }
        val outputIndex = decoder.dequeueOutputBuffer(bufferInfo, DEQUEUE_TIMEOUT_US)
        when {
            outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                updateFormat(decoder.outputFormat)
                progressed = true
            }
            outputIndex >= 0 -> {
                try {
                    if (bufferInfo.size > 0) {
                        updateFormat(decoder.getOutputFormat(outputIndex))
                        consumeOutput(outputIndex)
                        sawFrames = true
                    }
                    if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                        outputEos = true
                        require(sawFrames) { "В аудиофайле нет звука" }
                        finishWithSilence()
                    }
                } finally { decoder.releaseOutputBuffer(outputIndex, false) }
                progressed = true
            }
        }
        return progressed
    }

    private fun updateFormat(format: MediaFormat) {
        val rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        val channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        val encoding = if (format.containsKey(MediaFormat.KEY_PCM_ENCODING))
            format.getInteger(MediaFormat.KEY_PCM_ENCODING) else AudioFormat.ENCODING_PCM_16BIT
        require(rate in 8000..96000 && channels in 1..8) { "Неподдерживаемый формат декодированного звука" }
        require(encoding == AudioFormat.ENCODING_PCM_16BIT || encoding == AudioFormat.ENCODING_PCM_FLOAT) {
            "Декодер выдал неподдерживаемый формат PCM"
        }
        if (sourceRate == 0) {
            sourceRate = rate
            channelCount = channels
            pcmEncoding = encoding
            resamplingKernel = PcmResamplingKernel.forRate(rate, OUTPUT_RATE)
            formatDescription = "$mime, $rate Гц, $channels кан."
        } else require(rate == sourceRate && channels == channelCount && encoding == pcmEncoding) {
            "Формат аудио изменился во время декодирования"
        }
    }

    private fun consumeOutput(index: Int) {
        val bytesPerSample = if (pcmEncoding == AudioFormat.ENCODING_PCM_FLOAT) 4 else 2
        val frameBytes = channelCount * bytesPerSample
        require(bufferInfo.size <= MAX_OUTPUT_BUFFER_BYTES && bufferInfo.size % frameBytes == 0) {
            "Декодер выдал повреждённый PCM-буфер"
        }
        val data = decoder.getOutputBuffer(index)?.duplicate()?.order(ByteOrder.LITTLE_ENDIAN)
            ?: throw IOException("Декодер не выдал аудиоданные")
        data.position(bufferInfo.offset)
        data.limit(bufferInfo.offset + bufferInfo.size)
        val frames = bufferInfo.size / frameBytes
        require(frames <= sourceRate * 2) { "Декодер выдал слишком длинный буфер" }
        val mono = FloatArray(frames)
        try {
        val channelEnergy = DoubleArray(channelCount)
        var mixedEnergy = 0.0
        for (frame in 0 until frames) {
            var sum = 0f
            for (channel in 0 until channelCount) {
                val value = if (pcmEncoding == AudioFormat.ENCODING_PCM_FLOAT) data.float
                    else data.short / 32768f
                require(value.isFinite()) { "Декодер выдал некорректные аудиоданные" }
                channelEnergy[channel] += value * value
                sum += value
            }
            mono[frame] = sum / channelCount
            mixedEnergy += mono[frame] * mono[frame]
        }
        // Avoid losing speech when two recorded channels nearly cancel in a mono mix.
        if (channelCount > 1 && mixedEnergy < channelEnergy.max() * 0.02) {
            val selected = channelEnergy.indices.maxBy { channelEnergy[it] }
            for (frame in 0 until frames) {
                data.position(bufferInfo.offset + frame * frameBytes + selected * bytesPerSample)
                mono[frame] = if (pcmEncoding == AudioFormat.ENCODING_PCM_FLOAT) data.float
                    else data.short / 32768f
            }
        }
        val startFrame = (bufferInfo.presentationTimeUs * sourceRate / 1_000_000.0).roundToLong()
        require(startFrame >= -sourceRate && startFrame <= MAX_DURATION_US * sourceRate / 1_000_000 + sourceRate) {
            "Некорректное время аудиодорожки"
        }
        for (frame in mono.indices) if (startFrame + frame >= 0) appendSourceAt(startFrame + frame, mono[frame])
        } finally { mono.fill(0f) }
    }

    private fun appendSourceAt(index: Long, value: Float) {
        if (index <= lastSourceIndex) return // A decoder can repeat priming frames after a seek.
        require(index - lastSourceIndex <= sourceRate * 2L) { "Аудиодорожка содержит слишком большой разрыв времени" }
        while (lastSourceIndex + 1 < index) {
            checkInterrupted()
            appendSource(0f)
        }
        appendSource(value)
    }

    private fun appendSource(value: Float) {
        lastSourceIndex++
        sourceRing[(lastSourceIndex % SOURCE_RING_SIZE).toInt()] = value
        while (nextOutputIndex < sampleCount) {
            val sourcePosition = nextOutputIndex.toDouble() * sourceRate / OUTPUT_RATE
            val center = floor(sourcePosition).toLong()
            if (center + resamplingKernel.radius > lastSourceIndex) break
            val weights = resamplingKernel.weights(sourcePosition - center)
            var result = 0f
            for (tap in weights.indices) {
                val frame = center + tap - resamplingKernel.radius
                if (frame >= 0 && frame <= lastSourceIndex) {
                    result += sourceRing[(frame % SOURCE_RING_SIZE).toInt()] * weights[tap]
                }
            }
            outputRing[(nextOutputIndex % OUTPUT_RING_SIZE).toInt()] = result.coerceIn(-1f, 1f)
            nextOutputIndex++
        }
    }

    private fun finishWithSilence() {
        val lastNeededSource = (sampleCount.toDouble() * sourceRate / OUTPUT_RATE).toLong() + resamplingKernel.radius
        require(lastNeededSource - lastSourceIndex <= sourceRate / 2L + resamplingKernel.radius) {
            "Аудиоданные закончились раньше заявленной длительности"
        }
        while (nextOutputIndex < sampleCount && lastSourceIndex < lastNeededSource) {
            checkInterrupted()
            appendSource(0f)
        }
    }

    private fun checkInterrupted() {
        if (Thread.currentThread().isInterrupted) throw InterruptedException("Обработка отменена")
    }

    override fun close() {
        if (closed) return
        closed = true
        try {
            if (::decoder.isInitialized) {
                if (decoderStarted) runCatching { decoder.stop() }
                decoder.release()
            }
        } finally {
            try { extractor.release() } finally {
                outputRing.fill(0f)
                sourceRing.fill(0f)
            }
        }
    }

    companion object {
        private const val OUTPUT_RATE = 16000
        private const val MAX_WINDOW_SAMPLES = OUTPUT_RATE * 32
        // One decoder buffer can overshoot the requested boundary by up to two seconds;
        // an accepted PTS gap can add another two. Keep the complete 32-second read.
        private const val OUTPUT_RING_SIZE = OUTPUT_RATE * 36
        private const val SOURCE_RING_SIZE = 512
        private const val DEQUEUE_TIMEOUT_US = 5_000L
        private const val MAX_STALLS = 500
        private const val MAX_OUTPUT_BUFFER_BYTES = 4 * 1024 * 1024
        private const val MAX_DURATION_US = 30L * 60 * 1_000_000

    }
}
