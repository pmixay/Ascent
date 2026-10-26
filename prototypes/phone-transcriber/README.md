# Ascent v0.1 phone transcription prototype

An Android review prototype for local conversation transcription. Start
listening, press stop, and the app automatically produces a dialogue with speaker
labels and time ranges, then deletes its temporary audio. Multiple conversations
appear as collapsible timelines with linked dots, names and timestamps inside
warm amber glass surfaces. The header reads `Ascent.` with a square period;
listening and model status have separate elements. A dedicated glass model panel
contains readiness, update status, downloaded bytes, and download progress.
After stop, one stable loading indicator accompanies the audio-processing
status; intermediate recognition stages do not create competing loading states.
Consecutive phrases from the same known speaker occupy one element even across
pauses or processing boundaries. Elapsed times include decimal seconds; timer
icons mark estimated pauses while the underlying phrase timestamps remain intact.

The native UI uses Jetpack Compose / Material 3, a ViewModel with StateFlow,
lifecycle-aware collection, Activity Result contracts, Kotlin coroutines and
an activity-started foreground download service, OkHttp requests and WorkManager
for persistent recovery and update checks.
Stable dependency versions are centralized in the
[version catalog](android/gradle/libs.versions.toml); the
[dependency baseline](docs/dependencies.md) records sources and compatibility.

## Scope and current limitations

This prototype explores phone capture, local GigaAM inference, speaker separation
and the requested yellow interface. Capture uses a temporary mono WAV in app
cache. Stop starts transcription automatically; success, processing failure or
cancellation reaches audio cleanup. Process death can prevent that cleanup, so
the next launch removes abandoned temporary and legacy recordings. A failed
processing attempt has no audio retry or playback flow.

Text conversations and confirmed voice templates use AES-GCM encryption with an
Android Keystore key. Their retention filter uses the original capture timestamp
and removes content older than seven days on load/save, foreground entry and
periodic checks while the process is active. Pruning also removes voice-template
contributions from expired conversations. A stopped process cannot run cleanup;
this is not proof of deletion at an exact wall-clock deadline. Explicit text
exports are user-managed files outside app retention. There is no Wear OS app,
watch transport, continuous background listening, Rewind or remote AI.

Temporary audio still reaches disk. These differences do not satisfy the production architecture and protections in
the [project plan](../../README.md) and [engineering contract](../../AGENTS.md).
The prototype is retained as separate review work; none of the production
milestones is complete merely because this source or an APK exists.

## What is implemented

- A start/stop listening control with a 30-minute limit. Stopping, reaching the
  limit or leaving the foreground stops capture and starts local processing.
  Listening requires installed models. There are no audio-import, manual
  transcribe, playback or audio-delete controls.
- Local GigaAM Multilingual 220M CTC inference, the June 2026 GigaAM line. Bounded
  chunks prefer a sustained quiet seam near 20 seconds and include 1.25 seconds
  of context at each edge to reduce word cuts. All samples remain covered.
- ERes2NetV2 embeddings, context around short CTC emissions, quality gates, bounded
  clustering with voice-fragment consolidation, and conservative temporal recovery
  of short degraded runs supported by the same established voice on both sides.
  Recovered fragments cannot teach voice templates. Naming a sufficiently clear current
  speaker enrolls an encrypted voice template for later strong matches. Automatic
  predictions never teach their own templates. See [speaker research](docs/speaker-diarization.md).
- Participant labels for every completed phrase, including best-effort assignments
  when strict evidence is insufficient. Inferred provenance remains in stored
  segments and JSON exports; these guesses cannot train voice templates. Older
  unidentified phrases receive a stable participant label without changing their
  text or timestamps or reconstructing audio.
- Multiple encrypted text conversations, collapsible connected timelines,
  participant renaming and explicit per-conversation TXT/JSON export.
- Automatic compressed model downloads from the fixed Ascent HTTPS catalog,
  continued by a foreground service after ordinary app closure, with an ongoing
  progress notification and prompt reconnect retries. WorkManager provides
  recovery and daily update fallback. The model panel keeps installed readiness
  separate from update checks and download progress. Usable models show their last
  successfully checked revision status and relative check age, retaining both
  through later network failures. A newer catalog revision shows an available update.
  There are no model installation or reinstall controls.
- Internet permission is used for model downloads. Speech recognition and speaker
  processing remain on device; conversation audio is never uploaded.

Inspect the exact Android CI and model-export runs before treating an artifact as
verified. Real-phone accuracy/performance remains a separate device check.
Speaker attribution is experimental:
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
   Its additional **Evaluate two hours of labeled Russian dialogue** job scores a
   previously completed inference cache against the application attribution code.
   Model inference for algorithm improvement runs on the development machine; CI
   fails on a missing cache rather than downloading speech or starting models.
2. **Ascent Model Export**, with job **Export and validate offline models**, runs
   on relevant model/export/server changes on `main` and the current prototype
   branch. It exports pinned official GigaAM and speaker weights, requires
   numerical and real-speech checks, and publishes verified files to the fixed
   Ascent model host over restricted SSH. Its deployment archive is internal
   transport, not a GitHub artifact or an app import requirement. Deployment uses
   dedicated repository secrets and does not create a release tag.
3. Download the APK ZIP from a successful Android run and install `ascent.apk`.
   Opening the app starts the foreground model transfer from
   `https://ascent.arbuz.lol/models/catalog.json`. The app verifies the catalog's
   immutable manifest, download index, compressed and expanded chunks, then
   every completed model file before activation. Once installed, the models work
   without Internet access.

Ascent Android CI reuses a checksum-verified native dependency download, Gradle
dependency/build caches, and a clean emulator snapshot with its system image.
The snapshot is created before installing the test app; test sessions do not
save it. Required checks run on cache hits and misses. Cache reuse avoids
repeated work, but no fixed warm-cache duration is promised. The artifact uses an
uncompressed outer ZIP around the already compressed APK.

The initial ASR export uses FP32, so model storage is substantial (approximately
1 GB when expanded). Lossless gzip compresses each transport chunk independently;
this changes network transfer size, not model precision or installed model size.
The download index records the actual compressed byte total. No weights are
bundled in the APK. Peak RAM and processing speed need device measurements.

Opening the app starts a `dataSync` foreground service with a progress
notification. Ordinary activity closure, removal from recent apps and screen-off
do not deliberately stop the transfer. A validated default-network callback
cancels requests on the lost connection and wakes retry waits when connectivity
returns. Transient request failures retry after 1, 2, 4 and at most 10 seconds,
subject to server `Retry-After`; current-chunk progress updates are throttled to
approximately 150 ms; app status and notifications coalesce updates to about once
per second. Retries stay inside the same download operation, so they
do not reread the catalog or rehash every completed prefix.

WorkManager remains a recovery fallback with approximately four-minute resumable
batches, minimum 10-second linear retry backoff and 24-hour update checks. It does
not launch a foreground service from a background job. Reopening the app can
restore the direct foreground transfer without waiting through a scheduler
backoff. Android may still defer fallback jobs. Force-stop prevents further work
until reopening, device power policies can restrict execution, and Android 15+
limits background `dataSync` foreground-service time to six hours per 24 hours.
The service handles its timeout instead of attempting to bypass that limit.

Verified prefixes are rechecked after a process restart; an interrupted current
chunk is downloaded again. Matching installed revisions use metadata and file
size checks without rehashing all weights on every open. Initial installation,
resumed prefixes and completed new files retain their integrity checks. A failed
update leaves the previous models selected, and an active inference lease
protects its revision until processing finishes. There is no model choice,
install or cancel button.

Readiness comes from the selected local revision passing manifest, vocabulary,
file-presence and size validation. A saved successful transfer cannot make
missing or invalid files ready; an unavailable update cannot make valid installed
models unavailable. The model panel therefore keeps its ready state while a
compatible update is checked, downloaded, or waiting for a connection. Download
bytes and percentage appear inside that panel, including retained partial progress
while paused. Listening remains available with the installed revision.

Successful compatible catalog responses persist their advertised manifest hash
and check time. The panel says the latest version is installed only when that
hash matches the validated local selection, and shows elapsed time such as three
minutes since the check. A newer advertised hash remains an available update
until verified activation. Failed checks preserve the last known status and age;
with no successful check, usable models show readiness without a latest-version
claim. Operational failures remain available to the transfer service while the
usable-model panel avoids an update-error headline.

Selection changes are observed independently of transfer status and rechecked on
foreground entry and transfer completion. Progress notifications do not repeatedly
rescan the installed model directory. Only the transfer holding the shared download
lock publishes its lifecycle; a queued or cancelled fallback cannot overwrite that
owner's status. A persisted download without a live owner appears paused rather
than indefinitely running. Starting a fresh check or completing activation clears
obsolete byte counters. The first local check has its own checking state instead
of briefly claiming that installed models are missing.

## Validation in CI

Ascent Android CI runs the Python packaging/build-tool tests and the Android checks.
Executable validation must run in CI, not on the local machine.

JVM tests cover CTC repetition/blank rules, chunk ownership, WAV parsing, speaker
clustering, conservative voice enrollment, encrypted history/expiry, resumable
model-download validation, transient request recovery, partial progress, cancellation,
HTTP retry classification and invalid inputs. Model-status policy tests cover
first installation, installed models during update checks or failures, offline
waiting, interrupted owners, stale completion records, and bounded progress.
They also cover successful versus failed catalog timestamps, newer revisions
before activation and relative check-age formatting.
Server tests check immutable
publication, compressed chunk integrity, smoke-report requirements and failure cleanup. The
emulator tests cover optimized app startup, native library loading and foreground
transfer lifecycle with controlled fake data. Android preference/Flow regressions
check selection changes without transfer publications, failed-update readiness,
observation restart, selection removal, and rejection of missing payloads after
a local refresh, plus retained successful check metadata after failure/restart
and revision comparison after selection changes. These use tiny metadata fixtures and do not run fake weights
through the inference runtimes. A cached, checksum-verified CI fixture
uses the real speaker model on 16 public Russian utterances from six readers,
including reduced-gain, white-noise and synthetic channel variants. JVM tests apply the app's activity
gates and clustering to these embeddings. Additional sparse-context cases compare
the current grouping policy against a frozen test-only baseline, with short
phrases from source utterances distinct from the longer voice references.
Reconciliation requires an independently supported parent voice and sufficient
acoustic separation from alternatives; recovered examples cannot teach remembered profiles.
The fixture audio and model never enter
the APK or downloadable artifacts. The separate model workflow checks actual ASR
inference, prints reference-transcript WER/CER, and rejects chunk-policy changes
that increase word errors in either the clean or channel comparison. These
controlled regressions do not substitute for a Russian conversation
accuracy benchmark. See [fixture provenance](tools/fixtures/speakers/README.md).

The new [Dialogs evaluation](tools/fixtures/dialogs/README.md) freezes 1,134
distinct public audio payloads from three actors, totaling 7,233.107 metadata
seconds. CI verifies every source hash and requires at least two hours of actual
resampled source audio. Approximately five-minute sequences retain source turn
order with inserted 100 ms gaps. These are constructed acted dialogues, not
original continuous conversations. The job reports WER/CER, joint
speaker-attributed word errors (cpWER), strict and completed clip-time attribution,
per-actor results, extra/missing labels and inferred assignments. Kotlin checks
the host CTC/context mirrors and runs the production attribution policy on real
embeddings. Inference checkpoints resume only after integrity checks; scoring
runs on every evaluation job using the completed inference cache. Audio, transcripts,
vectors and reports are not download artifacts.

The inspected development baseline covers `masha_dima_part1`: 336 clips,
2,866.068 source seconds and 5,572 reference words. It measures 2.8715% WER,
0.8730% CER, 4.9892% cpWER and 97.6839% completed clip-time attribution.
[Baseline CI](https://github.com/pmixay/Ascent/actions/runs/40378549620) and the
[development diagnostic run](https://github.com/pmixay/Ascent/actions/runs/40381522671)
passed. The three originally reserved recordings were inspected and exposed a severe female/female merge (61.30% completed clip-time attribution). They are diagnostic data now, not held-out validation; M and D also occur across the original split.

Candidate `ffa5fa7` reserves acoustic diversity within the existing 256-anchor
budget, checks independent within-group agreement before centroid merging, and
reconciles unique duplicated CTC words across overlapping chunk decodes. The
original reserved set showed that these changes do not fix female/female merging.
A complete-linkage discovery candidate is under CI evaluation; its results are pending. This
corpus does not establish natural-conversation DER, cross-session identity,
overlap handling or supported-phone performance. See the linked evaluation
record for exact denominators and scope.

## Model and runtime references

- [GigaAM source and model release history](https://github.com/salute-developers/GigaAM)
- [GigaAM model-pack format and export instructions](docs/model-pack.md)
- [3D-Speaker / ERes2NetV2](https://github.com/modelscope/3D-Speaker)
- [sherpa-onnx Android runtime](https://github.com/k2-fsa/sherpa-onnx)
- [ONNX Runtime](https://github.com/microsoft/onnxruntime)

Model licenses and provenance are included with the downloaded model files.
