# Pasha — application and acceptance lead

## Mission

Deliver the application path from a selected local audio file to a safely retained transcript and specification-compatible JSON. Own the job lifecycle and meaningful local text-LLM integration. Parent plan: [HACKATHON_PLAN.md](../HACKATHON_PLAN.md), packages H00/H02/H03/H06/H07/H12.

All tasks below are pending. Dates are relative working days, assuming the parent's 5–7-day schedule. Re-estimate after the first device run. Build, application tests and lint run only in existing GitHub CI; physical phones install CI-built APKs.

## Ownership

Main source directory: `prototypes/phone-transcriber/android/app/src/main/java/com/pmixay/ascent/`.

- Own `ConversationViewModel.kt`, `AudioImporter.kt`, `PcmWave.kt`, `ConversationStore.kt` and new source/coordinator/export/text-model adapter files.
- Own shared domain contract changes; agree signatures before others implement adapters.
- Petya owns `MainActivity.kt`/`GlassUi.kt`; provide callable APIs and state definitions.
- Andrey owns `TranscriptionEngine.kt`/speaker algorithms; coordinate its input adapter rather than editing it concurrently.
- Timofey owns build scripts, Android manifest, model selection/installation and native dependency packaging.
- Reviewers: Timofey for resources/lifecycle; Petya for acceptance usability; Andrey for text-model behavior.

## First two hours — remove ambiguity

- [ ] Read `AGENTS.md`, the audit and parent plan; inspect current ViewModel, import and export paths.
- [ ] Ask organizers about local LLM meaning, input formats/lengths, Android/RAM/ABI, scoring and deadline. Record answers and current assumptions.
- [ ] Agree `AudioSource`, bounded `PcmWindowSource`, processing state, result and model-lease contracts with colleagues. Proposed names are design seams, not existing classes.
- [ ] Define ViewModel commands for import, cancel and export, with job IDs and error categories; hand the contract to Petya.
- [ ] Agree how Andrey's two-pass engine reopens/reads the same URI without creating an app-owned audio spool.

**Checkpoint:** all four can describe the same input → job → result → JSON path. While organizer answers are pending, implement the unambiguous import/export requirements.

## Day 1 — exact export and minimal real import

### A1. Implement the competition serializer first — 3–5 h

- [ ] Add a dedicated serializer using `transcript` and `speaker_id`, `start_ms`, `end_ms`, `text`.
- [ ] Keep internal encrypted history serialization backward-compatible.
- [ ] Validate string fields, integer milliseconds, source-duration bounds and deterministic order; preserve legitimate overlap.
- [ ] Export silence as `{"transcript":[]}`; retain stable IDs independently of user-visible names.
- [ ] Handle Unicode, escaped text, cancelled export selection and output-provider failures.
- [ ] Add focused CI contract checks; give Petya an independent expected example.

**Done:** JSON from a real device transcript passes Petya's independent schema/semantic validator, including an empty transcript. A successful serializer unit test alone is insufficient.

### A2. Connect an initial WAV input — first slice of 10–18 h

- [ ] Accept a `content://` URI from Petya's picker; handle permission lifetime and unreadable/revoked sources.
- [ ] Adapt WAV reading to a bounded source abstraction and establish media duration/time origin.
- [ ] Route it through real acoustic inference using Timofey's selected model lease.
- [ ] Save results with a distinct conversation/job identity and appropriate import metadata.
- [ ] Distinguish imported-at time from source-relative offsets; do not invent the recording's original capture date or silently reset known source expiry.

**Done:** import a new 16 kHz mono WAV without microphone permission; display and export computed results. The original file is unchanged.

## Day 2 — supported input decoding and integration

- [ ] Add WAV rate/channel conversion and common Android-decoded MP3/AAC/M4A formats as supported by target devices.
- [ ] Preserve sample/PTS mapping through resampling, including leading silence, chunk seams and decoder delay.
- [ ] Use bounded buffers; handle a second source read for speaker inference. Clearly reject unsupported non-seekable providers if no bounded strategy is available.
- [ ] Cover malformed/truncated/empty input, unknown length, unsupported format and declared duration limits.
- [ ] Ensure duplicate taps cannot start simultaneous jobs and stale callbacks cannot replace a later result.
- [ ] Merge the basic vertical slice with Petya and Timofey before broadening codec support further.

**Done:** the declared format matrix reaches real inference with correct media duration and time origin. Source files are neither deleted nor copied into a permanent app archive. No new disk-audio exception is introduced.

## Days 3–4 — job stability and local text model

### A3. Reliable processing ownership — 8–14 h

- [ ] Move long-running job ownership out of accidental activity lifetime; choose a supported Android execution mechanism with Timofey before adding service declarations.
- [ ] Define rotation, background and process-death outcomes. Process death may require reopening/reselecting a source; never claim automatic audio recovery that does not exist.
- [ ] Propagate real progress and cancellation; allow native calls to finish safely before releasing their resources.
- [ ] Close source/model/runtime handles on success, failure and cancellation; preserve already completed transcripts.
- [ ] Surface low-storage and encrypted-history errors with an explicit recovery path; never silently destroy existing history.
- [ ] Add executable lifecycle/error checks to CI and give Petya reproducible device procedures.

**Done:** cancellation followed by another job, rotation, background/return and kill/reopen produce coherent states; no original file loss, duplicate result or stale completion.

### A4. Meaningful local LLM — 8–14 h after Andrey's selection

- [ ] Integrate Andrey's selected model through the agreed runtime; obtain exact assets/native bindings from Timofey.
- [ ] Perform bounded punctuation/case edits locally after acoustic sessions are released.
- [ ] Validate unchanged lexical tokens, numbers, negation, order, speaker IDs and times. Preserve raw ASR text.
- [ ] Cap context/output/time/retries; reject malformed or meaning-changing output and show/use the raw fallback.
- [ ] Report actual local-model success/fallback and incremental runtime without content logging.

**Done:** the bundled text model demonstrably executes on ARM and has a useful constrained role. An unused bundled model or an always-failing adapter is not compliance. Revise this task only if organizers explicitly clarify that a distinct LLM is unnecessary.

## Days 5–7 — final integration and acceptance

- [ ] Integrate Andrey's accepted candidate; confirm source/word/ID/time contracts still hold.
- [ ] Fix Petya's reproduced application blockers; retest affected paths in CI and on device.
- [ ] Match each mandatory requirement to implementation and evidence; leave unverified rows open.
- [ ] Review installation/architecture/limitations documents and the final three-file offline rehearsal.
- [ ] Prepare a short explanation of import, job lifecycle, JSON, local LLM and failure recovery for the defense.

## Handoffs and blockers

| Need | From | Deliver to others |
| --- | --- | --- |
| Acoustic input/result agreement | Andrey, first two hours | Stable source/state/export APIs to Petya |
| Valid model lease and embedded assets | Timofey | Runtime/asset requirements and required manifest changes |
| Text model, tokenizer, prompt/edit contract | Andrey | Working local adapter and lexical-preservation results |
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
