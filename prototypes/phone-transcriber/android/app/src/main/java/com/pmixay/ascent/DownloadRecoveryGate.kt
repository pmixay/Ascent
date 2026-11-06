package com.pmixay.ascent

import java.util.concurrent.TimeUnit

/** Pure, interruptible recovery state; Android callbacks supply network identities and validation. */
internal class DownloadRecoveryGate(
    private val waiting: (Boolean) -> Unit = {},
    private val active: () -> Unit = {},
    private val inactive: () -> Unit = {},
    private val connectionInterrupted: () -> Unit = {},
) : AutoCloseable {
    private val monitor = Object()
    private var currentNetwork: Long? = null
    private var validated = false
    private var blocked = false
    private var closed = false
    private var generation = 0L

    fun networkAvailable(id: Long) {
        val changed = synchronized(monitor) {
            if (closed) return
            val changed = currentNetwork != null && currentNetwork != id
            currentNetwork = id
            validated = false
            blocked = false
            generation++
            monitor.notifyAll()
            changed
        }
        inactive()
        if (changed) connectionInterrupted()
    }

    fun networkCapabilities(id: Long, ready: Boolean) {
        val lost = synchronized(monitor) {
            if (closed || currentNetwork != id) return
            val lost = validated && !ready
            if (validated != ready) generation++
            validated = ready
            monitor.notifyAll()
            lost
        }
        if (lost) { inactive(); connectionInterrupted() }
    }

    fun networkBlocked(id: Long, isBlocked: Boolean) {
        synchronized(monitor) {
            if (closed || currentNetwork != id) return
            if (blocked != isBlocked) generation++
            blocked = isBlocked
            monitor.notifyAll()
        }
        if (isBlocked) { inactive(); connectionInterrupted() }
    }

    fun networkLost(id: Long) {
        synchronized(monitor) {
            // A late loss for Wi-Fi must not erase an already validated cellular network.
            if (closed || currentNetwork != id) return
            currentNetwork = null
            validated = false
            generation++
            monitor.notifyAll()
        }
        inactive()
        connectionInterrupted()
    }

    fun wakeForVisibleApp() = synchronized(monitor) { generation++; monitor.notifyAll() }

    fun beforeRequest() = synchronized(monitor) {
        checkOpen()
        if (!ready()) { inactive(); waiting(false) }
        while (!ready()) { checkOpen(); monitor.wait() }
        checkOpen()
        active()
    }

    fun retry(serverDelayMs: Long, attempt: Int): Boolean {
        val delay = maxOf(minOf(1_000L shl (attempt - 1).coerceIn(0, 4), 10_000L), serverDelayMs)
        synchronized(monitor) {
            checkOpen()
            inactive()
            waiting(ready())
            val initialGeneration = generation
            val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(delay)
            while (true) {
                checkOpen()
                val remaining = deadline - System.nanoTime()
                // Connectivity and explicit foreground entry bypass our short delay,
                // but never a server's Retry-After instruction.
                if (ready() && (remaining <= 0 || serverDelayMs == 0L && generation != initialGeneration)) break
                if (!ready() || remaining <= 0) monitor.wait()
                else monitor.wait(maxOf(1, TimeUnit.NANOSECONDS.toMillis(remaining)))
            }
        }
        return true
    }

    private fun ready() = currentNetwork != null && validated && !blocked
    private fun checkOpen() {
        if (closed || Thread.currentThread().isInterrupted) throw InterruptedException("Загрузка прервана")
    }

    override fun close() {
        synchronized(monitor) { closed = true; monitor.notifyAll() }
        inactive()
    }
}
