package com.pmixay.ascent

import android.content.Context
import android.net.Uri
import java.io.IOException

/** Opens a user-selected import as one bounded PcmWindowSource; nothing here owns an audio file. */
object AudioSources {
    fun open(context: Context, uri: Uri): PcmWindowSource {
        val header = ByteArray(12)
        val count = context.contentResolver.openInputStream(uri)?.use { input ->
            var read = 0
            while (read < header.size) {
                val next = input.read(header, read, header.size - read)
                if (next < 0) break
                if (next == 0) throw IOException("Источник аудио не отвечает")
                read += next
            }
            read
        } ?: throw IOException("Не удалось открыть выбранный файл")
        val riffWave = count == 12 && header.copyOfRange(0, 4).contentEquals("RIFF".toByteArray()) &&
            header.copyOfRange(8, 12).contentEquals("WAVE".toByteArray())
        return if (riffWave) PcmWave(context, uri) else CodecAudioWindowSource(context, uri)
    }
}
