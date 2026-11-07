# Only the instrumentation APK: preserve reflective JUnit discovery and exact
# class/method identities required by the CI XML execution gate. Do not disable R8
# or add these rules to the production app's proguardFiles.
-keepattributes *Annotation*,InnerClasses,EnclosingMethod
-keep @org.junit.runner.RunWith class com.pmixay.ascent.** { *; }
-keep class org.junit.** { *; }
-keep class junit.** { *; }
-keep class androidx.test.** { *; }
