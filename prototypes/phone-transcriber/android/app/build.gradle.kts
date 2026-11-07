import java.security.MessageDigest

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// The downloadable APK targets phones. CI selects x86_64 only for emulator tests.
val ascentAbi = providers.gradleProperty("ascentAbi").orElse("arm64-v8a").get()
require(ascentAbi in setOf("arm64-v8a", "x86_64")) { "Unsupported Ascent ABI: $ascentAbi" }
val embeddedModels = providers.gradleProperty("ascentEmbedded").orElse("false").get().toBooleanStrict()
val embeddedAssets = layout.buildDirectory.dir("generated/offline-assets").get().asFile
val embeddedManifest = embeddedAssets.resolve("models/bundle.json")
val embeddedDigest = if (embeddedModels) {
    require(embeddedManifest.isFile) { "CI must prepare the pinned offline model assets before building" }
    MessageDigest.getInstance("SHA-256").digest(embeddedManifest.readBytes())
        .joinToString("") { "%02x".format(it.toInt() and 255) }
} else ""

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
        // JUnit discovers these entry points reflectively in the optimized test APK.
        // Test-only rules do not change shrinking/optimization of the application APK.
        testProguardFiles("instrumentation-rules.pro")
        proguardFiles("runtime-rules.pro")
        if (embeddedModels) {
            applicationIdSuffix = ".offline"
            versionNameSuffix = "-offline-baseline"
        }
        buildConfigField("boolean", "EMBEDDED_MODELS", embeddedModels.toString())
        buildConfigField("String", "EMBEDDED_BUNDLE_SHA256", "\"$embeddedDigest\"")
        ndk { abiFilters += ascentAbi }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    // Built-in Kotlin follows compileOptions.targetCompatibility.
    // CI-only public speech and real embeddings: JVM resources never enter the phone APK.
    sourceSets.getByName("test").resources.srcDir(layout.buildDirectory.dir("generated/speaker-fixtures").get().asFile)
    // AGP 9 built-in Kotlin requires its Kotlin source directory DSL.
    sourceSets.named("main") {
        kotlin.directories += rootProject.file("shared/src/main/kotlin").absolutePath
    }
    if (embeddedModels) {
        sourceSets.getByName("main").assets.srcDir(embeddedAssets)
        sourceSets.named("androidTest") {
            kotlin.directories += project.file("src/offlineAndroidTest/java").absolutePath
        }
        sourceSets.getByName("debug").manifest.srcFile("src/offline/AndroidManifest.xml")
        sourceSets.getByName("release").manifest.srcFile("src/offline/AndroidManifest.xml")
    }
    buildFeatures { compose = true; buildConfig = true }
    androidResources { noCompress += "onnx" }
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

// The long evaluation reads verified resources outside the regular JVM source set.
// Score supplied development inputs; the long corpus study stays off push CI.
if (providers.environmentVariable("ASCENT_DIALOG_EVALUATION").isPresent) {
    tasks.withType<org.gradle.api.tasks.testing.Test>().configureEach {
        outputs.upToDateWhen { false }
        outputs.doNotCacheIf("External two-hour evaluation must be scored on this run") { true }
    }
}
