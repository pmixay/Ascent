# Timofey branch integration review

Target branch: `timofey/t2-embedded-bootstrap`. Fetched main:
`456fc1e41301e82e180a7a02130e7ef8b8c2e7eb`. This review does not merge into
`main`, publish a release or approve competition acceptance.

## Main synchronization and history

The remote main history was rewritten to re-attribute commits. Rather than
discarding either side, an ordinary merge preserves both histories. Five files
needed conflict resolution:

- `docs/interfaces/pcm-window-source.md`: use main's corrected commit IDs and
  scoped inspected-CI statement.
- `docs/interfaces/text-punctuation.md`: retain main's Qwen3.5 comparison and our
  paired embedded-lease contract. Do not adopt the rejected Qwen3.5 candidate.
- `docs/roles/ANDREY.md`: retain the new model-report link, T1 resolved-cache
  evidence, T2 packaging status and unchecked cross-role assignments.
- `docs/roles/PASHA.md`: retain main's corrected source-seam reference and the
  T2 application/text integration checklist.
- `tools/text_model_onnx_study.py`: retain main's entire newer host-study tool,
  including tokenizer/state and no-thinking-opening support. It remains byte-for-
  byte equal to the fetched main version; no new experiment was run.

All other production speech/text algorithm files from main remain unchanged.
T1 source/model/pipeline cache keys and acoustic graph hashes stay frozen. T2
adds a separate envelope rather than altering the acoustic/server schema.

## Review finding and scoped correction

The offline-only instrumentation class was in the shared `androidTest` directory,
although it asserts `BuildConfig.EMBEDDED_MODELS`. It could be selected by the
compact job, which intentionally builds without embedded models. Existing jobs
reported success, but the workflow did not require XML evidence that the intended
instrumentation methods executed. A successful Gradle invocation alone cannot
resolve that source-set/evidence discrepancy.

Correction in this integration:

- Move `EmbeddedModelRuntimeTest` to `src/offlineAndroidTest/java`, registered only
  with `-PascentEmbedded=true`. No expected test is silently disabled: compact
  retains all its pre-existing runtime tests; offline still explicitly runs the
  asset/lease test.
- Add `scripts/verify_android_tests.py` after both emulator invocations, requiring
  named actual tests to pass without skipping. Missing/empty results, skipped
  required cases and any recorded failure/error fail the job. Compact requires
  native loading/configuration, Activity/WorkManager, encrypted storage, selection/
  lease and service-lifetime cases. Offline requires actual bootstrap/corruption/
  paired-lease recovery. No model inference or report artifact is added.
- Add CI-only synthetic XML regression checks for the verifier. Local inspection
  parses source/AST/configuration and checks diffs/links only.

Earlier passing emulator **jobs** remain historical job results; exact execution
of the intended methods requires the strengthened checks on this merged revision.
No observed runtime failure is invented from this static finding.

## Can it merge?

**Pending exact merged-revision CI.** Static review and conflict resolution alone
do not authorize promotion. Require all four Android jobs (compact, embedded,
inference-cache and scoring), including the new non-skipped instrumentation
checks, to complete successfully for the same merged commit. Do not push over a
running cold inference merely to update this report. Inspect a completed run
before changing this verdict to an implementation-baseline recommendation.

If those checks pass, the branch can be integrated as an **internal T1/T2
implementation baseline**, not a competition-ready release. These deliberately
open handoffs are not hidden by green packaging CI:

| Owner | Remaining release requirement |
| --- | --- |
| Pasha | Real URI import/job/export path and meaningful sequential text-stage integration using `ModelLease.textDirectory`; raw text and lexical/ID/time invariants |
| Andrey | Provisional text-model/runtime approval from actual ARM evidence; acoustic/provenance and quality review |
| Petya | Real Android/ARM cold offline inference, negative/lifecycle/storage tests, timing/memory measurements, exact-build demo |
| Petya | Authenticated server host-key/TLS/catalog repair and normal model publication; no TLS/SSH bypass |

The offline artifact uses a separate application ID and no Internet permission;
the compact build keeps its download behavior. Bundled but unused text weights,
x86_64 extraction and public-reference dialogue scores do not satisfy ARM/local-
LLM release acceptance. Stable signing identity/durable final submission links
remain release work, not established by this integration.

## Branch cleanup

The obsolete branches `codex/ascent-android-prototype` and
`timofey/h01-ci-baseline` were confirmed ancestors of the retained branch before
deletion. The first was removed remotely and locally; the diagnostic branch
existed only locally and was removed locally. No unmerged commits were discarded.
Other participants' branches and local/remote `main` were not modified.
