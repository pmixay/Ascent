# Andrey — speech and model-quality lead

## Mission

Maximize measured Russian transcription, speaker and timestamp quality while keeping the phone pipeline stable and fast. Own model decisions and experiment validity. Parent plan: [HACKATHON_PLAN.md](../HACKATHON_PLAN.md), packages H07/H08/H09/H10.

**Today: 7 November 2026, 11:00–20:00 MSK (UTC+3). Personal handoff/sign-off: 19:30; 19:30–20:00 is shared submission reserve.** This replaces all former working-day estimates. Only the remaining work below is assigned; pushed studies are not to be repeated. Application builds/tests/lint use existing GitHub CI; no local Android build/toolchain. Approved public-corpus host inference remains distinct from actual judge processing on Android.

## Status — 7 November 2026

Already pushed in PR #2 (`e1a27f7`) and present in fetched `origin/main`
`4b16e3a`; excluded from today's implementation workload. Android acceptance
remains separate from these host/code outputs.

- **P1 baseline:** [ASR, timestamp and speed baseline](../../prototypes/phone-transcriber/docs/asr-baseline.md)
  with frozen development and fresh validation recordings, telephone-channel and
  English smoke slices, error inventory and three ranked hypotheses. It used a
  public reference export because the deployed pack was unreachable, ran on an
  x86 container, and did not re-measure diarization, cpWER or speaker counts: the
  CI scorer's inference cache is missing (needs Timofey).
- **P2 local text model:** host feasibility study and edit contract in
  [text-model feasibility](../../prototypes/phone-transcriber/docs/text-model-feasibility.md).
  No model is integrated, packaged or measured on ARM.
- **P3 diarization:** no candidate measured; the short-reply hypothesis is blocked
  on the same cache.
- **P4 timestamps:** frame-to-time mapping audited (no offset bug found); a
  constant start shift failed validation and was rejected. Manually checked
  boundaries in continuous recordings are still needed.
- **Code:** `TranscriptSegmenter` replaces inline phrase grouping and splits runs
  over 20 s at their longest pause. Current inspected [CI run 42358746042](https://github.com/pmixay/Ascent/actions/runs/42358746042)
  at `4b16e3a` has a successful build/validation job, but the overall run failed
  because the two-hour scorer lacks matching inference inputs. Do not rewrite the
  segmenter or claim whole-workflow success.

## Ownership

Main source directory: `prototypes/phone-transcriber/android/app/src/main/java/com/pmixay/ascent/`.

- Own `TranscriptionEngine.kt`, `AsrChunking.kt`, `CtcDecoder.kt`, `CtcChunkStitcher.kt`, `Speaker*.kt` and focused algorithm regressions.
- Own speech evaluation methodology/algorithms and model-quality acceptance. Coordinate shared Python tooling with Timofey.
- Pasha owns source decoding/job orchestration and the text-model adapter. Petya owns UI and fresh holdout custody. Timofey owns model packaging/native dependencies and device performance harness.
- Timofey reviews provenance/metrics; Pasha reviews pipeline integration.

## Today's sequence — remaining work only

| MSK | Output / handoff |
| --- | --- |
| **11:00–11:20** | Agree only the missing bounded engine/source seam with Pasha; preserve existing timed words, segmenter and attribution provenance. |
| **11:20–12:00** | Hand Pasha/Timofey existing Qwen2.5 1.5B Q4_K_M hashes/licenses/grammar/validator and the measured 0.5B fallback; hand Petya the pushed scoring protocol and manifests. No new shortlist, corpus freeze or host benchmark. |
| **12:00–13:00** | Implement the agreed engine source seam; help Timofey locate matching deployed-model inference inputs. Existing reference-export ASR checkpoints cannot replace missing speaker/deployed-model cache. Submit seam for the 13:00 baseline push. |
| **13:00–14:30** | Review Android grammar/lexical preservation with Pasha; verify actual CI-built ARM text inference as the APK arrives. Measure cost with Timofey; host feasibility alone is not acceptance. |
| **14:30–15:00** | Freeze the viable text model/runtime/config from ARM evidence; preserve acoustic models/thresholds. Escalate failed LLM/CI as qualification blockers, not reasons to relabel ASR. |
| **15:00–16:00** | Review candidate regressions, raw/presentation text projection and source-relative timing; prepare missing Android scoring using existing tools. No algorithm sweep. |
| **16:00–17:00** | Score the frozen candidate on Petya's predeclared app subset; Timofey measures the same jobs. With one phone, quality slot is 16:00–16:30, then Petya's negatives. Report failures immediately; no holdout tuning. |
| **17:00–18:00** | Assemble model/license/quality report, clearly separating pushed host numbers from new device numbers; mark missing metrics explicitly. |
| **18:00–19:00** | Support Petya's final exact-APK acceptance; check final metrics/config/provenance. Deliver report by 19:00. |
| **19:00–19:30** | Sign off claims and limitations; rehearse ASR/embeddings/clustering/LLM explanation. |
| **19:30–20:00** | Shared submission/link verification reserve; no new experiments. |

### Remaining acceptance checklist

- [ ] Bounded source seam preserves original-media offsets, words and existing segmenter behavior.
- [ ] Pushed grammar and independent validator actually run with the bundled text model on ARM; lexical content, numbers, negation, IDs and times stay unchanged. Record successful operation, fallbacks and incremental time.
- [ ] Existing stricter regressions remain passing on compatible inputs; missing cache stays a blocker for Timofey, not a waived check.
- [ ] Missing Android RU call/meeting, short-reply/speaker and English smoke measurements have denominators and build/model/device identifiers. Do not repeat the already published host baseline/error inventory.
- [ ] Checked natural-recording boundaries report start/end errors, matched/unmatched counts and annotation uncertainty; do not call the existing energy proxy human ground truth.
- [ ] Final report and model/license review reach Petya by 19:00; personal sign-off by 19:30. Unmeasured conditions are explicitly open.

## Deferred beyond 20:00 — not assigned today

New acoustic-model comparisons, diarization/short-turn tuning, neural VAD, quantization/thread sweeps, overlap handling, timestamp-refinement research and the expanded 100-boundary/50-short-turn study remain backlog. The constant start shift is already rejected; do not repeat it. No new quality gain is claimed from the pushed segmenter or host study.

## Targets and experiment rules

Use parent-plan targets as proposed internal goals, frozen after the first baseline: >75% overall fresh Russian attribution; aim >=90% clean two-speaker and >=80% short-turn attribution; WER <=15% clean / <=25% difficult; median boundary error <=200 ms and p95 <=500 ms. These are not organizer thresholds or current results.

Use one-to-one speaker mapping per recording; unknown/missing assignments are errors. Report DER only when suitable time-aligned speech/overlap annotations and a declared collar/overlap policy exist. The original Dialogs reserved recordings are already diagnostic. Keep prepared reference transcripts exclusively in evaluation, never as app inference outputs.

## Handoffs and AI work pattern

| Deliverable | Recipient | When |
| --- | --- | --- |
| Missing engine input/output seam | Pasha | Contract 11:20; code for 13:00 push |
| Existing study manifests/scoring and text model + grammar | Timofey, Petya, Pasha as applicable | 12:00; reuse pushed files |
| Real ARM LLM feasibility / frozen config | Pasha, Timofey, Petya | 14:30 / 15:00 |
| Missing Android quality report and limits | Petya | 19:00; sign-off 19:30 |

Use Claude for the bounded seam/runtime review and GPT for metric, denominator, leakage and contract review. No new acoustic experiments today. Reuse the pushed rejected-experiment record so another session does not repeat it.

## Final personal checklist

- [ ] Models, configuration and datasets are versioned with provenance.
- [ ] Existing regression gates remain passing.
- [ ] Short turns, similar voices, meetings and telephone speech have explicit results.
- [ ] Timestamps and English have measured coverage.
- [ ] Local text model selection is feasible and semantically constrained.
- [ ] Final quality claims distinguish development, diagnostics and held-out evidence.
