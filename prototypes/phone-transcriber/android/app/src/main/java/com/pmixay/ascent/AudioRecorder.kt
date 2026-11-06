package com.pmixay.ascent

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Records only while the owning Activity is visible. No cloud or system ASR is used. */
class AudioRecorder(private val context: Context, private val output: File, private val onLimit: () -> Unit) {
    @Volatile private var running = false
    @Volatile private var failure: Throwable? = null
    @Volatile var level: Float = 0f
        private set
    private var recorder: AudioRecord? = null
    private var worker: Thread? = null

    @SuppressLint("MissingPermission") // Explicit framework permission check below, also requested by Activity.
    fun start() {
        check(context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            "Для записи нужен доступ к микрофону"
        }
        check(worker == null) { "Запись уже запущена" }
        output.parentFile?.mkdirs()
        val minimum = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        check(minimum > 0) { "Микрофон не поддерживает запись 16 кГц" }
        val audio = AudioRecord(MediaRecorder.AudioSource.MIC, SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT, maxOf(minimum * 2, 8192))
        if (audio.state != AudioRecord.STATE_INITIALIZED) {
            audio.release()
            throw IOException("Не удалось открыть микрофон")
        }
        recorder = audio
        try {
            audio.startRecording()
            check(audio.recordingState == AudioRecord.RECORDSTATE_RECORDING) { "Не удалось начать запись" }
            running = true
            worker = Thread({
                var byteCount = 0L
                try {
                    RandomAccessFile(output, "rw").use { file ->
                        file.setLength(0)
                        writeWavHeader(file, 0)
                        val samples = ShortArray(2048)
                        val bytes = ByteBuffer.allocate(samples.size * 2).order(ByteOrder.LITTLE_ENDIAN)
                        while (running && byteCount < MAX_PCM_BYTES) {
                            val count = audio.read(samples, 0, samples.size, AudioRecord.READ_NON_BLOCKING)
                            if (count < 0) {
                                if (running) throw IOException("Ошибка микрофона: $count")
                                break
                            }
                            if (count == 0) {
                                Thread.sleep(10)
                                continue
                            }
                            bytes.clear()
                            val accepted = minOf(count, ((MAX_PCM_BYTES - byteCount) / 2).toInt())
                            var squared = 0.0
                            for (i in 0 until accepted) {
                                bytes.putShort(samples[i])
                                val normalized = samples[i] / 32768.0
                                squared += normalized * normalized
                            }
                            level = if (accepted > 0) kotlin.math.sqrt(squared / accepted).toFloat() else 0f
                            file.write(bytes.array(), 0, accepted * 2)
                            byteCount += accepted * 2
                        }
                        writeWavHeader(file, byteCount)
                    }
                    if (byteCount >= MAX_PCM_BYTES && running) onLimit()
                } catch (error: Throwable) {
                    failure = error
                    if (running) onLimit()
                }
            }, "ascent-recorder").also { it.start() }
        } catch (error: Throwable) {
            running = false
            audio.release()
            recorder = null
            output.delete()
            throw error
        }
    }

    /** Stops microphone capture immediately; finalization/join belongs on an IO dispatcher. */
    fun requestStop() {
        running = false
        level = 0f
        runCatching { recorder?.stop() }
    }

    /** Call off the UI thread: joins the writer before the WAV is exposed to recognition. */
    fun stop(): File {
        requestStop()
        try {
            worker?.join()
        } finally {
            recorder?.release()
            recorder = null
            worker = null
        }
        failure?.let {
            output.delete()
            throw IOException("Запись не завершена: ${it.message}", it)
        }
        if (output.length() <= 44) {
            output.delete()
            throw IOException("Микрофон не записал звук")
        }
        return output
    }

    companion object {
        const val SAMPLE_RATE = 16000
        const val MAX_DURATION_MINUTES = 30
        const val MAX_PCM_BYTES = 16000L * 2 * 60 * MAX_DURATION_MINUTES

        fun writeWavHeader(file: RandomAccessFile, bytes: Long) {
            require(bytes in 0..MAX_PCM_BYTES)
            val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
            header.put("RIFF".toByteArray(Charsets.US_ASCII)).putInt((36 + bytes).toInt())
            header.put("WAVEfmt ".toByteArray(Charsets.US_ASCII)).putInt(16)
            header.putShort(1).putShort(1).putInt(SAMPLE_RATE).putInt(SAMPLE_RATE * 2)
            header.putShort(2).putShort(16).put("data".toByteArray(Charsets.US_ASCII)).putInt(bytes.toInt())
            file.seek(0)
            file.write(header.array())
        }
    }
}
