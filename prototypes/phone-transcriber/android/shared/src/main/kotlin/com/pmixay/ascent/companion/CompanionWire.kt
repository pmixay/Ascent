package com.pmixay.ascent.companion

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.security.SecureRandom
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Used only inside an authenticated, encrypted Bluetooth RFCOMM socket between bonded devices. */
object CompanionWire {
    val SERVICE_UUID: UUID = UUID.fromString("9c474f54-1a26-43da-82cb-ae837450c55c")
    const val SAMPLE_RATE = 16_000
    const val FRAME_SAMPLES = 4_000
    const val MAX_FRAME_AGE_MS = 2_500L
    const val WINDOW_SAMPLES = SAMPLE_RATE * 8
    const val MAX_PACKET_BYTES = 16_384
    const val STARTED = 1
    const val PAUSED = 2
    const val STOPPED = 3
    const val GAP = 4
    const val PAUSE = 1
    const val RESUME = 2
    const val STOP = 3

    sealed interface Packet
    data class Hello(val session: UUID, val key: ByteArray, val epochMs: Long, val elapsedMs: Long) : Packet
    data class Ready(val accepted: Boolean, val message: String) : Packet
    data class Frame(val sequence: Long, val capturedElapsedMs: Long, val sampleOffset: Long,
        val sampleCount: Int, val ciphertext: ByteArray) : Packet
    data class State(val state: Int, val droppedFrames: Long, val commandId: Long = 0) : Packet
    data class Result(val text: String) : Packet
    data class Command(val action: Int, val id: Long) : Packet
    data class Ack(val sequence: Long, val accepted: Boolean) : Packet
    data object Ping : Packet
    data object Pong : Packet

    fun hello(epochMs: Long, elapsedMs: Long): Hello = Hello(UUID.randomUUID(),
        ByteArray(32).also(SecureRandom()::nextBytes), epochMs, elapsedMs)

    fun encrypt(hello: Hello, sequence: Long, capturedElapsedMs: Long, sampleOffset: Long,
        pcm: ByteArray, sampleCount: Int): Frame {
        require(sequence >= 0 && sampleOffset >= 0 && sampleCount in 1..FRAME_SAMPLES && pcm.size == sampleCount * 2)
        val frame = Frame(sequence, capturedElapsedMs, sampleOffset, sampleCount, ByteArray(0))
        val cipher = cipher(Cipher.ENCRYPT_MODE, hello, frame)
        return frame.copy(ciphertext = cipher.doFinal(pcm))
    }

    fun decrypt(hello: Hello, frame: Frame): ByteArray {
        require(frame.sequence >= 0 && frame.sampleOffset >= 0 && frame.sampleCount in 1..FRAME_SAMPLES)
        require(frame.ciphertext.size == frame.sampleCount * 2 + 16)
        return cipher(Cipher.DECRYPT_MODE, hello, frame).doFinal(frame.ciphertext)
    }

    private fun cipher(mode: Int, hello: Hello, frame: Frame): Cipher {
        require(hello.key.size == 32)
        val nonce = ByteBuffer.allocate(12).putInt(0x41534331).putLong(frame.sequence).array()
        val aad = ByteBuffer.allocate(48).putInt(1).putLong(hello.session.mostSignificantBits)
            .putLong(hello.session.leastSignificantBits).putLong(frame.sequence)
            .putLong(frame.capturedElapsedMs).putLong(frame.sampleOffset).putInt(frame.sampleCount).array()
        return Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(mode, SecretKeySpec(hello.key, "AES"), GCMParameterSpec(128, nonce))
            updateAAD(aad)
        }
    }

    fun write(output: OutputStream, packet: Packet) {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { data ->
            when (packet) {
                is Hello -> { data.writeByte(1); data.writeInt(1); data.writeLong(packet.session.mostSignificantBits)
                    data.writeLong(packet.session.leastSignificantBits); require(packet.key.size == 32)
                    data.write(packet.key); data.writeLong(packet.epochMs); data.writeLong(packet.elapsedMs) }
                is Ready -> { data.writeByte(2); data.writeBoolean(packet.accepted); text(data, packet.message) }
                is Frame -> { data.writeByte(3); data.writeLong(packet.sequence); data.writeLong(packet.capturedElapsedMs)
                    data.writeLong(packet.sampleOffset); data.writeInt(packet.sampleCount)
                    require(packet.ciphertext.size <= FRAME_SAMPLES * 2 + 16)
                    data.writeInt(packet.ciphertext.size); data.write(packet.ciphertext) }
                is State -> { data.writeByte(4); data.writeInt(packet.state); data.writeLong(packet.droppedFrames); data.writeLong(packet.commandId) }
                is Result -> { data.writeByte(5); text(data, packet.text) }
                is Command -> { data.writeByte(6); data.writeInt(packet.action); data.writeLong(packet.id) }
                is Ack -> { data.writeByte(7); data.writeLong(packet.sequence); data.writeBoolean(packet.accepted) }
                Ping -> data.writeByte(8)
                Pong -> data.writeByte(9)
            }
        }
        val payload = bytes.toByteArray()
        require(payload.size in 1..MAX_PACKET_BYTES)
        synchronized(output) {
            DataOutputStream(output).apply { writeInt(payload.size); write(payload); flush() }
        }
        if (packet is Hello) payload.fill(0)
    }

    fun read(input: InputStream): Packet {
        val data = DataInputStream(input)
        val size = data.readInt()
        require(size in 1..MAX_PACKET_BYTES) { "Invalid companion packet size" }
        val payload = ByteArray(size)
        data.readFully(payload)
        try {
            val body = DataInputStream(ByteArrayInputStream(payload))
            val packet = when (body.readUnsignedByte()) {
                1 -> { require(body.readInt() == 1); val session = UUID(body.readLong(), body.readLong())
                    val key = ByteArray(32).also(body::readFully)
                    Hello(session, key, body.readLong(), body.readLong()) }
                2 -> Ready(body.readBoolean(), text(body))
                3 -> { val sequence = body.readLong(); val captured = body.readLong(); val offset = body.readLong()
                    val samples = body.readInt(); val length = body.readInt()
                    require(samples in 1..FRAME_SAMPLES && length == samples * 2 + 16)
                    Frame(sequence, captured, offset, samples, ByteArray(length).also(body::readFully)) }
                4 -> State(body.readInt(), body.readLong(), body.readLong())
                5 -> Result(text(body))
                6 -> Command(body.readInt(), body.readLong())
                7 -> Ack(body.readLong(), body.readBoolean())
                8 -> Ping
                9 -> Pong
                else -> error("Unknown companion packet")
            }
            require(body.available() == 0) { "Trailing companion data" }
            return packet
        } finally { payload.fill(0) }
    }

    private fun text(output: DataOutputStream, value: String) {
        val bytes = value.take(2_000).toByteArray(Charsets.UTF_8)
        require(bytes.size <= 8_000)
        output.writeInt(bytes.size); output.write(bytes)
    }

    private fun text(input: DataInputStream): String {
        val length = input.readInt()
        require(length in 0..8_000)
        return ByteArray(length).also(input::readFully).toString(Charsets.UTF_8)
    }
}
