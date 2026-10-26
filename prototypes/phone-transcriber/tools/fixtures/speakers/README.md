# Public Russian speaker regression fixtures

These 16 unchanged WAV files (110.18 seconds, 3,526,464 bytes) are test inputs only. They never enter the APK,
downloadable model pack, or application storage. They contain public audiobook
speech, not recordings from Ascent users.

The source is [Russian LibriSpeech, OpenSLR 96](https://www.openslr.org/96/),
which identifies its LibriVox recordings as public domain in the USA. Files were
retrieved from the [istupakov mirror](https://huggingface.co/datasets/istupakov/russian_librispeech)
at revision `a519c986bb3342cc8136d3d14e5ad8a4f1e1a2bd`. That mirror also labels its
dataset metadata CC-BY-4.0. Credit the RuLS contributors, LibriVox volunteers,
the listed readers, and the mirror publisher when reusing these fixtures.

The [LibriVox catalog for Alexander Pushkin's Poems](https://librivox.org/poemi-alexander-pushkin/)
and the corpus's [pinned chapter/reader metadata](https://huggingface.co/datasets/istupakov/russian_librispeech/raw/a519c986bb3342cc8136d3d14e5ad8a4f1e1a2bd/CHAPTERS_READERS.TXT)
identify the six readers below. The source paths retain those reader IDs and the
book ID. `manifest.json` records the original path, test row, reader credit,
normalized and original reference transcript, source alignment score, exact
SHA-256, byte count and PCM frame count for each file. No gender labels are
inferred from their voices or catalog names. References are the corpus's
automatic alignments and can contain errors; they are not newly human-verified.

| Reader ID / credit | Source test rows | Chapters |
| --- | --- | --- |
| 2671 / Foreign Girl | 2, 3, 200, 400 | 01, 02, 03 |
| 3056 / albus865 | 500, 503, 600 | 04, 05 |
| 4471 / Create | 700, 703, 1001 | 06, 11 |
| 2826 / Chamberlain | 800, 803 | 08 |
| 5548 / ni | 1008, 1009 | 14 |
| 4372 / Cardray | 1200, 1201 | 15 |

The mirror's dataset viewer serves these rows through
`https://datasets-server.huggingface.co/rows?dataset=istupakov/russian_librispeech&config=default&split=test&offset=ROW&length=1`.
Its audio URLs expire, so CI uses the checked-in, checksum-verified bytes.
All inputs are mono PCM16 at 16 kHz. No edits were made to the source WAVs.
`python3 tools/download_speech_fixtures.py --output-dir /path/to/public-fixtures`
can reproduce the reviewed subset without downloading the complete multi-GB
corpus. It requires the pinned revision in the audio URL, matching reference text
and original source path, and exact WAV bytes/format. Existing mismatched files
fail verification rather than being silently replaced. This is public data
retrieval, not model inference, training, or a local test command.

`tools/generate_speaker_fixtures.py` runs only in GitHub CI. It verifies source
and model hashes, then generates clean, reduced-gain, deterministic 20 dB
white-noise, and mild synthetic channel variants. The channel uses a fixed
five-tap smoothing filter plus a 0.35 first-difference term, then restores source
RMS subject to clipping protection. It is not a measured microphone response.
Augmentation is quantized to PCM16 before both writing
the generated WAV and extracting embeddings. Each embedding covers a 1.8-second
window, advancing by 0.9 seconds and including the final anchored window.
The JVM regressions load those WAVs and use the app's actual activity gates and
clustering implementation. Generated inputs and embeddings are test resources
under `android/app/build/`, cached for CI and never uploaded as artifacts.

`tools/evaluate_speech.py` uses the existing ONNX session during model-export CI
to print micro and per-utterance macro WER/CER for all four conditions. Text
normalization is Unicode NFKC, lowercase, `ё` to `е`, punctuation to spaces and
whitespace collapse; CER excludes spaces. Insertions, deletions and substitutions
use Levenshtein distance and reference denominators. Empty clean hypotheses fail
the check; no accuracy threshold is invented before a baseline is measured.
Scores and counts remain in the CI console. No public speech or references are
added to the model download or APK, and no training occurs.

The evaluator also concatenates all clips with known alternating 0.3/1.3-second
pauses and compares the former fixed 20-second cores / 0.5-second context with
the new silence-aware cores / 1.25-second context, on clean and channel audio.
It mirrors Kotlin's energy seam selection, CTC word timing and midpoint ownership.
These host comparisons are documented approximations of app processing; Kotlin
unit checks separately cover the real boundary/decoder implementation. They
print both policies and error-count deltas, then fail CI if the new policy has
more word errors than the baseline in either condition. The relative gate uses
identical inputs and model weights; an improvement in one condition cannot hide
a regression in the other. CER remains diagnostic. Passing this development
gate does not establish a general accuracy improvement.

This small, fixed regression corpus checks known-reader consistency and
separation across different utterances, chapters and controlled perturbations. It is
development data, not a held-out accuracy benchmark. Sequentially arranging
its clips does not create natural conversational ground truth. It does not
measure ASR timestamp accuracy, overlap separation, real phone acoustics, or
cross-conversation identity false-match rates.
