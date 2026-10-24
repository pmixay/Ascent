# Public Russian speaker regression fixtures

These six unchanged WAV files are test inputs only. They never enter the APK,
downloadable model pack, or application storage. They contain public audiobook
speech, not recordings from Ascent users.

The source is [Russian LibriSpeech, OpenSLR 96](https://www.openslr.org/96/),
which identifies its LibriVox recordings as public domain in the USA. Files were
retrieved from the [istupakov mirror](https://huggingface.co/datasets/istupakov/russian_librispeech)
at revision `a519c986bb3342cc8136d3d14e5ad8a4f1e1a2bd`. That mirror also labels its
dataset metadata CC-BY-4.0. Credit the RuLS contributors, LibriVox volunteers,
the listed readers, and the mirror publisher when reusing these fixtures.

The [LibriVox catalog for Alexander Pushkin's Poems](https://librivox.org/poemi-alexander-pushkin/)
attributes chapter 01 to **Foreign Girl**, chapter 04 to **albus865**, and
chapter 06 to **Create**. The source paths retain those reader IDs and the book
ID. `manifest.json` records the original path, test row, reader credit, exact
SHA-256, byte count and PCM frame count for each file. No gender labels are
inferred from their voices or catalog names.

| Reader ID | Source test rows | Chapter |
| --- | --- | --- |
| 2671 | 2, 3 | 01 |
| 3056 | 500, 503 | 04 |
| 4471 | 700, 703 | 06 |

The mirror's dataset viewer serves these rows through
`https://datasets-server.huggingface.co/rows?dataset=istupakov/russian_librispeech&config=default&split=test&offset=ROW&length=1`.
Its audio URLs expire, so CI uses the checked-in, checksum-verified bytes.
All inputs are mono PCM16 at 16 kHz. No edits were made to the source WAVs.

`tools/generate_speaker_fixtures.py` runs only in GitHub CI. It verifies source
and model hashes, then generates clean, reduced-gain, and deterministic 20 dB
white-noise variants. Augmentation is quantized to PCM16 before both writing
the generated WAV and extracting embeddings. Each embedding covers a 1.8-second
window, advancing by 0.9 seconds and including the final anchored window.
The JVM regressions load those WAVs and use the app's actual activity gates and
clustering implementation. Generated inputs and embeddings are test resources
under `android/app/build/`, cached for CI and never uploaded as artifacts.

This small, fixed regression corpus checks known-reader consistency and
separation across different utterances and controlled perturbations. It is
development data, not a held-out accuracy benchmark. Sequentially arranging
its clips does not create natural conversational ground truth. It does not
measure ASR timestamp accuracy, overlap separation, real phone acoustics, or
cross-conversation identity false-match rates.
