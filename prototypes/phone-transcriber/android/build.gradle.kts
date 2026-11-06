buildscript {
    repositories { google(); mavenCentral() }
    dependencies {
        // AGP supplies built-in Kotlin; select the same compiler as Compose.
        classpath(libs.kotlin.gradle.plugin)
    }
}

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
}
