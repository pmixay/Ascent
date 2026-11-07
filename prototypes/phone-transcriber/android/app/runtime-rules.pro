# AndroidJUnitRunner and the app can share the tracing dependency across APKs.
# Keep its small public bridge stable when the app is optimized separately from
# the test APK; otherwise instrumentation can crash before reporting any tests.
# Do not disable application shrinking/resource optimization or keep all AndroidX.
-keep class androidx.tracing.Trace { *; }

# Kotlin stdlib is shared with the separately compiled instrumentation APK.
# Its consumers include AndroidX Test and our Kotlin test code; retaining just
# Lambda moved the startup failure to LazyKt. Keep the public/protected stdlib
# binary API (including file facades and constructors) consistently for both
# APKs. Application/Compose/coroutine implementation code still uses
# R8 optimization. This is not a package-wide keep for application code.
-keep class kotlin.** {
    public *;
    protected *;
}

# WorkManager's testing library lives in the other APK and calls runtime internals
# that cannot be inferred from the app-only R8 reachability graph.
-keep class androidx.work.** { *; }
