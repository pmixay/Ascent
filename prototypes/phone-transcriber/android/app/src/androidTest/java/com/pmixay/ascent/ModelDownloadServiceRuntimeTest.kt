package com.pmixay.ascent

import android.Manifest
import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.Configuration
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Exercises the actual Android service lifecycle with no production model/network traffic. */
@RunWith(AndroidJUnit4::class)
class ModelDownloadServiceRuntimeTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val serviceIntent get() = Intent(context, ModelDownloadService::class.java)
    private var activity: Activity? = null
    private lateinit var previousRunner: ModelTransferRunner
    private var previousComponentState = PackageManager.COMPONENT_ENABLED_STATE_DEFAULT
    private val completion = CountDownLatch(1)
    private val entered = CountDownLatch(1)
    private val exited = CountDownLatch(1)
    private val calls = AtomicInteger()
    private val pulses = AtomicInteger()

    @Before fun isolateTransferAndScheduler() {
        previousRunner = ModelDownloads.transferRunner
        previousComponentState = context.packageManager.getComponentEnabledSetting(ComponentName(context, ModelDownloadService::class.java))
        context.stopService(serviceIntent)
        awaitCondition("An earlier service did not stop") { !ModelDownloadService.running.value }
        context.packageManager.setComponentEnabledSetting(ComponentName(context, ModelDownloadService::class.java),
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED, PackageManager.DONT_KILL_APP)
        WorkManagerTestInitHelper.initializeTestWorkManager(context,
            Configuration.Builder().setExecutor(SynchronousExecutor()).build())
        WorkManager.getInstance(context).cancelAllWork().result.get(10, TimeUnit.SECONDS)
        if (Build.VERSION.SDK_INT >= 33) {
            instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.POST_NOTIFICATIONS)
        }
        ModelDownloads.transferRunner = ModelTransferRunner { _, _, progress ->
            calls.incrementAndGet()
            entered.countDown()
            try {
                // Deliberately bypass control.beforeRequest(): this fake does not use a network.
                // A blocking transfer must remain active when the UI's ViewModel is cleared.
                while (!completion.await(40, TimeUnit.MILLISECONDS)) {
                    val current = pulses.incrementAndGet()
                    progress(ModelDownloadProgress(current * 1024L, 100_000_000L))
                }
            } finally { exited.countDown() }
        }
    }

    @After fun restoreTransferAndStopService() {
        try {
            instrumentation.runOnMainSync { activity?.finish() }
            instrumentation.waitForIdleSync()
            context.stopService(serviceIntent)
            completion.countDown()
            awaitCondition("Download service leaked after test") { !ModelDownloadService.running.value }
            if (calls.get() > 0) assertTrue("Transfer runner did not exit", exited.await(10, TimeUnit.SECONDS))
        } finally {
            try { WorkManager.getInstance(context).cancelAllWork().result.get(10, TimeUnit.SECONDS) }
            finally {
                if (::previousRunner.isInitialized) ModelDownloads.transferRunner = previousRunner
                context.packageManager.setComponentEnabledSetting(ComponentName(context, ModelDownloadService::class.java),
                    previousComponentState, PackageManager.DONT_KILL_APP)
            }
        }
    }

    @Test fun transferContinuesAfterActivityClosesAndRepeatedStartsStaySingle() {
        val opened = openActivity()
        assertTrue("Foreground transfer did not start", entered.await(15, TimeUnit.SECONDS))
        assertTrue(ModelDownloadService.running.value)
        instrumentation.runOnMainSync {
            ModelDownloads.startFromVisible(opened)
            ModelDownloads.startFromVisible(opened)
        }
        instrumentation.waitForIdleSync()
        val beforeClose = pulses.get()
        instrumentation.runOnMainSync { opened.finish() }
        instrumentation.waitForIdleSync()
        awaitCondition("Transfer stopped progressing with the activity closed") { pulses.get() >= beforeClose + 3 }
        assertTrue("Closing the activity stopped its download service", ModelDownloadService.running.value)
        assertEquals("Repeated visible starts created concurrent transfers", 1, calls.get())
        completion.countDown()
        assertTrue("Transfer did not complete", exited.await(10, TimeUnit.SECONDS))
        awaitCondition("Completed transfer did not stop its foreground service") { !ModelDownloadService.running.value }
    }

    @Test fun stoppingServiceInterruptsBlockedTransferAndReleasesItsLifetime() {
        val opened = openActivity()
        assertTrue("Foreground transfer did not start", entered.await(15, TimeUnit.SECONDS))
        instrumentation.runOnMainSync { opened.finish() }
        instrumentation.waitForIdleSync()
        assertTrue(context.stopService(serviceIntent))
        assertTrue("Service stop did not interrupt the blocked transfer", exited.await(10, TimeUnit.SECONDS))
        awaitCondition("Service remained running after stop") { !ModelDownloadService.running.value }
        assertEquals(1, calls.get())
    }

    @Test fun serviceManifestSupportsIndependentDataTransfers() {
        @Suppress("DEPRECATION")
        val info = context.packageManager.getServiceInfo(ComponentName(context, ModelDownloadService::class.java), 0)
        assertFalse(info.exported)
        assertEquals(0, info.flags and ServiceInfo.FLAG_STOP_WITH_TASK)
        assertTrue(info.foregroundServiceType and ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC != 0)
    }

    private fun openActivity(): Activity = instrumentation.startActivitySync(
        Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
    ).also { activity = it; instrumentation.waitForIdleSync() }

    private fun awaitCondition(message: String, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 15_000
        while (!condition() && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(20)
        assertTrue(message, condition())
    }
}
