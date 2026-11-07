# Reproducible ASR, timestamp and speed baseline

This report covers recognition, word timing and speed on public Russian speech.
It is a **local development-machine study** of a public reference export of the
selected model family. It is **not** a measurement of the deployed model pack, of
a phone, or of natural conversation, and it completes no production acceptance
task. Diarization was not re-measured here.

## Why a reference export

The deployed model pack could not be obtained when this study was run on
7 November 2026:

- `ascent.arbuz.lol` had no DNS record (public resolvers answered NXDOMAIN),
  so the pinned download in `evaluate_dialogs.py` could not connect.
- Model Export on `main` failed in
  [run 42257595048](https://github.com/pmixay/Ascent/actions/runs/42257595048)
  because the upstream checkpoint host `cdn.chatwm.opensmodel.sberdevices.ru`
  presented an expired TLS certificate. Nothing was published from that run.
- The scoring job in
  [run 42257595068](https://github.com/pmixay/Ascent/actions/runs/42257595068)
  stopped at "Require a completed local-improvement inference cache": no
  inference cache existed for `main`. Local inference cannot create a GitHub
  Actions cache, so that job will keep failing until its inputs are restored.

The contract forbids running the exporter locally, so the baseline uses the
public ONNX conversion
[`istupakov/gigaam-multilingual-ctc-onnx`](https://huggingface.co/istupakov/gigaam-multilingual-ctc-onnx)
at immutable revision `458860e1983aef670dd9795fb6af603c82767d5d` (MIT), whose
declared base is [`ai-sage/GigaAM-Multilingual`](https://huggingface.co/ai-sage/GigaAM-Multilingual).
The 885,388,622-byte float32 graph has SHA-256
`8bc803289f9cb5147ee95451fd9bdba219b1ecf1ddcd59a3651177c103c9eeec`; the
224,762,204-byte int8 graph has
`e08e27ae5669b39f0c378fae101bbbb9a80505f74f9b66719c309bf5b894a480`. Both match the
repository's LFS records and are pinned in `tools/reference_asr.py`.

### Fidelity checks and their limits

- The graph takes mel features, while the deployed graph takes a waveform. The
  NumPy extractor in `tools/reference_asr.py` mirrors the upstream one. Its Hann
  window and mel filterbank must be rounded to bfloat16, as in the upstream
  checkpoint. With that rounding, a local check on the development machine found
  them equal to the arrays published with `onnx-asr` (10,304 filter values and 320
  window values) and log-mel features on four real clips identical to its
  preprocessor. That check is not committed; the CI test only checks shapes and
  bfloat16 rounding, and it is skipped there because CI has no NumPy.
- On the first 12 development clips the output text matched the references
  exactly.
- On the development subset, the reference export made 162 word errors against
  160 for the deployed pack in the earlier CI baseline (5,572 reference words).
  That is evidence of a close proxy, not of identity: the two graphs, extractors
  and runtimes differ, and the CI figure came from earlier CI inference outputs
  of the deployed pack.

Re-run the same protocol with the deployed pack once it is reachable; the
`evaluate_dialogs.py` path is unchanged.

## Frozen inputs

`tools/fixtures/dialogs/study_selection.json` pins the Dialogs revision
`a74ff44d8d27ca3d21098ea3ed4d31a32923fbdd`, the metadata hash and every payload's
byte count and SHA-256 (all 1,295 payloads verified after download). It was
committed before any inference on the validation recordings.

| Role | Recordings | Clips | Metadata seconds | Actors |
| --- | --- | --- | --- | --- |
| Development | `masha_dima_part1` | 336 | 2,866.193 | M, D |
| Validation | `masha_dima_part3`, `masha_sveta_part3` | 959 | 4,400.767 | M, D, S |

The existing `AGENTS.md` names three other recordings for validation. Those were
inspected during the earlier speaker work (see the
[dialogue evaluation notes](../tools/fixtures/dialogs/README.md)), so this study
uses recordings that no earlier selection referenced, chosen by a mechanical rule
recorded in the selection file: for each speaker pair, the lowest-numbered
recording not used before. Only counts, durations and emotion labels in the
metadata were read when freezing it. Unavailable payloads (73 and 130) are listed
in the file, as before. The split separates recordings, not identities: M and D
appear in both roles, S only in validation. No model is trained and no actor is
enrolled in voice memory.

Clips are assembled in source order into blocks of at most about five minutes
with 100 ms of inserted silence, transcribed with the application's
silence-aware chunking, CTC timing and ownership code, and scored after
normalization (lowercase, `ё` to `е`, punctuation removed). These are
constructed sequences of acted studio dialogue.

## Reproduce

From `prototypes/phone-transcriber`, in Python 3.12 with
`tests/requirements-dialog-evaluation.txt` installed:

```sh
python3 tools/asr_study.py prepare --cache .cache/asr-study --roles development validation
python3 tools/asr_study.py infer --cache .cache/asr-study --role development \
  --output .cache/asr-study/runs/dev --threads 2
python3 tools/asr_study.py score .cache/asr-study/runs/dev
python3 tools/asr_study.py analyze .cache/asr-study/runs/dev
python3 tools/asr_study.py compare .cache/asr-study/runs/dev .cache/asr-study/runs/other
```

`infer` accepts `--variant int8`, `--condition gain035|noise20db|channel|telephone`,
and `--max-blocks`. `timing-candidate CHECKPOINTS --start-shift-ms N` applies the
phrase-start rule below; `freeze-english` and `english` run the English smoke set;
`tools/text_model_study.py` runs the text-model study. Each checkpoint records the model hash, thread count, code
fingerprint, audio hash and the CPU/runtime that produced it. `.cache/` is
ignored by Git; checkpoints contain public transcripts and are never artifacts.
CI does not run this tool; it only unit-tests its scoring and selection logic.

## Development baseline

Measured on 7 November 2026 in a development container: Intel Xeon (AVX-512 with
VNNI and AMX), 4 logical cores, Python 3.12.3, NumPy 2.5.4, ONNX Runtime 1.31.0,
two intra-op threads, float32.

| Measure | Result |
| --- | --- |
| Word error rate | 162 / 5,572 = 2.9074% |
| Character error rate | 255 / 29,095 = 0.8764% |
| Excluding the 168 words in laughing-labelled clips (by hand from the emotion table) | 129 / 5,404 = 2.39% |
| Speaker D / M | 58 / 2,753 = 2.11% / 104 / 2,819 = 3.69% |
| Real-time factor (RTF) | 0.069 (blocks 0.062 to 0.074) |

The laughing-labelled clips hold 33 of the 162 errors in 3% of the words (19.6%
word error rate); the other seven emotion labels range from 0% (25 words) to 2.86%.

### Reproducibility on one machine

For the reference export on this machine, greedy token IDs were identical, frame
for frame, across a repeat run and runs with 1, 2 and 4 threads (three blocks, 48
windows, 25,025 frames, 1,758 words; word times identical). This does not show
what caused the earlier 19-word difference between a local and a cached CI run of
the deployed pack: that was a different graph, and another CPU instruction path
or runtime build is untested here. Checkpoints record the runtime and CPU, and a
resumed run now reuses a checkpoint only when they match.

### Error profile

Of the 162 errors (counted by alignment), 49 are close forms (at most two letter
edits, mostly endings), 43 other substitutions, 29 insertions, 27 deletions and
14 word-boundary variants such as "не просто" versus "непросто". Errors are
4.17% on first words and 3.88% on last words of a clip, against 2.06% for interior
words. Interior words within 1.5 s of a chunk seam had 18 errors in 752 words
(2.39%), against 83 in 4,149 (2.00%) elsewhere; clip-edge words had 6 in 104
(5.77%) near seams and 21 in 567 (3.70%) elsewhere. These samples are too small
to show or rule out a seam effect (z is about 0.7 for interior words). Seams fall
in the quiet gaps between concatenated clips, so this corpus cannot represent
seams inside continuous speech, and a deleted first word takes the time of the
next recognized word. An earlier summary that showed seam words at 5.6% mixed
seams with clip edges and is not a finding. Substitution pairs repeat rarely
(one pair twice).

The visible errors are mostly inflection and spelling variants, laughter in the
reference text, and edge words in cut clips. Seam behaviour needs continuous
recordings before any chunking change is judged.

### Robustness (development, same model)

| Condition | Word errors | Rate | Character errors |
| --- | --- | --- | --- |
| Clean | 162 | 2.9074% | 255 |
| Gain ×0.35 | 162 | 2.9074% | 256 |
| 20 dB white noise | 177 | 3.1766% | 307 |
| Mild synthetic channel | 156 | 2.7997% | 238 |

These controlled changes are the ones used by the six-reader regressions. They
are not measurements of reverberation, far-field phones or real background talk.

### Timestamps

A recognized turn is the first word start to the last word end among the words
owned by one reference clip. Two references are used:

- *Clip bounds*: the clip's position in the block. Clips contain unknown silence
  at both ends, so this reference is conservative (median start 380 ms late and
  median end 390 ms early on development; intersection over union 0.894).
- *Energy proxy*: first and last 10 ms frame within 30 dB of the clip's 95th
  percentile level. It is a labelled stand-in for speech onset and offset, not a
  forced alignment or human annotation; no word-level reference exists for this
  corpus.

| Against the 30 dB proxy | Median signed | Mean absolute | Within 100 ms |
| --- | --- | --- | --- |
| Start, development | +87.0 ms | 119.2 ms | 57.1% |
| End, development | −14.5 ms | 72.5 ms | 78.0% |
| Start, validation | +36.0 ms | 98.1 ms | 61.1% |
| End, validation | −11.0 ms | 75.4 ms | 72.1% |

Intersection over union with the proxy interval is 0.971 (development) and 0.930
(validation). Within 250 ms, starts and ends are at least 95.6% on both roles.
Word times are quantized by the 40 ms encoder frame.

### Speed

Real-time factor here is wall time for features, encoder and CTC decoding divided
by the assembled block duration, which includes 1 to 2% inserted silence. It
excludes file decoding, resampling and model loading, so it is lower than the
plan's complete-processing RTF, and it is not a phone number. Each row is one run
on a shared container.

| Run | RTF |
| --- | --- |
| float32, 1 thread (3 blocks) | 0.118 |
| float32, 2 threads (10 blocks) | 0.069 |
| float32, 4 threads (3 blocks) | 0.047 |
| int8, 2 threads (10 blocks) | 0.068 |

### Slices the jury named

The jury says call-like and meeting-like recordings are both scored, Russian is
first and English second, and the test device is ARM. This corpus cannot supply
real calls or meetings, so the slices below are controlled stand-ins.

| Slice | Development | Validation |
| --- | --- | --- |
| Clean (float32) | 162 / 5,572 = 2.9074% | 432 / 10,053 = 4.2972% |
| Telephone channel | 166 / 5,572 = 2.9792% | 465 / 10,053 = 4.6255% |
| Clips under 2 s (clip-level) | 1 / 18 words | 13 / 665 = 1.95% |

The telephone condition resamples to 8 kHz, applies a 300 Hz high-pass and 8-bit
mu-law companding (G.711-like), then returns to 16 kHz. It is a deterministic
codec simulation, not a phone network, handset or packet loss. Short clips are
whole utterances under 2 s, not short replies inside fast overlapping exchanges;
speaker attribution on them was not measured here.

**English smoke.** `tools/fixtures/english_smoke.json` freezes the first two
utterances in ID order of each of the 40 LibriSpeech test-clean speakers
(`openslr/librispeech_asr` revision `71cacbfb7e2354c4226d01e70d77d5fca3d04ba1`,
CC-BY-4.0; 80 utterances, 732.075 s). It was fixed before any English inference.
Each utterance is recognized on its own with the same chunking and decoder.

| English smoke | Word errors | Rate | Character error rate | RTF |
| --- | --- | --- | --- | --- |
| float32, clean | 128 / 1,901 | 6.73% | 1.90% | 0.058 |
| float32, telephone | 140 / 1,901 | 7.36% | 2.82% | 0.057 |
| int8, clean | 132 / 1,901 | 6.94% | 2.00% | 0.054 |

English output is lowercase letters and apostrophes with numbers spelled out;
read audiobook speech is easier than conversation. These are smoke results, not
an English benchmark, and every Russian figure stays separate.

## Validation

Validation covers 959 clips and 4,494.570 s of assembled audio. Nothing was tuned
on it; the candidates below were frozen on development first.

| Measure | float32 | int8 |
| --- | --- | --- |
| Word error rate | 432 / 10,053 = 4.2972% | 436 / 10,053 = 4.3370% |
| Character error rate | 772 / 53,851 = 1.4336% | 778 / 53,851 = 1.4447% |
| Speaker D / M / S (clip-level counts) | 4.65% / 5.01% / 2.13% | 4.75% / 5.09% / 1.97% |
| RTF (2 threads) | 0.070 | 0.064 |

Error rates are higher than on development and differ by speaker (S 2.1%, M 5.0%).
The per-clip score is 438 errors (4.36%) because words at clip joins can be
assigned to a neighbor; block-level scores follow the earlier evaluation. The
validation errors were deliberately not analyzed, so this set stays usable for
checking later changes. Comparisons between runs on the same audio are paired;
single runs without a significance test cannot show that two variants are
equivalent, only that no large difference appeared.

## Highest-impact failures and next hypotheses

These are ranked by the jury's weights and by what the evidence above shows; each
hypothesis still needs its own paired development measurement.

1. **Short replies in fast exchanges (diarization, 25 points).** Phrases join words
   across gaps up to 500 ms into 1.8 s embedding windows, windows under 320 ms of
   speech are skipped, and only windows with 800 ms or more can seed a voice. The
   earlier fixed evaluation scored 800 ms replies at 33.33%. Hypothesis: add
   embeddings for word groups split by short internal pauses that may vote but
   never seed, and let the narrowest covering window win. Measuring it needs fresh
   embedding inference and the CI scorer, whose cache is currently missing.
2. **Inflection and spelling substitutions (transcription, 40 points).** Close
   forms and other substitutions are 92 of 162 development errors. The text model
   is not allowed to change words, so this needs an acoustic or decoding change.
   Hypothesis: compare a Russian-only GigaAM v3 model on the same frozen inputs
   under a new provenance record before the APK model is frozen.
3. **Phrase-start lag that depends on the speaker (timestamps, 10 points).** The
   start lags the energy proxy by −12 to +87 ms across speakers and recordings, so
   a constant shift failed. Hypothesis: refine each phrase start to a local energy
   or VAD onset within a bounded window, never before the previous phrase. It must
   be judged on manually checked boundaries in continuous recordings, not on this
   proxy, which would make the check circular.

Clip-edge words (about twice the interior error rate) and laughter in reference
text are also visible, but they may be artifacts of segmented, acted clips.

## Decisions

| Candidate | Selection data | Validation | Decision |
| --- | --- | --- | --- |
| Shift phrase start 80 ms earlier | Development start bias +82 to +90 ms across 25, 30 and 35 dB proxies | Rule stated in the working session before the validation run, but committed only afterwards (now `asr_study.py timing-candidate`, which reproduces both outcomes): mean absolute start error must fall by at least 10 ms at 30 dB, with no loss in the 100 ms share and the same direction at 25 and 35 dB. It rose from 98.1 to 101.5 ms; the share fell from 61.1% to 59.5%; the 25 dB proxy worsened and 35 dB improved | **Rejected, no code change.** The bias depends on speaker (validation: S +79 ms, M +16.5 ms, D −12 ms), so one constant does not generalize |
| Level normalization before recognition | Gain ×0.35 changed nothing | Not run | Not warranted |
| Different chunk seam or context policy | Interior seam errors 2.39% against 2.00%, small samples | Not run | Undecided; needs continuous recordings |
| Split replies longer than 20 s at their longest pause (`TranscriptSegmenter`) | Ordinary phrases unchanged; only runs over 20 s are cut, now at a pause instead of mid-sentence | Unit-tested in CI; no corpus here has 20 s monologues | **Adopted.** Behaviour change limited to long monologues; turn granularity for the JSON export is agreed separately with the export owner |
| int8 weights instead of float32 | Errors 160 against 162; file 3.9 times smaller | 436 against 432; RTF 8% lower | **Candidate for the embedded APK, not yet adopted.** No large accuracy difference appeared, and 225 MB instead of 885 MB matters when weights ship inside the APK; ARM speed and the deployed export's own int8 behaviour still need measurement |

No accuracy improvement was found, so none is claimed.

## Limitations and open work

- The model is the public reference export, not the deployed pack; rerun against
  the deployed files before reporting production numbers.
- Acted studio dialogue with three actors, in constructed sequences: no overlap,
  natural pauses, far-field audio or phone microphones. Validation shares M and D
  with development.
- The energy proxy is not ground truth for timing. Moving its threshold from 25
  to 35 dB shifts the median start bias by 8 ms on development and 23 ms on
  validation. Timing against the hackathon's own reference turns is unknown.
- RTF comes from a four-core server CPU. Phone RTF, memory and int8 speed on ARM
  need device measurement.
- Speaker attribution was not re-measured. The earlier CI figures (for example
  97.6839% completed clip-time attribution on development) are clip-time
  attribution, not diarization error rate, and the Kotlin scorer needs a
  restored inference cache to run.
- Other speech models (for example the Russian-only GigaAM v3 CTC or RNNT
  variants) were not compared. The hackathon plan allows a new model only with a
  new provenance record and fresh inference inputs.
- The timing method is not the plan's P4 method: it uses concatenated isolated
  clips, a turn is the words owned by one clip rather than an application turn,
  and there are no manually checked boundaries. The proxy covered every clip in
  both roles (336 and 959).
