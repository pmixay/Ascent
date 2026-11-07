# Petya — UX, independent QA and submission lead

## Mission

Make the evaluator's task obvious, reproduce failures independently and deliver trustworthy release evidence. Own UI, held-out evaluation custody, device acceptance, instructions/results and demonstration video. Parent plan: [HACKATHON_PLAN.md](../HACKATHON_PLAN.md), packages H05/H11/H12.

**Today: 7 November 2026, 11:00–20:00 MSK (UTC+3). Baseline UI: 12:30; mandatory UI: 15:00; final QA: 18:00–19:00; video/package/sign-off: 19:30; submission confirmed by 20:00.** This replaces the former working-day/effort schedule. Use existing CI for application builds/tests/lint and its APKs for device checks. A planned case is not a passed test.

## Already pushed — reuse instead of recreating

At fetched `origin/main` `4b16e3a`, participant timeline/result UI and model-status UI already exist. PR #2 supplies frozen RU/English study manifests, normalization/scoring tools, host ASR/timestamp/telephone/English results, model feasibility/description and segmenter/tests. Link/reuse them; do not assign a new corpus selection, host benchmark report or timeline redesign. Remaining UX is picker/job-state/cancel/exact-export wiring; remaining evidence is real final Android/offline operation, missing natural call/meeting references and submission material. Pasha owns app APIs; Andrey owns ML.

## Ownership

### Explicit T2 handoff from Timofey — user-assigned

- [ ] **Own real Android/ARM validation and measurement**, including cold install with networking
  disabled before first launch, extraction of both model groups, microphone and
  imported-file inference, and the integrated text stage. Use the exact CI APK,
  not a local build; record commit/APK/bundle hashes, OS/ABI/RAM/storage, cold/warm
  RTF, PSS/RSS and all unrun cases. The CI x86_64 extraction check is not this gate.
- [ ] Exercise corrupted/missing extracted files, low disk space, kill during
  extraction, kill/reopen and three consecutive files. Confirm recovery never
  deletes the imported source or claims readiness from a saved success flag.
- [ ] **Own model-server repair:** inspect TLS/Caddy/catalog availability and
  verify the actual SSH ED25519 host key from the server/provider console before
  updating `ASCENT_MODEL_HOST_KEY`. T1 export passed; publication failed with a
  changed host key. Do not disable TLS or strict SSH checking. Credentials belong
  in protected environment/secrets, not docs/source/APKs. Re-run normal push CI
  publication and record the result; source export success is not publication.
- [ ] Review Russian embedded preparation/error UI and show local readiness
  separately from transfer/version-check age. Offline baseline has automatic
  network updates disabled; do not imply a fresh online catalog check.

Evidence handoff: [T1 exact runs and identities](TIMOFEY_T1_STATUS.md).
Timofey supplies T2 APK/bundle identities after inspected CI; this list is assigned
work, not completed device/server evidence. Original 19:30 sign-off remains.

Main source directory: `prototypes/phone-transcriber/android/app/src/main/java/com/pmixay/ascent/`.

- Own `MainActivity.kt`, `GlassUi.kt`, resources, new UI acceptance checks and judge-facing documentation/demo/results.
- Use Pasha's ViewModel/source/job APIs; model inference belongs outside composables.
- Own the new held-out recording/reference selection and final independent evaluation schedule, with Andrey's protocol.
- Pasha reviews UI state/URI integration; Timofey reviews test/artifact provenance.

## 11:00–11:20 — agree the missing evaluator flow

- [ ] Read audit and supplied JSON/three-file acceptance procedure.
- [ ] Write a step-by-step script: install → offline first launch → import → process → inspect → export → repeat twice.
- [ ] Agree commands/state/error categories with Pasha and model-readiness statuses with Timofey.
- [ ] Identify at least one primary physical ARM64 phone and a second device where available; record Android/RAM/storage.
- [ ] Create a test-results table with explicit `not run`, `pass`, `fail`, `blocked` states and APK/model identifiers.
- [ ] Reuse Andrey's pushed protocol/manifests; identify only missing natural call/meeting references and predeclare the small final app subset before its predictions. Do not restart source selection.

**Checkpoint:** the team has a single visible acceptance script and device/test ownership before UI polishing starts.

## 11:20–15:00 — mandatory UI and document drafts

Baseline picker/result/export wiring goes to Timofey/Pasha by **12:30** for the **13:00** baseline push. Finish required UI by **15:00**. During integration/CI waits, prepare instructions, reference annotations, validator and video script; do not leave all documentation until coding ends.

### E1. Wire import and processing into the existing screens

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

## Candidate and final QA — minimum before the extended matrix

- **13:00–15:00:** review references/validator and test the first real APK as it arrives (target 14:00); update actual instructions.
- **15:00–16:00:** prepare full-candidate cases and check requirement coverage; no decorative UI changes.
- **16:00–17:00:** candidate QA. If one phone, Andrey/Timofey share 16:00–16:30 for quality/time and you own 16:30–17:00 for negatives/lifecycle. Report blockers immediately for the 17:00 final source freeze.
- **17:00–18:00:** assemble documents/video setup while final CI runs. Escalate a missing final APK at 18:00 rather than silently dropping QA.
- **18:00–19:00:** on the exact final APK, clean cold-offline launch; process three independent files (RU call-like, RU meeting-like, EN smoke); independently validate JSON; test silence, denied microphone permission, picker cancellation, malformed input, cancel→new job, rotation/background/reopen and export failure. Verify source files unchanged and all mandatory models actually local.
- **19:00–19:30:** exact-build video, final results/limits/links and QA sign-off.
- **19:30–20:00:** shared submission and receipt/link verification reserve.

Choose short speech inputs (prefer 1–3 minutes each, 3–9 total) and use the measured RTF at 14:30 to budget final QA; do not assume faster-than-real-time processing. Do not silently shorten pipeline work or call unrun cases passing. The following E3–E5 entries are the extended matrix: run today's floor first; ten repetitions, every stage/device/codec and long/max-duration stress remain open when not exercised.

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

## 11:20–13:00 during integration waits — only missing references

### E6. Holdout custody

- [ ] Reuse pushed Dialogs/English selections and existing scoring. Add only missing authorized continuous call/meeting references for the three-file app subset, with sources/licenses/hashes; ready by 13:00.
- [ ] Freeze the app subset before its predictions; separate from development where possible and disclose shared speakers. Do not relabel already scored Dialogs part3 recordings as a newly untouched set after inspecting errors.
- [ ] Prepare/verify reference words, speaker turns and time boundaries; double-check ambiguous annotations.
- [ ] Cover RU call/meeting and short replies where available plus EN smoke, preferably from the existing English manifest. Expanded similar-voice/noise/overlap corpus and 100-boundary/50-short-turn targets are backlog, not another task today.
- [ ] Keep candidate predictions/scores out of tuning until Andrey freezes models and thresholds.
- [ ] Run final evaluation on the frozen candidate and report complete per-condition outcomes with denominators.

**Done:** final quality evidence is genuinely independent of candidate selection. If scores inform a new fix, mark that set diagnostic and obtain fresh validation for subsequent generalization claims.

## Drafts from 11:20; final package 19:00–19:30

### E7. Finish the judge-facing package using pushed material

- [ ] Prepare quick-start instructions: supported Android/ABI, storage needs, installation, first offline preparation, import and JSON export.
- [ ] Reuse pushed model/study descriptions; add only implemented Android/import/offline/text-runtime details with Pasha/Andrey review.
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
- From Timofey: exact CI APK/model hashes, install instructions and measurement procedures. You now execute and record the real-device measurements; Timofey reviews provenance.
- To all: reproducible failures, retest outcomes and final acceptance matrix.

## Final personal checklist

- [ ] Evaluator journey is usable without coaching or microphone permission.
- [ ] Fresh offline three-file acceptance passes on declared ARM targets.
- [ ] Export is independently validated, including silence.
- [ ] Negative/lifecycle/repeated-use cases have recorded outcomes.
- [ ] Holdout results are independent and honestly scoped.
- [ ] Complete submission and demonstration match the final APK.
