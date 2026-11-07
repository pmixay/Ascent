# Andrey — speech and model-quality lead

## Mission

Maximize measured Russian transcription, speaker and timestamp quality while keeping the phone pipeline stable and fast. Own model decisions and experiment validity. Parent plan: [HACKATHON_PLAN.md](../HACKATHON_PLAN.md), packages H07/H08/H09/H10.

**Today: 7 November 2026, 11:00–20:00 MSK (UTC+3). Personal handoff/sign-off: 19:30; 19:30–20:00 is shared submission reserve.** This replaces all former working-day estimates. Only the remaining work below is assigned; pushed studies are not to be repeated. Application builds/tests/lint use existing GitHub CI; no local Android build/toolchain. Approved public-corpus host inference remains distinct from actual judge processing on Android.

## Status — 7 November 2026, 18:50 MSK

Pushed and inspected. Host and CI evidence only; no Android device result exists.

- **CI:** [run 42379171322](https://github.com/pmixay/Ascent/actions/runs/42379171322) at
  `d934495` passed the whole workflow, including the text-stage JVM tests and the cached
  two-hour scorer. Run 42387665207 at `7a90580` (adds `AsrGain`) was in progress at this
  update; check it before claiming the gain change is built and tested.
- **Report:** [model-report.md](../hackathon/model-report.md): models, licences and hashes,
  CI two-hour Dialogs scores, host Russian/telephone/noise/English results, AMI English
  conversation, the GigaAM Multilingual Large comparison, the fine-tune search, the
  software-ARM-emulation worst case and limitations. Its device table is empty.
- **Recognizer level (`AsrGain`):** per-window peak normalization, chosen on AMI ES2011a and
  measured once on EN2002a (headset WER 60.36% -> 54.65%, distant microphone 83.91% ->
  74.96%); Russian Dialogs within noise. In `TranscriptionEngine`; unmeasured on a device.
- **Local text model:** Qwen2.5 0.5B int8 ONNX word-preserving punctuation stage, kept after
  the 19:00 host comparison with Qwen2.5 1.5B, Qwen3 0.6B and Qwen3.5 0.8B, see
  [text-punctuation.md](../interfaces/text-punctuation.md). Not packaged, not integrated into
  the export, not run on ARM.
- **Acoustic model decision open:** GigaAM Multilingual Large is better on Russian (validation
  3.20% vs 4.30%) and English but about three times slower and not packaged.
- **Device scoring tool:** `tools/score_competition_json.py` is ready for exported JSON and
  hand-made references.
- **Earlier work kept:** P1 baseline, rejected constant start shift, segmenter, bounded
  `PcmWindowSource` seam.

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
| **12:00–13:00** | Implement the engine seam; review the pushed cache job's accepted recognizer/speaker provenance with Timofey, not another bootstrap. Existing ASR-only host checkpoints cannot replace its complete inference inputs. Submit seam for the 13:00 push; freeze cache-key inputs by 14:00. |
| **13:00–14:30** | Review Android grammar/lexical preservation with Pasha; verify actual CI-built ARM text inference as the APK arrives. Measure cost with Timofey; host feasibility alone is not acceptance. |
| **14:30–15:00** | Freeze the viable text model/runtime/config from ARM evidence; preserve acoustic models/thresholds. Escalate failed LLM/CI as qualification blockers, not reasons to relabel ASR. |
| **15:00–16:00** | Review candidate regressions, raw/presentation text projection and source-relative timing; prepare missing Android scoring using existing tools. No algorithm sweep. |
| **16:00–17:00** | Score the frozen candidate on Petya's predeclared app subset; Timofey measures the same jobs. With one phone, quality slot is 16:00–16:30, then Petya's negatives. Report failures immediately; no holdout tuning. |
| **17:00–18:00** | Assemble model/license/quality report, clearly separating pushed host numbers from new device numbers; mark missing metrics explicitly. |
| **18:00–19:00** | Support Petya's final exact-APK acceptance; check final metrics/config/provenance. Deliver report by 19:00. |
| **19:00–19:30** | Sign off claims and limitations; rehearse ASR/embeddings/clustering/LLM explanation. |
| **19:30–20:00** | Shared submission/link verification reserve; no new experiments. |

### Remaining acceptance checklist

- [x] Bounded source seam preserves original-media offsets, words and existing segmenter
  behavior (`9127e20`, CI run 42366154116). The import path that uses it is Pasha's.
- [ ] Text model runs with the bundled model on ARM inside the export, with lexical checks and
  incremental time recorded. Blocked: no packaged model or integration yet.
- [x] Existing stricter regressions pass on compatible inputs: cached two-hour scorer and
  regressions green in run 42379171322.
- [ ] Android RU call/meeting, short-reply/speaker and English measurements with build,
  model and device identifiers. Blocked: no APK with embedded models. Host AMI English is
  reported separately and does not close this item.
- [ ] Checked natural-recording boundaries. Open: no hand-annotated recordings.
- [x] Final report and model/licence review in the repository by 19:00; unmeasured
  conditions are explicitly open. Defense notes:
  [ml-defense-notes.md](../hackathon/ml-defense-notes.md).

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
