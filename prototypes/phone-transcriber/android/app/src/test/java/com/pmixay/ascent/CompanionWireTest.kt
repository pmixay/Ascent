package com.pmixay.ascent

import com.pmixay.ascent.companion.CompanionWire
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import javax.crypto.AEADBadTagException

/** CI-only protocol checks: independent of Bluetooth, Android services, models and microphones. */
class CompanionWireTest {
    private fun hello() = CompanionWire.Hello(
        UUID.fromString("0de79fab-f17b-445a-ad82-b780408f1648"),
        ByteArray(32) { (it * 7 + 11).toByte() },
        epochMs = 1_800_000_000_000L,
        elapsedMs = 65_000L,
    )

    @Test fun everyPacketRoundTripsInOneContinuousStream() {
        val greeting = hello()
        val encrypted = CompanionWire.encrypt(greeting, 9, 65_250, 4_000, pcm(8), 8)
        val packets = listOf(
            greeting,
            CompanionWire.Ready(true, "Телефон готов"),
            CompanionWire.Ready(false, ""),
            encrypted,
            CompanionWire.State(CompanionWire.STARTED, 0),
            CompanionWire.State(CompanionWire.PAUSED, 3, 271),
            CompanionWire.State(CompanionWire.STOPPED, 5, 272),
            CompanionWire.State(CompanionWire.GAP, 6),
            CompanionWire.Result("Привет, Galaxy Watch! 🎤\nSecond line"),
            CompanionWire.Command(CompanionWire.PAUSE, 271),
            CompanionWire.Command(CompanionWire.RESUME, 272),
            CompanionWire.Command(CompanionWire.STOP, 273),
            CompanionWire.Ack(9, true),
            CompanionWire.Ack(10, false),
            CompanionWire.Ping,
            CompanionWire.Pong,
        )
        val output = ByteArrayOutputStream()
        packets.forEach { CompanionWire.write(output, it) }
        val input = ByteArrayInputStream(output.toByteArray())
        packets.forEach { expected -> assertPacketEquals(expected, CompanionWire.read(input)) }
        assertEquals("A packet boundary consumed or retained bytes", 0, input.available())
        assertThrows(EOFException::class.java) { CompanionWire.read(input) }
    }

    @Test fun knownWireBytesFixPacketTypeLengthAndUtf8Encoding() {
        assertArrayEquals(byteArrayOf(0, 0, 0, 1, 8), encode(CompanionWire.Ping))
        val ready = byteArrayOf(0, 0, 0, 8, 2, 1, 0, 0, 0, 2, 0xd0.toByte(), 0xa7.toByte())
        assertArrayEquals(ready, encode(CompanionWire.Ready(true, "Ч")))
        assertEquals(CompanionWire.Ready(true, "Ч"), CompanionWire.read(ByteArrayInputStream(ready)))
    }

    @Test fun maximumPcmFrameSurvivesEncryptionSerializationAndDecryptionWithoutMutation() {
        val greeting = hello()
        val originalKey = greeting.key.copyOf()
        val clear = pcm(CompanionWire.FRAME_SAMPLES)
        val originalPcm = clear.copyOf()
        val frame = CompanionWire.encrypt(greeting, 42, 65_250, 12_000, clear, CompanionWire.FRAME_SAMPLES)
        assertEquals(clear.size + 16, frame.ciphertext.size)
        assertFalse("Audio was sent as clear PCM", clear.contentEquals(frame.ciphertext.copyOf(clear.size)))
        val serialized = CompanionWire.read(ByteArrayInputStream(encode(frame))) as CompanionWire.Frame
        val originalCiphertext = serialized.ciphertext.copyOf()
        assertArrayEquals(clear, CompanionWire.decrypt(greeting, serialized))
        assertArrayEquals("Encryption changed the microphone buffer", originalPcm, clear)
        assertArrayEquals("Decryption changed the queued encrypted frame", originalCiphertext, serialized.ciphertext)
        assertArrayEquals("Encryption changed the session key", originalKey, greeting.key)
        val following = CompanionWire.encrypt(greeting, 43, 65_500, 16_000, clear, CompanionWire.FRAME_SAMPLES)
        assertFalse("Two frame sequences reused the same cipher stream", frame.ciphertext.contentEquals(following.ciphertext))
        assertArrayEquals(clear, CompanionWire.decrypt(greeting, following))
    }

    @Test fun gcmRejectsModifiedCiphertextAuthenticationTagAndEveryAuthenticatedFrameField() {
        val greeting = hello()
        val frame = CompanionWire.encrypt(greeting, 20, 65_250, 8_000, pcm(8), 8)
        val modifiedAudio = frame.ciphertext.copyOf().apply { this[0] = (this[0].toInt() xor 1).toByte() }
        val modifiedTag = frame.ciphertext.copyOf().apply {
            this[lastIndex] = (this[lastIndex].toInt() xor 1).toByte()
        }
        val invalid = listOf(
            frame.copy(ciphertext = modifiedAudio),
            frame.copy(ciphertext = modifiedTag),
            frame.copy(sequence = frame.sequence + 1),
            frame.copy(capturedElapsedMs = frame.capturedElapsedMs + 1),
            frame.copy(sampleOffset = frame.sampleOffset + 1),
            // Keep the structural length valid so authentication, rather than a size check, rejects it.
            frame.copy(sampleCount = frame.sampleCount + 1, ciphertext = frame.ciphertext + byteArrayOf(0, 0)),
        )
        invalid.forEach { changed ->
            assertThrows("Unauthenticated frame metadata/audio was accepted: $changed", AEADBadTagException::class.java) {
                CompanionWire.decrypt(greeting, changed)
            }
        }
        assertArrayEquals(pcm(8), CompanionWire.decrypt(greeting, frame))
    }

    @Test fun framesCannotBeDecryptedUnderADifferentSessionOrKey() {
        val greeting = hello()
        val frame = CompanionWire.encrypt(greeting, 1, 65_250, 0, pcm(4), 4)
        val otherSession = greeting.copy(session = UUID(123, 456))
        val otherKey = greeting.copy(key = greeting.key.copyOf().apply { this[0] = (this[0].toInt() xor 1).toByte() })
        for (other in listOf(otherSession, otherKey)) {
            assertThrows(AEADBadTagException::class.java) { CompanionWire.decrypt(other, frame) }
        }
    }

    @Test fun pcmAndCiphertextShapeAreValidatedBeforeCryptography() {
        val greeting = hello()
        val clear = pcm(4)
        val frame = CompanionWire.encrypt(greeting, 1, 65_250, 0, clear, 4)
        val badEncryptions = listOf<() -> Unit>(
            { CompanionWire.encrypt(greeting, -1, 65_250, 0, clear, 4) },
            { CompanionWire.encrypt(greeting, 1, 65_250, -1, clear, 4) },
            { CompanionWire.encrypt(greeting, 1, 65_250, 0, clear, 0) },
            { CompanionWire.encrypt(greeting, 1, 65_250, 0, clear, CompanionWire.FRAME_SAMPLES + 1) },
            { CompanionWire.encrypt(greeting, 1, 65_250, 0, clear.copyOf(7), 4) },
            { CompanionWire.encrypt(greeting.copy(key = ByteArray(31)), 1, 65_250, 0, clear, 4) },
        )
        badEncryptions.forEach { operation -> assertThrows(IllegalArgumentException::class.java) { operation() } }
        val invalid = listOf(
            frame.copy(sequence = -1),
            frame.copy(sampleOffset = -1),
            frame.copy(sampleCount = 0),
            frame.copy(sampleCount = CompanionWire.FRAME_SAMPLES + 1),
            frame.copy(ciphertext = frame.ciphertext.copyOf(frame.ciphertext.size - 1)),
            frame.copy(ciphertext = frame.ciphertext + byteArrayOf(0)),
        )
        invalid.forEach { changed ->
            assertThrows(IllegalArgumentException::class.java) { CompanionWire.decrypt(greeting, changed) }
        }
        assertThrows(IllegalArgumentException::class.java) {
            CompanionWire.decrypt(greeting.copy(key = ByteArray(33)), frame)
        }
    }

    @Test fun invalidOuterLengthsAreRejectedBeforeTheBodyIsRead() {
        for (size in listOf(-1, 0, CompanionWire.MAX_PACKET_BYTES + 1, Int.MAX_VALUE)) {
            val prefixOnly = ByteBuffer.allocate(4).putInt(size).array()
            // With no body, EOF would indicate the decoder trusted the invalid allocation length.
            assertThrows(IllegalArgumentException::class.java) { CompanionWire.read(ByteArrayInputStream(prefixOnly)) }
        }
    }

    @Test fun truncatedPacketsFailRatherThanReturningPartialData() {
        val encoded = encode(hello())
        for (size in listOf(0, 1, 3, 4, encoded.size - 1)) {
            assertThrows(EOFException::class.java) { CompanionWire.read(ByteArrayInputStream(encoded.copyOf(size))) }
        }
        val missingText = rawPacket { writeByte(5); writeInt(3); write(byteArrayOf(65, 66)) }
        assertThrows(EOFException::class.java) { CompanionWire.read(ByteArrayInputStream(missingText)) }
    }

    @Test fun malformedTextAndFrameLengthsAreRejectedWithoutAllocatingTheirClaimedBodies() {
        for (length in listOf(-1, 8_001, Int.MAX_VALUE)) {
            val encoded = rawPacket { writeByte(5); writeInt(length) }
            assertThrows(IllegalArgumentException::class.java) { CompanionWire.read(ByteArrayInputStream(encoded)) }
        }
        for ((samples, length) in listOf(0 to 16, (CompanionWire.FRAME_SAMPLES + 1) to 18,
            1 to -1, 1 to 17, 1 to 19, 1 to Int.MAX_VALUE)) {
            val encoded = rawPacket {
                writeByte(3); writeLong(1); writeLong(65_250); writeLong(0); writeInt(samples); writeInt(length)
            }
            assertThrows(IllegalArgumentException::class.java) { CompanionWire.read(ByteArrayInputStream(encoded)) }
        }
    }

    @Test fun unsupportedProtocolUnknownPacketAndTrailingBodyBytesAreRejected() {
        val unknown = rawPacket { writeByte(255) }
        assertThrows(IllegalStateException::class.java) { CompanionWire.read(ByteArrayInputStream(unknown)) }
        val futureHello = encode(hello()).apply { ByteBuffer.wrap(this).putInt(5, 2) }
        assertThrows(IllegalArgumentException::class.java) { CompanionWire.read(ByteArrayInputStream(futureHello)) }
        val trailing = rawPacket { writeByte(8); writeByte(0) }
        assertThrows(IllegalArgumentException::class.java) { CompanionWire.read(ByteArrayInputStream(trailing)) }
    }

    @Test fun writersRejectOversizedFramesAndInvalidSessionKeys() {
        val oversized = CompanionWire.Frame(1, 65_250, 0, CompanionWire.FRAME_SAMPLES,
            ByteArray(CompanionWire.FRAME_SAMPLES * 2 + 17))
        val output = ByteArrayOutputStream()
        assertThrows(IllegalArgumentException::class.java) { CompanionWire.write(output, oversized) }
        assertEquals("A failed write left a partial packet on the socket", 0, output.size())
        assertThrows(IllegalArgumentException::class.java) { CompanionWire.write(output, hello().copy(key = ByteArray(31))) }
        assertEquals(0, output.size())
    }

    private fun pcm(samples: Int): ByteArray = ByteBuffer.allocate(samples * 2).order(ByteOrder.LITTLE_ENDIAN).apply {
        val boundarySamples = shortArrayOf(Short.MIN_VALUE, -1, 0, 1, Short.MAX_VALUE)
        repeat(samples) { putShort(boundarySamples[it % boundarySamples.size]) }
    }.array()

    private fun encode(packet: CompanionWire.Packet): ByteArray = ByteArrayOutputStream().apply {
        CompanionWire.write(this, packet)
    }.toByteArray()

    private fun rawPacket(body: DataOutputStream.() -> Unit): ByteArray {
        val payload = ByteArrayOutputStream().apply { DataOutputStream(this).use(body) }.toByteArray()
        return ByteArrayOutputStream().apply {
            DataOutputStream(this).use { write -> write.writeInt(payload.size); write.write(payload) }
        }.toByteArray()
    }

    private fun assertPacketEquals(expected: CompanionWire.Packet, actual: CompanionWire.Packet) {
        when (expected) {
            is CompanionWire.Hello -> {
                assertTrue(actual is CompanionWire.Hello)
                actual as CompanionWire.Hello
                assertEquals(expected.session, actual.session)
                assertArrayEquals(expected.key, actual.key)
                assertEquals(expected.epochMs, actual.epochMs)
                assertEquals(expected.elapsedMs, actual.elapsedMs)
            }
            is CompanionWire.Frame -> {
                assertTrue(actual is CompanionWire.Frame)
                actual as CompanionWire.Frame
                assertEquals(expected.sequence, actual.sequence)
                assertEquals(expected.capturedElapsedMs, actual.capturedElapsedMs)
                assertEquals(expected.sampleOffset, actual.sampleOffset)
                assertEquals(expected.sampleCount, actual.sampleCount)
                assertArrayEquals(expected.ciphertext, actual.ciphertext)
            }
            else -> assertEquals(expected, actual)
        }
    }
}
