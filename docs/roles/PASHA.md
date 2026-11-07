# Pasha — speech and model-quality lead

## Mission

Maximize measured Russian transcription, speaker and timestamp quality while keeping the phone pipeline stable and fast. Own model decisions and experiment validity. Parent plan: [HACKATHON_PLAN.md](../HACKATHON_PLAN.md), packages H07/H08/H09/H10.

All tasks are pending. Relative schedule: 5–7 focused working days, revised after baseline measurements. Application builds/tests/lint use GitHub CI. Only approved public-corpus development inference may run locally under `AGENTS.md`; actual judge inputs are processed on Android.

## Status — 7 November 2026

Evidence so far; checklist boxes below stay unchecked until the acceptance they
describe is met.

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
  over 20 s at their longest pause. In CI run 42353343464 for commit `cb1f4b6`, the
  job "Build and validate Android app" passed (Python and Kotlin unit tests, lint,
  APK build, emulator runtime check). The run's overall conclusion is failure,
  because "Score cached two-hour Russian speech evaluation" stops at its missing
  inference cache, as it does on `main`.

## Ownership

Main source directory: `prototypes/phone-transcriber/android/app/src/main/java/com/pmixay/ascent/`.

- Own `TranscriptionEngine.kt`, `AsrChunking.kt`, `CtcDecoder.kt`, `CtcChunkStitcher.kt`, `Speaker*.kt` and focused algorithm regressions.
- Own speech evaluation methodology/algorithms and model-quality acceptance. Coordinate shared Python tooling with Timofey.
- Andrey owns source decoding/job orchestration and the text-model adapter. Petya owns UI and fresh holdout custody. Timofey owns model packaging/native dependencies and device performance harness.
- Timofey reviews provenance/metrics; Andrey reviews pipeline integration.

## First two hours — preserve an honest baseline

- [ ] Read audit, `docs/speaker-diarization.md` and `tools/fixtures/dialogs/README.md` under the prototype.
- [ ] Record current source/model hashes, thresholds, thread counts and algorithm configuration.
- [ ] Agree bounded source access and canonical timed-word/turn output with Andrey.
- [ ] Identify existing six-reader/two-hour gates that every candidate must preserve.
- [ ] Tell Petya which annotations and conditions are required for the fresh holdout; do not inspect its predictions while tuning.

**Checkpoint:** a baseline manifest and experiment protocol exist before comparing candidates. Historical 95.31% completed clip-time attribution is labeled as diagnostic corpus evidence, not natural-conversation DER.

## Day 1 — baseline and prioritized error inventory

### P1. Benchmark the current pipeline — part of 12–20 h

- [ ] Freeze development selection and normalization: case/punctuation/ё handling, number representation and silence scoring.
- [ ] Include one speaker, similar voices, late-arriving speakers, sub-1.5-second replies, fast call-like exchanges, moderate-pace meetings, telephone-channel audio, noise and an English smoke subset. Jury says both call-like and meeting-like cases are a direct criterion.
- [ ] Measure WER/CER, cpWER, speaker-count error, strict/completed attribution and inferred coverage.
- [ ] Report per-speaker/per-condition results and denominators, not only aggregate percentages.
- [ ] Preserve seam repetitions such as repeated “да”; inspect deletions and duplicate words near chunk boundaries.
- [ ] Deliver the three highest-impact failure categories and one proposed hypothesis per category.

**Done:** baseline outputs are reproducible and tied to source/model/config versions. Missing input/cache must be fixed with Timofey rather than bypassing regression gates.

## Days 1–2 — local text-model feasibility

### P2. Select a usable local LLM — 4–8 h initial spike

- [ ] Coordinate with Andrey on the organizer's interpretation; provision a distinct text model until clarified otherwise.
- [ ] Shortlist a small number of license-compatible Russian-capable quantized models with viable ARM runtime support.
- [ ] Check exact model/tokenizer files, redistribution terms, runtime compatibility and expected storage; confirm by an actual CI-built ARM trial.
- [ ] Test bounded punctuation/case edits, not free-form rewriting or textual speaker guessing.
- [ ] Specify the structured edit format, lexical-preservation normalization, context/output limits and invalid-output fallback.
- [ ] Give Timofey a pinned asset/runtime manifest and Andrey the integration/edit contract.

**Done:** a selected model executes locally within the measured device budget and preserves content under validation. Model size/name alone does not establish feasibility. Record the incremental total processing cost.

## Days 2–4 — targeted acoustic improvements

### P3. Improve diarization without hiding errors

- [ ] Investigate short evidence, similar-voice merging, mixed context boundaries and the linkage/spectral switch at 160 anchors.
- [ ] Compare the same speaker appearing alone and with others; distinguish fragmentation from absorption of a genuine new voice.
- [ ] Change one mechanism at a time and compare on identical development inputs.
- [ ] Consider neural VAD only with measured evidence; preserve source offsets and quiet speech.
- [ ] Preserve unknown/inferred provenance and enrollment exclusions. More completed display labels are not automatically more accurate.
- [ ] Keep all existing stricter regressions; document a rejected candidate if it fixes one case but breaks another.

**Done:** paired results show a useful improvement with no breached predeclared gate; Andrey can integrate the unchanged result contract.

### P4. Measure and improve timestamp boundaries — 6–10 h

- [ ] Establish reference turn matching and count unmatched turns separately.
- [ ] Measure start/end absolute errors, median/p95 and tolerance hit rates on at least 100 manually checked boundaries where data permits.
- [ ] Audit frame-to-sample mapping, input padding, clipped final tails and chunk seam ownership.
- [ ] Compare VAD-supported refinement or constrained local alignment when the baseline warrants it.
- [ ] Preserve original-media coordinates, leading silence, lexical order and true speaker changes.

**Done:** timing changes improve declared boundary metrics without removing words, hiding unmatched turns or degrading ASR/speaker gates. Merely converting seconds to milliseconds is not timing improvement.

## Days 4–5 — speed/quality tradeoffs and frozen candidate

- [ ] Review Timofey's ARM stage-time and memory measurements to identify the actual bottleneck.
- [ ] Compare quantization/thread/runtime candidates only when they solve a measured problem.
- [ ] Keep original model manifests/baselines; a new model has a new provenance record and fresh inference inputs.
- [ ] Evaluate Russian slices and English smoke separately; never let an English gain hide a Russian regression.
- [ ] Freeze the selected pipeline, models and thresholds before Petya runs final held-out evaluation.

**Done:** one release candidate is selected from development results, with quality/resource tradeoffs documented. All required regressions pass.

## Days 5–7 — independent validation and defense

- [ ] Give Petya only the frozen candidate and scoring protocol, with no tuning based on hidden outcomes.
- [ ] Review the final report: WER/CER, cpWER, attribution, timestamps, short replies, English and overlap limitations.
- [ ] If held-out failures inform a fix, label that set diagnostic and arrange fresh validation before claiming generalization.
- [ ] Explain ASR versus embeddings versus clustering versus LLM; prepare examples of known limits and measured improvements.
- [ ] Review the architecture/model notices and final quality claims.

## Targets and experiment rules

Use parent-plan targets as proposed internal goals, frozen after the first baseline: >75% overall fresh Russian attribution; aim >=90% clean two-speaker and >=80% short-turn attribution; WER <=15% clean / <=25% difficult; median boundary error <=200 ms and p95 <=500 ms. These are not organizer thresholds or current results.

Use one-to-one speaker mapping per recording; unknown/missing assignments are errors. Report DER only when suitable time-aligned speech/overlap annotations and a declared collar/overlap policy exist. The original Dialogs reserved recordings are already diagnostic. Keep prepared reference transcripts exclusively in evaluation, never as app inference outputs.

## Handoffs and AI work pattern

| Deliverable | Recipient | When |
| --- | --- | --- |
| Engine input/output seam | Andrey | First two hours |
| Baseline manifest and scoring/annotation rules | Timofey, Petya | Day 1 |
| Pinned text model + edit contract | Timofey, Andrey | After feasibility spike |
| Accepted acoustic candidate and regressions | Andrey, Timofey | Days 3–5 |
| Frozen config + evaluation protocol | Petya | Before final holdout |

Use Claude to implement one measured hypothesis; use GPT to challenge the metric, denominator, leakage and failure interpretation. Avoid concurrent algorithm variants in shared files. Record rejected experiments so another session does not repeat them blindly.

## Final personal checklist

- [ ] Models, configuration and datasets are versioned with provenance.
- [ ] Existing regression gates remain passing.
- [ ] Short turns, similar voices, meetings and telephone speech have explicit results.
- [ ] Timestamps and English have measured coverage.
- [ ] Local text model selection is feasible and semantically constrained.
- [ ] Final quality claims distinguish development, diagnostics and held-out evidence.
