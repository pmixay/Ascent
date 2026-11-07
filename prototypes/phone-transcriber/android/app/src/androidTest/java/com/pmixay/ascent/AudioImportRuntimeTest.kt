package com.pmixay.ascent

import android.content.ContentProvider
import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ProviderInfo
import android.content.res.AssetFileDescriptor
import android.database.Cursor
import android.net.Uri
import android.os.Handler
import android.os.HandlerThread
import android.os.ParcelFileDescriptor
import android.os.ProxyFileDescriptorCallback
import android.os.SystemClock
import android.os.storage.StorageManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.security.MessageDigest
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

/** Exercises real content-URI reads and Android's codec without writing audio fixtures to disk. */
@RunWith(AndroidJUnit4::class)
class AudioImportRuntimeTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun stereoWaveImportHonorsAssetOffsetAndKeepsWindowBoundariesStable() {
        val wave = pcmWave(rate = 48_000, channels = 2, frames = 96_000) { frame, channel ->
            if (frame < 12_000 || frame >= 84_000) 0
            else (sin(2 * PI * 1_000 * frame / 48_000) * if (channel == 0) 12_000 else 6_000).toInt()
        }
        MemoryDocument(context, wave, prefixBytes = 37, suffixBytes = 29).use { document ->
            val original = document.fingerprint()
            AudioSources.open(document.context, document.uri).use { source ->
                assertEquals(32_000L, source.sampleCount)
                assertEquals(2_000L, source.durationMs)
                val whole = source.read(0, source.sampleCount.toInt())
                val split = source.read(0, 12_347) + source.read(12_347, 19_653)
                assertArrayEquals("Changing ASR window boundaries changed the audio", whole, split, 0f)
                assertSilent(whole, 0, 3_500)
                assertSilent(whole, 28_500, 32_000)
                for (index in 5_000 until 6_000) {
                    val expected = sin(2 * PI * 1_000 * index / 16_000) * 9_000 / 32_768
                    assertEquals("Stereo conversion shifted or changed a tone sample at $index",
                        expected, whole[index].toDouble(), 0.003)
                }
                assertArrayEquals(whole.copyOfRange(8_000, 8_513), source.read(8_000, 513), 0f)
                assertEquals(0, source.read(source.sampleCount, 0).size)
                expectInvalidArgument { source.read(source.sampleCount, 1) }
            }
            assertArrayEquals("Import modified the selected document", original, document.fingerprint())
            document.assertAllDescriptorsReleased()
        }
    }

    @Test fun flacImportUsesThePlatformDecoderAndRetainsSilenceAndAntiPhaseSpeech() {
        // An anti-phase stereo source would disappear if channels were always averaged.
        val flac = flac(frames = 16_000, channels = 2) { frame, channel ->
            val mono = if (frame < 4_000 || frame >= 12_000) 0
                else (sin(2 * PI * 400 * frame / 16_000) * 12_000).toInt()
            if (channel == 0) mono else -mono
        }
        MemoryDocument(context, flac, prefixBytes = 23, suffixBytes = 41).use { document ->
            val original = document.fingerprint()
            AudioSources.open(document.context, document.uri).use { source ->
                assertTrue("FLAC did not use the non-WAV decoder", source.formatDescription.startsWith("audio/flac"))
                assertEquals(16_000L, source.sampleCount)
                assertEquals(1_000L, source.durationMs)
                val audio = source.read(0, 16_000)
                assertSilent(audio, 0, 3_900)
                assertSilent(audio, 12_100, 16_000)
                val meanSquare = (5_000 until 11_000).sumOf { audio[it].toDouble() * audio[it] } / 6_000
                assertTrue("Anti-phase channels cancelled the audible signal", meanSquare > 0.04)
                assertTrue("Decoded speech was amplified beyond the source", meanSquare < 0.09)
                val split = source.read(0, 7_999) + source.read(7_999, 8_001)
                assertArrayEquals("Overlapping codec reads changed the timeline", audio, split, 0f)
            }
            // Opening the same user document again must not depend on a consumed file descriptor.
            AudioSources.open(document.context, document.uri).use { source ->
                assertEquals(16_000L, source.sampleCount)
                assertSilent(source.read(0, 1_000), 0, 1_000)
            }
            assertArrayEquals("Decoding changed the source document", original, document.fingerprint())
            document.assertAllDescriptorsReleased()
        }
    }

    @Test fun compressedImportCanRewindAfterItsBoundedOutputCacheIsEvicted() {
        val flac = flac(frames = 37 * 16_000, channels = 1) { frame, _ ->
            (sin(2 * PI * 200 * frame / 16_000) * 8_000).toInt()
        }
        MemoryDocument(context, flac).use { document ->
            AudioSources.open(document.context, document.uri).use { source ->
                val first = source.read(0, 1_600)
                val end = source.read(source.sampleCount - 1_600, 1_600)
                assertTrue("The final decoded window is missing", end.any { abs(it) > 0.1f })
                assertArrayEquals("A second processing pass did not rewind the codec", first, source.read(0, 1_600), 0f)
            }
            document.assertAllDescriptorsReleased()
        }
    }

    @Test fun truncatedWaveFailsWithoutChangingTheDocumentOrLeakingItsDescriptor() {
        val wave = pcmWave(rate = 16_000, channels = 1, frames = 1_600) { _, _ -> 0 }
            .let { it.copyOf(it.size - 17) }
        MemoryDocument(context, wave).use { document ->
            val original = document.fingerprint()
            expectInvalidArgument { AudioSources.open(document.context, document.uri).close() }
            assertArrayEquals(original, document.fingerprint())
            document.assertAllDescriptorsReleased()
        }
    }

    private fun assertSilent(samples: FloatArray, from: Int, endExclusive: Int) {
        assertTrue("Source silence was changed or shifted", (from until endExclusive).all { abs(samples[it]) < 0.0001f })
    }

    private fun expectInvalidArgument(action: () -> Unit) {
        try {
            action()
            fail("Invalid or truncated audio was accepted")
        } catch (_: IllegalArgumentException) { /* Expected user-visible rejection. */ }
    }

    private fun pcmWave(rate: Int, channels: Int, frames: Int, sample: (Int, Int) -> Int): ByteArray {
        val pcmBytes = frames * channels * 2
        return ByteArrayOutputStream(44 + pcmBytes).apply {
            write("RIFF".toByteArray(Charsets.US_ASCII))
            littleEndian(36 + pcmBytes, 4)
            write("WAVEfmt ".toByteArray(Charsets.US_ASCII))
            littleEndian(16, 4)
            littleEndian(1, 2)
            littleEndian(channels, 2)
            littleEndian(rate, 4)
            littleEndian(rate * channels * 2, 4)
            littleEndian(channels * 2, 2)
            littleEndian(16, 2)
            write("data".toByteArray(Charsets.US_ASCII))
            littleEndian(pcmBytes, 4)
            for (frame in 0 until frames) for (channel in 0 until channels) {
                littleEndian(sample(frame, channel), 2)
            }
        }.toByteArray()
    }

    /** Minimal verbatim FLAC frames, generated in memory per RFC 9639 sections 8 and 9. */
    private fun flac(frames: Int, channels: Int, sample: (Int, Int) -> Int): ByteArray {
        val blockSize = 1_000
        require(frames % blockSize == 0 && channels in 1..2)
        return ByteArrayOutputStream().apply {
            write("fLaC".toByteArray(Charsets.US_ASCII))
            write(byteArrayOf(0x80.toByte(), 0, 0, 34)) // Last metadata block: STREAMINFO.
            bigEndian(blockSize.toLong(), 2)
            bigEndian(blockSize.toLong(), 2)
            bigEndian(0, 6) // Unknown minimum/maximum encoded frame sizes are permitted.
            val properties = (16_000L shl 44) or ((channels - 1).toLong() shl 41) or
                (15L shl 36) or frames.toLong()
            bigEndian(properties, 8)
            write(ByteArray(16)) // Zero MD5 means the optional source checksum is unavailable.
            for (block in 0 until frames / blockSize) {
                val frame = ByteArrayOutputStream().apply {
                    write(0xff)
                    write(0xf8) // Fixed block-size strategy.
                    write(0x70) // Block size follows as a 16-bit value; sample rate from STREAMINFO.
                    write(((channels - 1) shl 4) or 0x08) // Independent channels, signed 16-bit.
                    if (block < 0x80) write(block) else {
                        require(block < 0x800)
                        write(0xc0 or (block ushr 6))
                        write(0x80 or (block and 0x3f))
                    }
                    bigEndian((blockSize - 1).toLong(), 2)
                    write(crc(toByteArray(), 8, 0x07))
                    for (channel in 0 until channels) {
                        write(0x02) // Verbatim subframe, no wasted bits.
                        for (index in 0 until blockSize) {
                            bigEndian(sample(block * blockSize + index, channel).toLong(), 2)
                        }
                    }
                    bigEndian(crc(toByteArray(), 16, 0x8005).toLong(), 2)
                }.toByteArray()
                write(frame)
            }
        }.toByteArray()
    }

    private fun crc(bytes: ByteArray, width: Int, polynomial: Int): Int {
        var value = 0
        val mask = (1 shl width) - 1
        val top = 1 shl (width - 1)
        for (byte in bytes) {
            value = value xor ((byte.toInt() and 0xff) shl (width - 8))
            repeat(8) {
                val feedback = if (value and top != 0) polynomial else 0
                value = ((value shl 1) xor feedback) and mask
            }
        }
        return value
    }

    private fun ByteArrayOutputStream.littleEndian(value: Int, bytes: Int) {
        repeat(bytes) { write(value ushr (8 * it)) }
    }

    private fun ByteArrayOutputStream.bigEndian(value: Long, bytes: Int) {
        for (index in bytes - 1 downTo 0) write((value ushr (8 * index)).toInt())
    }

    /** A seekable SAF-like asset whose audio bytes live only in this test process's memory. */
    private class MemoryDocument(
        baseContext: Context,
        audio: ByteArray,
        private val prefixBytes: Int = 0,
        suffixBytes: Int = 0,
    ) : Closeable {
        private val backing = ByteArray(prefixBytes + audio.size + suffixBytes) { 0xa5.toByte() }
            .also { audio.copyInto(it, prefixBytes) }
        private val audioLength = audio.size.toLong()
        private val callbacks = HandlerThread("audio-import-test-source").apply { start() }
        private val handler = Handler(callbacks.looper)
        private val storage = requireNotNull(baseContext.getSystemService(StorageManager::class.java))
        private val descriptors = CopyOnWriteArrayList<ParcelFileDescriptor>()
        private val released = AtomicInteger()
        val uri: Uri = Uri.parse("content://com.pmixay.ascent.memoryaudio/source.bin")

        private val provider = object : ContentProvider() {
            override fun onCreate() = true
            override fun getType(uri: Uri) = "application/octet-stream"
            override fun query(uri: Uri, projection: Array<out String>?, selection: String?,
                selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
            override fun insert(uri: Uri, values: ContentValues?): Uri = throw UnsupportedOperationException()
            override fun update(uri: Uri, values: ContentValues?, selection: String?,
                selectionArgs: Array<out String>?): Int = throw UnsupportedOperationException()
            override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int =
                throw UnsupportedOperationException()
            override fun openAssetFile(uri: Uri, mode: String): AssetFileDescriptor {
                check(mode == "r") { "Import tried to write the selected source" }
                return AssetFileDescriptor(newDescriptor(), prefixBytes.toLong(), audioLength)
            }
            override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
                check(mode == "r") { "Import tried to write the selected source" }
                return newDescriptor()
            }
        }.apply {
            attachInfo(baseContext, ProviderInfo().apply {
                authority = "com.pmixay.ascent.memoryaudio"
                exported = false
            })
        }
        private val resolver = ContentResolver.wrap(provider)
        val context: Context = object : ContextWrapper(baseContext) {
            override fun getContentResolver(): ContentResolver = resolver
        }

        fun fingerprint(): ByteArray = MessageDigest.getInstance("SHA-256").digest(backing)

        fun assertAllDescriptorsReleased() {
            awaitDescriptorReleases()
            assertTrue("The selected document was never opened", descriptors.isNotEmpty())
            assertEquals("Import leaked a document descriptor", descriptors.size, released.get())
        }

        private fun awaitDescriptorReleases() {
            val deadline = SystemClock.elapsedRealtime() + 5_000
            while (released.get() < descriptors.size && SystemClock.elapsedRealtime() < deadline) {
                SystemClock.sleep(10)
            }
        }

        private fun newDescriptor(): ParcelFileDescriptor = storage.openProxyFileDescriptor(
            ParcelFileDescriptor.MODE_READ_ONLY,
            object : ProxyFileDescriptorCallback() {
                override fun onGetSize(): Long = backing.size.toLong()
                override fun onRead(offset: Long, size: Int, data: ByteArray): Int {
                    if (offset >= backing.size) return 0
                    val count = minOf(size.toLong(), backing.size - offset).toInt()
                    backing.copyInto(data, 0, offset.toInt(), offset.toInt() + count)
                    return count
                }
                override fun onRelease() { released.incrementAndGet() }
            }, handler,
        ).also(descriptors::add)

        override fun close() {
            try { descriptors.forEach { it.close() } } finally {
                awaitDescriptorReleases()
                callbacks.quitSafely()
                callbacks.join(5_000)
                backing.fill(0)
            }
        }
    }
}
