# Recognition modes: GigaAM and Parakeet TDT 0.6B v3

Status: model choice of the team, 7 November 2026, from host measurements in
[model-report.md](../hackathon/model-report.md#second-recognizer-english-and-multilingual-candidates-host).
The embedded Parakeet payload and optimized emulator creation probe passed at
`dbbce95` / `42404340446`; combined AUTO/timing source `42d4de9` passed
[CI 42406537594](https://github.com/pmixay/Ascent/actions/runs/42406537594). Actual ARM inference remains open.
Andrey's `50a5d90` AUTO path is integrated with the timing hooks. Main `77dd372`
includes Pasha/Petya's mode UI, triple lease, actual-used label, soft text budget
and ViewModel regressions. The combined signing/cleanup follow-up requires its
own exact-revision CI, tracked in [PR #5](https://github.com/pmixay/Ascent/pull/5).

Timofey's explicit packaging follow-up merges this engine path and adds a
schema-2 embedded `multilingual-asr/` group with unchanged four file pins,
CC-BY-4.0 attribution, uncompressed APK entries, triple-revision leases and a
required minified-emulator recognizer-creation test. [Implementation and evidence
status](../roles/TIMOFEY_PARAKEET_STATUS.md) and [current release handoff](../roles/TIMOFEY_REMAINING_STATUS.md). Petya's `codex/petya-open-work` integrates Pasha's mode API, the triple lease and
UI control; its own combined CI is recorded in [TEST_RESULTS.md](../TEST_RESULTS.md).

## Decision

| Mode | Model | Languages | Why |
| --- | --- | --- | --- |
| Russian and CIS languages (default) | GigaAM Multilingual CTC 220M, already shipped | ru, kk, ky, uz (+ weak en) | Best measured Russian: validation WER 4.30% vs 6.73% for Parakeet v3 |
| Multilingual | NVIDIA Parakeet TDT 0.6B v3, int8 | 25 European languages incl. en, ru, uk | Best measured English among phone-sized models: continuous AMI meetings 28.62% vs 32.32% WER and 96% vs 63% word starts within 250 ms; isolated utterances 18.57% vs 54.65% (headsets), 44.74% vs 74.96% (distant microphone) |

Russian is the brief's first priority. Besides the two fixed modes there is an automatic one,
described below; a fixed mode never falls back silently to the other model.

## Automatic mode (`RecognitionMode.AUTO`)

GigaAM starts on the recording. After each window `LanguageProbe` counts Latin (a-z) and other
letters in the words owned so far; once 30 letters are seen, or the probed audio reaches 60 s,
a Latin majority restarts the whole recording with Parakeet and discards GigaAM's words;
anything else, a tie, silence or an undecided end keeps GigaAM, which simply continues, so a
Russian recording costs nothing extra. Without installed Parakeet files the mode is plain
GigaAM. `TranscriptionResult.recognitionMode` reports the mode actually used.

Host check, rule fixed before running (`tools/language_probe_study.py`, GigaAM with `AsrGain`):

| Samples | Correct decisions | Decision after (median / max) |
| --- | --- | --- |
| Russian Dialogs blocks, development + validation (constructed, 3 actors) | 27 / 27 Russian | 19.2-19.9 s / 20.0 s |
| English AMI EN2002a headsets, blocks of at least 60 s (constructed) | 53 / 53 multilingual | 19.5 s / 39.0 s |
| English AMI EN2002a distant microphone | 52 / 53 multilingual | 19.7 s / 39.8 s |
| English AMI ES2011a headsets | 17 / 17 multilingual | 19.6 s / 20.0 s |
| 30 s of silence | Russian | 30 s |
| Petya's AMI holdout, six continuous 10-minute meetings | 6 / 6 multilingual | 28.6 s / 60.0 s |

Not measured: Russian with many English terms, mixed-language conversations, a recording that
changes language after the first minute (it keeps the first decision), other European
languages, and real calls or meetings. A wrong switch on Russian would cost about 2.7 points
of WER (validation 4.30% to 6.98%); a missed switch on English costs about 36 points (AMI
headsets 54.65% vs 18.57%).

## Positioning in the app

Name modes by language, not by model, and put the control where recording or import starts:

| Control | Mode | Subtitle |
| --- | --- | --- |
| **Авто** (recommended default) | `AUTO` | «Язык определяется сам по первым секундам записи» |
| **Русский** | `RUSSIAN_CIS` | «Русский и языки СНГ: казахский, киргизский, узбекский» |
| **Другие языки** | `MULTILINGUAL` | «Английский и европейские языки» |

- Remember the choice; show the mode actually used next to the result ("Распознано: русский" /
  "Распознано: другие языки") from `TranscriptionResult.recognitionMode`.
- Model names, versions, sizes and licences belong on an "О моделях" screen; Parakeet's
  CC-BY-4.0 requires the attribution "NVIDIA Parakeet TDT 0.6B v3".
- Do not promise "any language" (only Russian and English are measured) or mixed-language
  support (unmeasured).
- Judge instructions should still say which mode to pick for English if the default is not
  `AUTO`.

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
enum class RecognitionMode { RUSSIAN_CIS, MULTILINGUAL, AUTO }    // current engine default: RUSSIAN_CIS

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
- **Application lead (Pasha):** the three-way mode control from "Positioning in the app"
  (`AUTO` recommended as default), passed with the installed Parakeet directory to
  `TranscriptionEngine`; show the mode actually used from `TranscriptionResult.recognitionMode`;
  in a fixed multilingual mode show the "not installed" error instead of switching silently.
  *Integrated (Petya's branch, PR #5):* remembered selection, a job-frozen mode and the actual-used
  label reach the Russian UI. Inference uses the job's leased Parakeet directory. The compact
  APK still has no Parakeet files: Auto uses GigaAM and fixed multilingual is refused before
  capture/input opening. Selection is disabled during capture/processing/watch reception.
  CI: [run 42408030599](https://github.com/pmixay/Ascent/actions/runs/42408030599) (`5886020`, ViewModel wiring) and
  [run 42411299817](https://github.com/pmixay/Ascent/actions/runs/42411299817) (`16932d3`, picker and its emulator check)
  concluded success for both jobs. The current embedded gate also includes a synthetic silent Auto import through the real ViewModel.
  Spoken Auto/Multilingual inference and ARM quality remain unverified; the packaging
  probe establishes recognizer creation only. Current combined execution is tracked in PR #5.
  [Current handoff](../roles/PETYA_HANDOFF.md); phone evidence remains open.
- **QA and submission (Petya):** in the judge instructions, name the default mode and how to
  pick "Другие языки" for English; include an English and a mixed recording in device checks.
- **Speech lead:** measure both modes on the device with `tools/score_competition_json.py`
  once an APK contains the files.
