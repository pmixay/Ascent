package com.pmixay.ascent

import ai.onnxruntime.OrtEnvironment
import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractor
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractorConfig
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/** Exercises the optimized app; actual speech/model parity runs in the model workflow. */
@RunWith(AndroidJUnit4::class)
class NativeRuntimeTest {
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
        val activity = instrumentation.startActivitySync(
            Intent(instrumentation.targetContext, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        try {
            instrumentation.waitForIdleSync()
            instrumentation.runOnMainSync {
                assertFalse(activity.isFinishing)
                assertNotNull(activity.window.decorView.findViewById<android.view.View>(android.R.id.content))
            }
        } finally {
            instrumentation.runOnMainSync { activity.finish() }
        }
    }
}
