# Two-hour Russian dialogue evaluation

The frozen selection contains **1,134 distinct audio payloads** and **7,233.107
seconds** of source duration according to the publisher's metadata. All payload
SHA-256 values are unique. It covers three actors and both male/female and
female/female pairings. The actual resampled PCM duration must still exceed
7,200 seconds before CI can accept a complete evaluation.

The source is [Dialogs by Ilya Shigabeev and Ilya Latyshev](https://huggingface.co/datasets/langswap/dialogs-ru-emotional-conversations),
revision `a74ff44d8d27ca3d21098ea3ed4d31a32923fbdd`. The authors describe
face-to-face, studio-recorded, acted Russian dialogues with speaker identities,
transcripts and expressive-style labels. See their
[dataset card](https://huggingface.co/datasets/langswap/dialogs-ru-emotional-conversations/blob/a74ff44d8d27ca3d21098ea3ed4d31a32923fbdd/README.md)
and [Interspeech 2026 paper](https://arxiv.org/abs/2607.14310).
The dataset's [OpenRAIL license](LICENSE-Dialogs.md) is retained here. This is
used for algorithm development and validation under the frozen split below.
No model weights are trained and no actor is enrolled into application voice
memory. No recordings are distributed in the APK or downloadable archive.

## Input acquisition status

On 26 October 2026, all 1,134 selected files were downloaded and verified against
the pinned byte counts and SHA-256 hashes. Header-only inspection found
638,011,076 bytes of mono, 44.1 kHz, 16-bit PCM WAV files with a combined duration
of **7,233.119955 seconds (2 h 00 min 33.120 s)**. The largest per-file difference
from the publisher's rounded duration was below 0.023 ms. Speaker D contributes
1,756.203 seconds, M contributes 3,394.543 seconds, and S contributes 2,082.374
seconds. Public input files are outside the Git checkout and are not artifacts.

The acquisition checks establish input integrity separately from the measured
GitHub CI recognition baseline below. No accuracy result is inferred from
download hashes or header inspection.

## Selection frozen before inference

The algorithm candidate at `ffa5fa7` was selected using development results
before reading the original reserved recordings. That check exposed a severe
female/female speaker merge: completed clip-time attribution was 61.30% and
cpWER was 77.94% on the three original reserved recordings. Those recordings
are now diagnostic data, not an untouched validation set. The promising small
development gains do not establish a usable diarization improvement.

[manifest.json](manifest.json) pins the repository revision, complete metadata
hash, per-file byte counts and SHA-256 hashes, selected speaker labels and model
revision. It takes available utterances in numeric source order from
`masha_dima_part1`, `masha_sveta_part1` and `masha_sveta_part2`, followed by an
original-order prefix of `masha_dima_part2` until the metadata duration exceeds
7,230 seconds. The extra margin covers sub-millisecond resampling tails.

The metadata references files absent from the pinned repository. The 191
unavailable paths encountered in that selection are explicitly recorded in the
manifest; they are not silently counted as downloaded speech. No repeated audio
hash was found among the selected payloads. The selection was frozen before
running either recognition model.

## Development and validation split

Before baseline inference or score inspection, freeze the following allocation:

| Role | Selected source recordings | Actors |
| --- | --- | --- |
| Development | `masha_dima_part1` | M, D |
| Original diagnostic set | `masha_sveta_part1`, `masha_sveta_part2`, `masha_dima_part2` | M, S, D |

Development results and the original diagnostic set have both been inspected.
Do not report the original set as held-out validation. The next evaluation must
use a separately selected recording set and clearly state its limitations. Keep
the existing six-reader regressions independent with unchanged acceptance
thresholds.

This split separates source recordings, not all identities: M and D appear in
both subsets, while S is validation-only. The small number of actors and shared
identities limit claims about generalization to unseen people. Upstream model
training overlap remains unknown. No benchmark actor is added to voice memory,
and no model weights are trained.

## Measured development baseline

The baseline at `e9df416` passed
[CI 40378549620](https://github.com/pmixay/Ascent/actions/runs/40378549620).
[Diagnostic CI 40381522671](https://github.com/pmixay/Ascent/actions/runs/40381522671)
at `3ae8975` also passed. The original reserved recordings were subsequently inspected and failed; their
results are documented above and are no longer held out.

`masha_dima_part1` contributes 336 unique clips in ten blocks, 2,866.068 seconds
of resampled source audio, 5,572 reference words and 29,095 reference characters.

| Development metric | Baseline count | Baseline rate |
| --- | --- | --- |
| Word error rate | 160 / 5,572 reference words | 2.8715% |
| Character error rate | 254 / 29,095 reference characters | 0.8730% |
| Joint speaker/transcription cpWER | 278 / 5,572 reference words | 4.9892% |
| Completed clip-time attribution | 2,799,686 / 2,866,068 ms correct | 97.6839% |

Strict acoustic attribution correctly labels 2,573,284 ms on the same source
clip-time denominator. Final word assignments infer 56 of 5,567 recognized words;
the separate reference-probe diagnostic infers 255,893 ms of clip time. These
figures measure different populations and must not be conflated. Clip-time
accuracy includes internal source silence and is **not DER**.

The candidate reserves acoustic diversity within the existing bounded anchor
budget, requires independently supported centroid agreement before merging,
and reconciles unique overlapping duplicate words at CTC chunk seams. Model
weights and operating cutoffs remain fixed. Candidate comparison results are
pending; no improvement is claimed from the implementation alone.

A further candidate freezes small fragments' independent preferences among
established voices. Fragments that each satisfy the unchanged rules for the
same parent no longer block one another solely through mutual similarity.
Other rivals remain competitors; inferred labels neither corroborate further
recovery nor train profiles. A genuinely new short, similar-sounding speaker
can still be absorbed by this best-effort policy. The original reserved set
showed that this candidate does not solve female/female merging.

## Measurement and limitations

CI downloads and verifies every selected recording and the exact production
GigaAM/ERes2NetV2 model revision. It mixes source channels to mono, resamples to
16 kHz using SciPy's polyphase filter, quantizes to PCM16, and removes at most
15 resampled tail samples per source to obtain exact millisecond boundaries.
Original utterances remain intact and in their original numeric order.
Approximately five-minute blocks add 100 ms of known silence between clips.
Original pauses and overlapping speech cannot be reconstructed from segmented
files. These are staged dialogue sequences, not continuous natural meetings.

ASR uses the deployed ONNX graph, the app's silence-aware chunk policy and CTC
ownership rules. WER/CER compare complete normalized reference and recognized
texts; empty hypotheses and deletions remain errors. Normalization lowercases,
maps `ё` to `е`, removes punctuation and collapses spaces; numbers are not
rewritten into words. Kotlin verifies the host timing mirror using raw CTC
labels and processes the real embeddings through the application's attribution
code. Speaker scoring uses labeled source clip time, including internal silence,
and excludes only inserted silence. It is not speech-only DER or a measurement
of phone latency, far-field audio, overlap separation or cross-session identity.

The Kotlin harness reports both strict acoustic attribution and completed
participant labels, with a one-to-one anonymous-label mapping for each block.
Missing strict assignments stay in the denominator. Inferred completion is
scored as a prediction and reported separately; it is not verified identity.
Concatenated minimum-permutation WER (cpWER) measures transcription and speaker
errors together by comparing each participant's complete word stream. Aggregate
scores weight source milliseconds or reference word counts rather than giving
short blocks the same weight as long ones. Per-actor results and excess or
missing participant counts accompany the totals.

The three actors provide substantial expressive variation but limited speaker
diversity. Publisher train/validation/test labels do not determine this frozen
application selection or its development/validation split. Model weights are
not trained on this selection; upstream training exclusion cannot be established.

The existing **Ascent Android CI** workflow runs the dedicated **Evaluate two
hours of labeled Russian dialogue** job on pushes. It prepares verified inputs,
compiles the Kotlin harness, performs or resumes inference, then scores the
actual application attribution code. Run these commands only in GitHub CI,
from `prototypes/phone-transcriber`:

```sh
python3 -m pip install -r tests/requirements-dialog-evaluation.txt
python3 tools/evaluate_dialogs.py --cache .cache/long-evaluation \
  --output android/app/build/generated/long-evaluation --prepare-only
python3 tools/evaluate_dialogs.py --cache .cache/long-evaluation \
  --output android/app/build/generated/long-evaluation --threads 2
```

The scoring step runs `:app:testDebugUnitTest --tests
com.pmixay.ascent.TwoHourSpeechEvaluationTest` from `android`, with
`ASCENT_DIALOG_EVALUATION` set to the absolute path of the generated
`evaluation.json`. A skipped or missing test is a failed evaluation.

Per-block checkpoints bind the source selection, model revision, dependency
pins, evaluator code and audio hashes. Valid checkpoints resume expensive
inference without changing the evaluation denominator. Source and weight
caches are saved before inference; failed or interrupted jobs preserve completed
blocks for a retry. Cache hits still verify payload and checkpoint integrity.
The scorer always runs against the current application code. Console summaries carry
the aggregate measurements; public recordings, transcripts, vectors and reports
are not uploaded as download artifacts. A completed CI result must be inspected
before reporting this evaluation as run.
