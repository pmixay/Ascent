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

    /**
     * Cancelling a job interrupts its worker thread. A NIO channel would be closed by that interrupt and the
     * read would look like a damaged file; `pread` must be unaffected, leaving the cancel request for the
     * engine's own checks.
     */
    @Test fun readingStillWorksWhileTheThreadIsInterrupted() {
        val folder = File(context.cacheDir, "import-test-${UUID.randomUUID()}").apply { mkdirs() }
        try {
            val picked = File(folder, "picked.wav").apply { writeBytes(stereoWav(16_000, 1)) }
            AudioSources.open(context, Uri.fromFile(picked)).use { source ->
                Thread.currentThread().interrupt()
                try {
                    assertEquals(1_000, source.read(0, 1_000).size)
                    assertTrue("the interrupt must not be consumed by the read", Thread.currentThread().isInterrupted)
                } finally {
                    Thread.interrupted()
                }
            }
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

    /**
     * The assets are synthetic: 2 s of mono 44.1 kHz audio, silence, a 1 kHz tone of amplitude 0.5 from 0.5 s to
     * 1.5 s, silence. ffmpeg generated them (`aevalsrc='if(between(t,0.5,1.5),0.5*sin(2*PI*1000*t),0)':s=44100:d=2`,
     * `-c:a libmp3lame -b:a 64k` and `-c:a aac -b:a 64k`), and an independent ffmpeg decode puts the tone at
     * 0.500 to 1.500 s with peak 0.48 to 0.50 and 2000 zero crossings per second. Decoders may differ in priming
     * by a few tens of milliseconds, hence the margins.
     */
    @Test fun mp3AndM4aDecodeToSixteenKilohertzMonoWithTheToneAtItsOriginalTime() {
        val folder = File(context.cacheDir, "import-test-${UUID.randomUUID()}").apply { mkdirs() }
        try {
            for (asset in listOf("tone-silence-tone-44100.mp3", "tone-silence-tone-44100.m4a")) {
                val picked = File(folder, asset)
                InstrumentationRegistry.getInstrumentation().context.assets.open(asset).use { input ->
                    picked.outputStream().use { input.copyTo(it) }
                }
                val original = picked.readBytes()
                val samples = AudioSources.open(context, Uri.fromFile(picked)).use { source ->
                    assertTrue("$asset: ${source.sampleCount} samples", source.sampleCount in 29_600L..34_400L)
                    source.read(0, source.sampleCount.toInt())
                }
                val loud = samples.indices.filter { abs(samples[it]) > 0.2f }
                assertTrue("$asset: no tone found", loud.isNotEmpty())
                val onset = loud.first() / 16_000.0
                val end = (loud.last() + 1) / 16_000.0
                assertTrue("$asset: tone starts at $onset s, expected 0.5", onset in 0.40..0.60)
                assertTrue("$asset: tone ends at $end s, expected 1.5", end in 1.40..1.60)
                val middle = samples.copyOfRange(9_600, 22_400)
                val peak = middle.maxOf { abs(it) }
                assertTrue("$asset: peak $peak", peak in 0.4f..0.6f)
                var crossings = 0
                for (i in 1 until middle.size) if ((middle[i] < 0f) != (middle[i - 1] < 0f)) crossings++
                assertTrue("$asset: $crossings zero crossings in 0.8 s, expected about 1600", crossings in 1_500..1_700)
                assertArrayEquals("$asset must stay byte-identical", original, picked.readBytes())
            }
        } finally { folder.deleteRecursively() }
    }
}
