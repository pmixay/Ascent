# Local text stage: word-preserving punctuation

Status: provisional working choice of the speech lead, 7 November 2026 (14:30 MSK), from host
measurements. The final model choice is pending the team's independent model comparison.
Another Qwen2.5-family ONNX export needs only new `TextModelFiles` values and assets; a
different model family also needs its own tokenizer and chat template behind
`TextModelScores`. The Kotlin code is pushed with JVM unit tests that CI has not yet run. **Nothing here has run on
Android or ARM.** The brief's local-LLM requirement is not met until a CI-built APK runs this
stage on a device or the ARM emulator and the result is inspected.

## Provisional choice

| | |
| --- | --- |
| Model | Qwen2.5 0.5B Instruct, int8 ONNX export, Apache-2.0 |
| Source | `onnx-community/Qwen2.5-0.5B-Instruct` at `22942cb7d7ba4cc81bb4673549ca4d4614469b5e` |
| Files | `onnx/model_int8.onnx`: 512,096,557 bytes, SHA-256 `41834041ab1b29eff9fc592f1a29a1844133aea35832ea9fa91682be13016100`. `tokenizer.json`: 7,031,673 bytes, SHA-256 `a8506e7111b80c6d8635951a02eab0f4e1a8e4e5772da83846579e97b16f61bf` |
| Runtime | The Maven ONNX Runtime Android 1.31.0 that the recognizer already uses. No llama.cpp and no new native library |
| Method | One causal pass per phrase (no free generation), blocks of 24 tokens with the key/value cache carried over |

This replaces the earlier proposal (Qwen2.5 1.5B GGUF through llama.cpp). That path needed
a new NDK-built native library and a 1.1 GB file, and no Android runtime for it existed four
hours before the code handoff. The 1.5B ONNX export (1.58 GB int8) was rejected on size.

## What the stage does

The model sees the recognized words of one phrase exactly as recognized (lowercase, no marks)
as the answer to an instruction to punctuate them. At each word boundary its next-token scores
choose between one of `, . ? !` and the next word. At each word start they choose between a
capital and a lowercase first token. A capital always follows `.`, `?` or `!`. The model never
generates free text, so it cannot add, drop, reorder or respell a word. Speaker labels and times
are not inputs and cannot change.

## Kotlin API

Source files are in `prototypes/phone-transcriber/android/app/src/main/java/com/pmixay/ascent/`.

```kotlin
val tokenizer = QwenTokenizer.load(File(dir, TextModelFiles.TOKENIZER))
OnnxTextModel(File(dir, TextModelFiles.MODEL), TextPunctuator.instructionPrefix(tokenizer)).use { model ->
    val stage = TextPunctuator(tokenizer, model)
    val words = segment.text.split(' ')
    val shown = TextPunctuator.render(words, stage.styles(words))
    val text = if (TextPunctuator.preservesWords(segment.text, shown)) shown else segment.text
}
```

- `QwenTokenizer`: byte-level BPE for the pinned `tokenizer.json`, encoding only.
- `TextModelScores`: the seam. Tests use a fake. `OnnxTextModel` is the real implementation.
- `TextPunctuator.styles(words)`: one `WordStyle(capital, mark)` per word. Inputs over 64 words
  are split into blocks. It checks the thread's interrupted flag between blocks.
- `TextPunctuator.render` and `preservesWords`: projection onto the original words and an
  independent lexical check (lowercase, `ё` as `е`, marks removed).
- `TextModelFiles`: repository, revision, file names, sizes and hashes for packaging.

## Rules for the integration (application lead)

- Run the stage after recognition and speaker sessions are closed, so the acoustic and text
  models are never loaded at the same time. Create one `OnnxTextModel` per job, run all
  phrases and close it in `finally`. It is not thread-safe.
- Store the raw ASR text beside the presented text. On an exception, a cancelled job or a
  failed `preservesWords`, keep the raw text and increase a content-free fallback counter.
  Never log phrase text.
- **JSON export.** Recommended: export the raw ASR text in `text`. The brief scores WER, and
  marks or capitals can count as word errors if the jury does not normalize. Using punctuated
  text in the export needs either the jury's confirmation that WER is normalized or the team's
  explicit choice; in both cases the stage still runs and its output is shown in the interface.
- Report whether the stage ran, how many phrases fell back and the added processing time.

## Rules for packaging (release lead)

- Place `model_int8.onnx` and `tokenizer.json` in a `text-model/` directory next to, not inside,
  the acoustic model directory: `ModelSpec.load` rejects unexpected files.
- Pin the source revision and hashes above, check them at build time, include the Apache-2.0
  licence and the Qwen notice, and store the ONNX file uncompressed in the APK.
- ONNX Runtime needs a file path. Extract the file from the APK to app storage once and verify
  its size and hash before first use.

T2 packaging seam: APK assets use `models/text-model/`, while installed immutable
revision directory names are versioned. Obtain the actual directory from
`ModelSelection.acquire(context).textDirectory`, not a hard-coded files-dir path
or `TextModelFiles.DIRECTORY`. Keep that paired lease through the text stage.
See [embedded-models.md](embedded-models.md). Packaging does not execute the stage;
Pasha owns that job integration and Petya owns exact-build ARM validation.

## Host measurement

Run on the same 4-core x86 container as the ASR baseline, 2 threads, with the 120 development
clips of the earlier study (2,115 words, 1,067.59 s of speech, `masha_dima_part1`, recognized
words equal to the reference words). Recognized words came from the public-reference inference
output. Leaving every word lowercase gets 84.87% of capitals right.

| Variant | Comma F1 | Sentence-end F1 | Question F1 | Capitals | Added RTF (host) |
| --- | --- | --- | --- | --- | --- |
| **0.5B int8 ONNX, one pass, 24-token blocks (Android setting)** | **0.322** | **0.456** | **0.182** | **88.98%** | **0.081** |
| Qwen3.5 0.8B q4 ONNX, one pass, 24-token blocks | 0.235 | 0.007 | 0.000 | 86.19% | 0.096 |
| Qwen3.5 0.8B int8 ONNX, one pass, 24-token blocks | 0.461 | 0.039 | 0.000 | 88.98% | 0.808 |
| 0.5B int8 ONNX, one pass, one block per phrase | 0.153 | 0.519 | 0.182 | 90.07% | 0.035 |
| 0.5B int8 ONNX, word-constrained greedy generation | 0.445 | 0.581 | 0.367 | 90.02% | 0.601 |
| 0.5B Q4_K_M GGUF, grammar generation (earlier study) | 0.339 | 0.415 | 0.298 | 89.1% | 0.309 |
| 1.5B Q4_K_M GGUF, grammar generation (earlier study) | 0.649 | 0.792 | 0.644 | 94.1% | 0.469 |

- Qwen3.5 0.8B (`onnx-community/Qwen3.5-0.8B-Text-ONNX` at `25cdf33f386b487a25f3d743e86442517bdb77ff`,
  Apache-2.0 base model, hybrid linear/full attention, 248k vocabulary) was measured with the
  same instruction and the answer opened by an empty think block, as its non-thinking chat
  template does. It almost never ends a sentence: its own free greedy output for a test phrase
  was lowercase with one comma and no final mark, so this is model behaviour with this
  instruction, not a cache error (24- and 512-token blocks gave identical q4 logits). The int8
  export has the best comma score but is ten times slower than the Qwen2.5 stage; its files are
  938 MB plus a 19 MB tokenizer. It is not adopted. Using it would also need the Kotlin
  wrapper to carry fixed-size convolution and recurrent states and `num_logits_to_keep`.
- The single-pass variant was chosen for speed: the brief scores RTF and experts use an ARM
  emulator. Its punctuation is clearly weaker than the 1.5B model's. This is a compliance stage
  with a modest presentation benefit, not a quality gain, and it does not change WER.
- The int8 export quantizes activations dynamically per call, so the block size changes the
  scores. The two one-pass rows differ only in block size; the gap between them is within
  that noise, not a tuned improvement. The block size was not selected on these scores.
- These are development clips of acted dialogue scored against one annotator's punctuation.
  Validation recordings were not used. ARM speed and memory are unmeasured, and the
  emulator is likely several times slower than this host.
- Reproduce: `python3 tools/text_model_onnx_study.py OUTPUT --model model_int8.onnx
  --tokenizer tokenizer.json --block 24`, where `OUTPUT` is an `evaluate_dialogs.py` output
  directory (needs `onnxruntime`, `numpy`, `regex`).

## Verification status

- The Python tool mirrors the Kotlin code. With one block per phrase it reproduces the
  reference single-pass implementation exactly on all 120 clips.
- The tokenizer test fixture is a subset of the pinned `tokenizer.json`. Its expected IDs come
  from the Hugging Face `tokenizers` library.
- Not run yet: the JVM unit tests in CI, `OnnxTextModel` against ONNX Runtime Android
  (including zero-length past tensors for the prefix), the packaged APK, ARM timing and memory.
