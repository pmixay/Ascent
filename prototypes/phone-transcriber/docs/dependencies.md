# Dependency baseline

Stable releases were checked on 2026-10-24. Prototype Android versions are
centralized in `android/gradle/libs.versions.toml`; no dynamic `+` versions are
used. Preview, alpha, beta and release-candidate artifacts are excluded.

| Component | Pinned version | Upstream reference |
| --- | --- | --- |
| Android Gradle Plugin | 9.4.1 | [Android Gradle API](https://developer.android.com/reference/tools/gradle-api) |
| Kotlin / Compose compiler | 2.4.21 | [Kotlin releases](https://kotlinlang.org/docs/releases.html) |
| Compose BOM | 2026.09.00 | [Compose BOM](https://developer.android.com/develop/ui/compose/bom) |
| Activity Compose | 1.13.0 | [Activity releases](https://developer.android.com/jetpack/androidx/releases/activity) |
| Lifecycle / ViewModel | 2.11.0 | [Lifecycle releases](https://developer.android.com/jetpack/androidx/releases/lifecycle) |
| Coroutines | 1.11.0 | [Coroutines releases](https://github.com/Kotlin/kotlinx.coroutines/releases) |
| ONNX Runtime Android for ASR | 1.31.0 | [Maven Central](https://central.sonatype.com/artifact/com.microsoft.onnxruntime/onnxruntime-android) |
| sherpa-onnx | 2.0.0 | [Release and artifact checksums](https://github.com/k2-fsa/sherpa-onnx/releases/tag/v2.0.0) |
| Gradle | 9.8.2 | [Release notes](https://docs.gradle.org/9.8.2/release-notes.html) |

The Android build uses AGP's built-in Kotlin support and explicitly aligns the
Kotlin compiler with the Compose compiler plugin. It compiles against API 37.2 and targets API 37,
requires Android 12 or later (`minSdk 31`), and uses the documented JDK 17 baseline.
Gradle 9.8.2 is newer than the upper end of Kotlin's fully tested Gradle matrix;
Kotlin permits newer Gradle versions, but the actual build remains a required CI
gate. Local compilation is prohibited by the repository contract; inspect the actual CI run before claiming this configuration is verified.

## Android native runtime isolation

ASR uses the Java API from Maven ONNX Runtime Android 1.31.0. Speaker inference
uses sherpa-onnx 2.0.0 and ONNX Runtime C 1.28.2 shipped in its verified upstream AAR.
That bundled runtime is retained rather than replaced by the Maven Java runtime:
the latter does not export `OrtGetApiBase`, which the sherpa JNI library needs.

The preparation script renames the bundled C runtime to `libort_sherpa.so`,
updates its ELF `DT_SONAME`, and rewrites every sherpa native `DT_NEEDED`
reference to that name, including the C, C++ and JNI libraries. The prepared AAR
is `sherpa-onnx-2.0.0-isolated-ort.aar`. This gives the two runtimes separate
library identities in the APK. It
does not assert that sherpa has been upgraded to ONNX Runtime 1.31.0 or that the
Maven artifact supplies a compatible C ABI. The emulator check must verify
co-loading for the built APK; model inference and device behavior need their
own validation.

ASR and speaker model sessions run sequentially to avoid loading both models at
once. Both native runtime libraries may remain mapped in the process, so this
arrangement does not establish a memory budget or guarantee that either library
is unloaded. Measure peak RAM and processing latency on supported phones.

## Export environment

The Python exporter has a separate, pinned CPU dependency environment. GigaAM's
source commit is unchanged. Its ONNX/ONNX Runtime package-version bounds are
explicitly overridden to exercise the current libraries; numerical parity and
real-speech checks must pass before a model pack is emitted. OmegaConf remains
on the newest Hydra-compatible 2.3.x release. See [model-pack.md](model-pack.md) for that
environment's exact constraints and validation scope.
