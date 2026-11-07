# Ascent four-person delivery plan

Date: 6 November 2026. Baseline: `d7759627`.

This is a proposed execution plan for the supplied **Neuro-stenographer** competition, with explicit completion evidence. It does not mark tasks complete and does not claim the older watch-first Ascent product is finished. See [HACKATHON_AUDIT.md](HACKATHON_AUDIT.md) for sources, inspected CI and code-level findings.

## Individual execution plans

Each plan includes first assignments, ordered checklists, file ownership, colleague dependencies and completion evidence:

- [Pasha — application, import/export, lifecycle and local LLM](roles/PASHA.md)
- [Andrey — speech models, diarization, timestamps and evaluation](roles/ANDREY.md)
- [Timofey — CI, embedded models, performance and release](roles/TIMOFEY.md)
- [Petya — UX, independent QA and submission](roles/PETYA.md)

These plans expand the assignments below without changing the shared contracts or marking any implementation task complete.

## 1. Delivery objective and order

Deliver one clearly identified CI-built ARM64 APK containing every model needed for the default pipeline. On a clean supported phone with networking disabled before first launch, a judge can import an unfamiliar local recording, process it on the phone, inspect timestamped speaker turns and export the exact required JSON. Three successive files must work independently. Provide source, measured results, installation instructions, architecture, limitations and a video of the same release.

Priority order:

1. **P0 — qualification:** import, exact JSON, embedded models, meaningful local-LLM compliance, reproducible green CI and cold offline completion.
2. **P1 — scored quality and stability:** Russian WER, diarization including short turns, timestamps, RTF, bounded memory, recoverable errors and usable progress.
3. **P2 — measured improvements:** English robustness, neural VAD/denoising, quantization, optional text editing, overlap experiments and optional downloadable updates. English receives smoke coverage before release even if broader tuning is deferred.

Wear OS, Rewind, profiles/places expansion, sound-event recognition and remote AI do not enter this critical path. Existing encrypted storage and working inference are the base; a large multi-module rewrite is unnecessary.

## 2. Ownership and use of available AI tools

Assignments assume no information about the teammates' experience beyond available tools. Confirm Android/ML experience and device access at kickoff; swap owners if needed while retaining these boundaries. Subscription count is a concurrency resource, not evidence of competence or guaranteed throughput.

| Person | Accountable role | Owns | Reviewer / backup | AI use |
| --- | --- | --- | --- | --- |
| **Pasha** — Claude Pro + GPT Plus | Application architect and product/acceptance lead | Import/source lifecycle, processing orchestration, canonical domain contract, exact JSON, storage failure handling, local text-LLM integration, organizer questions | Timofey for application contracts; Petya for user flow | Claude for coherent application changes; GPT for independent contract/lifecycle review and counterexamples. |
| **Andrey** — Claude Pro + GPT Plus | Speech/ML lead | ASR, diarization, timestamp alignment, VAD experiments, model/quantization quality decisions, evaluation interpretation; selects and constrains text LLM with Pasha | Timofey checks measurement/provenance; Pasha checks integration | Claude for algorithm work; GPT for experiment design, metric review and failure analysis. Change one hypothesis at a time. |
| **Timofey** — three GPT Plus accounts via Codex LB | Integration, model packaging, CI and performance lead | Embedded-model bootstrap, manifests/native packaging, reproducible CI inputs, ARM benchmarks, release integration and final artifact provenance | Pasha for architecture; Andrey for model validity | Separate workspaces for (1) packaging/CI, (2) benchmark tooling, (3) read-only review/integration. Multiple accounts do not justify concurrent writes to the same branch/files. |
| **Petya** — Kimi + Cursor | UX and independent QA lead | Import/progress/result/export UI, error messages and accessibility, held-out test custody, device test matrix, judge-facing instructions/results/video | Pasha reviews UI state contract; Timofey reviews reproducibility | Cursor for bounded UI changes; Kimi for test matrices, documentation and review. Petya executes device checks and verifies claims personally. |

### File boundaries

Paths below are relative to `prototypes/phone-transcriber/` unless specified.

- **Pasha:** `android/app/src/main/java/com/pmixay/ascent/ConversationViewModel.kt`, `AudioImporter.kt`, `PcmWave.kt`, `ConversationStore.kt`; new `AudioSource`, `ProcessingCoordinator`, `CompetitionJsonExporter` and local text-model adapter files. Owns additions to the domain contract. Keeps import metadata time distinct from source-relative media time.
- **Andrey:** `TranscriptionEngine.kt`, `AsrChunking.kt`, `CtcDecoder.kt`, `CtcChunkStitcher.kt`, `Speaker*.kt`; speech evaluation algorithms and corresponding focused regressions. Owns `TranscriptionEngine` edits after the initial source-interface seam is agreed with Pasha.
- **Timofey:** root `.github/workflows/`, Android build/native packaging and manifest, `scripts/`, `Model*.kt`, model-pack schema and export tooling, performance harness. Changes to export numerical code require Andrey review. Manifest/schema changes require Pasha/Andrey sign-off through normal code review.
- **Petya:** `MainActivity.kt`, `GlassUi.kt`, resources, new end-to-end UI checks, `docs/` and submission material. Calls ViewModel APIs rather than implementing inference in composables. Pasha reviews activity-result handling.
- Shared DTOs (`TranscriptSegment.kt`, source/job/result contracts) and Gradle/native changes have a single designated editor at a time. Small targeted interfaces are preferable to a preemptive full refactor.

### Merge ownership

Timofey integrates reviewed changes; Pasha is the product acceptance owner. Andrey approves model-quality changes. Petya can block release on a reproduced acceptance failure. These are responsibilities, not extra approval ceremonies for trivial edits.

## 3. Freeze contracts in the first two hours

### Input and processing

- `AudioSource`: user-owned URI, name, media duration when known, format/channel/sample-rate metadata, read permission lifetime; no implicit file-path conversion for `content://` URIs.
- `PcmWindowSource`: bounded mono 16 kHz float samples with absolute sample offsets; explicit close/cancel behavior. Initially adapt the existing WAV source, then add platform decoding and resampling. Never allocate a full long recording in RAM.
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

Andrey selects a license-compatible small Russian-capable quantized text model after a short ARM feasibility comparison. Pasha integrates its runtime; Timofey packages the exact weights/tokenizer/native dependencies. A roughly 0.5–1.5B-class candidate is a starting search range, not a selected model or a guaranteed memory fit.

Give it a useful narrow job: punctuation/case restoration over bounded ASR spans, preferably outputting structured punctuation/case edits rather than rewriting sentences. Validate that lexical tokens, numbers, negation, order, speaker IDs and times are unchanged under a frozen lexical normalization. Invalid output falls back to the raw transcript and records a non-content diagnostic status. Never ask the LLM to invent missing speech or determine speakers from text alone.

Run it sequentially after releasing acoustic sessions; cap context/output, elapsed work and retries. Report total RTF including this stage. A bundled but unused model, or a hidden failed LLM, is not evidence that the requirement is satisfied. Persistent native/runtime failure remains a compliance defect even if the raw fallback keeps the app usable. If organizers explicitly accept ASR alone, record that answer and reconsider the extra model before release freeze.

### Embedded models

Use one default offline artifact first. Include ASR, speaker model and every tokenizer/text-model/VAD asset actually required by the chosen default pipeline. Bootstrap into an immutable internal model directory using streamed copying, checksums, adequate disk-space checks and atomic selection. Never activate a partially extracted revision. Recover interrupted extraction on next launch.

Use a manifest pinned in source/build configuration, not whatever the catalog happens to advertise during a build. Preserve leases during replacement. First-launch readiness must not wait for any network request. Prefer disabling automatic update work in the competition path for deterministic evaluation; optional updates can follow after acceptance.

Version the model-pack schema coherently across Kotlin, Python, server and tests, or place the text model in a separately versioned manifest. Existing rigid file lists reject arbitrary extra payloads. Baseline old-model regressions stay intact alongside separately identified candidate results.

Deleting an extracted model does not remove its bytes from an installed APK's immutable assets. Do not promise that a “delete embedded model” button reclaims those APK bytes. A second lightweight APK is optional and requires an explicitly updated artifact policy; it is not necessary for the initial deliverable.

## 4. Work packages, dependencies and acceptance

Estimates are focused human engineering hours with existing AI tools, excluding unattended CI time. They are ranges, not a deadline promise. Native model integration and device accuracy may require additional iterations.

| ID | Owner | Work / concrete output | Depends on | Estimate | Acceptance evidence |
| --- | --- | --- | --- | ---: | --- |
| H00 | Pasha + all | Record scope, deadline/device assumptions, LLM question, input/export/job contracts and owners. Synchronize README/roadmap/agent instructions. | — | 2–3 team hours | One agreed contract; no contradictory active instructions. |
| H01 | Timofey | Diagnose current export failure from full logs; restore reproducible two-hour inputs on `main`; reconcile CI inference policy; preserve all required checks. | H00 | 6–12 h | Existing workflows green on the intended commit, including clean-input bootstrap demonstration. |
| H02 | Pasha | Reachable SAF import; source adapter; WAV header/format validation; common sample rates/stereo; platform-decoded MP3/AAC/M4A where supported; bounded resampling. | H00 | 10–18 h | Selected-format fixtures reach real local inference; correct duration/time origin; bad input fails cleanly; original file is unchanged. |
| H03 | Pasha | Competition JSON serializer, empty-result export, stable IDs, contract validation and export failure handling. | H00; integrated with H02 | 3–5 h | CI schema/semantic tests plus exported device JSON accepted by an independent validator. |
| H04 | Timofey | Bundled model manifest/build inputs, streamed offline installation, integrity and interrupted-extraction recovery; packaging checks changed from forbidden to required weights. | H00, H01; H07 model choice | 8–14 h | Clean install with all networks off completes three imports without downloading weights; correct APK/model hashes recorded. |
| H05 | Petya | File selection entry, truthful stages/progress, cancellation, filename/duration, speaker turns, easy JSON export, useful errors and readable layout. | H00; integrate H02/H03/H06 | 8–14 h | Another teammate completes the judge flow unaided, including denial/cancel/empty results and font scaling. |
| H06 | Pasha | Lifecycle-safe job owner, serialized inference, cancel/error cleanup, corruption/low-storage recovery; safe model lease/resource handling. | H02/H03 | 8–14 h | Rotation, background/return, process death/reopen, repeated jobs and failures leave coherent state and no source loss. |
| H07 | Andrey + Pasha | Short text-LLM feasibility spike, actual local model selection, constrained punctuation/case adapter and validation. Pasha implements adapter; Andrey owns model/semantic quality. | H00; H04 packaging interface | Andrey 4–8 h; Pasha 8–14 h | Actual on-device text inference with bundled model, unchanged lexical content/IDs/times, bounded resources and measured incremental RTF. |
| H08 | Andrey | Freeze development/reference protocol, benchmark current pipeline, add short-turn/one-speaker/similar-voice/telephone/meeting cases; tune one diarization/segmentation hypothesis at a time. | H00; H02 for phone baseline | 12–20 h | Paired baseline/candidate report; existing regressions pass; fresh held-out test after model/threshold freeze. |
| H09 | Andrey | Boundary benchmark and timestamp improvement: correct sample/frame timing, VAD-supported refinement or constrained local alignment; preserve words and pause coordinates. | H08 | 6–10 h | Start/end MAE, median/p95 and threshold hit rates on manually checked boundaries; no seam-word regression. |
| H10 | Timofey + Andrey | ARM performance measurement; test thread counts, loading/reuse and quantization candidates only when needed. Include all stages and cold/warm costs. | H02/H04/H07 | Timofey 8–14 h; Andrey 4–8 h | RTF, peak PSS/RSS, installed storage and repeated-run table; selected optimization preserves quality gates. |
| H11 | Petya + all device owners | Independent E2E/negative/lifecycle/device matrix; custody of new held-out recordings and reference annotations. | H02–H10 as available | Petya 10–16 h; others 2–4 h each | Filled pass/fail matrix with device/build/model identifiers; failures reproducible; no blocker remains. |
| H12 | Petya + Pasha + Timofey | Judge-facing docs/results/video, clean final CI, signed identifiable APK, hashes and durable repository-linked release download. | H01–H11 | Petya 5–8 h; Pasha 2–4 h; Timofey 3–5 h | Full submission checklist and fresh offline rehearsal on the exact delivered APK. |

Parallel work produces an approximate **5–7 focused working-day** baseline for four people if supported devices, model artifacts and repository permissions are available. This estimate is for competition readiness, not completion of the old watch product; re-estimate after H00/H01/H07. Quality targets that fail need further iteration, not a renamed “done” state.

## 5. Integration milestones and schedule

### M0 — contract and reproducibility (first 2 hours, then H01 in parallel)

- Pasha: ask organizer questions, freeze minimal contracts and confirm scope.
- Andrey: preserve current model/algorithm baseline; list input model hashes and existing quality failures.
- Timofey: inspect full failing CI logs, identify available verified weights and missing evaluation-input recovery path.
- Petya: write the three-file judge script and identify two physical phones; prepare reference/format matrix.

**Exit:** everyone can work against the same source/job/result/JSON contract; no one starts a watch/cloud/UI redesign branch.

### M1 — baseline vertical slice (day 1–2)

- Pasha delivers simplest real WAV import and exact export first; then broadens decoding.
- Andrey supplies baseline expected behavior and begins the text-LLM feasibility spike.
- Timofey restores CI and embeds the existing acoustic models first; provisions the selected text-model assets next.
- Petya connects the import/results/export UI and performs the first offline walkthrough.

**Exit:** clean-install offline import → local real inference → result → exact JSON on ARM. If LLM integration is not yet ready, this is an internal checkpoint, not full specification compliance.

### M2 — compliance and stability (day 2–4)

- Pasha completes the local text stage, lifecycle/error/cancel behavior and source decoding.
- Andrey measures Russian failure categories and proposes evidence-backed speaker/timing changes.
- Timofey measures ARM memory/time and hardens installation/native runtime behavior.
- Petya tests negative inputs, three sequential jobs, denied microphone permission and background/restart behavior.

**Exit:** complete mandatory flow, including resolved LLM requirement, with no runtime blocker and a recorded baseline quality/performance report.

### M3 — scored quality (day 4–5)

- Andrey freezes a candidate using development results only; validates short/long/similar-voice cases and timestamps.
- Timofey compares performance candidates without changing accuracy data or weakening gates.
- Pasha integrates only accepted candidates and reviews lexical-preservation rules.
- Petya runs the held-out evaluation and checks Russian/English/telephone/meeting coverage.

**Exit:** chosen configuration passes the predeclared internal criteria. A failed held-out set becomes diagnostic data; subsequent claims require a fresh holdout.

### M4 — release freeze and defense (day 6–7, or final 20% of available time)

Only blocker fixes after freeze. Timofey pins artifact/model/config hashes and verifies all applicable jobs. Petya records the exact build, installation script, results and video. Pasha checks every mandatory requirement against evidence. All four rehearse explaining architecture, quality limits and their contributions.

**Exit:** a different teammate repeats the evaluator procedure offline on the final APK; all deliverables are reachable from the submitted repository.

### If only 36–48 hours remain

Use the same ownership and gates, compress the work rather than pretending the full estimate fits:

| Window | Team objective |
| --- | --- |
| 0–2 h | Resolve contracts, devices, inputs and LLM interpretation; diagnose red CI. |
| 2–12 h | Import + exact JSON + embedded existing models + minimal UI; first real offline ARM run. |
| 12–24 h | Meaningful local LLM, input conversion, job/error handling, required CI input recovery. |
| 24–34 h | Small representative phone benchmark; fix high-impact errors and crashes. |
| Final 10–14 h | Freeze, retest, package instructions/results/video and rehearse. |

Cut speculative model replacement, advanced overlap separation, decorative redesign, optional updates and nonessential editing first. Do not cut import, JSON compatibility, embedded usable models, local processing, required checks, LLM resolution or final offline testing. If a mandatory gate remains open, call the result incomplete; extra accounts do not remove native-integration or measurement dependencies.

## 6. Test and quality strategy

### A. Mandatory functional matrix — Petya owns evidence

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

### B. Quality corpus — Andrey designs, Petya holds the final set

Keep existing two-hour and six-reader regressions. Add a manageable new collection of continuous public/licensed annotated recordings, with both development and untouched evaluation portions separated by recording and preferably speaker. Initial target: 12–20 clips, 45–90 minutes total, with at least 6 held-out clips and 4 held-out speakers where obtainable. Represent both rapid dialogues and meetings; include RU clean/noisy/similar-voice/short-turn slices and an EN/code-switch smoke slice. Record duration, word count and number of short turns; small subsets are not universal accuracy evidence.

For timestamps, manually double-check at least 100 reference turn boundaries across several conditions. For short replies, collect at least 50 annotated turns below about 1.5 seconds across multiple speakers. Use original continuous recordings for boundary/overlap evaluation, not concatenated isolated clips. If adding team recordings would conflict with the existing audio policy, use authorized public media; do not create app recording archives for evaluation.

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

1. One task branch/workspace per bounded task. Small PRs against the same base; no broad automated renames while others implement contracts. All builds/tests/lint use the existing push-triggered GitHub workflows.
2. Each change includes task ID, exact changed contract, targeted verification, relevant failed/passed run links and any unmeasured claim. AI-generated code receives a human explanation/review.
3. Integrate in order: contracts/export → source seam/basic import → embedded bootstrap/UI → lifecycle/text LLM → accepted quality/performance changes. UI, fixtures and packaging can be developed in parallel against fixed interfaces.
4. Timofey's third AI workstream reviews the merged diff and records contradictions; it does not independently rewrite shared files. Andrey keeps one algorithm experiment active at a time to maintain causal attribution.
5. Reconcile missing CI inputs through a durable, integrity-checked source under repository policy. Caches accelerate verified data; they must not be the only copy. Do not upload public evaluation audio as downloadable artifacts or upload app recordings. Keep baseline model hashes and separate candidate manifests.
6. Keep the Android build and model-export workflows; do not bypass checks, add manual/PR triggers or build locally. Resolve the documented CI inference-policy inconsistency explicitly before changing that behavior.
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

**Pasha:** “Own H00/H02/H03 first. Read the audit and existing app flow. Freeze `AudioSource`/job/result contracts with Andrey and Petya, add a real SAF WAV import path and a separate PDF-compatible JSON serializer including silence. Preserve existing encrypted storage and original input. Coordinate the engine source seam with Andrey. Acceptance: another teammate imports an unknown local WAV without microphone permission and exports schema-valid JSON from real inference. Then own H06 and local text-model integration.”

**Andrey:** “Own H07 model feasibility and H08/H09 quality. Preserve the existing GigaAM/ERes2NetV2 baseline and stricter regression gates. Diagnose short turns, similar voices, seams and timing on development data. Propose a bounded local text-model role without lexical rewriting. Produce one candidate at a time with model/source/config hashes and paired metrics. Petya owns the new final holdout; do not tune against it.”

**Timofey:** “Own H01/H04. Current Android build is green but long evaluation lacks its cache; current model export fails before publication. Read full logs and restore reproducible inputs without weakening checks. Replace the competition APK's weight prohibition with complete pinned embedded-payload verification and atomic offline bootstrap. Preserve native runtime isolation and model leases. Workstreams for packaging, benchmarking and review must use separate files/workspaces. Then measure ARM RTF/memory and own release provenance.”

**Petya:** “Own H05/H11/H12. Work against Pasha's state/API contracts: file import, real progress/cancel, readable participant turns and one obvious JSON export. Build the independent three-file offline acceptance script and collect physical-device evidence, including denied permissions, silence, malformed inputs and repeated jobs. Hold the untouched test set. Prepare installation instructions, limitations, real results and a video of the final exact APK.”

## 10. After the competition release

The old product remains a separate multi-milestone effort: authenticated watch capture/transport, no app-persisted audio, bounded live inference, encrypted source-granular retention, text Rewind, watch views, sounds and grounded people/location features. Completing the competition checklist does not complete those guarantees. Resume that track only with its own device/latency/retention acceptance and a fresh product prioritization.
