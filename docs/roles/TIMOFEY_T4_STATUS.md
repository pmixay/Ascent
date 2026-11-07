# Timofey T4 — processing-stage measurement contract

Branch: `timofey/plan-next-incomplete`, following verified `d9776cd`.
This is measurement instrumentation, not an optimization, an ARM benchmark result
or release approval. The acoustic weights/thresholds, threads, source decoding,
text decisions, exported words, encryption and retention are unchanged.

## Already verified before this slice

[Android CI 42400311459](https://github.com/pmixay/Ascent/actions/runs/42400311459)
passed both jobs on `d9776cd00f22f9320c80fee53cad0720c3442658`:
compact `128486449802`, embedded `128486449883`. The embedded job restored the
**exact** output cache, skipped only cold export/dependency/receipt production,
and revalidated the stored receipt, every pinned payload and checked APK. It
executed required offline instrumentation, not a successful empty test report.

- Exact cache key:
  `Linux-X64-offline-export-py312-v2-4b44332905b81bd2f87187d297f288c9768a3a81d47384e7798cf56b349b1326`.
- Verified input fingerprint:
  `32b2230794368c4877654127c8a037eac00c255c6b7229313e6e359febfa27e9`.
- Receipt still identifies the real cold producer `62fbe3f`, run `42396663795`,
  attempt 1, instead of falsely claiming a fresh export on the consumer commit.
- Bundle SHA-256:
  `ef0378fb122baaec876bf9e5a02bd2dc91a9252eb007b3a29407abfd7a5be878`.

| Checked ARM64 APK | Bytes | Raw APK SHA-256 |
| --- | ---: | --- |
| Compact | 25,231,345 | `dee38b0415e6d01438b5a25e9c023b55c71f6d21e922b10ca5b44d4da37e05aa` |
| Embedded | 1,496,578,223 | `04f585927af000a15893a282591bc349dfe4a4146a23053c6ca53f4bc46f6b1a` |

These figures identify the **previous checked candidate**, not the new T4 source
changes. [Compact artifact](https://github.com/pmixay/Ascent/actions/runs/42400311459/artifacts/13585178661)
and [offline artifact](https://github.com/pmixay/Ascent/actions/runs/42400311459/artifacts/13585323857)
are temporary handoffs, not durable releases. Despite the workflow's requested
14-day retention, the inspected API expiry is **14 November 2026, 18:14:51 UTC**;
do not promise that they remain accessible longer. No final signing/device gate
is closed by these internal artifacts.

## T4 source implementation

`ProcessingTimings` uses a monotonic nanosecond clock and exclusive nested scopes.
One collector follows a single serialized phone file/microphone job. Child work
pauses the parent bucket: decoding during ASR and diarization is counted **once**.
Repeated windows accumulate counts/durations in fixed-size arrays, not a list of
audio windows. There is no timer/sampler thread, file write, upload or log output.

The last finished report lives only in `ConversationState.measurements` and is
shown below job status in Russian, at readable text size. A new phone job/capture
clears the old report. Nothing is added to encrypted history or competition JSON;
reopening the process cannot recover an old report. The collector contains only
enum stages, counters, durations, outcome and optional media duration. It never
retains audio, text, URI, filename, source/conversation ID or a throwable.

| Exclusive bucket | Actual boundary / limitation |
| --- | --- |
| Queue | Waiting for the shared phone/watch inference mutex. Dispatch time after acquiring it is overhead. |
| Model validation | Paired lease acquisition/local readiness checks and acoustic manifest loading. Full embedded extraction is separate T3 preparation. |
| Source open | Source construction, metadata/picker provider query and codec/WAV setup. For a stopped prototype microphone session this also includes waiting for recorder finalization. Any eager decoding during construction is here, not window decode. |
| Input decode | Every `PcmWindowSource.read`, including quiet-boundary search, repeated windows, resampling, codec rewind and original-source speaker pass. Includes in-memory ring hits; this is source-read time, not pure codec CPU time. |
| ASR model load | ORT environment/telemetry initialization and acoustic session creation. Session-options setup and teardown stay in ASR. |
| ASR | Complete recognition pass, excluding nested load/reads; includes gain, tensor work, native execution, CTC/stitching and resource teardown. |
| Speaker model load | sherpa extractor construction/frontend setup. |
| Diarization | Speaker-context/evidence/embedding/clustering/attribution/segment work and teardown, excluding nested load/reads. No speech means this bucket is absent, not fabricated zero work. |
| Text model load | Tokenizer loading, instruction construction, ORT text session creation **and prefix-cache inference**. Prefix setup is real model work; do not describe this as pure file I/O. |
| Text | Whole text stage except nested initialization; includes projection/validation/fallback and teardown. A missing/failed LLM retains its truthful existing `TextStageReport` status; RTF alone never establishes LLM compliance. |
| Saving | Dispatch, history mutex wait, encrypted history read/save, allowed name matching and pruning. It is not only write syscall time. |
| Cleanup | Existing permitted cleanup and pending-operation-marker completion, including dispatch. |
| Overhead | Other job/UI dispatch, metadata, lease/source teardown and measurement-accounting work outside explicit scopes. Not hidden or assigned to a model. |

Every measured scope closes in `finally` on failure/cancellation. The original
error/result/source arrays/offsets and source ownership are preserved. The adapter
does not retain returned PCM or create additional copies. The optional collector
leaves existing watch and uninstrumented callers unchanged.

### Total and RTF

Total starts after the job is accepted, before the initial busy-state publication,
and ends after automatic cleanup, immediately before the final status/report is
published. It includes queueing, all model initialization/text work, saving and
cleanup. It excludes human picker/export time, prior microphone capture and
first-launch embedded extraction/validation already measured in T3. It also
excludes rendering/reading the final timing summary. Source closing is included.

`RTF = total_ns / (media_duration_ms × 1,000,000)`. RTF is available only for a
completed job with known positive media duration. Cancelled/failed/unknown/empty
duration jobs retain partial elapsed counters but **no completed-processing RTF**.
A saved raw fallback may complete the job while the LLM status is failed or
unavailable; record that separately. Genuine silence can have a positive duration
and a completed RTF but does not demonstrate diarization or text-model execution.

Stages add to the nanosecond total without double counting; each displayed stage
is rounded independently to three decimal seconds. Zero rounded time is not a
missing scope. Do not add the older inclusive `TextStageReport.elapsedMs` to this
breakdown: its time already contributes here. Nanoseconds are clock units, not a
claim of nanosecond measurement accuracy. Instrumentation adds some overhead.

## Verification and handoff

New JVM regressions use fake clocks and synthetic sources for nesting, repeated
calls, errors/interruption, source transparency, stable finish and completed-only
RTF. The existing required offline test instruments its **already authorized**
single synthetic text phrase and requires positive load/text durations and exact
counter reconciliation. Its RTF stays null: it processed no audio. No additional
corpus inference, model search or acoustic runtime benchmark is added to CI.

New code has static review only until its own push CI finishes. This does not
exercise a full phone import/inference/save job or establish physical ARM behavior.
Petya/user must install the exact new CI APK, retain the corresponding raw APK,
bundle, model and source hashes, then record the on-screen total, media duration,
RTF and stages for the fixed three-file cold/warm sequence. Record run order,
device/OS/ABI/RAM/free storage, runtime/thread configuration and thermal conditions
with the existing [judge procedure](../QA_JUDGE_SCRIPT.md). Cold/warm is a declared
trial condition, not guessed from a timer; sessions are recreated per job even
when filesystem/runtime caches are warm.

Peak PSS/RSS, device inventory, thermal observations, repeated-job memory behavior,
maximum-duration capacity, actual ARM stage timings and release signing remain
**open**. No new evidence is invented to fill those fields. Model decisions stay
with Andrey and application/lifecycle acceptance with Pasha; this change adds only
the owned measurement hooks and a small status presentation seam for Petya review.
