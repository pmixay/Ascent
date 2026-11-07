# AndroidJUnitRunner and the app can share the tracing dependency across APKs.
# Keep its small public bridge stable when the app is optimized separately from
# the test APK; otherwise instrumentation can crash before reporting any tests.
# Do not disable application shrinking/resource optimization or keep all AndroidX.
-keep class androidx.tracing.Trace { *; }
