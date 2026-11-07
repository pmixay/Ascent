# Only the instrumentation APK: preserve reflective JUnit discovery and exact
# class/method identities required by the CI XML execution gate. Do not disable R8
# or add these rules to the production app's proguardFiles.
-keepattributes *Annotation*,InnerClasses,EnclosingMethod
-keep @org.junit.runner.RunWith class com.pmixay.ascent.** { *; }
-keep class org.junit.** { *; }
-keep class junit.** { *; }
-keep class androidx.test.** { *; }
# This dependency can live in either APK after AGP runtime dependency de-duplication.
-keep class androidx.tracing.Trace { *; }
-keep class kotlin.** {
    public *;
    protected *;
}

# Instrumentation installs a fake implementing this application-owned interface;
# preserve that cross-APK seam as well as the shared Kotlin binary API.
-keep interface com.pmixay.ascent.ModelTransferRunner { *; }
