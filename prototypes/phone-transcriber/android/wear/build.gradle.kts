plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.pmixay.ascent.wear"
    compileSdk {
        version = release(37) {
            minorApiLevel = 2
        }
    }
    buildToolsVersion = "37.0.0"
    defaultConfig {
        applicationId = "com.pmixay.ascent"
        minSdk = 36 // Wear OS 6.0
        targetSdk = 37
        versionCode = 1
        versionName = "0.1"
    }
    sourceSets.getByName("main").java.srcDir(rootProject.file("shared/src/main/kotlin"))
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    lint { abortOnError = true }
    buildTypes {
        debug { optimization { enable = true } }
        release { optimization { enable = true } }
    }
}
