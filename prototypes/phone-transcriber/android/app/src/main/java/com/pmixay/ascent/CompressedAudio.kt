package com.pmixay.ascent

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaDataSource
import android.media.MediaExtractor
import android.media.MediaFormat
import java.nio.ByteOrder

/**
 * MP3, AAC (ADTS and MP4/M4A), FLAC, Ogg and whatever else the device's own codecs decode.
 *
 * The container is read in place through a [MediaDataSource] over the picked file, so nothing is
 * copied. The decoded audio is converted to 16 kHz mono as it arrives and kept in memory only, at
 * most [MAX_DURATION_MS]; a longer recording fails clearly instead of being truncated. Positions
 * follow the decoder's presentation times: a stream that starts after zero begins with that much
 * silence, and a gap in the middle of a stream is not removed or re-inserted.
 */
internal object CompressedAudio {
    const val MAX_DURATION_MS = 30L * 60 * 1000
    private const val TIMEOUT_US = 10_000L

    /** Decodes everything, closes [input] and returns the audio. Blocking; interruptible between buffers. */
    fun decode(input: RandomAccessInput): PcmWindowSource {
        val blocks = Pcm16Blocks()
        val extractor = MediaExtractor()
        try {
            try {
                extractor.setDataSource(InputDataSource(input))
            } catch (error: Exception) {
                throw if (error is AudioImportException) error else unsupported(error)
            }
            val track = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: throw AudioImportException(ImportFailure.UNSUPPORTED, "В файле нет звуковой дорожки или формат не поддерживается")
            val format = extractor.getTrackFormat(track)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: throw unsupported(null)
            if (format.containsKey(MediaFormat.KEY_DURATION) && format.getLong(MediaFormat.KEY_DURATION) / 1000 > MAX_DURATION_MS) {
                throw tooLong()
            }
            extractor.selectTrack(track)
            decodeTrack(extractor, format, mime, blocks)
        } finally {
            extractor.release()
            runCatching { input.close() }
        }
        if (blocks.size == 0L) throw AudioImportException(ImportFailure.EMPTY, "В файле нет звука")
        return Pcm16Source(blocks)
    }

    private fun decodeTrack(extractor: MediaExtractor, format: MediaFormat, mime: String, blocks: Pcm16Blocks) {
        val codec = try {
            MediaCodec.createDecoderByType(mime)
        } catch (error: Exception) {
            throw unsupported(error)
        }
        try {
            try {
                codec.configure(format, null, null, 0)
                codec.start()
            } catch (error: Exception) {
                throw unsupported(error)
            }
            val info = MediaCodec.BufferInfo()
            var rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            var stream: Pcm16Stream? = null
            var inputDone = false
            var outputDone = false
            while (!outputDone) {
                if (Thread.currentThread().isInterrupted) throw InterruptedException("Обработка отменена")
                if (!inputDone) {
                    val inputIndex = codec.dequeueInputBuffer(TIMEOUT_US)
                    if (inputIndex >= 0) {
                        val buffer = checkNotNull(codec.getInputBuffer(inputIndex))
                        val size = extractor.readSampleData(buffer, 0)
                        if (size < 0) {
                            codec.queueInputBuffer(inputIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(inputIndex, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                val outputIndex = codec.dequeueOutputBuffer(info, TIMEOUT_US)
                if (outputIndex >= 0) {
                    if (info.size > 0) {
                        val active = stream ?: Pcm16Stream(rate, channels, blocks, MAX_DURATION_MS * 16).also { created ->
                            stream = created
                            // A stream that starts after time zero keeps its original-media coordinates.
                            created.pushSilence(info.presentationTimeUs.coerceAtLeast(0L) * rate / 1_000_000)
                        }
                        val buffer = checkNotNull(codec.getOutputBuffer(outputIndex))
                        buffer.position(info.offset)
                        buffer.limit(info.offset + info.size)
                        val shorts = buffer.order(ByteOrder.nativeOrder()).asShortBuffer()
                        val samples = ShortArray(shorts.remaining())
                        shorts.get(samples)
                        active.push(samples, samples.size / channels)
                    }
                    codec.releaseOutputBuffer(outputIndex, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                } else if (outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val changed = codec.outputFormat
                    if (changed.containsKey(MediaFormat.KEY_PCM_ENCODING) &&
                        changed.getInteger(MediaFormat.KEY_PCM_ENCODING) != AudioFormat.ENCODING_PCM_16BIT) {
                        throw unsupported(null)
                    }
                    val newRate = changed.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    val newChannels = changed.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    // The converter is built from the first output; a later change cannot be mixed in.
                    if (stream != null && (newRate != rate || newChannels != channels)) throw unsupported(null)
                    rate = newRate
                    channels = newChannels
                }
            }
            stream?.finish()
        } catch (error: IllegalStateException) {
            // MediaCodec.CodecException: the stream is damaged or the codec failed mid-file.
            throw AudioImportException(ImportFailure.MALFORMED, "Не удалось декодировать файл: он повреждён или кодек не справился", error)
        } finally {
            runCatching { codec.stop() }
            codec.release()
        }
    }

    private fun unsupported(cause: Throwable?) = AudioImportException(ImportFailure.UNSUPPORTED,
        "Формат этого файла не поддерживается устройством. Выберите WAV, MP3 или M4A.", cause)

    private fun tooLong() = AudioImportException(ImportFailure.TOO_LONG,
        "Для этого формата максимальная длина записи — ${MAX_DURATION_MS / 60_000} минут. Выберите файл короче или WAV.")

    /** Lets the framework read the picked file at any position. The caller still owns [input]. */
    private class InputDataSource(private val input: RandomAccessInput) : MediaDataSource() {
        override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
            if (position < 0 || position >= input.size) return -1
            val count = input.readAt(position, buffer, offset, minOf(size.toLong(), input.size - position).toInt())
            return if (count <= 0) -1 else count
        }

        override fun getSize(): Long = input.size

        override fun close() = Unit
    }
}
