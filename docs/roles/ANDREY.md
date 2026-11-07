# Andrey — speech and model-quality lead

## Mission

Maximize measured Russian transcription, speaker and timestamp quality while keeping the phone pipeline stable and fast. Own model decisions and experiment validity. Parent plan: [HACKATHON_PLAN.md](../HACKATHON_PLAN.md), packages H07/H08/H09/H10.

**Today: 7 November 2026, 11:00–20:00 MSK (UTC+3). Personal handoff/sign-off: 19:30; 19:30–20:00 is shared submission reserve.** This replaces all former working-day estimates. Only the remaining work below is assigned; pushed studies are not to be repeated. Application builds/tests/lint use existing GitHub CI; no local Android build/toolchain. Approved public-corpus host inference remains distinct from actual judge processing on Android.

## Status — 7 November 2026, main integration

Main through `6b02188` is integrated with the application branch. Host studies,
historical CI and Android emulator checks are separate evidence; no physical
ARM Android result exists. The latest merged candidate and artifact identities
are recorded in [PR #4](https://github.com/pmixay/Ascent/pull/4).

- **CI:** pre-gain integration [run 42387321849](https://github.com/pmixay/Ascent/actions/runs/42387321849)
  at `32e3bc8` passed compact and embedded jobs, all 25 required compact runtime
  cases, and offline extraction/recovery with one actual synthetic text-model call.
  The merged `AsrGain` revision needs its own inspected CI result. The two-hour
  scores in the report are historical; the user removed long inference/scoring
  jobs from push CI, and `CI=true` explicitly excludes the opt-in JVM scorer.
- **Report:** [model-report.md](../hackathon/model-report.md) preserves model
  licences/hashes, historical two-hour Dialogs scores, host Russian/telephone/
  noise/English results, AMI English conversation, the larger GigaAM comparison,
  fine-tune search and software-ARM-emulation worst case. Its device table is empty.
- **Recognizer level (`AsrGain`):** per-window peak normalization, chosen on AMI
  ES2011a and measured once on EN2002a (headset WER 60.36% -> 54.65%, distant
  microphone 83.91% -> 74.96%); Russian Dialogs within noise. The merged engine
  applies it only to recognizer input, preserving original source reads for speaker
  embeddings and timing. Its quality and performance remain unmeasured on a device.
- **Local text model:** provisional Qwen2.5 0.5B int8 ONNX word-preserving
  punctuation, see [text-punctuation.md](../interfaces/text-punctuation.md).
  The embedded APK packages both model groups and the pipeline uses one paired
  lease; competition JSON preserves recognized words. Synthetic x86_64 runtime
  evidence exists as linked above. Qwen3.5 0.8B was measured and not chosen;
  final approval still needs the team's comparison and exact-build ARM evidence.
- **Acoustic model decision open:** GigaAM Multilingual Large is better on Russian
  (validation 3.20% vs 4.30%) and English but about three times slower and not packaged.
- **Device scoring tool:** `tools/score_competition_json.py` is ready for exported
  JSON and hand-made references.
- **Earlier work kept:** [P1 baseline](../../prototypes/phone-transcriber/docs/asr-baseline.md),
  [text feasibility](../../prototypes/phone-transcriber/docs/text-model-feasibility.md),
  rejected constant start shift, segmenter and bounded `PcmWindowSource` seam.
  Public-reference scores do not measure the deployed pack. Short-reply
  diarization and manually checked natural-recording boundaries remain open.

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
| **12:00–13:00** | Implement the engine seam; review the retained development-evaluation provenance with Timofey, not another bootstrap. Existing ASR-only host checkpoints cannot replace complete development inference inputs. Submit seam for the 13:00 push. |
| **13:00–14:30** | Review Android grammar/lexical preservation with Pasha; verify actual CI-built ARM text inference as the APK arrives. Measure cost with Timofey; host feasibility alone is not acceptance. |
| **14:30–15:00** | Freeze the viable text model/runtime/config from ARM evidence; preserve acoustic models/thresholds. Escalate failed LLM/CI as qualification blockers, not reasons to relabel ASR. |
| **15:00–16:00** | Review candidate regressions, raw/presentation text projection and source-relative timing; prepare missing Android scoring using existing tools. No algorithm sweep. |
| **16:00–17:00** | Score the frozen candidate on Petya's predeclared app subset; Timofey measures the same jobs. With one phone, quality slot is 16:00–16:30, then Petya's negatives. Report failures immediately; no holdout tuning. |
| **17:00–18:00** | Assemble model/license/quality report, clearly separating pushed host numbers from new device numbers; mark missing metrics explicitly. |
| **18:00–19:00** | Support Petya's final exact-APK acceptance; check final metrics/config/provenance. Deliver report by 19:00. |
| **19:00–19:30** | Sign off claims and limitations; rehearse ASR/embeddings/clustering/LLM explanation. |
| **19:30–20:00** | Shared submission/link verification reserve; no new experiments. |

### Remaining acceptance checklist

#### T2 model handoff from Timofey

- [ ] Review the pinned acoustic export provenance/identical graph hashes in
  [T1 evidence](TIMOFEY_T1_STATUS.md); packaging must not change acoustic weights,
  thresholds or scorer pins.
- [ ] Approve or reject the provisional ONNX Qwen 0.5B choice from
  [text-punctuation.md](../interfaces/text-punctuation.md), using Petya's exact
  APK real-ARM execution/time/memory evidence. Hand Timofey any changed immutable
  asset/runtime contract; host scores and bundled bytes alone are not acceptance.
- [ ] Review the integrated text stage's lexical/ID/time preservation, raw versus
  presentation export and real success/fallback counters on ARM. The synthetic
  x86_64 invocation verifies integration without replacing that device review.

#### Existing acceptance floor

- [x] Bounded source seam implemented with historical evidence (`9127e20`, CI run
  42366154116). Its import consumers and the gain change also need the merged
  revision's checks recorded in PR #4.
- [ ] Bundled text model runs on ARM inside the complete import/export pipeline,
  with lexical checks, preserved IDs/times, fallbacks and incremental time recorded.
  Packaging/integration is present; exact-build ARM evidence remains open.
- [ ] Current merged candidate passes existing bounded regressions. Historical
  cached two-hour scores remain in the report; do not restore their CI job or
  present them as a measurement of `AsrGain`.
- [ ] Android RU call/meeting, short-reply/speaker and English measurements include
  denominators and build/model/device identifiers. Host AMI English is separate.
- [ ] Checked natural-recording boundaries report start/end errors, matched and
  unmatched counts and annotation uncertainty. No hand-annotated recordings yet.
- [x] Model/licence report and [ML defense notes](../hackathon/ml-defense-notes.md)
  are published. Unmeasured conditions remain explicit; device sign-off is open.

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
