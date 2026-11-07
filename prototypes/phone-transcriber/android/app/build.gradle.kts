import java.security.MessageDigest
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// The downloadable APK targets phones. CI selects x86_64 only for emulator tests.
val ascentAbi = providers.gradleProperty("ascentAbi").orElse("arm64-v8a").get()
require(ascentAbi in setOf("arm64-v8a", "x86_64")) { "Unsupported Ascent ABI: $ascentAbi" }
val embeddedModels = providers.gradleProperty("ascentEmbedded").orElse("false").get().toBooleanStrict()
val sourceCommit = providers.environmentVariable("GITHUB_SHA").orElse("unknown").get()
require(sourceCommit == "unknown" || sourceCommit.matches(Regex("[0-9a-f]{40}"))) { "Invalid CI source revision" }
val ciRunNumber = providers.environmentVariable("GITHUB_RUN_NUMBER").orNull
val ciVersionCode = ciRunNumber?.let {
    require(it.matches(Regex("[1-9][0-9]*"))) { "Invalid CI version number" }
    it.toInt().also { code -> require(code in 1..2_100_000_000) }
} ?: 1
val ciVersionName = if (ciRunNumber != null) {
    require(sourceCommit != "unknown") { "CI version requires an exact source revision" }
    "0.1.$ciRunNumber-${sourceCommit.take(8)}"
} else "0.1"
// Passwords never appear in Gradle arguments/source; CI creates these private temporary files.
val protectedSigning = providers.environmentVariable("ASCENT_SIGNING_PROPERTIES").orNull?.let { path ->
    Properties().apply { file(path).inputStream().use { load(it) } }.also { properties ->
        require(listOf("storeFile", "storePassword", "keyAlias", "keyPassword").all {
            !properties.getProperty(it).isNullOrEmpty()
        }) { "Incomplete protected signing configuration" }
    }
}
if (providers.environmentVariable("ASCENT_REQUIRE_STABLE_SIGNING").orNull == "true") {
    require(protectedSigning != null) { "Stable signing is required; refusing debug signing" }
}
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
        versionCode = ciVersionCode
        versionName = ciVersionName
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // JUnit discovers these entry points reflectively in the optimized test APK.
        // Test-only rules do not change shrinking/optimization of the application APK.
        testProguardFiles("instrumentation-rules.pro", "instrumented-api-rules.pro")
        proguardFiles("runtime-rules.pro")
        if (embeddedModels) {
            applicationIdSuffix = ".offline"
            versionNameSuffix = "-offline-baseline"
        }
        buildConfigField("boolean", "EMBEDDED_MODELS", embeddedModels.toString())
        buildConfigField("String", "EMBEDDED_BUNDLE_SHA256", "\"$embeddedDigest\"")
        buildConfigField("String", "SOURCE_COMMIT", "\"$sourceCommit\"")
        buildConfigField("String", "NATIVE_ABI", "\"$ascentAbi\"")
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
    androidResources {
        noCompress += "onnx"
        // Parakeet's tokens.txt (and bundled attribution) must also remain uncompressed.
        if (embeddedModels) noCompress += "txt"
    }
    lint { abortOnError = true }
    packaging {
        // Smaller direct APK downloads; Android extracts native libraries on install.
        jniLibs { useLegacyPackaging = true }
        dex { useLegacyPackaging = true }
    }
    val ciSigning = protectedSigning?.let { properties ->
        signingConfigs.create("protectedCi") {
            storeFile = file(properties.getProperty("storeFile"))
            storePassword = properties.getProperty("storePassword")
            keyAlias = properties.getProperty("keyAlias")
            keyPassword = properties.getProperty("keyPassword")
        }
    }
    buildTypes {
        debug {
            // Retain debug signing for installation, with production-style R8 optimization.
            isDebuggable = false
            if (ciSigning != null) signingConfig = ciSigning
            proguardFiles("instrumented-api-rules.pro")
            optimization { enable = true }
        }
        release {
            if (ciSigning != null) signingConfig = ciSigning
            optimization { enable = true }
        }
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
if (providers.environmentVariable("CI").orNull == "true") {
    tasks.withType<org.gradle.api.tasks.testing.Test>().configureEach {
        exclude("**/TwoHourSpeechEvaluationTest*")
    }
} else if (providers.environmentVariable("ASCENT_DIALOG_EVALUATION").isPresent) {
    tasks.withType<org.gradle.api.tasks.testing.Test>().configureEach {
        outputs.upToDateWhen { false }
        outputs.doNotCacheIf("External two-hour evaluation must be scored on this run") { true }
    }
}
