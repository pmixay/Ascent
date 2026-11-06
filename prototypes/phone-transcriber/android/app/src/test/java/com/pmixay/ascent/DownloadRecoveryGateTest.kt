package com.pmixay.ascent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Exercises reconnect signals without Android callbacks, real networks or model payloads. */
class DownloadRecoveryGateTest {
    @Test fun validatedConnectionWakesAnOfflineRequestWithoutPolling() {
        val waiting = CountDownLatch(1)
        val active = AtomicInteger()
        val gate = DownloadRecoveryGate(waiting = { waiting.countDown() }, active = { active.incrementAndGet() })
        val executor = Executors.newSingleThreadExecutor()
        try {
            val request = executor.submit<Boolean> { gate.beforeRequest(); true }
            assertTrue(waiting.await(2, TimeUnit.SECONDS))
            assertFalse(request.isDone)
            gate.connect()
            assertTrue(request.get(2, TimeUnit.SECONDS))
            assertEquals(1, active.get())
        } finally { gate.close(); executor.shutdownNow() }
    }

    @Test fun reconnectionBypassesTheCappedRetryDelay() {
        val waiting = CountDownLatch(1)
        val gate = DownloadRecoveryGate(waiting = { waiting.countDown() })
        val executor = Executors.newSingleThreadExecutor()
        try {
            gate.connect()
            val retry = executor.submit<Boolean> { gate.retry(serverDelayMs = 0, attempt = 10) }
            assertTrue(waiting.await(2, TimeUnit.SECONDS))
            assertFalse(retry.isDone)
            gate.networkLost(1)
            gate.connect(2)
            // The ordinary attempt-10 delay is ten seconds; an event must release it promptly.
            assertTrue(retry.get(2, TimeUnit.SECONDS))
        } finally { gate.close(); executor.shutdownNow() }
    }

    @Test fun reopeningTheAppWakesAConnectedRetryWithoutStartingAnotherTransfer() {
        val waiting = CountDownLatch(1)
        val gate = DownloadRecoveryGate(waiting = { waiting.countDown() })
        val executor = Executors.newSingleThreadExecutor()
        try {
            gate.connect()
            val retry = executor.submit<Boolean> { gate.retry(serverDelayMs = 0, attempt = 10) }
            assertTrue(waiting.await(2, TimeUnit.SECONDS))
            gate.wakeForVisibleApp()
            assertTrue(retry.get(2, TimeUnit.SECONDS))
        } finally { gate.close(); executor.shutdownNow() }
    }

    @Test fun serverRetryAfterIsNotBypassedByConnectivityOrForegroundEvents() {
        val waiting = CountDownLatch(1)
        val completed = CountDownLatch(1)
        val gate = DownloadRecoveryGate(waiting = { waiting.countDown() })
        val executor = Executors.newSingleThreadExecutor()
        try {
            gate.connect()
            val retry = executor.submit<Boolean> {
                gate.retry(serverDelayMs = 1_000, attempt = 1).also { completed.countDown() }
            }
            assertTrue(waiting.await(2, TimeUnit.SECONDS))
            gate.networkLost(1)
            gate.connect(2)
            gate.wakeForVisibleApp()
            assertFalse("A server's retry deadline was ignored", completed.await(150, TimeUnit.MILLISECONDS))
            assertTrue(retry.get(2, TimeUnit.SECONDS))
        } finally { gate.close(); executor.shutdownNow() }
    }

    @Test fun closingTheServiceGateInterruptsAnOfflineWait() {
        val waiting = CountDownLatch(1)
        val gate = DownloadRecoveryGate(waiting = { waiting.countDown() })
        val executor = Executors.newSingleThreadExecutor()
        try {
            val request = executor.submit<Boolean> { gate.beforeRequest(); true }
            assertTrue(waiting.await(2, TimeUnit.SECONDS))
            gate.close()
            try {
                request.get(2, TimeUnit.SECONDS)
                throw AssertionError("A closed network gate must interrupt its waiter")
            } catch (error: ExecutionException) {
                assertTrue(error.cause is InterruptedException)
            }
        } finally { gate.close(); executor.shutdownNow() }
    }

    @Test fun staleCallbacksCannotPauseOrCancelTheNewDefaultNetwork() {
        val interrupted = AtomicInteger()
        val gate = DownloadRecoveryGate(connectionInterrupted = { interrupted.incrementAndGet() })
        val executor = Executors.newSingleThreadExecutor()
        try {
            gate.connect(1)
            gate.connect(2)
            val afterHandover = interrupted.get()
            gate.networkLost(1)
            gate.networkCapabilities(1, false)
            gate.networkBlocked(1, true)
            val request = executor.submit<Boolean> { gate.beforeRequest(); true }
            assertTrue(request.get(2, TimeUnit.SECONDS))
            assertEquals(afterHandover, interrupted.get())
        } finally { gate.close(); executor.shutdownNow() }
    }

    private fun DownloadRecoveryGate.connect(id: Long = 1) {
        networkAvailable(id)
        networkCapabilities(id, true)
    }
}
