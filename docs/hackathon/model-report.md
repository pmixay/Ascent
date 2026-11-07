# Models, licences and measured quality

Speech lead's measured studies, 7 November 2026, with integration status reconciled
on 8 November against source `25ae8eee`. Numeric studies are retained, not rerun
or upgraded into device results. Current Android CI executes bounded synthetic
x86_64 cases, but **no real ARM spoken-audio measurement exists**. Software ARM
emulation below is not Android device acceptance. See [current tests](../TEST_RESULTS.md)
and [submission identity](../SUBMISSION.md).

## Pipeline

The implemented pipeline runs locally on Android, closing acoustic sessions before
Qwen opens. All three embedded groups and language modes are integrated; full ARM
runtime/quality acceptance remains open.

1. **Recognition.** Russian/CIS uses GigaAM CTC; English/multilingual uses Parakeet
   TDT v3 with token-to-word timing. Remembered `Авто` starts GigaAM and may restart
   the whole recording with Parakeet based on an early script probe. GigaAM uses
   20 s windows with 1.25 s of context on each side and a
   quiet-seam search (`AsrChunking`), per-window level normalization of the recognizer
   input (`AsrGain`: peak to -1 dBFS, at most +30 dB), greedy CTC decoding into timed words
   (`CtcDecoder`), stitched across windows (`CtcChunkStitcher`). Parakeet uses the
   bounded chunk/stitching seam without GigaAM's level normalization.
2. **Speakers.** ERes2NetV2 embeddings over short windows around recognized speech, stable
   seeds, agglomerative clustering and completion so that every word carries a label;
   assignments without enough evidence are marked inferred internally (`Speaker*.kt`).
3. **Phrases.** `TranscriptSegmenter` groups words by speaker and splits runs over 20 s at
   the longest pause. Times are approximate: CTC-derived for GigaAM (40 ms frames),
   token-derived for Parakeet, not human-verified phonetic alignment.
4. **Text model.** Word-preserving punctuation and capitals (`TextPunctuator`), see
   [text-punctuation.md](../interfaces/text-punctuation.md). It cannot change words,
   speakers or times.

## Models and licences

| Stage | Model | Source and revision | Size | Licence |
| --- | --- | --- | --- | --- |
| ASR | GigaAM Multilingual CTC, about 220M parameters | Deployed pack exported from `salute-developers/GigaAM` `7447938d791c4f3e643386ee22c33777004293a5`; measurements below used the public conversion `istupakov/gigaam-multilingual-ctc-onnx` `458860e1983aef670dd9795fb6af603c82767d5d` | 885 MB float32, 225 MB int8 (host study) | MIT |
| Speaker embeddings | 3D-Speaker ERes2NetV2 (`3dspeaker_speech_eres2netv2_sv_zh-cn_16k-common.onnx`) | `csukuangfj/speaker-embedding-models`, SHA-256 `bf1a75b9930474cf3389ef415e6e5d38ca96fea4a3a00f7e301d080a58ee2239` | 71,441,526 bytes | Apache-2.0 |
| Multilingual ASR (embedded/integrated; ARM open) | NVIDIA Parakeet TDT 0.6B v3, int8 | `csukuangfj/sherpa-onnx-nemo-parakeet-tdt-0.6b-v3-int8` `ed5d745623472f97cfc2e6c2916ee248798fc5a8`; hashes in [multilingual-recognizer.md](../interfaces/multilingual-recognizer.md) | 670,576,784 bytes | CC-BY-4.0 |
| Text model | Qwen2.5 0.5B Instruct, int8 ONNX | `onnx-community/Qwen2.5-0.5B-Instruct` `22942cb7d7ba4cc81bb4673549ca4d4614469b5e`, SHA-256 `41834041ab1b29eff9fc592f1a29a1844133aea35832ea9fa91682be13016100` | 512,096,557 bytes plus 7,031,673-byte tokenizer | Apache-2.0 |

Runtimes: ONNX Runtime Android 1.31.0 (ASR and text model) and sherpa-onnx 2.0.0 with its
own isolated ONNX Runtime 1.28.2 (speaker embeddings). No weights were trained or fine-tuned,
and no benchmark actor was enrolled into voice memory. The text model was compared with
Qwen2.5 1.5B, Qwen3 0.6B and Qwen3.5 0.8B on the same stage and clips and kept
([text-punctuation.md](../interfaces/text-punctuation.md)). The acoustic model stays GigaAM
Multilingual 220M for this release; the Large comparison is below.

The current APK packages acoustic, text and Parakeet groups. Source `25ae8eee`
passed both jobs in [Android CI 42420616552](https://github.com/pmixay/Ascent/actions/runs/42420616552):
synthetic text execution, minified Parakeet creation and silent ViewModel
import/export/cancel/restart checks on x86_64. Actual logs confirm 25/four required
cases. [Exact identity](../SUBMISSION.md). These are integration checks, not speech
quality measurements; the ARM table below remains open.

## Historical CI evidence: two hours of Russian dialogue

These are retained historical measurements. The user removed long inference and
scoring from push CI; `CI=true` also excludes the opt-in two-hour JVM scorer.

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

## English conversation and recognizer level (host)

Same container and runtime, 2 threads unless stated. Utterances of the AMI meeting corpus
(`edinburghcstr/ami` `46f28f25`, CC-BY-4.0), frozen in `tools/fixtures/ami_english.json`
before inference and run with `tools/ami_english_study.py`: development meeting ES2011a
(IHM headsets, 364 utterances, 954 s) chose the level rule; test meeting EN2002a (1,367
utterances, 2,634 s, 8,015 reference words, 4 speakers) was then measured once per
configuration on the IHM headset channel (call-like) and the single distant microphone
(SDM, meeting-like). Each utterance is recognized on its own, so this is recognition only.

| EN2002a WER | IHM | SDM | RTF |
| --- | --- | --- | --- |
| GigaAM Multilingual 220M (current), float32 | 60.36% (4,838) | 83.91% (6,725) | 0.122 |
| same, with `AsrGain` | 54.65% (4,380) | 74.96% (6,008) | 0.121 |
| GigaAM Multilingual Large 600M, public int8 | 56.66% (4,541) | 71.28% (5,713) | 0.27 * |
| same, with `AsrGain` | 52.80% (4,232) | 65.39% (5,241) | 0.30 * |

\* two runs shared the 4 cores; not a clean speed measurement. AMI RTF is higher than the
Dialogs figure (0.069) because every short utterance is padded and run as its own window.

Conversational English is much harder than the read LibriSpeech smoke (6.73%). AMI audio is
quiet (median utterance RMS about -43 dBFS); without the level rule 277 of 1,367 IHM
hypotheses were empty (mostly short backchannels) and 524 contained Cyrillic letters: the
multilingual character decoder drifts into Russian spelling on accented English. On the
development meeting the peak rule lowered WER from 42.67% to 38.67% and an RMS rule (-23 dBFS)
to 39.06%; the peak rule was frozen before the test meeting was run.

Russian check of the frozen rule, applied per recognizer window exactly as `AsrGain` does
(`tools/asr_study.py infer --asr-gain peak`), and of the large model:

| Russian Dialogs WER | Development (5,572 words) | Validation (10,053 words) |
| --- | --- | --- |
| Current, float32 | 2.9074% (162) | 4.2972% (432) |
| Current with `AsrGain` | 2.8715% (160) | 4.3271% (435) |
| Large, public int8 | 2.3510% (131) | 3.2030% (322) |

The level rule is neutral on Russian (Dialogs clips already peak near -10 dBFS) and helps
quiet English input, so the app applies it. The large model is better on both languages but
about three times slower (RTF 0.114 at 4 threads on validation, against 0.07-0.09 for the
current model at 2 threads); it is not packaged, its public int8 export is not the deployed
pack, and switching is a team model decision. The historical CI Dialogs scores measured
recognition without `AsrGain`; that CI job is now removed. The host table above is the
evidence for the rule's effect, and the current bounded CI does not rescore the corpus.

**Script guard, measured and not adopted.** Re-decoding a window without Cyrillic letters when
Latin letters dominate its greedy path (frozen on ES2011a: 38.67% -> 37.86%) moved EN2002a
only from 54.65% to 54.05% (IHM, 4,332 errors) and from 74.96% to 74.85% (SDM, 5,999), and
left Russian development unchanged (2.8715%). The gain was too small to justify a code change
an hour before submission.

**Fine-tuned GigaAM models.** A search of Hugging Face (7 November 2026) for models derived
from `ai-sage/GigaAM-Multilingual` or `ai-sage/GigaAM-v3` found no usable English
fine-tune. `nur-dev/asr-220m-kk-ru-en` (Kazakh/Russian/English) is gated with manual approval
under a non-commercial licence and publishes no English figures; the others are Kazakh,
Uzbek or Bashkir fine-tunes, Russian-only GigaAM-v3 derivatives, or format conversions
without retraining. GigaAM Multilingual Large is the only stronger model of the same family.

## Second recognizer: English and multilingual candidates (host)

**Team decision, 7 November:** Russian/CIS keeps GigaAM Multilingual (its card
covers Russian, Kazakh, Kyrgyz and Uzbek); multilingual uses Parakeet TDT 0.6B v3.
Fixed modes and remembered Auto are integrated and embedded in `25ae8eee`; actual
ARM speech execution remains unverified. Files, hashes and the engine contract are in
[multilingual-recognizer.md](../interfaces/multilingual-recognizer.md). The candidates
below all run on sherpa-onnx 2.0.0, which the app already ships for speaker embeddings, so no
new native library is needed. Same container, 2 threads (runs shared the 4 cores, so RTF is
indicative), int8 exports from `csukuangfj/sherpa-onnx-*`, measured once with
`tools/sherpa_asr_compare.py` on the frozen AMI EN2002a utterances (8,015 words) and on the
Russian Dialogs clips; nothing was tuned.

| Model (int8, size) | Languages | Word times in sherpa-onnx | EN2002a IHM | EN2002a SDM | RTF (IHM) |
| --- | --- | --- | --- | --- | --- |
| NVIDIA Parakeet TDT 0.6B v2 (661 MB, CC-BY-4.0) | English | yes | **18.02%** | **44.72%** | 0.15 |
| NVIDIA Parakeet TDT 0.6B v3 (671 MB, CC-BY-4.0) | 25 European incl. en, ru, uk | yes | 18.57% | 44.74% | 0.15 |
| Moonshine base (287 MB, MIT) | English | no | 27.21% | 59.41% | 0.06 |
| Whisper distil-small.en (298 MB, MIT) | English | no | 28.06% | 56.93% | 0.34 |
| Whisper small.en (375 MB, MIT) | English | no | 29.39% | 56.78% | 0.84 |
| Whisper base.en (160 MB, MIT) | English | no | 30.75% | 61.02% | 0.24 |
| Whisper tiny.en (103 MB, MIT) | English | no | 34.55% | 66.25% | 0.09 |
| SenseVoice small (237 MB) | zh, en, ja, ko, yue | yes | 52.75% | 72.44% | 0.05 |
| GigaAM Multilingual 220M with `AsrGain` (current) | ru, en, kk, ky, uz | yes | 54.65% | 74.96% | 0.12 |
| GigaAM Multilingual Large 600M with `AsrGain` | ru, en, kk, ky, uz | yes | 52.80% | 65.39% | 0.30 |

| Russian Dialogs WER | Development (5,572 words) | Validation (10,053 words) |
| --- | --- | --- |
| GigaAM Multilingual 220M (current, windowed) | 2.91% (162) | 4.30% (432) |
| Parakeet TDT 0.6B v3 (each clip on its own) | 4.74% (264) | 6.73% (677) |

The app's multilingual path (`MultilingualRecognizer.kt`: Parakeet v3 over the assembled
Dialogs blocks with the `AsrChunking` windows, token-to-word mapping and midpoint ownership),
mirrored by `tools/sherpa_asr_compare.py windowed` and scored with `tools/asr_study.py score`:

| Parakeet v3, windowed like the app | Development (5,572 words) | Validation (10,053 words) |
| --- | --- | --- |
| WER (block level) | 5.76% (321) | 6.98% (702) |
| Word start / end vs energy proxy, median signed | -65.5 / +181.5 ms | -69.0 / +49.0 ms |
| Starts within 250 ms | 94.9% | 97.1% |
| RTF, 2 threads | 0.139 | 0.141 |

Windowing costs about 1 point on development (87 of 321 errors are deletions) and 0.3 on
validation against clips recognized one by one; the Russian default mode stays GigaAM, so this
mainly bounds Russian quality in the multilingual mode. Word times are as close to the energy
proxy as GigaAM's (start median +87 ms, end -14.5 ms).

**Continuous English meetings: Petya's AMI holdout (host, one run).** The six frozen
10-minute headset-mix excerpts of `tools/qa/fixtures/ami_holdout_manifest.json` (ES2008a,
ES2009a, IS1008a, IS1009a, TS3003a, TS3004a; 60 min, 8,539 reference words, 4 speakers each),
reproduced and hash-checked with `tools/qa/prepare_ami_holdout.py`, run once with
`tools/ami_holdout_asr_study.py` after the models, `AsrGain` and the probe were frozen. The
reference policy was fixed before inference: all non-punctuation `<w>` words (fillers and
truncated words kept) ending by 600 s, all speakers merged in start order. WER is
speaker-agnostic, so overlapping talk inflates it for both models; speaker attribution runs
only in Kotlin and is not measured here. 4 threads.

| AMI holdout, 6 meetings | GigaAM 220M with `AsrGain` | Parakeet v3 (windowed) | Automatic mode |
| --- | --- | --- | --- |
| WER | 32.32% (2,760) | **28.62% (2,444)** | 28.62%: multilingual chosen for 6 of 6, after 19-60 s |
| CER | **19.33%** | 23.52% | 23.52% |
| Word start error, median / p95 (matched words) | 182 / 627 ms | **80 / 240 ms** | 80 / 240 ms |
| Word starts within 250 ms | 63.4% | **96.2%** | 96.2% |
| RTF (4 threads) | 0.059 | 0.084 | 0.084 plus the probe |

Parakeet has the lower WER on 5 of 6 meetings (IS1009a: 36.68% vs 34.73% for GigaAM). On
continuous meeting audio the English gain is about 11% of word errors, far smaller than on
the quiet single-headset utterances above, where GigaAM fails on short isolated replies; the
larger gain is in word timing. Parakeet's higher CER with lower WER is unexplained (digits are
not spelled out on this path; transcripts were not inspected). Under the custody rules this
holdout now has one inspected run: tuning anything on it would make it diagnostic.

Revisions (`csukuangfj/sherpa-onnx-*` on Hugging Face, int8 files): whisper-tiny.en `d026532c`,
whisper-base.en `59eea950`, whisper-small.en `d9533f69`, whisper-distil-small.en `0492324b`,
sense-voice-zh-en-ja-ko-yue-int8-2025-09-09 `355f4d48`, nemo-parakeet-tdt-0.6b-v2-int8
`84b478d5`, nemo-parakeet-tdt-0.6b-v3-int8 `ed5d7456`, moonshine-base-en-int8 `052b0798`.

Findings:

- No compact open model we measured is best on both languages. GigaAM makes 36-39% fewer
  Russian errors than the multilingual Parakeet v3; Parakeet v3 makes 2.9 times fewer English
  errors on isolated headset utterances and 1.7 times fewer on the distant microphone, but
  only about 11% fewer on continuous headset-mix meetings (holdout above), where its main
  advantage is word timing. Models strong on both,
  such as Whisper large-v3 (about 1.5B parameters; GigaAM Multilingual card, Whisper vs
  GigaAM: Russian CV 9.1% vs 7.1%, Russian FLEURS 3.1% vs 4.4%), are too large for the phone;
  Whisper turbo (about 1 GB int8) was not run.
- Parakeet v3 is the natural multilingual model: within 0.6 points of the English-only v2 on
  headsets and equal on the distant microphone, and it also covers Russian and Ukrainian.
- Whisper and Moonshine return no token times through sherpa-onnx 2.0.0's offline API, so
  they cannot feed speaker attribution or the JSON timestamps; Whisper also pads every input
  to 30 s, which makes it slow on short phrases. SenseVoice is weak on English meetings, and
  its licence (FunASR model licence) was not reviewed.
- Caveats: EN2002a comparison is utterance-level; the six-meeting study uses
  continuous headset mixes. English diarization is unmeasured. In the per-clip
  Russian comparison Parakeet ran clips separately while GigaAM used assembled
  windows; a separate windowed table is provided. Parakeet, its mode control,
  timed-word engine and assets are now integrated; host scores are not ARM scores.

## Worst case: software ARM emulation on an x86 host

A lower bound on speed if the jury's ARM emulator runs on an x86 host with pure
instruction translation. This is not a device or Android-emulator measurement. The same
model files ran with aarch64 CPython 3.12 and ONNX Runtime 1.31.0 (aarch64 wheel) under
`qemu-aarch64-static` user-mode emulation of a Neoverse-N1 core, 2 threads, on the same
4-core x86 container, against the same files natively. Inputs are synthetic features of the
production window sizes (ASR: 22.5 s window with a 20 s core; speaker: 1.5 s window), except
the text stage, which ran on development clips. RTF excludes model loading.

| Model | Native x86 | Emulated ARM | Slowdown |
| --- | --- | --- | --- |
| ASR (GigaAM CTC), one window | 1.62 s, RTF 0.081 | 440.8 s, RTF 22.0 | ≈ 270× |
| Speaker (ERes2NetV2), one window | 0.089 s, RTF ≈ 0.085 | ≈ 25 s, RTF ≈ 24 | ≈ 280× |
| Text stage (Qwen2.5 0.5B int8) | RTF 0.091, load 15 s | RTF 6.3, load 56 s | ≈ 70× (rough) |
| Sum of the stages above | RTF ≈ 0.26 | RTF ≈ 52 | |

The emulated text row covers only 2 clips (3.8 s of audio), against 10 natively, so it is
approximate. An emulator on an ARM host (Apple Silicon, ARM servers) runs this code at close
to native speed, so this table bounds the x86-host case only; other translators may be
faster and were not measured. Under QEMU's default `max` CPU model, which advertises SVE,
no ONNX Runtime run finished in 40 minutes; disabling SME alone did not help, disabling
SVE and SME did. Behaviour of an emulator that advertises SVE without hardware support is
therefore a risk, unobserved on real hardware.

## Limitations

- **No device evidence.** Speed, memory and behaviour on ARM devices and on the jury's ARM
  emulator are unmeasured. Software emulation on an x86 host is about 270× slower than the
  host (see the worst case above); an ARM-host emulator should be close to native.
- **Not the deployed pack.** Historical Russian corpus CI/host ASR measurements
  use a public reference export, not byte-identical to the deployed acoustic graph.
  Current bounded Android checks validate packaged assets, not new corpus WER/RTF.
- **Acted, constructed dialogue.** Two speakers per session, clean studio audio and
  synthetic gaps. Real calls, meetings with three or more speakers, overlap, far-field
  audio and background talk are not represented. Natural-conversation DER is not reported.
- **Short replies and similar voices** are the known weak point of attribution; tuning is
  deferred after the deadline.
- **`AsrGain` is host-evidence only.** The CI two-hour Dialogs scores above were produced
  without it, and its effect on the device is unmeasured.
- **Timestamps** are CTC-derived and approximate; no human-checked boundaries exist yet.
- **Text model** changes presentation only. Competition JSON exports raw recognized
  words. English punctuation is unmeasured; the instruction was studied on Russian.
- **English** is difficult: GigaAM with gain scored 54.65%/74.96% WER on isolated
  AMI headsets/distant microphone; bundled Parakeet **v3** scored 18.57%/44.74%
  there and 28.62% on six continuous meetings. The v2 comparison 18.02%/44.72%
  is not the shipped v3 identity. These are host scores; Android English quality
  and diarization remain unmeasured.

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
