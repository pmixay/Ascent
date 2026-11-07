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
-keep class kotlin.jvm.internal.Lambda { *; }
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
