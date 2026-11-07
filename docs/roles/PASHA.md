# Pasha — application and acceptance lead

## Mission

### New explicit packaging handoff — Parakeet

Timofey is implementing the user's requested [schema-2 Parakeet bundle](TIMOFEY_PARAKEET_STATUS.md),
not the mode chooser. After verified bootstrap, the same `ModelLease` exposes
`multilingualDirectory` beside `directory` and `textDirectory`; it stays leased
through both acoustic and text stages. Compact leases return null. Default GigaAM
behavior remains unchanged. Packaging/smoke and the merged AUTO/timing source
passed both jobs at `42d4de9` / `42406537594`; this is not ARM acceptance.

- [x] Wire all three recognition modes (Auto, Russian, Other languages) using the leased multilingual directory;
  keep GigaAM as the compact Auto fallback and refuse a fixed multilingual choice without files.
  Pasha's worker and Petya's remembered selector/actual-used label are integrated.
  The UI passed compact CI at `16932d34` ([42411299817](https://github.com/pmixay/Ascent/actions/runs/42411299817));
  actual Auto/Multilingual audio inference on ARM remains open.
- [ ] Qualify the new per-job text budget on ARM. `dba08d9` adds a soft budget after
  model loading: `max(30 s, 50% of media duration)`, checked between phrases, with
  unchanged words and reported timeout fallbacks. It is source-level policy, not
  a strict native-call deadline or measured ARM acceptance.
- [ ] Supply full ViewModel lifecycle evidence and final application/source/export
  freeze. `570e9c7` adds required synthetic import/export and cancel→new job/double-tap/
  background checks in the embedded CI job. Rotation, kill/reopen and ARM remain open. [Exact-build handoff and
  cross-role blockers](TIMOFEY_REMAINING_STATUS.md). Timofey imported the documentation-only
  sign-off commit `f3e4386` without replacing these newer assignments; no app code was missing.

Deliver the application path from a selected local audio file to a safely retained transcript and specification-compatible JSON. Own the job lifecycle and meaningful local text-LLM integration. Parent plan: [HACKATHON_PLAN.md](../HACKATHON_PLAN.md), packages H00/H02/H03/H06/H07/H12.

**Today: 7 November 2026, 11:00–20:00 MSK (UTC+3). Code handoff: 17:00; acceptance sign-off: 19:30; submission reserve: 19:30–20:00.** This replaces all working-day/effort estimates below. Builds, application tests and lint use existing GitHub CI; phones install its APKs. Missing mandatory work is a blocker, not automatically complete at the deadline.

## Already pushed — reuse, do not implement again

At fetched `origin/main` `4b16e3a`, the application already has local acoustic inference, encrypted conversation/voice storage, model leases/downloads and timeline UI. PR #2 adds the segmenter/tests, frozen speech/English manifests, host ASR/timestamp study and text-model shortlist/grammar contract. Do not rewrite those components or wait for another model-selection study. **Remaining:** reachable source-safe import, competition JSON, job safeguards and the actual Android text stage. Andrey is the ML owner; this role remains the application/acceptance owner after the pushed role swap.

## Integration follow-up on Petya's branch

`codex/petya-open-work` integrates this branch through `00bb1ab` with Timofey's
schema-2 packaging through `42d4de9`. The temporary null directory is replaced by
the job's triple lease; the Russian mode picker and actual-used label are wired.
The timing counters and cancellation/source ownership paths are preserved.
[PR #5](https://github.com/pmixay/Ascent/pull/5) and
[TEST_RESULTS.md](../TEST_RESULTS.md) carry combined CI/artifact evidence.
The older sign-off below remains historical; phone/ARM acceptance is still open.

## Application sign-off record at 19:58 MSK (CI green on the merged revision; nothing run on a phone or ARM)

Written after fetching `origin/main` `f04aaa7` and re-reading the source. It supersedes the 16:30 table
below where they differ (the 16:30 text says nothing installs the text model; the embedded bootstrap in
`593f6bd` now does). No code was changed for this record: the 17:00 freeze has passed and no CI result
could be inspected before the deadline for a new push. **No checkbox in this file is ticked.**

**CI evidence, inspected.** Run [42392428158](https://github.com/pmixay/Ascent/actions/runs/42392428158)
(`593f6bd`, branch `claude/improve-app-peter`) concluded success for both jobs: *Build and validate Android app*
(tooling tests, lint, JVM tests, R8 assemble, ARM64 APK inspection, x86_64 emulator runtime checks) and
*Build and validate embedded offline baseline* (ARM64 offline APK, x86_64 first-launch extraction with
networks off, one synthetic text-stage call through the paired lease in `EmbeddedModelRuntimeTest`).
These are job conclusions; per-test counts were not read. `f04aaa7` (main, merge of PR #4) differs from
`593f6bd` in six documentation files only, no source, manifest, script or workflow file, so the result applies
to its code, but its own runs (Android CI 42393688728, Model Export 42393688781) were still in progress and
are not inspected.

| Requirement | State | Evidence and what is missing |
| --- | --- | --- |
| Import without microphone permission, file left unchanged | **Pass in CI only** | `importAudio` → `AudioSources` / `CodecAudioWindowSource` read in place; JVM no-write guard and emulator read tests pass. Not run on a phone; real speech files and the target emulator's codecs are unmeasured |
| Exact JSON, silence gives `{"transcript":[]}` | **Pass in CI only** | `CompetitionJsonExporter` contract tests pass. **Not run:** validation of a real device export by Petya's validator |
| Job lifecycle: one job, cancel, stale callbacks, closed handles | **Not run** | `launchJob` implements them and the tracker is unit-tested, but nothing executes the ViewModel job path. Rotation, background, cancel then new job and kill/reopen have no record |
| Process death and background ownership | **Open, declared limit** | No foreground service for processing (the manifest declares only the watch and model-download services). A killed process loses the job; reopening reports the interruption and the file must be selected again. Nothing recovers it automatically |
| Local text model executes on Android | **Blocked on ARM evidence** | `TextStages.run(lease.textDirectory, …)` runs after the acoustic sessions close under the same lease, and reports count, fallbacks and time. The only execution record is one synthetic call on x86_64. **The local-LLM requirement is not shown to be met** until an ARM run is inspected |
| Raw ASR text preserved, words unchanged | **Pass in CI only** | `rawText` keeps the recognized words; `preservesWords` gates every edit; `TextStageTest` passes with a fake model. Not checked with the real model on real speech |
| Export text is raw or punctuated | **Decided in source** | The export writes `recognizedText` (raw), following the recommendation in [text-punctuation.md](../interfaces/text-punctuation.md); the interface and TXT export show the edited text. No written confirmation from Andrey or the jury is on file |
| Text stage time cap | **Source and JVM tests written after this record; not yet CI-verified** | `TextStages` stops asking the model once `max(30 s, 50% of the media duration)` has elapsed after the model loaded; remaining phrases keep their recognized words and are reported as `timedOut` (`TextStageTest`, fake clock). Checked between phrases, so it can overrun by one phrase. The numbers are an unmeasured policy default; the export is unaffected because it carries the recognized words. Context stays capped at 64-word blocks, nothing is generated and nothing is retried. No ARM timing exists to tune it |
| Storage and encrypted-history errors | **Pass in CI only** | Atomic non-cancellable save and a `Storage` error category exist; low-storage and corrupt-history behavior on a device is not run |
| Three unfamiliar files in sequence, offline, on ARM | **Not run** | Petya's rehearsal; see [TEST_RESULTS.md](../TEST_RESULTS.md) |
| Language mode (`AUTO` / Russian / other languages) in the job path | **CI green on the merged commit; nothing run on a device** | Added at 22:46 MSK, after the 20:00 deadline, and merged with Timofey's Parakeet packaging branch. [Run 42408030599](https://github.com/pmixay/Ascent/actions/runs/42408030599) (`5886020`, branch `claude/pasha-work-lh3bs1`) concluded success for both jobs, *Build and validate Android app* and *Build and validate embedded offline baseline*; job conclusions only, per-test counts and the new `RecognitionSettingsTest` result were not read. The change: `setRecognitionMode`, remembered choice, mode and `ModelLease.multilingualDirectory` passed to `TranscriptionEngine`, `recognizedMode` in state, refusal of a fixed multilingual choice without installed files (`RecognitionSettings`, `RecognitionSettingsTest`). **Integrated on Petya's branch:** the three-way control, remembered selection and the used-mode label; [current UI and QA handoff](PETYA_HANDOFF.md). **Open:** physical-device mode/inference evidence; the compact build has no Parakeet; no `AUTO` or `MULTILINGUAL` job has run on Android or ARM and English has no device result. See [multilingual-recognizer.md](../interfaces/multilingual-recognizer.md#handoff) |

**Readiness.** The application lane is **incomplete**, not signed off as ready: the local-LLM requirement,
the lifecycle matrix, a validated device JSON and the three-file ARM rehearsal are unmeasured, and the deadline
does not turn them into passes. Source and CI cover only what is listed as "Pass in CI only".

## Status at 16:30 MSK (superseded above where it differs; whole CI workflow green; nothing run on a phone)

Contract and details: [app-pipeline.md](../interfaces/app-pipeline.md). No checkbox below is ticked.

**CI evidence, inspected.** Run [42376061272](https://github.com/pmixay/Ascent/actions/runs/42376061272)
(commit `2545310`, branch rebased onto the current main) concluded **success** for all three jobs: *Build and
validate Android app* (lint, JVM unit tests, R8 assemble, ARM64 APK inspection, and the x86_64 emulator
instrumentation tests on Android 16 including `ImportRuntimeTest` with its MP3/M4A decode test, whose assets
and test code were built in that run), the public inference job and the cache scoring job. Its build job
produced the checked ARM64 APK artifact `debug-25453108`. The first run of this code (42373255631)
compiled and failed one of my own tests, because the test tokenizer lacked a capital letter; that was fixed.
The emulator log shows the task succeeding, not per-test counts. Nothing ran on an ARM emulator or a phone.

| Item | In source, with CI status | Still open |
| --- | --- | --- |
| A1 serializer | `CompetitionJsonExporter`, wired to the JSON export, silence gives `{"transcript":[]}`; contract tests pass in CI | Petya's independent validation of a real device export |
| A2 import | `AudioSources` (descriptor read in place), WAV any rate/channels/sample format, `Pcm16Stream`, `CompressedAudio` (MP3/AAC/M4A, in memory, 30 min); the copying `AudioImporter` is deleted. WAV conversion, rejection cases and the no-write guard pass JVM tests; reading a picked file and decoding synthetic MP3 and M4A tone files (tone found at its original time, correct pitch and level) pass the emulator tests | No picker button (Petya); device and ARM trials; real speech files, telephone-band and unusual encodings, and which formats the target emulator decodes are unmeasured |
| A3 job safety | `launchJob` shared by import and recording: one job, job IDs, cancel, stale-callback guard, handles closed on every path, interrupted-job notice, atomic non-cancellable save. Only the job tracker and progress mapping have tests | No foreground service (process death needs reselecting the file); the ViewModel job path itself, rotation, background, cancel and kill/reopen are untested |
| A4 text stage | `TextStages` calls the pushed `TextPunctuator` after the acoustic lease is released, keeps raw text, reports counts; unit tests use a fake model | **Nothing installs the model files yet (Timofey); the stage reports `UNAVAILABLE`, so the local-LLM requirement is not met.** Never run with the real model, on Android or ARM |

Not done by this change: UI wiring, text-model packaging, English and call/meeting evidence, RTF.

## Ownership

Main source directory: `prototypes/phone-transcriber/android/app/src/main/java/com/pmixay/ascent/`.

- Own `ConversationViewModel.kt`, `AudioSource.kt`, `WavAudio.kt`, `PcmStream.kt`, `CompressedAudio.kt`, `ProcessingJob.kt`, `PcmWave.kt`, `ConversationStore.kt` and the export/text-model adapter files (`CompetitionJsonExporter.kt`, `TextStage.kt`). `AudioImporter.kt` was removed because it copied audio into app storage.
- Own shared domain contract changes; agree signatures before others implement adapters.
- Petya owns `MainActivity.kt`/`GlassUi.kt`; provide callable APIs and state definitions.
- Andrey owns `TranscriptionEngine.kt`/speaker algorithms; coordinate its input adapter rather than editing it concurrently.
- Timofey owns build scripts, Android manifest, model selection/installation and native dependency packaging.
- Reviewers: Timofey for resources/lifecycle; Petya for acceptance usability; Andrey for text-model behavior.

## 11:00–11:20 — agree only missing interfaces

- [ ] Read `AGENTS.md`, the audit and parent plan; inspect current ViewModel, import and export paths.
- [ ] Reuse pushed brief/clarifications. Ask only unresolved local-LLM, format/length/device/scoring questions; deadline is already fixed at 20:00 today. Do not restart the audit.
- [ ] Agree `AudioSource`, bounded `PcmWindowSource`, processing state, result and model-lease contracts with colleagues. Proposed names are design seams, not existing classes.
- [ ] Define ViewModel commands for import, cancel and export, with job IDs and error categories; hand the contract to Petya.
- [ ] Agree how Andrey's two-pass engine reopens/reads the same URI without creating an app-owned audio spool.

**Checkpoint:** all four can describe the same input → job → result → JSON path. While organizer answers are pending, implement the unambiguous import/export requirements.

## 11:20–13:00 — exact export and minimal real import

Reviewable source/serializer code reaches Timofey and Petya by **12:30**, integrated baseline push at **13:00**. Prepare the narrow text-adapter interface alongside this work; Andrey supplies the existing model contract by 12:00.

### A1. Dedicated competition serializer

- [ ] Add a dedicated serializer using `transcript` and `speaker_id`, `start_ms`, `end_ms`, `text`.
- [ ] Keep internal encrypted history serialization backward-compatible.
- [ ] Validate string fields, integer milliseconds, source-duration bounds and deterministic order; preserve legitimate overlap.
- [ ] Export silence as `{"transcript":[]}`; retain stable IDs independently of user-visible names.
- [ ] Handle Unicode, escaped text, cancelled export selection and output-provider failures.
- [ ] Add focused CI contract checks; give Petya an independent expected example.

**Done:** JSON from a real device transcript passes Petya's independent schema/semantic validator, including an empty transcript. A successful serializer unit test alone is insufficient.

### A2. Connect the existing engine to a bounded URI/WAV source

- [ ] Accept a `content://` URI from Petya's picker; handle permission lifetime and unreadable/revoked sources.
- [ ] Adapt WAV reading to a bounded source abstraction and establish media duration/time origin. The engine side (`PcmWindowSource` and a `TranscriptionEngine` overload) is pushed in `9127e20`; read the contract in [pcm-window-source.md](../interfaces/pcm-window-source.md) and implement a source for the picked `content://` URI. Its build and unit tests passed in CI run 42366154116.
- [ ] Route it through real acoustic inference using Timofey's selected model lease.
- [ ] Save results with a distinct conversation/job identity and appropriate import metadata.
- [ ] Distinguish imported-at time from source-relative offsets; do not invent the recording's original capture date or silently reset known source expiry.

**Done:** import a new 16 kHz mono WAV without microphone permission; display and export computed results. The original file is unchanged.

## 13:00–15:00 — required decoding, job safety and text-stage integration

These are one shared two-hour time box, not three additive estimates. Use platform decoding and existing architecture; no database/module rewrite. Real ARM LLM feasibility is due **14:30**; full mandatory integration **15:00**. Escalate any nonviable runtime/codec early; a missing required format or LLM remains a blocker.

- [ ] Add WAV rate/channel conversion and common Android-decoded MP3/AAC/M4A formats as supported by target devices.
- [ ] Preserve sample/PTS mapping through resampling, including leading silence, chunk seams and decoder delay.
- [ ] Use bounded buffers; handle a second source read for speaker inference. Clearly reject unsupported non-seekable providers if no bounded strategy is available.
- [ ] Cover malformed/truncated/empty input, unknown length, unsupported format and declared duration limits.
- [ ] Ensure duplicate taps cannot start simultaneous jobs and stale callbacks cannot replace a later result.
- [ ] Merge the basic vertical slice with Petya and Timofey before broadening codec support further.

**Done:** the declared format matrix reaches real inference with correct media duration and time origin. Source files are neither deleted nor copied into a permanent app archive. No new disk-audio exception is introduced.

### A3. Reliable processing ownership

- [ ] Move long-running job ownership out of accidental activity lifetime; choose a supported Android execution mechanism with Timofey before adding service declarations.
- [ ] Define rotation, background and process-death outcomes. Process death may require reopening/reselecting a source; never claim automatic audio recovery that does not exist.
- [ ] Propagate real progress and cancellation; allow native calls to finish safely before releasing their resources.
- [ ] Close source/model/runtime handles on success, failure and cancellation; preserve already completed transcripts.
- [ ] Surface low-storage and encrypted-history errors with an explicit recovery path; never silently destroy existing history.
- [ ] Add executable lifecycle/error checks to CI and give Petya reproducible device procedures.

**Done:** cancellation followed by another job, rotation, background/return and kill/reopen produce coherent states; no original file loss, duplicate result or stale completion.

### A4. Android integration of the already studied text model

- [ ] Use the pushed Qwen2.5 1.5B Q4_K_M candidate or the already measured 0.5B fallback after ARM budget checks. Integrate its grammar/validator contract; obtain exact assets/native bindings from Timofey. Do not repeat the host shortlist or feasibility sweep. **Superseded at 14:30:** the provisional stage is Qwen2.5 0.5B int8 ONNX (final model after the team's comparison) on the existing ONNX Runtime; `TextPunctuator`, `OnnxTextModel` and `QwenTokenizer` are pushed. Call them as described in [text-punctuation.md](../interfaces/text-punctuation.md), including the raw-text fallback and JSON export rule. No GBNF grammar or native binding is needed.
- [ ] Perform bounded punctuation/case edits locally after acoustic sessions are released.
- [ ] Validate unchanged lexical tokens, numbers, negation, order, speaker IDs and times. Preserve raw ASR text.
- [ ] Cap context/output/time/retries; reject malformed or meaning-changing output and show/use the raw fallback.
- [ ] Report actual local-model success/fallback and incremental runtime without content logging.

**Done:** the bundled text model demonstrably executes on ARM and has a useful constrained role. An unused bundled model or an always-failing adapter is not compliance. Revise this task only if organizers explicitly clarify that a distinct LLM is unnecessary.

## 15:00–17:00 — candidate QA and reproduced blocker fixes only

- [ ] Review the full-pipeline candidate with Andrey; preserve the shipped segmenter and acoustic configuration. No new quality candidate today.
- [ ] Fix Petya's reproduced application blockers; retest affected paths in CI and on device.
- [ ] Match each mandatory requirement to implementation and evidence; leave unverified rows open.
- [ ] Review installation/architecture/limitations documents and the final three-file offline rehearsal.
- [ ] Prepare a short explanation of import, job lifecycle, JSON, local LLM and failure recovery for the defense.

Final application code handoff/push is **17:00**. After that only a release-blocking fix agreed with Timofey can change code, with new CI/artifact identity and retests. Do not consume final QA with optional work.

## 17:00–20:00 — acceptance and submission

- **17:00–18:00:** review requirement/evidence rows and actual installation/lifecycle/architecture text while final CI runs.
- **18:00–19:00:** support Petya's final exact-APK cold offline three-file rehearsal and affected retests.
- **19:00–19:30:** sign off application requirements as pass/fail/blocked/not-run; rehearse the defense. Leave missing mandatory evidence open.
- **19:30–20:00:** shared submission/link verification reserve, not feature development.

## Handoffs and blockers

### T2 application integration handoff — still required

- [ ] Use the embedded bootstrap via existing `ModelDownloads.schedule/observe`;
  do not add a parallel installer or make first-run readiness wait for networking.
  `ModelSelection.acquire` now supplies one lease with `directory` (unchanged
  acoustic file set) and nullable `textDirectory` (separate versioned text assets).
- [ ] Connect the real `OnnxTextModel`/`TextPunctuator` stage after acoustic
  sessions close, consuming `lease.textDirectory` for the entire job. Keep that
  lease until both acoustic and text stages stop; expose real execution/fallback
  counts and elapsed time. Merely bundling weights does not close H07.
- [ ] Preserve raw ASR text, lexical/ID/time invariants and encrypted history.
  Propagate installation failures/cancellation safely; never delete imported
  source audio. Coordinate job/background/reopen behavior with Petya's real-device
  tests. Agree the export raw/presentation-text choice with Andrey.

These cross-role prerequisites are not implemented by Timofey's T2 packaging.
Petya now owns real-device verification and server repair; no task is waived.
Callable details: [embedded-models.md](../interfaces/embedded-models.md).
The first packaged baseline and its run/APK identities are available in
[T2 status](TIMOFEY_T2_STATUS.md); do not wait for another host/model search or
reimplement the installer. Use the implementation branch and the final tested
revision identified there for integration.

| Need | From | Deliver to others |
| --- | --- | --- |
| Acoustic input/result agreement | Andrey, by 11:20 | Stable source/state/export APIs to Petya by 11:20; baseline code 12:30 |
| Valid model lease and embedded assets | Timofey | Runtime/asset requirements and required manifest changes |
| Already pushed model/grammar/validator contract, by 12:00 | Andrey | Actual ARM adapter evidence by 14:30; integrated stage by 15:00 |
| Reproduced UX/lifecycle failures | Petya | Fixed APK commit, affected tests and clear remaining limits |

If weights/CI block device inference, continue serializer, URI validation and orchestration work against interfaces. Mark test doubles explicitly; they are development scaffolding, never delivered as precomputed transcription.

## AI work pattern

Use Claude for one cohesive application task at a time. Use GPT to review the diff against the JSON contract, cancellation and resource ownership, then inspect its findings yourself. Each task prompt includes allowed files, fixed interfaces, prohibited regressions and a measurable acceptance case. Do not accept “works” without the CI/device evidence.

## Final personal checklist

- [ ] Reachable import works without microphone permission.
- [ ] Exact JSON, including silence, is independently validated.
- [ ] Input audio remains user-owned and unchanged; buffers are bounded.
- [ ] Long jobs have defined lifecycle/cancel/recovery behavior.
- [ ] Local-LLM compliance is resolved with evidence.
- [ ] Every mandatory application requirement has a passing acceptance record.
