# Recognition modes: GigaAM and Parakeet TDT 0.6B v3

Status: model choice of the team, 7 November 2026, from host measurements in
[model-report.md](../hackathon/model-report.md#second-recognizer-english-and-multilingual-candidates-host).
**Engine path written, not yet integrated:** the engine can run Parakeet (JVM-tested logic,
host-mirrored), but no APK contains the files, no UI selects the mode, and nothing here has
run on Android or ARM.

Timofey's explicit packaging follow-up merges this engine path and adds a
schema-2 embedded `multilingual-asr/` group with unchanged four file pins,
CC-BY-4.0 attribution, uncompressed APK entries, triple-revision leases and a
required minified-emulator recognizer-creation test. [Implementation and evidence
status](../roles/TIMOFEY_PARAKEET_STATUS.md). New CI is pending; normal app mode
selection and actual ARM acceptance remain Pasha/Petya handoffs.

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

## Engine API (speech lead, pushed with JVM tests)

`MultilingualRecognizer.kt` and `TranscriptionEngine.kt`:

```kotlin
enum class RecognitionMode { RUSSIAN_CIS, MULTILINGUAL }          // RUSSIAN_CIS is the default

object ParakeetFiles {                                              // pinned files above
    const val DIRECTORY = "multilingual-asr"
    val ALL: List<Asset>                                            // name, bytes, sha256
    fun installed(directory: File?): Boolean                        // every file with its pinned size
}

TranscriptionEngine(
    modelDirectory: File,                                           // GigaAM pack: always used for speakers
    mode: RecognitionMode = RecognitionMode.RUSSIAN_CIS,
    multilingualDirectory: File? = null,                            // directory holding ParakeetFiles.ALL
    timings: ProcessingTimings? = null,                            // optional exclusive stage counters
).transcribeWithSpeakers(source, onProgress)
```

- `MULTILINGUAL` runs Parakeet through sherpa-onnx 2.0.0 `OfflineRecognizer`
  (`nemo_transducer`, greedy search, 2 threads) over the same `AsrChunking` windows and
  `CtcChunkStitcher` as GigaAM. Speaker attribution, segmenter and text stage are unchanged.
- `ParakeetWords` turns tokens into the same lowercase, punctuation-free timed words as the
  GigaAM path: a token starting with a space or `▁` starts a word; a word spans its first to
  last letter/digit token (start + TDT duration); Parakeet's own punctuation and capitals are
  dropped so the text stage treats both modes alike. A word starting after the recording end
  is dropped.
- No `AsrGain` in this mode: Parakeet was measured without it.
- If the files are not installed, `MULTILINGUAL` fails with "Мультиязычная модель не
  установлена"; the engine never falls back silently to the other model.
- The same path is mirrored on the host by `tools/sherpa_asr_compare.py windowed` and scored
  with `tools/asr_study.py score`: Russian Dialogs WER 5.76% development / 6.98% validation,
  word starts within 250 ms of the energy proxy for 94.9% / 97.1% of clips (report has the
  details). English was measured per utterance only (AMI), not through the windowed path.

## Handoff

- **Release lead (Timofey):** add the four files of `ParakeetFiles.ALL` under
  `multilingual-asr/` of the embedded model set, stored uncompressed, with build-time SHA-256
  checks against `ParakeetFiles`, and the CC-BY-4.0 attribution for `nvidia/parakeet-tdt-0.6b-v3`.
  Check that R8 keeps what sherpa-onnx's JNI reads from `OfflineRecognizerConfig` and its nested
  config classes (fields are looked up by name); if the minified build fails to create the
  recognizer, add a keep rule for `com.k2fsa.sherpa.onnx.Offline*` and `FeatureConfig`. Add an
  instrumented smoke test that creates `ParakeetRecognizer` on the emulator. APK size grows by
  about 670 MB.
- **Application lead (Pasha):** a mode control (Russian and CIS languages / multilingual,
  Russian default) and pass it with the installed directory to `TranscriptionEngine`; show the
  "not installed" error instead of switching modes silently.
- **Speech lead:** measure both modes on the device with `tools/score_competition_json.py`
  once an APK contains the files.
