# Timofey — CI, offline packaging and integration lead

## Mission

Deliver a reproducibly checked APK with a complete embedded model set, prove offline first-run operation on ARM and own final artifact provenance. Parent plan: [HACKATHON_PLAN.md](../HACKATHON_PLAN.md), packages H01/H04/H10/H12.

All tasks are pending. Schedule assumes 5–7 focused working days, revised after initial diagnosis. Local application builds/tests/lint and local Android toolchain preparation remain prohibited. Use the existing GitHub workflows and install their APKs on phones.

## Ownership

- Root `.github/workflows/android.yml` and `model-pack.yml`.
- Android build configuration, manifest/native packaging, prototype `scripts/` and `Model*.kt`.
- Model-pack/export schema and delivery tooling, coordinated with Pasha on numerical/model changes.
- Device performance harness, merged release integration and final hashes.
- Andrey owns application lifecycle/source contracts; Pasha owns algorithms/model quality; Petya owns UI/independent QA.

## First two hours — diagnose and unblock

- [ ] Read audit and inspect current CI again; runs cited in the audit are a snapshot, not a substitute for the latest status.
- [ ] Inspect full logs for model export run `42257595048`; record the actual error before proposing a fix.
- [ ] Diagnose missing two-hour inputs in Android run `42257595068`; inspect cache scope/key/availability.
- [ ] Locate verified model artifacts and establish who has permissions for CI, model publication and signing.
- [ ] Agree embedded-install readiness/lease contract with Andrey and expected status UI with Petya.
- [ ] Identify available ARM phones with RAM/OS/ABI/storage information.

**Checkpoint:** documented root cause or precise unresolved evidence gap for each red job; owners/access identified; baseline model availability established.

## Days 1–2 — make checks reproducible

### T1. Repair current CI — 6–12 h

- [ ] Fix the diagnosed export/validation failure while preserving numerical, integrity and speaker checks.
- [ ] Provide a durable integrity-checked source/bootstrap process for completed evaluation inputs. GitHub cache is an accelerator, not the sole source of truth.
- [ ] Bind inputs to source/model/pipeline hashes; verify behavior on cache miss and in the intended branch scope.
- [ ] Keep two-hour scoring required; never turn absence into skip/success or reuse inputs for a different model silently.
- [ ] Reconcile the documented CI-inference policy inconsistency explicitly: existing fixture/export validation runs inference while the written rule broadly forbids it. Do not remove validation to conceal the contradiction.
- [ ] Preserve existing push triggers/workflow names, R8, native integrity and emulator checks.

**Done:** applicable existing jobs pass on the intended revision, and the input-recovery path works without relying on a particular warm cache. If access prevents this, report the exact missing permission/artifact instead of asserting success.

## Days 1–3 — embed the complete usable pipeline

### T2. Offline model bootstrap — 8–14 h

- [ ] Pin model manifest and build-time asset hashes; package current acoustic models first to unblock an internal baseline.
- [ ] Replace the competition APK's weight-absence check with required-payload, integrity, ABI and provenance checks.
- [ ] Include all necessary tokenizer/config/license files; add Pasha's selected text-model assets and runtime through a versioned contract.
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

## Days 3–5 — performance and integration

### T4. Measure before optimizing — 8–14 h

- [ ] Record device/OS/ABI/RAM, APK/model hashes, threads, run order and thermal conditions.
- [ ] Measure decode, model load, ASR, diarization, text LLM and save time, plus total wall time.
- [ ] Report cold model preparation separately, cold/warm processing RTF and actual media duration.
- [ ] Record peak PSS/RSS and repeated-job memory trend for short/long/maximum supported inputs.
- [ ] Compare thread counts/loading policies and, with Pasha, quantized candidates only against identical inputs and frozen quality gates.
- [ ] Test ten consecutive jobs and three evaluator-style files; investigate resource growth and cancellation cleanup with Andrey.

**Done:** a reproducible per-device table exists. Target warm total RTF <=1 is an internal goal; report slower results honestly. Do not infer device speed from desktop cached inference or model file size.

### T5. Integrate in a controlled order

- [ ] Merge agreed source/result/export contracts first, then basic import and offline bootstrap/UI.
- [ ] Integrate lifecycle/text-model stages before final quality/performance tuning.
- [ ] Merge only Pasha's accepted quality candidate with its tests/provenance.
- [ ] Inspect all required checks for the merged revision; artifact presence does not prove workflow-wide success.
- [ ] Coordinate shared manifest/Gradle/DTO edits through a single editor at a time.

## Days 6–7 — final release

- [ ] Freeze source, model and configuration revisions with Andrey/Pasha.
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

- From Pasha: exact model files/hashes/licenses, acceptable runtime/quantization and frozen quality gates.
- From Andrey: lease/readiness contract and job-resource lifecycle requirements.
- To Petya: installable candidate APK, clean-install instructions and exact versions for every test round.
- To all: failing check/root-cause evidence, integration status and final release identity.

## Final personal checklist

- [ ] Required checks are green and inputs recoverable on a cold cache.
- [ ] Complete model set is embedded and verified offline.
- [ ] Native runtimes actually infer on ARM after optimization.
- [ ] Cold/warm time, memory and storage are measured.
- [ ] Final APK is tied to passing checks and independent device evidence.
- [ ] Submission APK remains durably accessible.
