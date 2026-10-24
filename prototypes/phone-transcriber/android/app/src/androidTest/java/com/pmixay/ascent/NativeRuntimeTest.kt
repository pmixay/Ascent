package com.pmixay.ascent

import ai.onnxruntime.OrtEnvironment
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith

/** Verifies native co-loading; real model/speech parity runs in the model-pack workflow. */
@RunWith(AndroidJUnit4::class)
class NativeRuntimeTest {
    @Test fun bothInferenceLibrariesLoadInOneAndroidProcess() {
        val environment = OrtEnvironment.getEnvironment()
        environment.setTelemetry(false)
        assertNotNull(environment)
        // Resolves sherpa's C API against its isolated bundled runtime after Java ORT loads.
        System.loadLibrary("sherpa-onnx-jni")
    }
}
