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
- No network permission or cloud speech service. Models are imported once as a
  ZIP; audio is never part of the model-export workflow.

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
and produces the debug APK. ASR uses Maven ONNX Runtime Android 1.31.0; speaker
inference retains the C runtime bundled with sherpa-onnx 2.0.0 under a distinct
library name. These are separate native runtimes. The emulator check must
establish that both load in the same Android process. ASR and speaker model
sessions run sequentially, but both native libraries may remain loaded; peak
memory and actual inference still require device measurements.

Supported architectures are ARM64 phones and x86_64 emulators. Android 12
(`minSdk 31`) is the minimum; compile and target SDKs are pinned independently.
Model files are not embedded in the APK. Install only an artifact from an
inspected successful run for the intended commit.

## GitHub Actions

The workflows live at the root of [pmixay/Ascent](https://github.com/pmixay/Ascent)
and operate on `prototypes/phone-transcriber/`. They require no signing keys or
speech-service credentials. The base repository had no workflow to reuse.

1. **Ascent Android CI**, with job **Build and validate Android app**, runs on
   pushes, pull requests and manual dispatch. It validates packaging tools, runs
   Android lint/unit tests, builds a debug APK, and checks native runtime loading
   on an emulator. Its only download artifact is a ZIP containing the APK. Test
   and lint diagnostics remain in the workflow console; report artifacts are not
   uploaded.
2. **Ascent Model Export**, with job **Export and validate offline models**, is
   started manually in the Actions tab. It downloads pinned official GigaAM
   source/checkpoint and verified speaker weights, exports the waveform frontend
   and ASR network to ONNX, and runs
   numerical/decoding checks against upstream on public Russian speech. It
   publishes a model ZIP only after the checks pass. Manual dispatch requires
   the workflow to exist on the default branch, so it may be unavailable while
   this change remains only on a review branch.
3. Download the APK and model-pack artifacts. Install the APK, then choose the
   inner `ascent-model-pack.zip` in the app's model picker. The outer GitHub
   artifact ZIP is a transport container, not the model pack.

Ascent Android CI reuses a native AAR download that is checksum-verified on every
run, Gradle dependency and build caches, and a clean emulator snapshot with its
Android system image. The snapshot is created before installing the test app;
test sessions do not save it. Pull requests restore caches without saving
them. Pull-request checks validate the merge commit. A separate gate can skip
a duplicate branch-push run when the matching pull request will run those checks;
uncertain API results keep validation enabled. Every executed build retains the
required checks on both cache hits and misses; cache reuse is intended to reduce
repeated work, with no measured speedup claimed. Failure diagnostics are printed
in the workflow console. The APK download uses an uncompressed ZIP container.

The initial ASR export uses FP32, so the model package and required free storage
are substantial (approximately 1 GB before compression). Actual peak RAM and
speed need device measurements. Models are checked before installation; a failed
import leaves the previously selected model intact.

## Validation in CI

Ascent Android CI runs the Python packaging/build-tool tests and the Android checks.
Executable validation must run in CI, not on the local machine.

JVM tests cover CTC repetition/blank rules, chunk ownership, WAV parsing, speaker
identity reuse and invalid ZIP imports. The emulator test covers native library
loading. The separate model workflow checks actual speech inference; these tests
do not substitute for a Russian conversation accuracy benchmark.

## Model and runtime references

- [GigaAM source and model release history](https://github.com/salute-developers/GigaAM)
- [GigaAM model-pack format and export instructions](docs/model-pack.md)
- [3D-Speaker / ERes2NetV2](https://github.com/modelscope/3D-Speaker)
- [sherpa-onnx Android runtime](https://github.com/k2-fsa/sherpa-onnx)
- [ONNX Runtime](https://github.com/microsoft/onnxruntime)

Model licenses and provenance are included in every exported model pack.
