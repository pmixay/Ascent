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
| WorkManager | 2.12.0 | [WorkManager releases](https://developer.android.com/jetpack/androidx/releases/work) |
| OkHttp | 5.5.1 | [Official changelog](https://raw.githubusercontent.com/square/okhttp/master/CHANGELOG.md) |
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
updates its ELF `DT_SONAME`, and rewrites the retained sherpa JNI `DT_NEEDED`
reference to that name. The unused standalone sherpa C and C++ wrapper libraries
are omitted after checking that retained libraries do not depend on them. The prepared AAR
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

## Compact APK packaging

The phone download targets only `arm64-v8a`. CI selects `x86_64` separately for
the emulator through `-PascentAbi=x86_64`; it does not distribute that APK.
Both build types enable AGP's R8 code/resource optimization. The installed
prototype excludes Compose preview/runtime tooling and retains only targeted
JNI/reflection entry points. Native libraries and DEX use compressed packaging;
Android extracts the native libraries during installation. Download size and
installed size are therefore different measurements.

The earlier APK contained no model weights yet occupied 172,724,060 bytes
(172.7 MB). Static inspection found 65,676,344 bytes of ARM64 native libraries,
76,515,592 bytes of x86_64 native libraries and 29,883,036 bytes of DEX, all stored
without compression. R8 can reduce managed code/resources but does not remove
native inference engines merely because their weights are downloaded separately.
A single phone ABI, omitted unused wrappers/tooling and compressed payloads
address that overhead. The new exact size must be measured from CI; no reduced
byte count is asserted here before a successful artifact is inspected.

Models are separate HTTPS downloads from the fixed Ascent catalog. The APK
contains the runtime engines and downloader, not ASR or speaker weights. The
Android workflow inspects its output for expected architecture, compression,
optimization evidence and absence of model files before publishing the short
`debug-<8-character commit>.zip` archive with only `ascent.apk`.

An Android `dataSync` foreground service started from the visible activity owns
the primary model transfer and its progress notification. WorkManager 2.12.0,
released September 23, 2026, supplies connected-network recovery fallback and
approximate daily update checks. Android scheduling, foreground-service budgets,
force-stop and device power policies still apply; the app does not promise to
bypass these limits.

OkHttp 5.5.1, released October 22, 2026, provides cancellable HTTPS requests with
connection reuse and eight-second connect/read timeouts. The fixed-host source
keeps redirects disabled and requests identity transport encoding because gzip
chunks are verified and decompressed explicitly. Per-request retries and
validated-network callbacks replace long scheduler waits during an active
foreground transfer. Independent gzip chunks use Android/Java compression APIs,
without another native compression library. Encrypted text and confirmed voice
templates use platform AES-GCM and Android Keystore APIs.

## Export environment

The Python exporter has a separate, pinned CPU dependency environment. GigaAM's
source commit is unchanged. Its ONNX/ONNX Runtime package-version bounds are
explicitly overridden to exercise the current libraries; numerical parity and
real-speech checks must pass before a model pack is emitted. OmegaConf remains
on the newest Hydra-compatible 2.3.x release. See [model-pack.md](model-pack.md) for that
environment's exact constraints and validation scope.
