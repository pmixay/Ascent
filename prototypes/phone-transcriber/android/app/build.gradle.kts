plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// The downloadable APK targets phones. CI selects x86_64 only for emulator tests.
val ascentAbi = providers.gradleProperty("ascentAbi").orElse("arm64-v8a").get()
require(ascentAbi in setOf("arm64-v8a", "x86_64")) { "Unsupported Ascent ABI: $ascentAbi" }

android {
    namespace = "com.pmixay.ascent"
    compileSdk {
        version = release(37) {
            minorApiLevel = 2
        }
    }
    buildToolsVersion = "37.0.0"
    defaultConfig {
        applicationId = "com.pmixay.ascent"
        minSdk = 31
        targetSdk = 37
        versionCode = 1
        versionName = "0.1"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk { abiFilters += ascentAbi }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    // Built-in Kotlin follows compileOptions.targetCompatibility.
    // CI-only public speech and real embeddings: JVM resources never enter the phone APK.
    sourceSets.getByName("test").resources.srcDir(layout.buildDirectory.dir("generated/speaker-fixtures"))
    buildFeatures { compose = true }
    lint { abortOnError = true }
    packaging {
        // Smaller direct APK downloads; Android extracts native libraries on install.
        jniLibs { useLegacyPackaging = true }
        dex { useLegacyPackaging = true }
    }
    buildTypes {
        debug {
            // Retain debug signing for installation, with production-style R8 optimization.
            isDebuggable = false
            optimization { enable = true }
        }
        release { optimization { enable = true } }
    }
}

dependencies {
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    compileOnly(libs.compose.ui.tooling.preview)
    implementation(libs.activity.compose)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.lifecycle.viewmodel.ktx)
    implementation(libs.coroutines.android)
    implementation(libs.work.runtime)
    implementation(libs.okhttp)
    implementation(libs.onnxruntime.android)
    // Isolates sherpa's C runtime from the ASR Java runtime. See scripts/prepare_android.py.
    implementation(files("libs/sherpa-onnx-${libs.versions.sherpa.get()}-isolated-ort.aar"))
    testImplementation(libs.junit4)
    testImplementation(libs.json)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.junit)
    // AndroidX Test references these annotations; its optimized APK must resolve them.
    androidTestImplementation(libs.errorprone.annotations)
    androidTestImplementation(libs.work.testing)
}
