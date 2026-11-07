package com.pmixay.ascent

import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.ByteArrayOutputStream
import java.util.UUID
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

/** The picked-file layer on a real Android runtime: positional reads, rejection and no copying. */
@RunWith(AndroidJUnit4::class)
class ImportRuntimeTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val audioNames = listOf(".wav", ".pcm", ".part", ".mp3", ".m4a", ".aac")

    private fun le(value: Long, size: Int) = ByteArray(size) { ((value shr (8 * it)) and 0xFF).toByte() }

    private fun stereoWav(rate: Int, seconds: Int): ByteArray {
        val frames = rate * seconds
        val data = ByteArray(frames * 4)
        for (i in 0 until frames) {
            val left = (0.5 * 32767 * sin(2 * PI * 1000.0 * i / rate)).toInt()
            val right = (0.25 * 32767 * sin(2 * PI * 1000.0 * i / rate)).toInt()
            data[4 * i] = (left and 0xFF).toByte(); data[4 * i + 1] = ((left shr 8) and 0xFF).toByte()
            data[4 * i + 2] = (right and 0xFF).toByte(); data[4 * i + 3] = ((right shr 8) and 0xFF).toByte()
        }
        val out = ByteArrayOutputStream()
        out.write("RIFF".toByteArray()); out.write(le(36L + data.size, 4)); out.write("WAVEfmt ".toByteArray())
        out.write(le(16, 4)); out.write(le(1, 2)); out.write(le(2, 2)); out.write(le(rate.toLong(), 4))
        out.write(le(rate * 4L, 4)); out.write(le(4, 2)); out.write(le(16, 2))
        out.write("data".toByteArray()); out.write(le(data.size.toLong(), 4)); out.write(data)
        return out.toByteArray()
    }

    private fun audioFilesUnderAppStorage(): Set<String> =
        listOf(context.filesDir, context.cacheDir).flatMap { root ->
            root.walkTopDown().filter { it.isFile && audioNames.any { suffix -> it.name.endsWith(suffix) } }.map { it.path }.toList()
        }.toSet()

    @Test fun aPickedStereo48kFileIsReadInPlaceConvertedAndNeverCopied() {
        val folder = File(context.cacheDir, "import-test-${UUID.randomUUID()}").apply { mkdirs() }
        try {
            val picked = File(folder, "picked.wav")
            picked.writeBytes(stereoWav(48_000, 1))
            val original = picked.readBytes()
            val before = audioFilesUnderAppStorage()
            AudioSources.open(context, Uri.fromFile(picked)).use { source ->
                assertEquals(16_000L, source.sampleCount)
                val window = source.read(2_000, 4_000)
                // The two channels average to 0.375 of full scale for a 1 kHz tone.
                val peak = window.maxOf { abs(it) }
                assertTrue("peak $peak", peak in 0.30f..0.45f)
            }
            assertArrayEquals("the picked file must stay byte-identical", original, picked.readBytes())
            assertEquals("no audio may be copied into app storage", before, audioFilesUnderAppStorage())
        } finally { folder.deleteRecursively() }
    }

    private fun failureOf(block: () -> Unit): ImportFailure {
        try {
            block()
        } catch (error: AudioImportException) {
            return error.failure
        }
        fail("an AudioImportException was expected")
        throw IllegalStateException()
    }

    @Test fun missingEmptyAndNonSeekableSourcesAreRejectedWithTheirOwnCategory() {
        val folder = File(context.cacheDir, "import-test-${UUID.randomUUID()}").apply { mkdirs() }
        try {
            assertEquals(ImportFailure.UNREADABLE, failureOf { AudioSources.open(context, Uri.fromFile(File(folder, "missing.wav"))) })
            val empty = File(folder, "empty.wav").apply { writeBytes(ByteArray(0)) }
            assertEquals(ImportFailure.EMPTY, failureOf { AudioSources.open(context, Uri.fromFile(empty)) })
            val pipe = ParcelFileDescriptor.createPipe()
            try {
                assertEquals(ImportFailure.NOT_SEEKABLE, failureOf { DescriptorInput(pipe[0]).close() })
            } finally {
                pipe[0].close()
                pipe[1].close()
            }
        } finally { folder.deleteRecursively() }
    }
}
