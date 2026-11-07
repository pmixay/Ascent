# Pasha — application and acceptance lead

## Mission

Deliver the application path from a selected local audio file to a safely retained transcript and specification-compatible JSON. Own the job lifecycle and meaningful local text-LLM integration. Parent plan: [HACKATHON_PLAN.md](../HACKATHON_PLAN.md), packages H00/H02/H03/H06/H07/H12.

**Today: 7 November 2026, 11:00–20:00 MSK (UTC+3). Code handoff: 17:00; acceptance sign-off: 19:30; submission reserve: 19:30–20:00.** This replaces all working-day/effort estimates below. Builds, application tests and lint use existing GitHub CI; phones install its APKs. Missing mandatory work is a blocker, not automatically complete at the deadline.

## Already pushed — reuse, do not implement again

At fetched `origin/main` `4b16e3a`, the application already has local acoustic inference, encrypted conversation/voice storage, model leases/downloads and timeline UI. PR #2 adds the segmenter/tests, frozen speech/English manifests, host ASR/timestamp study and text-model shortlist/grammar contract. Do not rewrite those components or wait for another model-selection study. **Remaining:** reachable source-safe import, competition JSON, job safeguards and the actual Android text stage. Andrey is the ML owner; this role remains the application/acceptance owner after the pushed role swap.

## Ownership

Main source directory: `prototypes/phone-transcriber/android/app/src/main/java/com/pmixay/ascent/`.

- Own `ConversationViewModel.kt`, `AudioImporter.kt`, `PcmWave.kt`, `ConversationStore.kt` and new source/coordinator/export/text-model adapter files.
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
- [ ] Adapt WAV reading to a bounded source abstraction and establish media duration/time origin. The engine side (`PcmWindowSource` and a `TranscriptionEngine` overload) is pushed in `7782992`; read the contract in [pcm-window-source.md](../interfaces/pcm-window-source.md) and implement a source for the picked `content://` URI. CI has not yet verified it.
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
