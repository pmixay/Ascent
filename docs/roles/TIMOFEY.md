# Timofey — CI, offline packaging and integration lead

## Mission

Deliver a reproducibly checked APK with a complete embedded model set, prove offline first-run operation on ARM and own final artifact provenance. Parent plan: [HACKATHON_PLAN.md](../HACKATHON_PLAN.md), packages H01/H04/H10/H12.

**Today: 7 November 2026, 11:00–20:00 MSK (UTC+3). Last model/export-input push: 15:00; final app push: 17:00; final APK handoff by 18:00; release sign-off by 19:30.** The last 30 minutes are shared submission reserve. This replaces all working-day/effort estimates. Local application builds/tests/lint and Android toolchain preparation remain prohibited; use existing GitHub workflows and their APKs.

## Already pushed / inspected — subtract from implementation

- Existing push-only Android/model workflows, R8/JNI isolation, downloader verification/leases and acoustic pipeline are the base, not tasks to recreate.
- At fetched `origin/main` `4b16e3a`, [run 42358746042](https://github.com/pmixay/Ascent/actions/runs/42358746042) has a **successful build/validation job**. The overall run fails because two-hour inference inputs are missing. Do not assign generic build repair; repair the specific input bootstrap.
- PR #2 already supplies study/scoring tools, frozen RU/English selections, host timing/int8 results, text-model hashes/licenses/contract and the segmenter/tests. Use these instead of a new performance harness or host-model search. Reference-export ASR outputs do not substitute for deployed-model speaker cache.
- The pushed ASR report diagnoses the failed model export's expired upstream TLS certificate and unreachable deployed host. Verify a secure source/deployment repair; never bypass certificate validation. Packaging, ARM text runtime and final device cost are still open.
- **Newly pushed `6a078e3`:** dedicated `dialog-inference` job, public-reference ASR + identical pinned speaker model, provenance-aware scoring and the scoped policy exception. Do not implement bootstrap again. [Run 42363045969](https://github.com/pmixay/Ascent/actions/runs/42363045969) was in progress when inspected; remaining work is complete-run/cache/provenance verification and diagnosed failure repair. Reference results are not deployed-pack measurements.

## Ownership

- Root `.github/workflows/android.yml` and `model-pack.yml`.
- Android build configuration, manifest/native packaging, prototype `scripts/` and `Model*.kt`.
- Model-pack/export schema and delivery tooling, coordinated with Andrey on numerical/model changes.
- Device performance harness, merged release integration and final hashes.
- Pasha owns application lifecycle/source contracts; Andrey owns algorithms/model quality; Petya owns UI/independent QA.

## 11:00–12:00 — confirm access and the specific red paths

- [ ] Reuse inspected current run `42358746042`; check for any newer pushed result before integrating.
- [ ] Use the pushed diagnosis for export run `42257595048`; confirm secure checkpoint access and model publication availability rather than restarting a generic investigation.
- [ ] Inspect the pushed cache-producing run and its cache key/scope/provenance with Andrey; verify completed inference → scoring. Do not recreate its public-model bootstrap. Escalate actual missing access/data or failed inference by 12:00.
- [ ] Locate verified model artifacts and establish who has permissions for CI, model publication and signing.
- [ ] Agree embedded-install readiness/lease contract with Pasha and expected status UI with Petya.
- [ ] Identify available ARM phones with RAM/OS/ABI/storage information.

**Checkpoint:** documented root cause or precise unresolved evidence gap for each red job; owners/access identified; baseline model availability established.

## 12:00–13:00 — repair bootstrap and integrate the first slice

### T1. Repair specific failed checks; keep the working build path

- [ ] Fix the diagnosed export/validation failure while preserving numerical, integrity and speaker checks.
- [ ] Verify the pushed integrity-checked regeneration path from durable pinned public sources; do not rely only on a warm cache or implement this path again.
- [ ] Bind inputs to source/model/pipeline hashes; verify behavior on cache miss and in the intended branch scope.
- [ ] Keep two-hour scoring required; never turn absence into skip/success or reuse inputs for a different model silently.
- [ ] Reconcile the documented CI-inference policy inconsistency explicitly: existing fixture/export validation runs inference while the written rule broadly forbids it. Do not remove validation to conceal the contradiction.
- [ ] Preserve existing push triggers/workflow names, R8, native integrity and emulator checks.

**Done:** applicable existing jobs pass on the intended revision, and the input-recovery path works without relying on a particular warm cache. If access prevents this, report the exact missing permission/artifact instead of asserting success.

## 11:20–15:00 in the same workstream — complete offline packaging

These packaging and CI tasks share the same window, not additive effort estimates. Integrate acoustic baseline at **13:00**; hand the first CI APK to the team, target **14:00**. Include a text-runtime probe early enough for **14:30** real ARM feasibility. All mandatory model/runtime changes and the last export-triggering push are due **15:00**.

### T2. Reuse existing model infrastructure; add embedded bootstrap

- [ ] Pin model manifest and build-time asset hashes; package current acoustic models first to unblock an internal baseline.
- [ ] Replace the competition APK's weight-absence check with required-payload, integrity, ABI and provenance checks.
- [ ] Include all necessary tokenizer/config/license files; add Andrey's selected text-model assets and runtime through a versioned contract. Confirmed by the 19:00 comparison: `model_int8.onnx` and `tokenizer.json` from `onnx-community/Qwen2.5-0.5B-Instruct` with the revision, sizes and hashes in [text-punctuation.md](../interfaces/text-punctuation.md) and `TextModelFiles`; it runs on the existing ONNX Runtime, so no new native library is needed.
- [ ] Coordinate exact file-set/schema changes across Kotlin, Python and server validators/tests.
- [ ] Stream assets to a staging directory with disk-space checks; verify hashes before atomically selecting an immutable revision.
- [ ] Preserve active inference leases and clean failed/interrupted staging safely; recover on the next launch.
- [ ] Ensure first-launch readiness does not wait for DNS, catalog access, WorkManager or downloads.
- [ ] Keep update behavior deterministic for evaluation; expose local readiness separately from optional transfer activity.

**Done:** clean install with Wi-Fi/mobile data off before first launch reaches real inference. Then add all mandatory model stages and repeat. A basic acoustic-only checkpoint is not proof of full LLM compliance.

### T3. Verify native and installation behavior

- [ ] Preserve isolation between Java ONNX Runtime and sherpa's C runtime; do not resolve collisions using arbitrary `pickFirst`.
- [ ] Verify added text-model runtime ABI/JNI/R8 behavior with CI and actual ARM inference.
- [ ] Measure APK archive size, installed size, expanded model size and peak extraction disk requirement separately.
- [ ] Test corrupted payload, insufficient space and process kill during extraction.
- [ ] Ensure models are not activated merely because a saved success flag exists.

**Done:** interrupted/corrupted installs cannot create false readiness; the delivered artifact works on declared ARM targets. Removing extracted files must not be presented as reclaiming immutable APK asset bytes.

## 15:00–17:00 — full candidate CI, shared measurements and blocker fixes

Full candidate target: **16:00**. Measure the same jobs as Andrey/Petya rather than scheduling a separate benchmark sweep. One-phone quality/performance slot is **16:00–16:30**, then Petya's negative/lifecycle slot **16:30–17:00**.

### T4. Fixed-configuration ARM measurement, no optimization search

- [ ] Record device/OS/ABI/RAM, APK/model hashes, threads, run order and thermal conditions.
- [ ] Measure decode, model load, ASR, diarization, text LLM and save time, plus total wall time.
- [ ] Report cold model preparation separately, cold/warm processing RTF and actual media duration.
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

Final APK-producing push by **17:00**. Workflow limits: **45 min** Android, **150 min inference + 45 min scoring** on a cold cache, **120 min** model export, plus queues/retries. Freeze cache-key inputs by **14:00** and model/export inputs by **15:00**. Since `6a078e3`, default-branch pushes no longer cancel the running workflow; other branches retain cancellation. Let the running cache job finish, batch pushes and avoid redundant queued runs. Do not weaken checks or triggers to recover time.

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

If matching inference inputs, secure model export, native LLM operation or final CI cannot be recovered in time, deliver the implemented changes and explicit blocker record by 19:30. Do not skip checks, use reference-model outputs under deployed hashes or present a successful build job as a ready release.

## Final personal checklist

- [ ] Required checks are green and inputs recoverable on a cold cache.
- [ ] Complete model set is embedded and verified offline.
- [ ] Native runtimes actually infer on ARM after optimization.
- [ ] Cold/warm time, memory and storage are measured.
- [ ] Final APK is tied to passing checks and independent device evidence.
- [ ] Submission APK remains durably accessible.
