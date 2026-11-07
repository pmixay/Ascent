# AndroidJUnitRunner and the app can share the tracing dependency across APKs.
# Keep its small public bridge stable when the app is optimized separately from
# the test APK; otherwise instrumentation can crash before reporting any tests.
# Do not disable application shrinking/resource optimization or keep all AndroidX.
-keep class androidx.tracing.Trace { *; }

# AndroidX Test's TestDirCalculator contains a subclass of Lambda in the test
# APK. R8 cannot see that subclass while optimizing the app, and otherwise
# rewrites/removes Lambda(int), causing a runner startup NoSuchMethodError.
-keep class kotlin.jvm.internal.Lambda { *; }

# AndroidX Test's output-directory delegate calls the shared lazy facade even
# when app optimization has inlined its own calls and would remove that facade.
-keep class kotlin.LazyKt** { *; }
-keep interface kotlin.Lazy { *; }
-keep class kotlin.LazyThreadSafetyMode { *; }
