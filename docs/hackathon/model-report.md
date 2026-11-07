# Models, licences and measured quality

Speech lead's report for the hackathon verification build, 7 November 2026. It separates
four kinds of evidence: an inspected GitHub CI run, host measurements on a development
container, decisions without measurements, and Android device results. **No Android
device or ARM measurement exists yet**; the last section is the table to fill.

## Pipeline

All stages run on the phone, one after another, so only one model family is loaded at a time.

1. **Recognition.** GigaAM CTC over 20 s windows with 1.25 s of context on each side and a
   quiet-seam search (`AsrChunking`), greedy CTC decoding into timed words (`CtcDecoder`),
   stitched across windows (`CtcChunkStitcher`).
2. **Speakers.** ERes2NetV2 embeddings over short windows around recognized speech, stable
   seeds, agglomerative clustering and completion so that every word carries a label;
   assignments without enough evidence are marked inferred internally (`Speaker*.kt`).
3. **Phrases.** `TranscriptSegmenter` groups words by speaker and splits runs over 20 s at
   the longest pause. Times stay CTC-derived and approximate (40 ms frames).
4. **Text model.** Word-preserving punctuation and capitals (`TextPunctuator`), see
   [text-punctuation.md](../interfaces/text-punctuation.md). It cannot change words,
   speakers or times.

## Models and licences

| Stage | Model | Source and revision | Size | Licence |
| --- | --- | --- | --- | --- |
| ASR | GigaAM Multilingual CTC, about 220M parameters | Deployed pack exported from `salute-developers/GigaAM` `7447938d791c4f3e643386ee22c33777004293a5`; measurements below used the public conversion `istupakov/gigaam-multilingual-ctc-onnx` `458860e1983aef670dd9795fb6af603c82767d5d` | 885 MB float32, 225 MB int8 (host study) | MIT |
| Speaker embeddings | 3D-Speaker ERes2NetV2 (`3dspeaker_speech_eres2netv2_sv_zh-cn_16k-common.onnx`) | `csukuangfj/speaker-embedding-models`, SHA-256 `bf1a75b9930474cf3389ef415e6e5d38ca96fea4a3a00f7e301d080a58ee2239` | 71,441,526 bytes | Apache-2.0 |
| Text model (provisional) | Qwen2.5 0.5B Instruct, int8 ONNX | `onnx-community/Qwen2.5-0.5B-Instruct` `22942cb7d7ba4cc81bb4673549ca4d4614469b5e`, SHA-256 `41834041ab1b29eff9fc592f1a29a1844133aea35832ea9fa91682be13016100` | 512,096,557 bytes plus 7,031,673-byte tokenizer | Apache-2.0 |

Runtimes: ONNX Runtime Android 1.31.0 (ASR and text model) and sherpa-onnx 2.0.0 with its
own isolated ONNX Runtime 1.28.2 (speaker embeddings). No weights were trained or fine-tuned,
and no benchmark actor was enrolled into voice memory. The text model is provisional until
the team's independent model comparison.

## CI evidence: two hours of Russian dialogue

[CI run 42363045969](https://github.com/pmixay/Ascent/actions/runs/42363045969), commit
`6a078e3`, job *Score cached two-hour Russian speech evaluation*. The same job also passed from the
saved cache in run 42369021893 (commit `544ac34`, re-attributed as `a7edeee` with identical
content); its numbers were not compared. Inference used the **public reference** recognizer export,
not the deployed pack, together with the byte-identical speaker model. The Kotlin scorer
runs the application's own attribution and segmentation code on cached recognizer outputs
and embeddings.

Corpus: public Dialogs recordings, 1,134 acted clips by three actors (M, D, S) placed in
their original order with synthetic gaps, 7,232.6 s, 15,740 reference words, 27 sessions.
These are constructed sequences, not continuous recordings.

| Set | Words | WER | CER | cpWER | Speaker time, strict | Speaker time, completed |
| --- | --- | --- | --- | --- | --- | --- |
| Development (`masha_dima_part1`) | 5,572 | 2.84% (158) | 0.79% | 5.10% (284) | 89.00% | 97.51% |
| Original validation recordings (now diagnostic) | 10,168 | 2.76% (281) | 0.77% | 13.12% (1,334) | 90.49% | 93.86% |
| **Total** | **15,740** | **2.79% (439)** | **0.78% (640 / 81,890)** | **10.28% (1,618)** | **89.90%** | **95.31%** |

- *Strict* counts only confirmed speaker evidence as correct; *completed* also scores the
  best-effort (inferred) assignments. Unknown and missing attribution stay in the
  denominator. Inferred assignments covered 6.2% of clip time.
- Per actor (completed / strict): D 96.27% / 86.82%, M 94.32% / 89.47%, S 96.12% / 93.20%.
- Speaker counts: 2 extra and 2 missing labels over 27 sessions. The worst session,
  `masha_sveta_part2-006` (33.5 s), was merged into one speaker: cpWER 46.6%, speaker time
  77.6% completed.
- About three quarters of the joint error (cpWER minus WER) comes from speaker
  attribution, not from recognized words.
- The "validation" recordings of this split were inspected earlier and are diagnostic,
  not held-out evidence.

## Host evidence

All from a 4-core x86 development container, ONNX Runtime 1.31.0, 2 threads, with the
same public reference export. RTF excludes file decoding and model loading. Source:
[asr-baseline.md](../../prototypes/phone-transcriber/docs/asr-baseline.md) and
[text-punctuation.md](../interfaces/text-punctuation.md).

| Measurement | Result |
| --- | --- |
| Fresh Russian validation (`masha_dima_part3`, `masha_sveta_part3`; 959 clips, 4,494.6 s) | WER 4.30% (432 / 10,053), CER 1.43% |
| Simulated telephone channel (8 kHz, G.711-like), development / validation | WER 2.98% / 4.63% |
| 20 dB white noise, development | WER 3.18% |
| English smoke (LibriSpeech test-clean, 80 utterances, 732 s), clean / telephone | WER 6.73% / 7.36% |
| ASR RTF, float32 / int8 | 0.069 / 0.068 (development), 0.070 / 0.064 (validation) |
| Word timing against an energy proxy, development (start / end) | median +87 ms / −14.5 ms; within 250 ms at least 95.6% |
| Text stage, 120 development clips: comma / sentence-end / question F1, capitals | 0.322 / 0.456 / 0.182, 88.98% (lowercase baseline 84.87%) |
| Text stage added RTF | 0.081 |

The energy proxy is a labelled stand-in, not human annotation. English output is lowercase
with numbers spelled out; read audiobook speech is easier than conversation.

## Limitations

- **No device evidence.** Speed, memory and behaviour on ARM and on the jury's ARM emulator
  are unmeasured. The host CPU is likely several times faster than the emulator.
- **Not the deployed pack.** CI and host numbers come from the public reference export,
  which is not byte-identical to the deployed pack.
- **Acted, constructed dialogue.** Two speakers per session, clean studio audio and
  synthetic gaps. Real calls, meetings with three or more speakers, overlap, far-field
  audio and background talk are not represented. Natural-conversation DER is not reported.
- **Short replies and similar voices** are the known weak point of attribution; tuning is
  deferred after the deadline.
- **Timestamps** are CTC-derived and approximate; no human-checked boundaries exist yet.
- **Text model** improves presentation only. If the jury does not normalize text before
  WER, punctuation could add word errors; the integration contract recommends exporting the
  raw ASR text.
- **English** is a smoke test of read speech only.

## Device measurements (to fill)

Score each exported JSON against a reference written by a listener with
`tools/score_competition_json.py MANIFEST`; it prints only aggregates. Record the APK
commit, model hashes, device or emulator, Android version and threads with every row.
Rows stay "not measured" until a run is inspected.

| Condition | Recordings / duration | WER | cpWER | Speaker time | Boundary median / p95 (start, end) | RTF | Build, device |
| --- | --- | --- | --- | --- | --- | --- | --- |
| Russian call-like | not measured | | | | | | |
| Russian meeting-like | not measured | | | | | | |
| English | not measured | | | | | | |
| Silence / empty input | not measured | | | | | | |
