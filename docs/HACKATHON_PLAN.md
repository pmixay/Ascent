# Ascent four-person delivery plan

Updated: 7 November 2026. Pushed baseline inspected: `6a078e3` on `origin/main`, including PR #2 (`e1a27f7`) and role swap `4b16e3a`. Historical audit baseline: `d7759627`.

**Hard deadline: today, 7 November 2026, 20:00 Moscow time (MSK, UTC+3). Planning window: 11:00–20:00 MSK, nine wall-clock hours for four people.** All times below are MSK on this date. This schedule replaces the previous 5–7-day estimate and the 36–48-hour fallback, including the relative dates in the role plans.

This is a time-boxed execution plan for the supplied **Neuro-stenographer** competition, not evidence of completed implementation or a guarantee that all qualification blockers fit into nine hours. The original full scope does not fit: today delivers the smallest complete mandatory pipeline, measured smoke coverage and the submission package; optional improvements and extended evaluation move to backlog. A missing mandatory requirement remains a release blocker, not a deferred requirement disguised as completion. See [HACKATHON_AUDIT.md](HACKATHON_AUDIT.md) for the historical source/CI findings. The older watch-first product remains separate.

Status on 7 November: the current branch has source changes for Petya's import,
processing, result and JSON export UI (H05), a bounded seekable WAV source, an
in-memory Android-codec source and separate jury serializer, an independent
validator and prepared H11/H12
procedures. These are pending Android CI and device verification. The user
removed the long two-hour inference and scoring jobs from push CI; that evaluation
now runs on a development machine. The competition model bundle,
local text LLM, physical-device results, APK release and video remain open.

## Individual execution plans

Each plan includes first assignments, ordered checklists, file ownership, colleague dependencies and completion evidence:

- [Pasha — application, import/export, lifecycle and local LLM](roles/PASHA.md)
- [Andrey — speech models, diarization, timestamps and evaluation](roles/ANDREY.md)
- [Timofey — CI, embedded models, performance and release](roles/TIMOFEY.md)
- [Petya — UX, independent QA and submission](roles/PETYA.md)

Each role plan has the same absolute handoff times and a bounded workload ending before 20:00. No implementation or acceptance task is marked complete by this rescheduling.

## Already pushed — excluded from today's remaining implementation

This inventory was reconciled against fetched `origin/main`, not just the older audit. Keep the pushed role swap: **Andrey owns ML; Pasha owns the application**. Reuse these outputs; do not assign their creation again.

| Pushed work | Evidence | Remaining delta only |
| --- | --- | --- |
| Brief/clarifications, audit, requirements reconciliation and individual plans | `3a05e3b`, `517b572`, `05afbbc`, `05a1714`; role swap `4b16e3a` | Today's timing, implementation seam and final evidence updates; no new audit/specification exercise. |
| Frozen development/fresh validation selection and English smoke manifest | PR #2; `tools/fixtures/dialogs/study_selection.json`, `tools/fixtures/english_smoke.json` under the prototype | Reuse manifests/splits and normalization. Petya adds only missing natural call/meeting references and final app acceptance inputs; no corpus reselection. |
| ASR/scoring/reference tooling, host RU/telephone/EN baseline and error inventory | PR #2; [ASR baseline](../prototypes/phone-transcriber/docs/asr-baseline.md), `asr_study.py`, `asr_metrics.py`, `reference_asr.py` and focused tests | Measure the actual packaged Android pipeline, speakers and complete RTF. Host reference-export scores do not validate the deployed weights or ARM. |
| Frame/time audit, rejected constant −80 ms start shift and host int8 comparison | Same baseline report | Do not repeat the rejected shift or quantization study. Human-checked natural boundaries and ARM measurements remain open; keep current acoustic weights today. |
| Pinned text-LLM shortlist, host feasibility, grammar/validation/fallback contract | PR #2; [text-model study](../prototypes/phone-transcriber/docs/text-model-feasibility.md), `text_model_study.py` | Android runtime/grammar integration, embedded exact assets and real ARM acceptance only. Proposed Qwen2.5 1.5B Q4_K_M; measured 0.5B fallback if ARM budget requires it. No new shortlist/host sweep. |
| `TranscriptSegmenter` and focused tests; >20 s runs split at their longest pause | PR #2; engine integration and `TranscriptSegmenterTest.kt` | Preserve/retest the shipped behavior; agree competition export turn granularity, not reimplement phrase grouping. |
| Existing acoustic inference, speaker pipeline, encrypted history, model downloader/leases, timeline and R8/native CI | Existing prototype; current source and workflow | Integrate only import/export/offline/text-stage gaps. Do not recreate storage, inference, download or UI architecture. |
| Development-machine two-hour evaluation tooling, public-reference option and two-provenance scorer | `6a078e3`; `evaluate_dialogs.py`, `reference_asr.py`, opt-in Kotlin scorer | Keep the tooling for local algorithm improvement. The long CI `dialog-inference` and `speech-evaluation` jobs were removed on 7 November 2026; do not restore long inference, cache bootstrapping or scoring to push CI. Do not label reference-export results as deployed-pack measurements. |

**Inspected CI:** [run 42363045969](https://github.com/pmixay/Ascent/actions/runs/42363045969), commit `6a078e3`, was **in progress** when inspected. Its long inference/scoring jobs were subsequently removed by user instruction; this is historical context, not a current gate. Earlier [run 42358746042](https://github.com/pmixay/Ascent/actions/runs/42358746042) at `4b16e3a` had a passing build job but failed for missing inference inputs. Latest model export remains [42257595048](https://github.com/pmixay/Ascent/actions/runs/42257595048), failed at `d7759627`; the study reports an expired upstream checkpoint TLS certificate. Verify a secure repair, never disable TLS. No application build or device test was performed locally for this plan edit.

## 1. Delivery objective and order

Historical execution update: [Timofey's 13:55 MSK H01 checkpoint](roles/TIMOFEY_H01_STATUS.md)
records inspected attempts, the cold cache key, access and current TLS blockers.
The first inference attempt was cancelled; attempt 2 is running. Latest-run
diagnosis is recorded, but H01 acceptance, verified embedded assets and the
remaining inference-policy decision are still open.

T1 implementation follow-up: the existing fixture/export validation checks are
now explicitly covered in `AGENTS.md`. Model export gains a separately identified,
hash-pinned official Hugging Face state-dictionary source, preserving all parity
and speaker checks. Main and task-branch cold inference/cache/scoring runs passed.
At `a83fdff`, both new cache guards and scoring passed; HF export graph hashes
match the historical acoustic pins. The entire Android run has now passed,
including the 18 new regression tests and R8/native/emulator checks. Publication
still fails strict SSH host-key verification and is excluded by the user. See
[T1 evidence](roles/TIMOFEY_T1_STATUS.md). Frozen dialogue inputs/gates are unchanged;
non-server T1 is verified, but full H01 and competition acceptance remain open.

T2 ownership update: Petya owns **real Android/ARM tests and server repair**;
Timofey implements pinned embedded preparation/bootstrap and the separately
checked `offline-baseline-<sha>` artifact, preserving compact checks. Pasha owns
job/text-stage integration; Andrey approves model/runtime choice. Reusing the
same fixed export validation in Android's embedded job is explicitly authorized,
not a new corpus-inference job. Cross-role blockers are recorded unchecked in
their role files and [T2 status](roles/TIMOFEY_T2_STATUS.md). An asset-only baseline
is not a final LLM/offline-ARM-qualified release. Existing absolute handoffs remain.

Main synchronization and branch-cleanup review: [TIMOFEY_MERGE_REVIEW.md](roles/TIMOFEY_MERGE_REVIEW.md).
The merged baseline requires complete same-revision CI, with intended emulator
cases proven executed rather than only a successful Gradle invocation. The review
does not promote an internal APK to a compliant competition release.

Deliver one clearly identified CI-built ARM64 APK containing every model needed for the default pipeline. On a clean supported phone with networking disabled before first launch, a judge can import an unfamiliar local recording, process it on the phone, inspect timestamped speaker turns and export the exact required JSON. Three successive files must work independently. Provide source, measured results, installation instructions, architecture, limitations and a video of the same release.

Priority order:

1. **P0 — qualification:** import, exact JSON, embedded models, meaningful local-LLM compliance, reproducible green CI and cold offline completion.
2. **P1 — scored quality and stability:** Russian WER, diarization including short turns, timestamps, RTF, bounded memory, recoverable errors and usable progress.
3. **P2 — measured improvements:** English robustness, neural VAD/denoising, quantization, optional text editing, overlap experiments and optional downloadable updates. English receives smoke coverage before release even if broader tuning is deferred.

Wear OS, Rewind, profiles/places expansion, sound-event recognition and remote AI do not enter this critical path. Existing encrypted storage and working inference are the base; a large multi-module rewrite is unnecessary.

### Today's scope boundary

- Keep the existing GigaAM/ERes2NetV2 acoustic weights, thresholds and stricter regressions. No acoustic-model replacement, quantization search, new VAD, overlap separation, timestamp research or decorative redesign today.
- Implement reachable import, exact JSON including silence, complete embedded models, real local text processing, truthful job state/cancellation and source-safe cleanup. Preserve existing microphone capture and encrypted history; do not add an audio-copy shortcut to make import faster to implement.
- Start with PCM16 mono 16 kHz WAV, then rate/channel conversion and common platform-decoded formats within H02's time box. Record supported formats from actual trials. Unknown organizer formats are not permission to declare WAV-only sufficient; an unmet required format is a blocker.
- One competition ARM64 artifact is the submission priority. Preserve the compact variant and its existing checks when extending the workflow; no model-removal UI, second new distribution flow or optional download UX today. Do not change artifact policy silently.
- Prepare documents/video from 11:20 onward, not after coding. Target all role deliverables by 19:30; reserve 19:30–20:00 solely for submission/link verification and contingency.

## 2. Ownership and use of available AI tools

Assignments assume no information about the teammates' experience beyond available tools. Confirm Android/ML experience and device access at kickoff; swap owners if needed while retaining these boundaries. Subscription count is a concurrency resource, not evidence of competence or guaranteed throughput.

| Person | Accountable role | Owns | Reviewer / backup | AI use |
| --- | --- | --- | --- | --- |
| **Pasha** — Claude Pro + GPT Plus | Application architect and product/acceptance lead | Import/source lifecycle, processing orchestration, canonical domain contract, exact JSON, storage failure handling, local text-LLM integration, organizer questions | Timofey for application contracts; Petya for user flow | Claude for coherent application changes; GPT for independent contract/lifecycle review and counterexamples. |
| **Andrey** — Claude Pro + GPT Plus | Speech/ML lead | ASR, diarization, timestamp alignment, VAD experiments, model/quantization quality decisions, evaluation interpretation; selects and constrains text LLM with Pasha | Timofey checks measurement/provenance; Pasha checks integration | Claude for algorithm work; GPT for experiment design, metric review and failure analysis. Change one hypothesis at a time. |
| **Timofey** — three GPT Plus accounts via Codex LB | Integration, model packaging, CI and performance lead | Embedded-model bootstrap, manifests/native packaging, reproducible CI inputs, ARM benchmarks, release integration and final artifact provenance | Pasha for architecture; Andrey for model validity | Separate workspaces for (1) packaging/CI, (2) benchmark tooling, (3) read-only review/integration. Multiple accounts do not justify concurrent writes to the same branch/files. |
| **Petya** — Kimi + Cursor | UX and independent QA lead | Import/progress/result/export UI, error messages and accessibility, held-out test custody, device test matrix, judge-facing instructions/results/video | Pasha reviews UI state contract; Timofey reviews reproducibility | Cursor for bounded UI changes; Kimi for test matrices, documentation and review. Petya executes device checks and verifies claims personally. |

T2 user override to the original table/schedule: Petya executes all real
Android/ARM validation and measurements and owns server TLS/SSH/publication
repair. Timofey supplies build assets, harness/measurement contracts and artifact
identity, and reviews evidence. The older table's shared measurement slots remain
coordination slots, not duplicated physical-device ownership.

### File boundaries

Paths below are relative to `prototypes/phone-transcriber/` unless specified.

- **Pasha:** `android/app/src/main/java/com/pmixay/ascent/ConversationViewModel.kt`, `PcmWave.kt`, `ConversationStore.kt`; new `AudioSource.kt`, `WavAudio.kt`, `PcmStream.kt`, `CompressedAudio.kt`, `ProcessingJob.kt`, `CompetitionJsonExporter.kt` and `TextStage.kt` (the copying `AudioImporter.kt` was removed; see [app-pipeline.md](interfaces/app-pipeline.md)). Owns additions to the domain contract. Keeps import metadata time distinct from source-relative media time.
- **Andrey:** `TranscriptionEngine.kt`, `AsrChunking.kt`, `CtcDecoder.kt`, `CtcChunkStitcher.kt`, `Speaker*.kt`; speech evaluation algorithms and corresponding focused regressions. Owns `TranscriptionEngine` edits after the initial source-interface seam is agreed with Pasha.
- **Timofey:** root `.github/workflows/`, Android build/native packaging and manifest, `scripts/`, `Model*.kt`, model-pack schema and export tooling, performance harness. Changes to export numerical code require Andrey review. Manifest/schema changes require Pasha/Andrey sign-off through normal code review.
- **Petya:** `MainActivity.kt`, `GlassUi.kt`, resources, new end-to-end UI checks, `docs/` and submission material. Calls ViewModel APIs rather than implementing inference in composables. Pasha reviews activity-result handling.
- Shared DTOs (`TranscriptSegment.kt`, source/job/result contracts) and Gradle/native changes have a single designated editor at a time. Small targeted interfaces are preferable to a preemptive full refactor.

### Merge ownership

Timofey integrates reviewed changes; Pasha is the product acceptance owner. Andrey approves model-quality changes. Petya can block release on a reproduced acceptance failure. These are responsibilities, not extra approval ceremonies for trivial edits.

## 3. Freeze contracts by 11:20

Use a 20-minute kickoff, not two hours of design. Reuse the pushed requirements, study manifests and text-edit contract. Agree only the missing source/engine seam, job commands/states, canonical result, export API and embedded/text-runtime seam. Pasha records signatures and owners; refinements must not start a cross-module rewrite.

### Input and processing

- `AudioSource`: user-owned URI, name, media duration when known, format/channel/sample-rate metadata, read permission lifetime; no implicit file-path conversion for `content://` URIs.
- `PcmWindowSource`: bounded mono 16 kHz float samples with absolute sample offsets; explicit close/cancel behavior. The source now selects a seekable WAV reader or an in-memory Android codec reader, resampling as needed. Never allocate a full long recording in RAM.
- Two-pass processing may reopen/redecode the user's source. Preserve decoder PTS/sample counts, resampler state and mapping to original time; do not remove pauses from the timeline. For non-seekable providers, use a documented bounded sequential strategy or clearly reject that provider rather than silently spooling audio to disk.
- `ProcessingState`: `Idle`, `PreparingModels`, `ReadingInput`, `Recognizing`, `Diarizing`, `Formatting`, `Saving`, `Completed`, `Cancelled`, `Failed`. Model readiness remains separate from job state.
- One active inference job at a time. Retain a job ID, source identity and the model-revision lease. Ignore stale callbacks and stop repeated taps from launching duplicate inference.
- Staged progress uses completed media units; model loading and genuinely unknown work can be indeterminate. Do not invent percentages or ETA. Expose cancellation; native stages may finish their current call before cancellation becomes effective.
- Completed transcript stores source duration, canonical timed words/turns, anonymous speaker IDs, inferred attribution, raw ASR text, any constrained text-model presentation and model/pipeline revision. Checkpoint only permitted text/metadata, never audio.

### Export

- One dedicated serializer for the PDF schema. Keep the old storage serializer separate; do not rename storage fields and break old history simply to change export.
- Required root: `transcript` array. Each entry: string `speaker_id`, integer `start_ms`, integer `end_ms`, string `text`.
- Times refer to the original imported media, including leading silence and retained pause positions; `0 <= start_ms < end_ms <= mediaDurationMs` for nonempty speech turns.
- Sort by start time with stable ties. Do not globally prohibit overlapping intervals: overlapping speech is a legitimate future output; its scoring needs organizer clarification.
- IDs remain stable per input and independent of display-name edits. Preserve recognized words; do not merge UI timeline blocks across all pauses into one exported turn.
- Silent input exports an empty array. JSON strings use correct escaping and UTF-8. Export does not require microphone permission or internet.
- Export written through Android Storage Access Framework; surface provider/write failures. Preserve a valid existing transcript if export fails.

### Local text model

The plan treats the PDF's local-LLM requirement literally unless organizers confirm otherwise in writing.

Do not repeat the pushed host feasibility study. By **12:00**, Andrey hands Pasha and Timofey the existing pinned Qwen2.5 1.5B Q4_K_M manifest and grammar/validation contract, plus the already studied 0.5B fallback. Select between them from actual ARM cost, not the host's speed. **Update 7 November, 14:30 MSK:** with no Android llama.cpp runtime available, Andrey provisionally chose Qwen2.5 0.5B int8 ONNX on the existing ONNX Runtime with a single word-preserving pass; see [text-punctuation.md](interfaces/text-punctuation.md). A 19:00 host comparison with Qwen2.5 1.5B, Qwen3 0.6B and Qwen3.5 0.8B kept it; ARM acceptance is still open. Pasha starts the bounded Android adapter immediately; Timofey prepares native bindings/assets in parallel. Prefer a compatible existing runtime integration over a new runtime research project. The actual feasibility gate is a CI-built on-device run by **14:30**; the host report and a provisional manifest are not Android model acceptance.

Implement the pushed contract: punctuation/case restoration over bounded ASR spans using the per-phrase grammar and an independent lexical validator, projecting only allowed edits onto original words. Do not redesign it as free-form rewriting. Validate that lexical tokens, numbers, negation, order, speaker IDs and times are unchanged under the frozen normalization. Invalid output falls back to the raw transcript and records a non-content diagnostic status. Never ask the LLM to invent missing speech or determine speakers from text alone.

Run it sequentially after releasing acoustic sessions; cap context/output, elapsed work and retries. Report total RTF including this stage. A bundled but unused model, or a hidden failed LLM, is not evidence that the requirement is satisfied. Persistent native/runtime failure remains a compliance defect even if the raw fallback keeps the app usable. If organizers explicitly accept ASR alone, record that answer and reconsider the extra model before release freeze.

### Embedded models

Use one default offline artifact first. Include ASR, speaker model and every tokenizer/text-model/VAD asset actually required by the chosen default pipeline. Bootstrap into an immutable internal model directory using streamed copying, checksums, adequate disk-space checks and atomic selection. Never activate a partially extracted revision. Recover interrupted extraction on next launch.

Use a manifest pinned in source/build configuration, not whatever the catalog happens to advertise during a build. Preserve leases during replacement. First-launch readiness must not wait for any network request. Prefer disabling automatic update work in the competition path for deterministic evaluation; optional updates can follow after acceptance.

Version the model-pack schema coherently across Kotlin, Python, server and tests, or place the text model in a separately versioned manifest. Existing rigid file lists reject arbitrary extra payloads. Baseline old-model regressions stay intact alongside separately identified candidate results.

Deleting an extracted model does not remove its bytes from an installed APK's immutable assets. Do not promise that a “delete embedded model” button reclaims those APK bytes. A second lightweight APK is optional and requires an explicitly updated artifact policy; it is not necessary for the initial deliverable.

## 4. Work packages, dependencies and acceptance

The rows below are **handoff deadlines, not additive effort estimates**. Each person's sequential schedule is in section 5 and their role file. Four people provide at most 36 person-hours, including coordination, review, device work and submission; AI subscriptions do not increase wall-clock time. Native integration and red CI are the highest risks.

| ID | Owner | Today's bounded output | Depends on / handoff deadline | Evidence required |
| --- | --- | --- | --- | --- |
| H00 | Pasha + all | Only missing devices/access and source/job/result interfaces; reuse published scope/export/text contracts. | **11:20** | Shared callable seam; unanswered organizer questions recorded as assumptions. |
| H01 | Timofey; Andrey reviews provenance | Keep the bounded push CI green after removing the long inference/scoring jobs; verify the single build job covers app/wear lint, unit tests, ARM64 APK inspection and x86_64 emulator checks. Securely repair the separate model-export failure. Keep the narrowly scoped fixture/export speech checks. | Diagnosis/access status **12:00**; required jobs green before final acceptance. | Inspected current push run, exact accepted model provenance and unchanged bounded gates. Public-reference development results are not deployed-pack evidence. |
| H02/H03 | Pasha; Andrey edits engine seam | Minimal source-safe URI/WAV import and dedicated exact JSON serializer, including empty output; then declared rate/channel/codec support. | APIs **11:20**; first reviewable code **12:30**; baseline integration **13:00**; required decoding **15:00**. | Real ARM import/export; CI contract checks; independent JSON validation; unchanged source and bounded PCM. |
| H04 | Timofey | Pinned embedded acoustic/text assets, runtime dependencies, checksum-verified atomic bootstrap and no first-launch network wait. | Acoustic baseline push **13:00**; complete pipeline and last model/export-input push **15:00**. | Fresh offline activation; interrupted/corrupt-payload checks; APK/model hashes. Keep compact and embedded packaging rules distinct. |
| H05 | Petya | System picker, actual processing state/cancel, readable turns, empty-result and exact JSON export/error UI. | Callable seam **11:20**; baseline UI **12:30**; full UI **15:00**. | Unaided real flow; permission/picker/export errors; no fake transcript/progress. |
| H06 | Pasha | Serialized job IDs, stale-callback rejection, safe close/cancel, defined background/restart behavior and storage-error handling; reuse existing architecture. | Integrated with H02; complete by **15:00**; blocker fixes only afterward. | Rotation/background, cancel→new job and kill/reopen; no source deletion or silent history destruction. |
| H07 | Andrey supplies studied candidate; Pasha integrates; Timofey packages | Only the remaining Android punctuation/case stage with the pushed grammar/validator, or an explicit written organizer waiver. | Existing manifest handoff **12:00**; real CI-built ARM feasibility **14:30**; integration **15:00**. | Actual successful model execution, unchanged content, fallback counts and incremental time. Host feasibility is already done, not ARM compliance. |
| H08/H09 | Andrey; Petya holds final app references | Reuse pushed selection/scoring/error study and segmenter; measure missing Android Russian call/meeting, short-turn, speaker, timing and English results. Fix only reproduced blocking correctness defects. | Existing protocol handoff **12:00**; missing references **13:00**; models/thresholds freeze **15:00**; report **19:00**. | Separate host/reference and APK/device denominators. No repeat baseline study, −80 ms experiment or new acoustic tuning; expanded boundary research is backlog. |
| H10 | Timofey measures; Andrey reviews | One fixed-configuration ARM cold/warm stage-time/RTF and available memory/storage table, collected during QA. No optimization sweep. | Baseline device run target **14:00**; candidate measurements **16:00–17:00**; final provenance **19:00**. | All automatic stages including LLM; measured versus unmeasured fields explicit. |
| H11 | Petya; all fix their blockers | Minimum offline three-file, schema, negative and lifecycle matrix; final exact-APK rehearsal. | Candidate QA **16:00–17:00**; final artifact target **17:45**, latest useful handoff **18:00**; final QA **18:00–19:00**. | Per-case pass/fail/blocked/not-run with APK/device IDs; no unresolved qualification blocker. Extended matrix stays open. |
| H12 | Petya packages; Timofey publishes; Pasha accepts; Andrey checks claims | Reuse pushed study/model descriptions; add actual installation/offline flow, missing architecture/limitations, final results, APK/source links and exact-build video. | Drafts from **11:20**; evidence **19:00**; all role sign-offs and package **19:30**; submission confirmed by **20:00**. | Durable reachable links and matching commit/APK/model/video identities; unchecked gates stay explicit. |

If H01/H04/H07 cannot be closed, every role still hands over its actual work and blocker record by 19:30, but the team must report an incomplete submission rather than claim competition readiness.

## 5. Integration milestones and schedule

### Fixed nine-hour schedule — 7 November, MSK

| Time | Pasha — application | Andrey — ML and evidence | Timofey — CI/release | Petya — UX/QA/submission |
| --- | --- | --- | --- | --- |
| **11:00–11:20** | Freeze missing callable seam; send unresolved organizer questions. | Hand over pushed acoustic/study/text contracts; agree engine seam. | Confirm CI/model/signing access, known red jobs, device and verified assets. | Agree UI commands; write three-file script and evidence table. |
| **11:20–13:00** | Exact serializer + bounded URI/WAV+codec source; first code by 12:30. | Existing text manifest/grammar handoff by 12:00; engine seam; review development-evaluation provenance. | Inspect the optimized push CI and fix observed build/export failures; embed acoustics and prepare text runtime/assets. Integrate baseline at 13:00. | Picker/result/export wiring by 12:30; add only missing natural references, reuse studies in doc drafts during integration waits. |
| **13:00–14:00** | Complete decoder/job safeguards; integrate constrained text adapter. | Check model/edit behavior and scoring; support first ARM trial. | Inspect baseline CI; hand off first CI-built ARM APK, target 14:00. | Check real baseline flow as APK arrives; finalize independent validator and references. |
| **14:00–15:00** | Close required formats/lifecycle/text stage; review Petya's integration. | Demonstrate real ARM LLM by 14:30; freeze model/threshold/config by 15:00. No acoustic tuning. | Close complete asset/runtime integration; last model/export-input push by 15:00; start full-pipeline candidate CI. | Test baseline negatives; complete mandatory UI and update actual instructions. |
| **15:00–16:00** | Review merged app; fix reproduced qualification blockers only. | Review preserved regressions and lexical/timing behavior; prepare frozen scoring. | Inspect candidate CI/model export and fix diagnosed blockers; deliver full candidate as available, target 16:00. | Review candidate against acceptance checklist; prepare final test queue. |
| **16:00–17:00** | Fix/review only reproduced P0 application defects; final source handoff by 17:00. | Score frozen candidate with Petya's held-out references; collect denominators and limitations. | Measure fixed-config RTF/memory during QA; integrate approved blocker fixes; final APK-producing push by 17:00. | Execute candidate quality/negative/lifecycle checks; send reproducible failures immediately. |
| **17:00–18:00** | Requirement/evidence review and installation/architecture text; no feature work. | Finalize quality/model/license report; explain unmeasured conditions. | Inspect final CI; APK target 17:45, latest useful handoff 18:00; supply hashes/run links and durable download. | Assemble package and video setup; do not record a substitute build as final. |
| **18:00–19:00** | Support final exact-build retests; acceptance sign-off or blocker list. | Validate reported scores/config and LLM evidence; help final tests without tuning. | Verify provenance; capture final measurements; support cold install. | Lead final cold-offline three-file rehearsal, JSON validation and essential negative checks on the delivered APK. |
| **19:00–19:30** | Sign off requirement matrix; rehearse app explanation. | Sign off metrics/limits; rehearse model explanation. | Verify final release/download/source links and checks; sign off release identity. | Record exact-build demo, finish instructions/results and assemble submission; sign off QA. |
| **19:30–20:00** | All four: submit, verify access/links and receipt; contingency only. No optional coding, tuning or redesign. | Same shared submission reserve. | Own technical upload/link recovery. | Own submission checklist and confirmation. |

### Checkpoints and dependency rules

1. **11:20 — contracts/access.** Everyone leaves kickoff with a bounded task and named handoff. Missing phone, verified weights, signing/deployment access or a viable CI path is escalated immediately; do not spend hours discovering it in isolation.
2. **12:00 — Android feasibility decision.** Andrey hands over the pushed model sources/hashes/licenses and grammar/validator; Pasha and Timofey confirm an integration route. Do not rerun model discovery. If no runtime path is viable, Pasha escalates the LLM question while all continue import/export work. Silence from organizers is not a waiver.
3. **13:00 — baseline push.** Timofey integrates contracts + audio import/export + minimal UI + embedded acoustics. Include the text-runtime probe if ready. An acoustic-only checkpoint is internal and not a compliant final release.
4. **14:30 — real ARM feasibility.** Inspect actual baseline/LLM results, CI failures and processing time. If the text stage, model bootstrap or CI still has no viable path, all four explicitly record the qualification risk and focus on that blocker; do not start quality experiments. Estimate final rehearsal time from measured speed, not an assumed RTF <1.
5. **15:00 — mandatory implementation and model/config freeze.** Complete full-pipeline candidate, push the last changes that trigger model export, and stop adding formats/features. This is a target, not permission to drop a required codec or unfinished LLM. Freeze before held-out scoring; unresolved mandatory work is red. Broader quality experiments are already out of scope.
6. **17:00 — final application source freeze/push.** Thereafter only a release-blocking fix agreed by Pasha and Timofey can change code. A fix requires new CI/artifact hashes and affected device retests; if there is no time, report incomplete instead of waiving checks. Do not tune on the holdout.
7. **18:00 — final artifact handoff.** Petya needs the exact final APK for 18:00–19:00 tests and 19:00–19:30 video. A late APK consumes this reserve; escalate explicitly rather than silently deleting final QA. Unfinished/red CI cannot be promoted just to meet the clock.
8. **19:30 — all role deliverables closed.** Each owner hands over changed commit/files, evidence links and remaining `fail`/`blocked`/`not run` items. Pasha records ready/incomplete; Petya and Timofey verify the submitted package by 20:00. The deadline never turns an unchecked gate into a pass.

### CI and device time are on the critical path

The current workflow configuration allows **45 minutes** for Android build/checks and **120 minutes** for model export; queueing/failures add time. These are limits, not measured completion estimates. The former 150-minute inference and 45-minute scoring jobs are gone; the two-hour evaluation runs on a development machine and is not on the push-CI critical path. Keep model/export-triggering changes before **15:00** and final application push by **17:00**. Preserve push-only triggers and every bounded check.

Timofey owns integration pushes. Superseded Android runs now cancel automatically, so batch changes to avoid redundant queued runs. Do not schedule many submission-only pushes ahead of final CI. Record the tested release commit separately from later documentation. Export evidence must match pinned inputs; earlier runs do not certify changed tooling/assets. Never present historical green builds as today's result.

If only one phone is available: reserve **14:00–14:30** for baseline/LLM feasibility, **16:00–16:30** for Andrey's quality inputs with Timofey measuring the same jobs, **16:30–17:00** for Petya's negative/lifecycle tests, and **18:00–19:00** exclusively for final exact-APK acceptance. Use a second already available ARM device in parallel if possible; do not spend today's window preparing a local Android toolchain. When processing overruns, report which cases remain unrun.

## 6. Test and quality strategy

### A. Today's acceptance floor — Petya owns evidence

Run this first on the candidate and repeat it on the final exact APK during **18:00–19:00**:

- Cold install/first launch with networking disabled before opening; all model stages, including the text model, run locally.
- Three independent unfamiliar files in sequence: Russian call-like speech with short replies, Russian meeting-like speech and English smoke. Prefer 1–3 minutes per file (3–9 minutes total) so the measured processing cost fits the rehearsal; this is a smoke subset, not a new supported-duration limit.
- Validate each exported JSON independently; verify empty/silent export, stable per-file IDs, source-relative time bounds, Unicode/escaping and unchanged source files. Import requires no microphone permission.
- Cancel the picker, reject malformed/unreadable input, cancel processing then start another job, rotate/background/return and kill/reopen. Confirm explicit recovery and no stale/wrong-file result. Check interrupted model extraction and export failure without losing valid text.
- Collect WER, speaker errors/inferred coverage, checked boundary errors and complete RTF on the applicable speech inputs. Use `not measured` when references/instrumentation do not support a metric; never fill the gap with host results.

At 14:30 use observed RTF/extraction time to check the one-hour final-test budget. A slow mandatory stage is a scheduling blocker to escalate, not permission to omit it or silently trim processing. If a required gate cannot be exercised, keep it open.

### Extended matrix — not a nine-hour completion commitment

The cases below remain the full hardening/coverage backlog. Reuse existing CI coverage; execute additional cases only after today's floor. Unrun cases and unsupported quality claims stay open. A reproduced mandatory-flow defect blocks release even when discovered in an extended case.

- Fresh install with airplane mode and Wi-Fi/mobile data disabled before first launch; no preinstalled app/model directory. Test APK and model activation, not just reopening an already provisioned app.
- Import without microphone permission; cancel picker; unknown size; unreadable/revoked URI; unsupported codec; malformed/truncated/empty file; genuine silence; Unicode filenames.
- WAV 16 kHz mono baseline; 44.1/48 kHz and stereo conversion; MP3/AAC/M4A on declared platform decoders. Preserve original timestamps and check stereo/anti-phase/channel-layout failure cases. Final supported list follows organizer answers and device results.
- Leading/trailing silence, a word at a chunk seam, intentional repeated words, subsecond final tail, 1/5/15/30-minute inputs and the documented maximum-limit error. Adjust length matrix if judge limits differ.
- One speaker, two similar voices, three speakers, late arrival, rapid short replies, interruptions, meeting pace, telephone-bandlimited speech, noise/music and overlap.
- Three evaluator-style files in sequence; at least ten repeated jobs; no speaker-ID/name leakage between independent files; no cumulative memory/resource growth.
- Rotation, home/background and return, screen off, process kill/reopen at each stage, cancel and immediately start another file; late callbacks must not overwrite the new job.
- Insufficient storage during model installation/result/export; interrupted installation; invalid/corrupted model payload; corrupted encrypted history; failed export provider. Original selected audio is never modified or deleted.
- JSON type/field validator, stable IDs, time bounds, nondecreasing start order, empty transcript, quotes/newlines/Cyrillic, unchanged word order and no duplicate/missing seam text.
- At least one physical Android 12+ ARM64 phone of modest available memory and one additional ARM64 device/OS version where available. Existing x86_64 CI remains useful but does not establish ARM model execution.

Keep application builds and executable automated tests in GitHub CI. Physical devices install CI-built APKs. Approved public-corpus development inference may run on a development workstation; processing the judge's actual input always runs in the submitted Android app.

### B. Reuse pushed studies; add only missing Android evidence

Keep the bounded six-reader and model-export regressions required; the long two-hour evaluation is development-machine work. The pushed Dialogs study selection, English manifest, scoring normalization and host baseline already exist; do not recreate them. The original reserved recordings are diagnostic. `masha_dima_part3` and `masha_sveta_part3` were frozen before the pushed study and have now been scored: reuse that history honestly, not as a newly untouched set after inspecting its errors. Keep host reference-export results separate from the deployed acoustic model and final Android pipeline.

By 13:00 Petya supplies only missing continuous public/licensed call-like and meeting-like references for today's small app subset, using Andrey's existing scoring protocol; English inputs can be a predeclared subset of the pushed manifest. Freeze the subset before its Android predictions. Include short replies where available and check as many real turn boundaries as the time box permits, reporting exact counts and annotation uncertainty. Do not claim speaker-disjoint or comprehensive natural-conversation validation from three files. No new app recording archive is authorized.

The earlier 12–20-clip / 45–90-minute collection, six held-out clips/four speakers, 100 checked boundaries and 50 sub-1.5-second turns are **post-deadline evaluation backlog**, not today's assignments or completed acceptance. Do not rerun host ASR/quantization/text-model sweeps simply to occupy a CI wait.

Record for every experiment:

```text
input/source manifest and split; source/license/hash
APK commit/hash; model manifest/hash; pipeline configuration
device, Android, ABI, RAM, runtime/thread settings
reference words; substitutions/deletions/insertions; WER/CER
speaker count; mapped attribution; unknown/inferred coverage; cpWER
DER only with suitable time-aligned references and declared overlap/collar policy
start/end boundary errors; median/p95; matched and unmatched turns
decode/model-load/ASR/diarization/LLM/save times; complete wall time
audio duration; RTF; peak PSS/RSS; cold versus warm run
status/failure, repetition index and thermal condition
```

`WER = (S + D + I) / referenceWords`; silence has no ordinary WER denominator, so report false insertions explicitly. Freeze case/punctuation/number/ё normalization before comparison. `RTF = completeProcessingSeconds / mediaDurationSeconds`; RTF below 1 means faster than real time. Exclude time spent by a user choosing/exporting a path, but report cold model extraction separately and include all automatic model loading/LLM work in the appropriate cold processing measurement. If the organizer defines a different boundary, publish both.

Use one global one-to-one anonymous-label mapping per recording. Unknown/missing attribution counts as error. Report macro/per-speaker/per-condition results so long turns cannot hide short-turn failure. Do not call clip-time attribution “DER” or a display label “confirmed identity”.

### C. Proposed internal release targets

These are team targets to confirm after the first phone baseline, not claims about current quality or organizer cutoffs. Freeze them before candidate selection; report misses honestly.

| Area | Initial proposed target |
| --- | --- |
| Required flow | 100% completion of mandatory acceptance cases on declared supported inputs/devices; zero reproduced release-blocking crash/ANR/data-loss defect. |
| Existing regressions | All existing stricter speaker, ASR/chunk and integrity gates remain passing. |
| Fresh natural Russian attribution | >75% overall with unknowns as errors as already required by the project; aim >=90% on clean two-speaker speech; show short-turn and similar-voice slices independently. |
| Short-turn attribution | Aim >=80% on the declared held-out short-turn slice; do not accept an overall improvement that hides a severe slice regression. |
| Russian text | Initial target WER <=15% clean, <=25% difficult/noisy on the declared new set; compare corpus counts, not only an average of clip percentages. |
| Timing | Initial target median absolute boundary error <=200 ms and p95 <=500 ms on matched non-overlap turns; disclose unmatched turns and matching coverage. |
| Speed | Aim total warm RTF <=1 on the selected primary ARM phone; report cold RTF and each device separately. A slower stable baseline may still be specification-compliant but loses speed quality. |
| Memory/stability | No OOM over declared maximum duration; record measured peak and repeated-job trend. Set a numerical device budget from actual available RAM, not an arbitrary percentage or model file size. |
| English | Complete readable English inference/export on held-out smoke inputs; report WER separately. Broader English improvement follows Russian priorities. |
| Text LLM | Successful real on-device operation; 100% lexical/ID/time-preservation by output validation, with fallback counts reported. No quality claim from punctuation alone. |

For improvements, freeze an error budget before experiments: by default no WER increase on fixed clean/telephone/meeting comparison sets, no decrease in required attribution gates, and no resource regression that breaks the device budget. Choose changes from development data, run final validation once, and separate numerical CPU-runtime variation from algorithm changes.

## 7. CI, release and collaboration rules

1. For the current user session, all app, watch and CI work stays on `claude/improve-app-peter`, synchronized with `main`. Parallel agents own bounded files within that branch; no broad automated renames while others implement contracts. All builds/tests/lint use the existing push-triggered GitHub workflows.
2. Each change includes task ID, exact changed contract, targeted verification, relevant failed/passed run links and any unmeasured claim. AI-generated code receives a human explanation/review.
3. Integrate remaining gaps in order: contracts/export → source seam/basic import → embedded bootstrap/UI → lifecycle/text LLM → blocker fixes. Reuse the pushed segmenter/study tooling. UI, fixtures and packaging can be developed in parallel against fixed interfaces; new quality/performance experiments are backlog today.
4. Timofey's third AI workstream reviews the merged diff and records contradictions; it does not independently rewrite shared files. Andrey supplies the existing model study and measured Android evidence, not another parallel acoustic experiment.
5. Run the two-hour public-corpus inference and scoring on a development machine, not in push CI. Do not upload public evaluation audio, transcripts, embeddings or reports as downloadable artifacts or upload app recordings. Keep baseline model hashes and separate candidate manifests.
6. Keep the existing Android build and model-export workflows, push-only triggers and all build/export gates. The user removed the long `dialog-inference` and `speech-evaluation` jobs; do not restore long inference, cache bootstrapping or scoring to CI without explicit authorization. Keep the narrowly scoped fixture and model-export speech validations.
7. Do not publish/promote a release merely because the build job uploaded an APK. Inspect all applicable current-commit jobs and the physical-device evidence. Keep existing R8/JNI checks and add required embedded-payload checks.
8. Use a stable signing identity for the final distributable/update path, a distinct version code and pinned model configuration. Report the exact SHA-256; do not substitute another APK after recording evidence without retesting the changed paths.
9. Review model licenses and redistribution requirements as part of selecting bundled assets. Include notices with the deliverable; model source, quantization recipe and hash are reproducibility inputs.

## 8. Submission package and final done checklist

Adapt the organizer's repository structure with English project documentation under the current repository convention and Russian UI. Provide a simple source-location map rather than a risky last-minute Android directory move.

Suggested additions when implemented:

```text
README.md                         judge quick start, release link and requirement summary
src/README.md                     map to prototypes/phone-transcriber/android
docs/ARCHITECTURE.md               actual pipeline, model/runtime versions and device execution
docs/INSTALLATION.md               supported device, disk needs, offline first run, import/export
docs/LIMITATIONS.md                measured formats/lengths/voices/overlap/lifecycle limits
docs/TEST_RESULTS.md               protocol, datasets/splits, metrics, devices and CI evidence
docs/PROTOCOL.md                   input/output contract for this speech task
artifacts/test-results/results.csv content-free aggregate/per-case measurements
artifacts/demo/README.md           durable video link and exact demonstrated APK/model hashes
config/                           nonsecret model/pipeline manifest and defaults
```

Do not copy the template's unrelated acoustic-transfer CSV fields or its fake example result. Include only measured metrics; source audio, private transcripts and embeddings stay out of published artifacts. Make the final APK accessible by a durable repository-linked release/download consistent with organizer submission rules; Actions-only 14-day retention is insufficient as the sole archival plan.

Release remains unchecked until evidence exists:

- [ ] The current branch's build, evaluation and applicable model-export checks pass; exact run/commit links recorded.
- [ ] A clean offline install activates all necessary bundled models without network dependency.
- [ ] Import and exact JSON export work on three unfamiliar files in sequence on ARM.
- [ ] The local-LLM requirement has written interpretation and/or demonstrated meaningful local implementation.
- [ ] Results are actually computed from the selected file on Android; no remote API or prewritten transcript path.
- [ ] Russian, English smoke, telephone and meeting tests have reported results.
- [ ] Time/word/speaker correctness, low-resource and lifecycle checks are documented; no unresolved blocker.
- [ ] Candidate metrics distinguish development, old diagnostic data and fresh holdout; no unsupported DER/95% accuracy claim.
- [ ] APK, source, installation, architecture/models, limitations, own tests and demonstration video are reachable.
- [ ] All four can explain the pipeline, their changes, failure cases and measured tradeoffs.

## 9. First assignments to hand to each person

**Pasha — application:** “By 11:20 give Petya the callable source/job/export seam. By 12:30 hand over minimal URI/WAV+codec import and exact JSON, including silence, for the 13:00 baseline push. By 15:00 finish required decoding, safe job lifecycle and Android text-stage integration using Andrey's already pushed model/grammar contract. Final blocker-fix handoff is 17:00; acceptance sign-off by 19:30. Reuse encrypted storage and the shipped engine/segmenter; do not reimplement them.”

**Andrey — ML:** “Do not repeat PR #2: selection, ASR/English/telephone host baseline, time audit, rejected start shift, int8 comparison, text shortlist/grammar and segmenter are pushed. By 12:00 hand over existing model hashes/licenses/contract and scoring protocol. Support the engine seam, development-machine evaluation and real ARM text-model feasibility by 14:30; freeze config at 15:00. Measure only missing Android/speaker/boundary evidence; report by 19:00 and sign off claims by 19:30. No acoustic tuning today.”

**Timofey — CI/release:** “The long inference and scoring jobs pushed in 6a078e3 were removed by user instruction because push CI was too slow; keep the development evaluation tooling and do not restore those jobs. Verify the optimized build job, repair the separate export failure and embed pinned models with compact/R8/native checks retained. Integrate baseline at 13:00; last model/export inputs at 15:00 and final app push by 17:00. Deliver final CI APK/hashes/run links by 18:00. Shared ARM measurement, durable release and sign-off by 19:30; submission reserve ends 20:00.”

**Petya — UX/QA/submission:** “Baseline picker/result/export UI by 12:30; missing natural call/meeting references by 13:00; mandatory UI complete by 15:00. Reuse existing timelines, study descriptions and English manifest. Start instruction/result drafts during integration waits. Candidate QA is 16:00–17:00; final exact-build offline three-file QA is 18:00–19:00. Finish video/package by 19:30 and confirm submission by 20:00. Keep unrun cases explicit; do not recreate the pushed studies.”

## 10. After the competition release

The old product remains a separate multi-milestone effort: authenticated watch capture/transport, no app-persisted audio, bounded live inference, encrypted source-granular retention, text Rewind, watch views, sounds and grounded people/location features. Completing the competition checklist does not complete those guarantees. Resume that track only with its own device/latency/retention acceptance and a fresh product prioritization.
