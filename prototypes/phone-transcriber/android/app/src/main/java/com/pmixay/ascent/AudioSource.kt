package com.pmixay.ascent

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.content.res.AssetFileDescriptor
import android.provider.OpenableColumns
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import java.io.Closeable
import java.io.FileNotFoundException
import java.io.InterruptedIOException

/** Why a picked file could not become audio. The message is the user-facing explanation. */
enum class ImportFailure { UNREADABLE, NOT_SEEKABLE, MALFORMED, UNSUPPORTED, EMPTY, TOO_LONG }

class AudioImportException(val failure: ImportFailure, message: String, cause: Throwable? = null) : IllegalArgumentException(message, cause)

/**
 * Positional read access to the user's own file. Nothing here writes: the picked file is read in
 * place and never copied into application storage, caches or temporary directories.
 */
internal interface RandomAccessInput : Closeable {
    val size: Long

    /** Reads up to [length] bytes at [position]; fewer only when the file ends first. */
    fun readAt(position: Long, target: ByteArray, offset: Int, length: Int): Int
}

internal fun RandomAccessInput.readFully(position: Long, length: Int): ByteArray {
    val bytes = ByteArray(length)
    if (readAt(position, bytes, 0, length) < length) {
        throw AudioImportException(ImportFailure.MALFORMED, "Файл оборван или повреждён")
    }
    return bytes
}

/**
 * A seekable file descriptor from the system picker, read with `pread`. A plain system call is used on
 * purpose: NIO channels are interruptible, so cancelling a job would close the channel and look like
 * a damaged file. Here an interrupt only reaches the engine's own cancellation checks.
 */
internal class DescriptorInput(
    private val descriptor: ParcelFileDescriptor,
    private val startOffset: Long = 0,
    private val declaredLength: Long = AssetFileDescriptor.UNKNOWN_LENGTH,
) : RandomAccessInput {
    override val size: Long = measure()

    private fun measure(): Long {
        try {
            val stat = Os.fstat(descriptor.fileDescriptor)
            // Pipes and sockets (some cloud providers) cannot seek and report no useful size.
            if (!OsConstants.S_ISREG(stat.st_mode)) throw notSeekable(null)
            // A positional read at the end shows whether the provider really supports random access.
            if (stat.st_size > 0) Os.pread(descriptor.fileDescriptor, ByteArray(1), 0, 1, stat.st_size - 1)
            require(startOffset >= 0 && startOffset <= stat.st_size) { "Некорректное начало аудиофайла" }
            return if (declaredLength >= 0) {
                require(declaredLength <= stat.st_size - startOffset) { "Неполный аудиофайл" }
                declaredLength
            } else stat.st_size - startOffset
        } catch (error: ErrnoException) {
            throw notSeekable(error)
        }
    }

    private fun notSeekable(cause: Throwable?) = AudioImportException(ImportFailure.NOT_SEEKABLE,
        "Источник файла не поддерживает чтение с произвольной позиции. Сохраните файл в память телефона и выберите его снова.", cause)

    override fun readAt(position: Long, target: ByteArray, offset: Int, length: Int): Int {
        require(position >= 0 && offset >= 0 && length >= 0 && offset <= target.size - length)
        val boundedLength = minOf(length.toLong(), (size - position).coerceAtLeast(0)).toInt()
        var total = 0
        try {
            while (total < boundedLength) {
                val count = Os.pread(descriptor.fileDescriptor, target, offset + total, boundedLength - total,
                    startOffset + position + total)
                if (count <= 0) break
                total += count
            }
        } catch (error: ErrnoException) {
            throw AudioImportException(ImportFailure.UNREADABLE, "Не удалось прочитать файл", error)
        } catch (error: InterruptedIOException) {
            throw AudioImportException(ImportFailure.UNREADABLE, "Чтение файла прервано", error)
        }
        return total
    }

    override fun close() {
        runCatching { descriptor.close() }
    }
}

object AudioSources {
    /** Longest accepted recording. The limit is declared and fails clearly; it never truncates. */
    const val MAX_DURATION_MS = 2L * 60 * 60 * 1000

    fun displayName(context: Context, uri: Uri): String? = try {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0)?.trim()?.take(MAX_SOURCE_NAME)?.ifEmpty { null } else null
        }
    } catch (_: Exception) { null }

    /**
     * Opens the picked file as a bounded 16 kHz mono window source. The caller owns the result and
     * closes it. The descriptor is opened here, so later reads do not depend on how long the picker's
     * temporary read permission lasts.
     */
    fun open(context: Context, uri: Uri): PcmWindowSource {
        val asset = try {
            context.contentResolver.openAssetFileDescriptor(uri, "r")
        } catch (error: SecurityException) {
            throw AudioImportException(ImportFailure.UNREADABLE, "Доступ к файлу закрыт. Выберите файл снова.", error)
        } catch (error: FileNotFoundException) {
            throw AudioImportException(ImportFailure.UNREADABLE, "Файл недоступен или удалён. Выберите файл снова.", error)
        } ?: throw AudioImportException(ImportFailure.UNREADABLE, "Не удалось открыть файл")
        val input = try {
            DescriptorInput(asset.parcelFileDescriptor, asset.startOffset, asset.declaredLength)
        } catch (error: Throwable) {
            runCatching { asset.close() }
            throw error
        }
        try {
            if (input.size == 0L) throw AudioImportException(ImportFailure.EMPTY, "Файл пустой")
            val head = ByteArray(12)
            val count = input.readAt(0, head, 0, minOf(12L, input.size).toInt())
            if (count == 12 && String(head, 0, 4, Charsets.US_ASCII) == "RIFF" &&
                String(head, 8, 4, Charsets.US_ASCII) == "WAVE") return WavWindowSource.open(input)
            if (count >= 4 && String(head, 0, 4, Charsets.US_ASCII) == "RF64") {
                throw AudioImportException(ImportFailure.UNSUPPORTED, "WAV формата RF64 не поддерживается")
            }
            // Keep the bounded codec ring and anti-phase handling already exercised by Petya's tests.
            // MediaExtractor opens the selected provider again; the header descriptor is no longer needed.
            input.close()
            return try { CodecAudioWindowSource(context, uri) }
            catch (error: Exception) {
                val failure = if (error.message?.contains("30 минут") == true) ImportFailure.TOO_LONG else ImportFailure.UNSUPPORTED
                throw AudioImportException(failure, error.message ?: "Формат аудио не поддерживается", error)
            }
        } catch (error: Throwable) {
            input.close()
            throw error
        }
    }

    /** Detects the container and returns a source that owns [input] (or has finished with it). */
    internal fun openInput(input: RandomAccessInput): PcmWindowSource {
        if (input.size <= 0L) throw AudioImportException(ImportFailure.EMPTY, "Файл пустой")
        val head = ByteArray(12)
        val count = input.readAt(0, head, 0, minOf(12L, input.size).toInt())
        if (count >= 12 && String(head, 0, 4, Charsets.US_ASCII) == "RIFF" && String(head, 8, 4, Charsets.US_ASCII) == "WAVE") {
            return WavWindowSource.open(input)
        }
        if (count >= 4 && String(head, 0, 4, Charsets.US_ASCII) == "RF64") {
            throw AudioImportException(ImportFailure.UNSUPPORTED, "WAV формата RF64 не поддерживается")
        }
        return CompressedAudio.decode(input)
    }
}
