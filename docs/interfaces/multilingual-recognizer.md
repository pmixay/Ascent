# Recognition modes: GigaAM and Parakeet TDT 0.6B v3

Status: model choice of the team, 7 November 2026, from host measurements in
[model-report.md](../hackathon/model-report.md#second-recognizer-english-and-multilingual-candidates-host).
**Not integrated:** no APK contains Parakeet, no engine path runs it, and nothing here has run
on Android or ARM.

## Decision

| Mode | Model | Languages | Why |
| --- | --- | --- | --- |
| Russian and CIS languages (default) | GigaAM Multilingual CTC 220M, already shipped | ru, kk, ky, uz (+ weak en) | Best measured Russian: validation WER 4.30% vs 6.73% for Parakeet v3 |
| Multilingual | NVIDIA Parakeet TDT 0.6B v3, int8 | 25 European languages incl. en, ru, uk | Best measured English among phone-sized models: AMI headsets 18.57% vs 54.65% for GigaAM, distant microphone 44.74% vs 74.96% |

Russian is the brief's first priority, so the default stays GigaAM. The user chooses the mode;
there is no automatic language identification, and a wrong mode is a user-visible choice, not
a silent fallback.

## Files

From `csukuangfj/sherpa-onnx-nemo-parakeet-tdt-0.6b-v3-int8` at
`ed5d745623472f97cfc2e6c2916ee248798fc5a8`, converted from `nvidia/parakeet-tdt-0.6b-v3`,
licence **CC-BY-4.0** (attribution required in the app's licence screen and the report).

| File | Bytes | SHA-256 |
| --- | --- | --- |
| `encoder.int8.onnx` | 652,282,294 | `1afbe69c1ffdabee94755f42e4daf1d93549131a3da11356bda84fcef0f2c479` |
| `decoder.int8.onnx` | 11,845,274 | `0222a86147299d4dee90edf95b6d13827edb42ad8b7c9cf0b2582f030937c0b5` |
| `joiner.int8.onnx` | 6,355,277 | `cbd02f3696e9944970111fb439d9c3fd365a80374565f13f2b9c7007d253fc98` |
| `tokens.txt` | 93,939 | `d58544679ea4bc6ac563d1f545eb7d474bd6cfa467f0a6e2c1dc1c7d37e3c35d` |

About 670 MB in total, stored uncompressed like the other embedded models and verified by
hash at build time.

## Runtime and engine contract

- Runtime: sherpa-onnx 2.0.0 `OfflineRecognizer` with a NeMo transducer config
  (`model_type = "nemo_transducer"`), the library the app already ships for speaker
  embeddings. No new native dependency.
- Input: the same bounded `PcmWindowSource` windows as GigaAM (`AsrChunking`, 20 s cores with
  context). Whether `AsrGain` helps Parakeet is unmeasured; the host numbers above ran without it.
- Output: sherpa-onnx returns tokens with start times (80 ms frames). Words start at tokens
  beginning with `▁`; a word ends at the next word's start or at the window end, then the
  existing stitcher, speaker attribution, segmenter and text stage run unchanged.
- Times stay approximate and must be checked like the CTC times; Parakeet timing accuracy is
  unmeasured.

## Owners

- Speech lead: engine path producing `TimedWord`s from Parakeet, with JVM tests, and the
  device measurement of both modes.
- Release lead: packaging, hashes, licence notice and APK size check.
- Application lead: the mode control and passing the mode to the engine.
