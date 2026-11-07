package com.pmixay.ascent

import org.junit.Assert.*
import org.junit.Test
import java.io.File

/**
 * Static guard for the no-copy rule: the code that reads a picked recording must contain no way to
 * write audio to application storage, caches or temporary directories. It reads the source files
 * themselves, so a later edit that adds a spool fails here before it reaches a device.
 */
class AudioImportPolicyTest {
    private val sources = File("src/main/java/com/pmixay/ascent")

    private val readers = listOf("AudioSource.kt", "WavAudio.kt", "CompressedAudio.kt", "PcmStream.kt")
    private val forbidden = listOf(
        "FileOutputStream", "createTempFile", "cacheDir", "filesDir", "externalCacheDir", "openOutputStream",
        "RandomAccessFile", ".outputStream(", "writeBytes", "copyTo(", "Files.copy", "FileWriter",
    )

    @Test fun importReadersNeverContainAnyWritePath() {
        for (name in readers) {
            val file = File(sources, name)
            assertTrue("missing ${file.path}", file.isFile)
            val text = file.readText()
            for (token in forbidden) assertFalse("$name must not use $token", text.contains(token))
        }
    }

    @Test fun theOldImporterThatCopiedAudioIntoAppStorageIsGone() {
        assertFalse(File(sources, "AudioImporter.kt").exists())
        val viewModel = File(sources, "ConversationViewModel.kt").readText()
        assertFalse(viewModel.contains("importWav"))
        // The only output stream in the view model is the user-chosen export document.
        assertEquals(1, Regex("openOutputStream").findAll(viewModel).count())
    }
}
