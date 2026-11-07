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

# These shared Kotlin facade families are called from the separately optimized
# instrumentation APK (CI 42381554380: copyInto$default, listOf, MapsKt and
# deleteRecursively). Keep their public bridges on both sides of that boundary.
# Other Kotlin packages and application classes remain optimized.
-keep class kotlin.collections.ArraysKt** { public static *** *(...); }
-keep class kotlin.collections.CollectionsKt** { public static *** *(...); }
-keep class kotlin.collections.MapsKt** { public static *** *(...); }
-keep class kotlin.io.FilesKt** { public static *** *(...); }

# The test APK installs a transfer fake implementing this application interface.
-keep interface com.pmixay.ascent.ModelTransferRunner { *; }
