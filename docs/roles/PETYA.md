# Petya — UX, independent QA and submission lead

## Mission

Make the evaluator's task obvious, reproduce failures independently and deliver trustworthy release evidence. Own UI, held-out evaluation custody, device acceptance, instructions/results and demonstration video. Parent plan: [HACKATHON_PLAN.md](../HACKATHON_PLAN.md), packages H05/H11/H12.

All tasks are pending. Relative schedule assumes 5–7 focused working days. Use CI for application builds/tests/lint, and CI-built APKs for device checks. A planned test case is not a passed test.

## Ownership

Main source directory: `prototypes/phone-transcriber/android/app/src/main/java/com/pmixay/ascent/`.

- Own `MainActivity.kt`, `GlassUi.kt`, resources, new UI acceptance checks and judge-facing documentation/demo/results.
- Use Pasha's ViewModel/source/job APIs; model inference belongs outside composables.
- Own the new held-out recording/reference selection and final independent evaluation schedule, with Andrey's protocol.
- Pasha reviews UI state/URI integration; Timofey reviews test/artifact provenance.

## First two hours — define the evaluator journey

- [ ] Read audit and supplied JSON/three-file acceptance procedure.
- [ ] Write a step-by-step script: install → offline first launch → import → process → inspect → export → repeat twice.
- [ ] Agree commands/state/error categories with Pasha and model-readiness statuses with Timofey.
- [ ] Identify at least one primary physical ARM64 phone and a second device where available; record Android/RAM/storage.
- [ ] Create a test-results table with explicit `not run`, `pass`, `fail`, `blocked` states and APK/model identifiers.
- [ ] Agree fresh dataset/annotation requirements with Andrey; cover both jury-required call-like and meeting-like cases; separate development inputs from the held-out set.

**Checkpoint:** the team has a single visible acceptance script and device/test ownership before UI polishing starts.

## Days 1–2 — implement the mandatory UI

### E1. Import and processing screens — part of 8–14 h

- [ ] Make “Import audio” a primary entry point using the system document picker.
- [ ] Handle picker cancellation without an error; importing must not request microphone permission.
- [ ] Show filename/duration/format when available and a clear unsupported/unreadable-input message.
- [ ] Represent preparing models, reading input, ASR, diarization, text formatting and saving accurately.
- [ ] Display real progress when provided; use an indeterminate state when work cannot be quantified. Keep model readiness distinct.
- [ ] Connect cancellation and prevent duplicate starts; retain an understandable state during native-call cancellation latency.

### E2. Result and export

- [ ] Display participant turns with text and source-relative times; preserve the readable Ascent visual style.
- [ ] Make exact JSON export easy to find; distinguish display names from stable speaker IDs.
- [ ] Offer export for silent/empty results and show “No speech detected”.
- [ ] Handle write failure without losing the transcript; distinguish failed processing from failed export.
- [ ] Check large font scaling, touch targets, contrast, long text and accessibility descriptions.

**Done:** another teammate follows the three-file script unaided. A polished screen using fake data is not completion; connect it to real local results.

## Days 2–4 — systematic acceptance tests

### E3. Offline and input matrix

- [ ] Remove previous installation/data; disable Wi-Fi/mobile data before first launch; install Timofey's exact APK.
- [ ] Confirm models activate locally and import reaches inference without a network dependency.
- [ ] Test baseline mono 16 kHz WAV, 44.1/48 kHz/stereo conversion and declared MP3/AAC/M4A formats.
- [ ] Test denied microphone permission, cancelled picker, revoked/unreadable URI, Unicode filename and unknown file size.
- [ ] Test malformed/truncated/empty audio, silence, leading/trailing silence and duration-limit errors.
- [ ] Verify original files remain unchanged and are not deleted by cleanup.

### E4. Export contract

- [ ] Independently validate root `transcript`, exact field names/types, UTF-8 escaping, stable IDs and media-relative time bounds.
- [ ] Check empty array export, quotes/newlines/Cyrillic and repeated exports.
- [ ] Confirm UI grouping does not replace actual export turn boundaries or erase words.
- [ ] Reject missing/duplicated seam words and source-time resets; preserve legitimate overlap rather than imposing a false non-overlap rule.
- [ ] Submit the independent validator/contract checks to existing CI through Timofey.

### E5. Lifecycle and repeated use

- [ ] Rotate; background/return; screen off; cancel then import another file.
- [ ] Kill/reopen during model installation and each processing stage; compare observed recovery with the documented contract.
- [ ] Process three unrelated files consecutively and ten repeated jobs; inspect wrong-file results, speaker/name leakage and growing memory symptoms.
- [ ] Exercise low storage, corrupted model/history and failed output provider using agreed test mechanisms.
- [ ] Record crash/ANR/recovery failures with exact build, device, input reference and steps; avoid private content in published logs.

**Done:** every supported mandatory case has an actual outcome and evidence; fixes are retested on an identified newer APK. Do not turn an unrun case into pass because a similar case succeeded.

## Days 1–5 in parallel — prepare independent quality evidence

### E6. Holdout custody

- [ ] Select authorized public/licensed continuous dialogue with Andrey's condition/annotation criteria; record sources, licenses and hashes.
- [ ] Separate recordings and preferably speakers from algorithm-development material; do not reuse the old diagnostic Dialogs set as a fresh holdout.
- [ ] Prepare/verify reference words, speaker turns and time boundaries; double-check ambiguous annotations.
- [ ] Include rapid/short turns, meetings, similar voices, noise/telephone channel and English smoke coverage.
- [ ] Keep candidate predictions/scores out of tuning until Andrey freezes models and thresholds.
- [ ] Run final evaluation on the frozen candidate and report complete per-condition outcomes with denominators.

**Done:** final quality evidence is genuinely independent of candidate selection. If scores inform a new fix, mark that set diagnostic and obtain fresh validation for subsequent generalization claims.

## Days 5–7 — submission and demonstration

### E7. Judge-facing package — 5–8 h

- [ ] Prepare quick-start instructions: supported Android/ABI, storage needs, installation, first offline preparation, import and JSON export.
- [ ] Write architecture/model descriptions from implemented code with Pasha/Andrey review.
- [ ] Write measured limitations: formats, duration, voices, overlap, approximate timings and recovery behavior.
- [ ] Publish aggregate/per-case results with source/config/device/APK identifiers; no invented example rows.
- [ ] Map existing source into the organizer's requested layout with a source-location document if needed; adapt unrelated template fields to speech metrics.
- [ ] Link the durable final APK location supplied by Timofey, not only an expiring Actions download.
- [ ] Record a video of the exact final APK: offline status, model readiness, file import, processing, transcript and JSON export. Clearly label any shortened waiting period.
- [ ] Rehearse the judge script on the final build with someone who did not implement that flow.

**Done:** APK, source, installation, architecture/models, limitations, own tests and video are accessible from the repository and identify the same release.

## Bug/report format

```text
case ID / priority / pass-fail-blocked-not-run
APK commit + SHA-256 / model manifest hash
device / Android / ABI / available RAM
authorized input reference + hash / format / duration
preconditions and exact reproduction steps
expected versus observed behavior
content-free error evidence / timing / repetition number
owner / fix commit / retest build and result
```

An acceptance blocker includes failed cold offline readiness, unsupported required input, schema-invalid export, wrong-file output, reproducible crash/ANR/data loss or unresolved mandatory model operation. Cosmetic issues can be prioritized below these without hiding them.

## AI work pattern and handoffs

Use Cursor for one bounded UI change against agreed APIs. Use Kimi for test-case generation, documentation structure and independent checklist review. Personally execute device trials and inspect exported files. An AI-generated test report is not measured evidence.

- From Pasha: commands/state/error contract, source/export semantics and recovery expectations.
- From Andrey: scoring/annotation protocol and frozen candidate identity.
- From Timofey: exact CI APK/model hashes, install instructions and device benchmark measurements.
- To all: reproducible failures, retest outcomes and final acceptance matrix.

## Final personal checklist

- [ ] Evaluator journey is usable without coaching or microphone permission.
- [ ] Fresh offline three-file acceptance passes on declared ARM targets.
- [ ] Export is independently validated, including silence.
- [ ] Negative/lifecycle/repeated-use cases have recorded outcomes.
- [ ] Holdout results are independent and honestly scoped.
- [ ] Complete submission and demonstration match the final APK.
