package com.pmixay.ascent

import android.os.SystemClock
import com.pmixay.ascent.companion.CompanionWire
import kotlinx.coroutines.sync.Mutex

internal object AudioWork {
    val inference = Mutex()
    val history = Mutex()
}

/** The watch window remains encrypted between bounded reads and is never written to a file. */
internal class WatchPcmWindow(
    private val hello: CompanionWire.Hello,
    private val frames: List<CompanionWire.Frame>,
    private val expiresAtElapsedMs: Long,
) : PcmWindowSource {
    @Volatile private var closed = false
    override val sampleCount = frames.sumOf { it.sampleCount.toLong() }
    override val formatDescription = "Микрофон часов, 16 кГц, моно"

    override fun read(start: Long, count: Int): FloatArray {
        require(start >= 0 && count >= 0 && start + count <= sampleCount)
        check(!closed) { "Источник аудио закрыт" }
        check(SystemClock.elapsedRealtime() < expiresAtElapsedMs) { "Фрагмент с часов устарел" }
        if (Thread.currentThread().isInterrupted) throw InterruptedException("Обработка отменена")
        val output = FloatArray(count)
        try {
            var frameStart = 0L
            for (frame in frames) {
                check(SystemClock.elapsedRealtime() < expiresAtElapsedMs) { "Фрагмент с часов устарел" }
                if (Thread.currentThread().isInterrupted) throw InterruptedException("Обработка отменена")
                val from = maxOf(start, frameStart)
                val to = minOf(start + count, frameStart + frame.sampleCount)
                if (from < to) {
                    val pcm = CompanionWire.decrypt(hello, frame)
                    try {
                        for (sample in from until to) {
                            val index = ((sample - frameStart) * 2).toInt()
                            output[(sample - start).toInt()] =
                                (((pcm[index].toInt() and 255) or (pcm[index + 1].toInt() shl 8)).toShort().toInt()) / 32768f
                        }
                    } finally { pcm.fill(0) }
                }
                frameStart += frame.sampleCount
            }
            return output
        } catch (error: Throwable) {
            output.fill(0f)
            throw error
        }
    }

    override fun close() {
        closed = true
        frames.forEach { it.ciphertext.fill(0) }
        hello.key.fill(0)
    }
}
