# Timofey — CI, offline packaging and integration lead

## Mission

Deliver a reproducibly checked APK with a complete embedded model set, prove offline first-run operation on ARM and own final artifact provenance. Parent plan: [HACKATHON_PLAN.md](../HACKATHON_PLAN.md), packages H01/H04/H10/H12.

**Today: 7 November 2026, 11:00–20:00 MSK (UTC+3). Last model/export-input push: 15:00; final app push: 17:00; final APK handoff by 18:00; release sign-off by 19:30.** The last 30 minutes are shared submission reserve. This replaces all working-day/effort estimates. Local application builds/tests/lint and Android toolchain preparation remain prohibited; use existing GitHub workflows and their APKs.

## Already pushed / inspected — subtract from implementation

**Current main reconciliation:** [ordered review and first remaining T1 slice](TIMOFEY_PLAN_REVIEW.md).
At `f04aaa7`, both Android jobs in run `42393688728` and Model Export/publication
in run `42393688781` passed. Embedded packaging and real sequential text integration
are already in source. The validation-input receipt changes in
`timofey/plan-next-incomplete` are pushed as `2270a40`; both jobs in [CI 42395674575](https://github.com/pmixay/Ascent/actions/runs/42395674575)
passed, including cold export/receipt generation. Warm receipt reuse also passed
at `d9776cd` in [run 42400311459](https://github.com/pmixay/Ascent/actions/runs/42400311459),
with the real `62fbe3f` producer identity preserved and payloads reverified.
[T3 measurement tooling](TIMOFEY_T3_STATUS.md) at `62fbe3f` also passed both jobs
in [run 42396663795](https://github.com/pmixay/Ascent/actions/runs/42396663795),
with separate measured emulator/ARM64-archive figures. ARM/release acceptance stays open.
The next [T4 timing slice](TIMOFEY_T4_STATUS.md) adds per-job exclusive stage totals
and completed-job RTF in volatile UI state; its own CI and actual ARM measurements
remain required.

- Existing push-only Android/model workflows, R8/JNI isolation, downloader verification/leases and acoustic pipeline are the base, not tasks to recreate.
- At fetched `origin/main` `4b16e3a`, historical [run 42358746042](https://github.com/pmixay/Ascent/actions/runs/42358746042) has a **successful build/validation job**. The overall run failed because the since-removed two-hour inference inputs were missing; that failure is not a current CI gate.
- PR #2 already supplies study/scoring tools, frozen RU/English selections, host timing/int8 results, text-model hashes/licenses/contract and the segmenter/tests. Use these instead of a new performance harness or host-model search. Reference-export ASR outputs do not substitute for deployed-model speaker measurements.
- The pushed ASR report diagnoses the failed model export's expired upstream TLS certificate and unreachable deployed host. Verify a secure source/deployment repair; never bypass certificate validation. Packaging, ARM text runtime and final device cost are still open.
- **Pushed `6a078e3`:** the user removed its long `dialog-inference` and `speech-evaluation` jobs on 7 November 2026 because they made push CI too slow. Keep the development evaluation tooling and opt-in scorer, but do not restore long inference, cache bootstrapping or scoring to CI. Reference results are not deployed-pack measurements.

## Ownership

Latest user assignment: [Parakeet packaging/R8/emulator smoke](TIMOFEY_PARAKEET_STATUS.md).
Source adds the four pinned files and CC-BY-4.0 notice in schema 2, with a third
leased directory. Both jobs passed at `dbbce95` in [42404340446](https://github.com/pmixay/Ascent/actions/runs/42404340446),
including enlarged-APK emulator installation and required minified recognizer creation.
Exact new APK/bundle hashes remain uncollected because follow-up network access failed;
mode selection and actual ARM acceptance remain open.
T4 `68487f1` passed both jobs in `42402862397`; no ARM results are inferred.

- Root `.github/workflows/android.yml` and `model-pack.yml`.
- Android build configuration, manifest/native packaging, prototype `scripts/` and `Model*.kt`.
- Model-pack/export schema and delivery tooling, coordinated with Andrey on numerical/model changes.
- Performance harness/provenance review, merged release integration and final hashes. Petya executes real Android/ARM checks and measurements and owns server repair per the user's T2 reassignment.
- Pasha owns application lifecycle/source contracts; Andrey owns algorithms/model quality; Petya owns UI/independent QA.

## 11:00–12:00 — confirm access and the specific red paths

- [x] Reuse inspected current run `42358746042`; check for any newer pushed result before integrating. See the [13:55 MSK diagnosis/access handoff](TIMOFEY_H01_STATUS.md): newer build success, cancelled first inference attempt and active second attempt; full H01 remains open.
- [x] Use the pushed diagnosis for export run `42257595048`; confirm secure checkpoint access and model publication availability rather than restarting a generic investigation. Current exact-main Model Export `42393688781` passed; live metadata/pins verified in the current review.
- [x] Inspect the current push CI after the job removal: confirm bounded compact and embedded jobs cover Python tooling, app/wear lint, unit tests, ARM64 APK inspection and x86_64 emulator checks, and that superseded runs cancel. Both jobs passed at `f04aaa7` in `42393688728`; long corpus jobs remain absent.
- [ ] Locate verified model artifacts and establish who has permissions for CI, model publication and signing.
- [ ] Agree embedded-install readiness/lease contract with Pasha and expected status UI with Petya.
- [ ] Identify available ARM phones with RAM/OS/ABI/storage information.

**Checkpoint:** documented root cause or precise unresolved evidence gap for each red job; owners/access identified; baseline model availability established.

## 12:00–13:00 — repair bootstrap and integrate the first slice

### T1. Repair specific failed checks; keep the working build path

- [x] Fix the diagnosed export/validation failure while preserving numerical, integrity and speaker checks. Existing recovery and current exact-main publication verified in `42393688781`; no new exporter repair is needed.
- [x] Verify the pushed integrity-checked model/export regeneration path from pinned public sources; do not rely only on a warm cache. `2270a40` / `42395674575` actually executed export validation and receipt recording on an output-cache miss; historical cold checkpoint transport evidence remains in T1 status.
- [x] Bind inputs to source/model/pipeline hashes; verify behavior on cache miss and in the intended branch scope. Independent receipt/cold output-cache paths passed at `2270a40` and `62fbe3f`; `d9776cd` / `42400311459` verified exact warm reuse, retained producer identity and required payload/Android checks on this branch. [Evidence](TIMOFEY_T4_STATUS.md#already-verified-before-this-slice).
- [x] Keep the two-hour public-corpus evaluation on a development machine; never turn absence into skip/success or restore it as a push-CI gate. No long jobs in the inspected `2270a40` run; explicit CI scorer exclusion retained.
- [x] Keep the narrowly scoped fixture and model-export speech checks while the long dialogue study stays off CI. Retained export checks actually ran at `2270a40`.
- [x] Preserve existing push triggers/workflow names, R8, native integrity and emulator checks. Both original bounded jobs passed at `2270a40`.

**Done:** applicable existing jobs pass on the intended revision, and the build path works without relying on a particular warm cache. If access prevents this, report the exact missing permission/artifact instead of asserting success.

**T1 handoff:** non-server implementation and verification are complete; see
[exact commits/runs/hashes](TIMOFEY_T1_STATUS.md). The user excluded server work.
That is historical: Petya has since repaired authenticated publication and
verified live payloads; see [server evidence](PETYA_SERVER_STATUS.md). Long corpus
inference/scoring jobs remain removed by the newer user instruction.

## 11:20–15:00 in the same workstream — complete offline packaging

These packaging and CI tasks share the same window, not additive effort estimates. Integrate acoustic baseline at **13:00**; hand the first CI APK to the team, target **14:00**. Include a text-runtime probe early enough for **14:30** real ARM feasibility. All mandatory model/runtime changes and the last export-triggering push are due **15:00**.

### T2. Reuse existing model infrastructure; add embedded bootstrap

Implementation branch/status: [TIMOFEY_T2_STATUS.md](TIMOFEY_T2_STATUS.md).
User-updated ownership: Petya performs real Android/ARM validation and server
repair; Timofey owns assets/bootstrap/CI, Pasha actual job/text integration and
Andrey model/runtime approval. Cross-role blockers are assigned in their checklists,
not claimed as completed work here.

- [x] Pin model manifest and build-time asset hashes; package current acoustic models first to unblock an internal baseline. Required payloads/receipt checked in successful `62fbe3f` CI.
- [x] Replace the competition APK's weight-absence check with required-payload, integrity, ABI and provenance checks. Embedded checks pass at `62fbe3f`; compact weight-absence gate remains unchanged.
- [x] Include all necessary tokenizer/config/license files; add Andrey's selected text-model assets and runtime through a versioned contract. Confirmed by the 19:00 comparison: `model_int8.onnx` and `tokenizer.json` from `onnx-community/Qwen2.5-0.5B-Instruct` with the revision, sizes and hashes in [text-punctuation.md](../interfaces/text-punctuation.md) and `TextModelFiles`; it runs on the existing ONNX Runtime, so no new native library is needed. Packaging and synthetic x86_64 execution passed at `62fbe3f`; ARM approval remains open.
- [ ] Coordinate exact file-set/schema changes across Kotlin, Python and server validators/tests.
- [x] Stream assets to a staging directory with disk-space checks; verify hashes before atomically selecting an immutable revision. Installer regressions and actual embedded extraction passed at `62fbe3f`.
- [x] Preserve active inference leases and clean failed/interrupted staging safely; recover on the next launch. JVM interrupted-promotion regressions and required paired-lease corruption recovery passed at `62fbe3f`; physical process-kill acceptance remains in T3/Petya's checklist.
- [x] Ensure first-launch readiness does not wait for DNS, catalog access, WorkManager or downloads. Required network-disabled bootstrap and absence of download work passed at `62fbe3f`.
- [x] Keep update behavior deterministic for evaluation; expose local readiness separately from optional transfer activity. Offline network permissions/download service stay disabled; compact transfer gates retained in `62fbe3f` CI.

**Done:** clean install with Wi-Fi/mobile data off before first launch reaches real inference. Then add all mandatory model stages and repeat. A basic acoustic-only checkpoint is not proof of full LLM compliance.

### T3. Verify native and installation behavior

- [x] Preserve isolation between Java ONNX Runtime and sherpa's C runtime; do not resolve collisions using arbitrary `pickFirst`. Retained native checks and required emulator execution passed at `62fbe3f`; no ARM inference claim.
- [ ] Verify added text-model runtime ABI/JNI/R8 behavior with CI and actual ARM inference.
- [ ] Measure APK archive size, installed size, expanded model size and peak extraction disk requirement separately. [T3 evidence](TIMOFEY_T3_STATUS.md#inspected-ci-measurements--62fbe3f) provides actual ARM64 APK sizes and distinct x86_64 own-package/model statistics. Actual ARM installation and physical-peak measurements remain required.
- [ ] Test corrupted payload, insufficient space and process kill during extraction.
- [x] Ensure models are not activated merely because a saved success flag exists. Required corruption/readiness recovery and hash-recheck regressions passed at `62fbe3f`.

**Done:** interrupted/corrupted installs cannot create false readiness; the delivered artifact works on declared ARM targets. Removing extracted files must not be presented as reclaiming immutable APK asset bytes.

## 15:00–17:00 — full candidate CI, shared measurements and blocker fixes

Full candidate target: **16:00**. Measure the same jobs as Andrey/Petya rather than scheduling a separate benchmark sweep. One-phone quality/performance slot is **16:00–16:30**, then Petya's negative/lifecycle slot **16:30–17:00**.

### T4. Fixed-configuration ARM measurement, no optimization search

Petya performs these physical-device trials and records the results. Timofey
supplies the exact CI APK/pins and measurement contract, then reviews the table;
this checklist is not permission to substitute CI x86_64 extraction for ARM work.

- [ ] Record device/OS/ABI/RAM, APK/model hashes, threads, run order and thermal conditions.
- [ ] Measure decode, model load, ASR, diarization, text LLM and save time, plus total wall time. [T4 instrumentation](TIMOFEY_T4_STATUS.md) adds exclusive monotonic counters and an on-screen report; device measurements are not yet recorded.
- [ ] Report cold model preparation separately, cold/warm processing RTF and actual media duration. T3 keeps preparation separate; T4 computes completed-job RTF only with a known positive duration. Actual cold/warm ARM trials remain open.
- [ ] Record available peak PSS/RSS, storage and repeated-job memory observations on today's tested inputs; label unmeasured maximum-duration behavior explicitly.
- [ ] Keep acoustic weights/thresholds fixed. Host int8/thread studies are pushed; ARM optimization/quantization sweeps are backlog today.
- [ ] Measure three evaluator-style files and shared cancellation checks; ten-job/long-duration stress tests stay open unless time remains after the mandatory floor.

**Done:** a reproducible per-device table exists. Target warm total RTF <=1 is an internal goal; report slower results honestly. Do not infer device speed from desktop cached inference or model file size.

### T5. Integrate in a controlled order

- [ ] Merge agreed source/result/export contracts first, then basic import and offline bootstrap/UI.
- [ ] Integrate lifecycle/text-model stages before final quality/performance tuning.
- [ ] Merge only Andrey's accepted quality candidate with its tests/provenance.
- [ ] Inspect all required checks for the merged revision; artifact presence does not prove workflow-wide success.
- [ ] Coordinate shared manifest/Gradle/DTO edits through a single editor at a time.

## 17:00–20:00 — final CI, exact artifact and submission

Final APK-producing push by **17:00**. Workflow limits: **45 min** Android build/emulator and **120 min** model export, plus queues/retries. The former 150 min inference + 45 min scoring CI jobs are removed; two-hour evaluation runs on a development machine. Freeze model/export inputs by **15:00**. Superseded Android runs now cancel automatically; batch pushes and avoid redundant queued runs. Do not weaken checks or triggers to recover time.

- **17:00–18:00:** inspect all applicable checks; target final APK 17:45, latest useful QA handoff 18:00. Supply APK/commit/model hashes and run links. Required model-export evidence must match pinned inputs; an earlier run cannot certify changed tooling/assets.
- **18:00–19:00:** support Petya's cold offline exact-build rehearsal and capture final provenance/measurements. Do not substitute an untested APK.
- **19:00–19:30:** verify durable download/source/video links and sign off release identity.
- **19:30–20:00:** shared submission/link/access recovery reserve, no optional development.

- [ ] Freeze source, model and configuration revisions with Pasha/Andrey.
- [ ] Produce the final CI artifact with identifiable version and stable signing identity; protect signing credentials from source/APKs/logs.
- [ ] Verify all applicable jobs for the exact commit, not an earlier branch baseline.
- [ ] Give Petya the exact APK hash, model hash, run links, size figures and device benchmark table.
- [ ] Provide a durable repository-linked APK location consistent with submission rules; 14-day Actions retention is not sufficient as the only archive.
- [ ] Repeat clean offline first-run and three-file processing on the delivered build; retest affected paths after any final fix.

**Done:** source commit → CI runs → model manifests → APK hash → physical-device results → demonstration all identify the same release.

## Three Codex workstreams

| Stream | Allowed work | Output |
| --- | --- | --- |
| 1. Packaging/CI | Workflow, model installation/schema and native packaging in its own branch/workspace | Reproducible checked offline artifact |
| 2. Benchmark tooling | Measurement/report harness and device procedures, disjoint from stream 1 | RTF/memory/size tables with provenance |
| 3. Review/integration | Read-only cross-component review; integration edits made by the designated human/owner | Contract mismatch list, merged-release evidence |

Every session reads `AGENTS.md` and the same task contract. Three accounts do not permit concurrent competing writes to shared files or bypassing required checks. If a toolchain step compiles/builds/prepares Android dependencies, it belongs in CI.

## Handoffs

- From Andrey: exact model files/hashes/licenses, acceptable runtime/quantization and frozen quality gates.
- From Pasha: lease/readiness contract and job-resource lifecycle requirements.
- To Petya: installable candidate APK, clean-install instructions and exact versions for every test round.
- To all: failing check/root-cause evidence, integration status and final release identity.

If matching secure model export, native LLM operation or final CI cannot be recovered in time, deliver the implemented changes and explicit blocker record by 19:30. Do not skip checks, use reference-model outputs under deployed hashes or present a successful build job as a ready release.

## Final personal checklist

- [ ] Required checks are green on a cold run without relying on a warm cache.
- [ ] Complete model set is embedded and verified offline.
- [ ] Native runtimes actually infer on ARM after optimization.
- [ ] Cold/warm time, memory and storage are measured.
- [ ] Final APK is tied to passing checks and independent device evidence.
- [ ] Submission APK remains durably accessible.
