package com.pmixay.ascent

import ai.onnxruntime.OrtEnvironment
import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.Configuration
import androidx.work.NetworkType
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractor
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractorConfig
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.BeforeClass
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/** Exercises the optimized app; actual speech/model parity runs in the model workflow. */
@RunWith(AndroidJUnit4::class)
class NativeRuntimeTest {
    companion object {
        @BeforeClass @JvmStatic fun initializeBackgroundWorkForTests() {
            // Network constraints are controlled by TestDriver, so opening the activity
            // does not download production weights as a side effect of this smoke test.
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            WorkManagerTestInitHelper.initializeTestWorkManager(context,
                Configuration.Builder().setExecutor(SynchronousExecutor()).build())
        }
    }

    @Test fun bothInferenceLibrariesLoadInOneAndroidProcess() {
        val environment = OrtEnvironment.getEnvironment()
        environment.setTelemetry(false)
        assertNotNull(environment)
        // Resolves sherpa's C API against its isolated bundled runtime after Java ORT loads.
        System.loadLibrary("sherpa-onnx-jni")
    }

    @Test fun optimizedSpeakerBindingReachesNativeConfigurationValidation() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val missingModel = File(context.cacheDir, "missing-${UUID.randomUUID()}.onnx")
        assertFalse(missingModel.exists())
        try {
            val extractor = SpeakerEmbeddingExtractor(config = SpeakerEmbeddingExtractorConfig(
                model = missingModel.absolutePath, numThreads = 1, debug = false, provider = "cpu",
            ))
            extractor.release()
            fail("The missing model must be rejected by native validation")
        } catch (error: IllegalArgumentException) {
            assertTrue(error.message.orEmpty().contains("SpeakerEmbeddingExtractorConfig"))
        }
    }

    @Test fun optimizedActivityAndViewModelStart() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        if (Build.VERSION.SDK_INT >= 33) {
            instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.POST_NOTIFICATIONS)
        }
        val component = ComponentName(context, ModelDownloadService::class.java)
        val previous = context.packageManager.getComponentEnabledSetting(component)
        val preferences = context.getSharedPreferences("ascent", android.content.Context.MODE_PRIVATE)
        val previousMode = preferences.getString("recognitionMode", null)
        preferences.edit().putString("recognitionMode", "AUTO").commit()
        // This smoke test exercises UI startup and language selection only. Service lifecycle tests supply
        // an isolated fake transfer; no instrumentation test downloads real weights.
        context.packageManager.setComponentEnabledSetting(component,
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP)
        var activity: android.app.Activity? = null
        try {
            val started = instrumentation.startActivitySync(
                Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            activity = started
            instrumentation.waitForIdleSync()
            instrumentation.runOnMainSync {
                assertFalse(started.isFinishing)
                assertNotNull(started.window.decorView.findViewById<android.view.View>(android.R.id.content))
            }
            // Exercise the real optimized UI and ViewModel, without loading models or audio.
            // Accessibility actions also verify that whole labelled rows expose their click action.
            waitForUiIdle()
            for ((label, stored) in listOf("Русский" to "RUSSIAN_CIS", "Другие языки" to "MULTILINGUAL")) {
                clickText(if (stored == "RUSSIAN_CIS") "Язык: Авто" else "Язык: Русский")
                clickText(label)
                assertEquals(stored, preferences.getString("recognitionMode", null))
                assertTrue(uiHasText("Язык: $label"))
            }
            assertTrue(uiHasText("Модели для других языков не установлены. Выберите «Авто» или «Русский»."))
            instrumentation.runOnMainSync { activity?.finish() }
            instrumentation.waitForIdleSync()
            activity = instrumentation.startActivitySync(
                Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            waitForUiIdle()
            assertTrue("The selected language survives ViewModel/activity recreation", uiHasText("Язык: Другие языки"))
            clickText("Язык: Другие языки")
            clickText("Авто")
            assertEquals("AUTO", preferences.getString("recognitionMode", null))
        } finally {
            instrumentation.runOnMainSync { activity?.finish() }
            instrumentation.waitForIdleSync()
            context.packageManager.setComponentEnabledSetting(component, previous, PackageManager.DONT_KILL_APP)
            preferences.edit().apply {
                if (previousMode == null) remove("recognitionMode") else putString("recognitionMode", previousMode)
            }.commit()
        }
    }

    private fun waitForUiIdle() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.waitForIdleSync()
        instrumentation.uiAutomation.waitForIdle(250, 10_000)
    }

    private fun uiHasText(text: String): Boolean = InstrumentationRegistry.getInstrumentation()
        .uiAutomation.rootInActiveWindow?.findAccessibilityNodeInfosByText(text)?.any { hasExactLabel(it, text) } == true

    private fun hasExactLabel(node: AccessibilityNodeInfo, text: String): Boolean =
        node.text?.toString()?.lineSequence()?.any { it == text } == true

    private fun clickText(text: String) {
        val root = checkNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.rootInActiveWindow)
        // Android's query is a case-insensitive substring search: "Русский" also matches
        // Auto's subtitle. Select the exact label within a merged semantics node instead.
        var node = root.findAccessibilityNodeInfosByText(text).firstOrNull { hasExactLabel(it, text) }
        while (node != null && !node.isClickable) node = node.parent
        assertNotNull("Missing accessible control: $text", node)
        assertTrue("Disabled control: $text", node!!.isEnabled)
        assertTrue("Failed accessibility action: $text", node.performAction(AccessibilityNodeInfo.ACTION_CLICK))
        waitForUiIdle()
    }

    @Test fun automaticModelWorkIsUniqueAndWaitsForNetwork() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        ModelDownloads.schedule(context)
        ModelDownloads.schedule(context)
        val manager = WorkManager.getInstance(context)
        val transfers = manager.getWorkInfosForUniqueWork("ascent-model-download").get().filter { !it.state.isFinished }
        assertEquals(1, transfers.size)
        assertEquals(NetworkType.CONNECTED, transfers.single().constraints.requiredNetworkType)
        assertEquals(1, manager.getWorkInfosForUniqueWork("ascent-model-updates").get().size)
        // WorkerFactory must still resolve these constructors in the R8-optimized APK.
        assertNotNull(TestListenableWorkerBuilder<ModelDownloadWorker>(context).build())
        assertNotNull(TestListenableWorkerBuilder<ModelUpdateWorker>(context).build())
    }

}
