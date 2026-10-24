# Ascent v0.1 phone transcription prototype

An Android review prototype for on-device conversation transcription. Record a
conversation, stop recording, and read a dialogue with speaker labels and time
ranges. The interface uses warm yellow, translucent surfaces and a luminous
record control inspired by Apple Intelligence.

The native UI uses Jetpack Compose / Material 3, a ViewModel with StateFlow,
lifecycle-aware collection, Activity Result contracts and Kotlin coroutines.
Stable dependency versions are centralized in the
[version catalog](android/gradle/libs.versions.toml); the
[dependency baseline](docs/dependencies.md) records sources and compatibility.

## Scope and current limitations

This prototype explores phone capture, local GigaAM inference, anonymous speaker
labels and the requested yellow interface. It stores WAV audio in the app's
private directory and transcripts as plaintext JSON. Storage is not encrypted
by the app and has no automatic seven-day expiry. There is no Wear OS app,
watch transport, continuous background listening, Rewind or remote AI.

Those differences do not satisfy the production architecture and protections in
the [project plan](../../README.md) and [engineering contract](../../AGENTS.md).
The prototype is retained as separate review work; none of the production
milestones is complete merely because this source or an APK exists.

## What is implemented

- Recording to app-private, mono 16 kHz PCM16 WAV; up to 30 minutes.
- Import of WAV files in the same format.
- Local GigaAM Multilingual 220M CTC inference, the June 2026 GigaAM line.
- Speaker embeddings from ERes2NetV2, session-local speaker clustering, and
  timestamped dialogue segments. Participant labels can be renamed.
- Plaintext local transcript storage and explicit TXT/JSON export.
- In-app model downloads from the fixed Ascent HTTPS catalog, with progress,
  cancellation, and file size/hash validation. No manual model archive is needed.
- Internet permission is used for model downloads. Speech recognition and speaker
  processing remain on device; conversation audio is never uploaded.

This is an initial implementation. Android compilation, native co-loading, model
export and real-phone accuracy/performance must pass the included CI and device
checks before treating an APK as verified. Speaker attribution is experimental:
short replies, similar voices, background speech and simultaneous speakers can
be mislabelled. It does not separate overlapping voices or infer people's names.
Time ranges are estimates from CTC frames, not guaranteed forced alignments.

## Build and install through CI

Builds, tests, lint, native dependency preparation and model export run only in
GitHub Actions. Do not run them locally or download local Android build
toolchains. Local editing and static inspection follow
[AGENTS.md](../../AGENTS.md).

[Ascent Android CI](../../.github/workflows/android.yml) sets up the pinned JDK, Gradle
and Android SDK, prepares the checksum-verified sherpa-onnx AAR, runs the checks,
and produces the compact, R8-optimized APK. ASR uses Maven ONNX Runtime Android 1.31.0; speaker
inference retains the C runtime bundled with sherpa-onnx 2.0.0 under a distinct
library name. These are separate native runtimes. The emulator check must
establish that both load in the same Android process. ASR and speaker model
sessions run sequentially, but both native libraries may remain loaded; peak
memory and actual inference still require device measurements.

The downloadable APK targets ARM64 phones on Android 12 or later (`minSdk 31`).
CI builds a separate x86_64 variant of the same optimized app for emulator checks;
that variant is not included in the download. Compile and target SDKs are pinned
independently. Install only an artifact from an inspected successful run for the
intended commit.

Both build types enable R8 code and resource optimization. The CI APK uses debug
signing for installation but disables debuggability; its short `debug-` filename
is an artifact label. JNI and reflection entry points have targeted keep rules.
Compose preview/runtime tooling is excluded from the installed app. Native
libraries and DEX are compressed in the APK; Android extracts the native libraries
on installation, so installed storage is larger than the compressed download.

The earlier universal APK was **172,724,060 bytes (172.7 MB)** even without any
model weights. It stored 65,676,344 bytes of ARM64 native libraries, 76,515,592
bytes of x86_64 native libraries, and 29,883,036 bytes of DEX without compression.
The current build packages one phone ABI, removes unused sherpa C/C++ wrapper
libraries and Compose runtime tooling, and applies shrinking and compression.
A new exact APK size must come from its successful CI artifact; these source
changes alone do not establish a measured size reduction.

## GitHub Actions

The workflows live at the root of [pmixay/Ascent](https://github.com/pmixay/Ascent)
and run only on pushes. There are no separate pull-request or manual-dispatch
triggers. The base repository had no workflow to reuse.

1. **Ascent Android CI**, with job **Build and validate Android app**, validates
   packaging and server-publication tooling, runs Android lint/unit tests, builds
   the compact ARM64 APK, and checks the optimized app and native runtime loading
   on an x86_64 emulator. It inspects the APK for its expected ABI, compression,
   optimization output and absence of model weights. Its only download artifact
   is `debug-<8-character commit>.zip`, containing only `ascent.apk`. Test and lint
   diagnostics remain in the workflow console; report artifacts are not uploaded.
2. **Ascent Model Export**, with job **Export and validate offline models**, runs
   on relevant model/export/server changes on `main` and the current prototype
   branch. It exports pinned official GigaAM and speaker weights, requires
   numerical and real-speech checks, and publishes verified files to the fixed
   Ascent model host over restricted SSH. Its deployment archive is internal
   transport, not a GitHub artifact or an app import requirement. Deployment uses
   dedicated repository secrets and does not create a release tag.
3. Download the APK ZIP from a successful Android run and install `ascent.apk`.
   In the app, download the models from
   `https://ascent.arbuz.lol/models/catalog.json`. The app verifies the catalog's
   immutable manifest and every model file before activation. Once installed,
   those models work without Internet access.

Ascent Android CI reuses a checksum-verified native dependency download, Gradle
dependency/build caches, and a clean emulator snapshot with its system image.
The snapshot is created before installing the test app; test sessions do not
save it. Required checks run on cache hits and misses. Cache reuse avoids
repeated work, but no fixed warm-cache duration is promised. The artifact uses an
uncompressed outer ZIP around the already compressed APK.

The initial ASR export uses FP32, so model storage is substantial (approximately
1 GB before any transport compression). Those files are downloaded separately;
none are bundled in the APK. Actual peak RAM and processing speed need device
measurements. Failed or cancelled downloads keep the previously selected models.

## Validation in CI

Ascent Android CI runs the Python packaging/build-tool tests and the Android checks.
Executable validation must run in CI, not on the local machine.

JVM tests cover CTC repetition/blank rules, chunk ownership, WAV parsing, speaker
identity reuse, model-download validation and invalid model inputs. Server tests
check immutable publication, smoke-report requirements and failure cleanup. The
emulator tests cover optimized app startup and native library loading. The separate model workflow checks actual speech inference; these tests
do not substitute for a Russian conversation accuracy benchmark.

## Model and runtime references

- [GigaAM source and model release history](https://github.com/salute-developers/GigaAM)
- [GigaAM model-pack format and export instructions](docs/model-pack.md)
- [3D-Speaker / ERes2NetV2](https://github.com/modelscope/3D-Speaker)
- [sherpa-onnx Android runtime](https://github.com/k2-fsa/sherpa-onnx)
- [ONNX Runtime](https://github.com/microsoft/onnxruntime)

Model licenses and provenance are included with the downloaded model files.
