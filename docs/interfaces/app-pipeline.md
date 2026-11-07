# Application pipeline: import, job, result, JSON

Status, 7 November 2026: **source pushed; the whole CI workflow is green, but nothing has run on a phone or an
ARM device.** CI run [42376061272](https://github.com/pmixay/Ascent/actions/runs/42376061272) (commit `2545310`)
concluded success for the build job (lint, unit tests, assemble, APK inspection, x86_64 emulator
instrumentation tests), the inference job and the scoring job. In the "Verified" column below, only what a
named test covers counts; the rest is unmeasured.
Source files are in `prototypes/phone-transcriber/android/app/src/main/java/com/pmixay/ascent/`.

```text
picker (Petya) -> ConversationViewModel.importAudio(uri)
  -> job (one at a time, own ID) -> AudioSources.open: content:// descriptor, read in place
  -> PcmWindowSource (16 kHz mono windows)  -> TranscriptionEngine (Andrey), model lease
  -> lease released -> TextStages.run (local text model) -> encrypted history save
  -> exportTranscript(id, uri, json = true) -> CompetitionJsonExporter -> user-chosen file
```

## Commands (for the interface)

All are on `ConversationViewModel`. None needs the microphone permission.

| Command | Behaviour |
| --- | --- |
| `importAudio(uri: Uri?)` | `null` (picker cancelled) changes nothing. Refused silently while a job, a recording or a rename/export runs, so repeated taps cannot start two jobs. With models not yet installed it sets a status message. Otherwise it starts a job. |
| `cancelProcessing()` | Cancels the running job. A native recognition call finishes first, so cancellation can lag by one window. The busy flag stays set until cleanup ends; only then can another job start. |
| `stopRecording()` | Unchanged for the user; it now runs through the same job path. |
| `exportTranscript(id, uri: Uri?, json: Boolean)` | `null` changes nothing. `json = true` writes the competition format, also for a recording without speech. `json = false` writes the TXT. The document is built in memory first. |

## State the interface can show

`ConversationState` (existing fields unchanged, new fields default to idle):

- `status`: still one stable string, `Обработка аудио…` while a job runs. It is not a stage label.
- `processingState`: `Idle, PreparingModels, ReadingInput, Recognizing, Diarizing, Formatting, Saving, Completed, Cancelled, Failed`.
  Model readiness stays in the model panel fields.
- `jobProgress`: fraction `0..1` of **the current stage's media**, `null` while unknown. Recognition and
  speaker analysis each count from 0. It is parsed from the engine's progress messages
  (`EngineProgress`), so a change of those Russian strings in `TranscriptionEngine` must keep the
  `Загружаю` / `Распознаю` / `Определяю` / `Расшифровка готова` prefixes, or the stage falls back to the last known one.
  Never show a percentage when it is `null` and never invent an ETA.
- `jobId` (new per job), `jobSource` (display name of the picked file, once read), `jobError`.
- `jobError.category`: `SourceUnreadable, FormatUnsupported, SourceMalformed, SourceTooLong,
  ModelsUnavailable, OutOfMemory, Storage, Interrupted, Internal`. `jobError.message` is the Russian
  sentence to show; it never contains transcript text.
- `textStage`: counts and time of the text model for the last completed job (see below).

### Picker wiring (Petya; `MainActivity.kt` and `GlassUi.kt` are not edited by this change)

```kotlin
val importAudio = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
    conversation.importAudio(uri)          // null when the picker is cancelled
}
// button: importAudio.launch(arrayOf("audio/*"))   // disable while state.busy
// cancel button while state.processingAudio: conversation.cancelProcessing()
```

The descriptor is opened when the job starts, so the picker's temporary read permission only has to
last that long. The JSON export launcher already exists; `exportTranscript` now accepts its `Uri?`.
The existing export button label says "Участники и время — JSON"; it now writes the competition file.

## Integrated Petya branch — 7 November, 14:20 UTC

`claude/improve-app-peter` integrates this API with the existing picker, source
metadata and watch controls. A random job ID remains separate from the stable
URI-derived imported-conversation ID: importing the same retained URI replaces
its text without restarting its seven-day expiry. Imports do not enroll public
benchmark voices. History reads/writes and inference share the watch's locks,
so a phone job does not overwrite a newer watch transcript or run native sessions
concurrently with a watch window.

The integrated path uses `PendingImportStore` for both microphone and file jobs.
It commits an atomic, content-free marker before opening input and reports an
interruption once on reopening; it stores no URI/audio and performs no automatic
resume. Cancellation remains visible until native work and cleanup finish. Once
an atomic transcript save commits, a late cancel still reports that saved result.
The UI uses actual stage progress, with an indeterminate bar when unavailable;
percentages describe the current stage, not overall completion or an ETA.

The runtime WAV path uses the positional reader below, including asset offsets
and declared lengths. Compressed input keeps Petya's bounded codec ring and
anti-phase handling; Android's extractor reopens the provider after the header
read. `CompressedAudio.decode` remains covered as Pasha's in-memory adapter but
is not selected by the merged URI runtime. The integrated APK needs fresh CI and
phone evidence; the earlier Pasha-only result does not validate this merge.

## Input formats

| Input | Handling | Limit | Verified |
| --- | --- | --- | --- |
| WAV PCM 8/16/24/32 bit, IEEE float 32, A-law, μ-law, WAVE_FORMAT_EXTENSIBLE | Streamed from the picked file; any 4 to 192 kHz rate; 1 to 8 channels averaged to mono | 2 hours | JVM tests pass in CI (formats, conversion accuracy, windows); the emulator test reads a picked stereo 48 kHz file in place |
| MP3, AAC (ADTS, MP4/M4A), FLAC, Ogg and others the device codecs decode | `MediaExtractor` over the picked file, `MediaCodec` decode, converted through a bounded PCM ring; a backward read outside the ring restarts decoding | 30 minutes; bounded decode windows | Emulator test decodes synthetic MP3 and M4A tones (position within 100 ms, pitch, level); the streaming converter is JVM-tested and equals the WAV conversion. No real speech file, no ARM device |
| RF64, non-seekable providers (pipes, some cloud providers), DRM, corrupt or truncated files | Rejected with a category and a Russian message; never truncated silently | | JVM tests pass in CI; emulator test passes for a missing file, an empty file and a pipe |

Notes: 2 hours and 30 minutes are declared limits, not measured capacity. WAV averages channels, so exactly anti-phase WAV can cancel; the compressed
runtime preserves audible anti-phase input using the existing channel policy.
WAV inference passes read the same offset-bounded descriptor with no spool.
Compressed decoding may reopen the provider and restart its bounded ring. Which MP3/AAC variants a given device decodes is unmeasured.

## What is stored, and when it expires

A job saves one encrypted conversation. For an import, `startedAtMs` is the **import time**: the
original recording date is unknown and is not invented, so the seven-day expiry runs from the import.
Repeated imports of an already retained URI preserve its original expiry.
`sourceName` keeps the display name (encrypted, at most 120 characters). Times inside the result are
relative to the start of the original media, including leading silence. The picked file is never
modified, deleted or copied. `AudioImporter`, which copied audio into app storage, is removed, and
`AudioImportPolicyTest` fails if the reader files gain any write path.

## Competition JSON

`CompetitionJsonExporter` writes exactly `{"transcript":[{"speaker_id","start_ms","end_ms","text"}]}`.

- `speaker_id`: the stable anonymous ID (`speaker_1`, ...), never the editable name, never blank.
- `start_ms`, `end_ms`: integers, `0 <= start_ms < end_ms <= duration`. Approximate CTC times outside the
  range are clamped, not dropped. Sorted by start, then end, then input order. Overlap is kept.
- `text`: the recognizer's words (`TranscriptSegment.recognizedText`), without the text model's
  punctuation or capitals. Reason: the brief scores WER and normalization by the jury is unconfirmed.
  The interface and the TXT export show the presented text. Changing this is one line in the exporter.
- Inferred attribution and every other internal field stay out of the file.
- Silence gives `{"transcript":[]}`. A phrase with only whitespace is not exported.
- One entry per stored phrase (same-speaker run, split at pauses over 0.7 s or spans over 20 s). The
  interface timeline merges phrases across pauses; the export does not.

Independent expected example (check it with `load_transcript` in `tools/score_competition_json.py`):

```json
{"transcript":[{"speaker_id":"speaker_1","start_ms":120,"end_ms":2400,"text":"здравствуйте это поддержка"},{"speaker_id":"speaker_2","start_ms":2650,"end_ms":3900,"text":"добрый день у меня вопрос"}]}
```

A write failure leaves the stored transcript unchanged and the export can be repeated. The chosen file
is opened for truncation by the system picker's contract, so a failure after opening can leave that new
file empty.

## Local text model (Timofey: packaging; Andrey: behaviour)

`TextStages.run` runs after the acoustic lease is released. It reads the files from
`filesDir/models/text-model/` (`TextModelFiles.MODEL`, `TextModelFiles.TOKENIZER`), checks only their
sizes, and expects the packaging step to have verified the hashes at extraction. Every outcome is
reported in `textStage` and appended to the finished status line:

| Status | Meaning |
| --- | --- |
| `COMPLETED` | The model ran. `phrases`, `fallbacks` (phrases left unedited) and `elapsedMs` are exact counts; no text is logged |
| `UNAVAILABLE` | Files missing or the wrong size: **the stage did not run.** Never counted as compliance |
| `FAILED` | The model failed to load, or failed for every phrase. Recognized words are shown |
| `SKIPPED` | No speech |

**Open on this integration (blocker for the local-LLM requirement):** nothing extracts the files yet, so on a CI-built APK the
status will read `UNAVAILABLE` until packaging lands. The stage has not run on Android or ARM.

## Lifecycle outcomes to test (Petya)

| Action | Expected |
| --- | --- |
| Rotate during a job | Job continues; the screen shows the same job |
| Home, wait, return | Job continues or, if the system killed the process, see the next row |
| Force-stop during a job, reopen | No transcript. The status says the previous processing was interrupted and asks to select the file again. No automatic recovery is claimed |
| Cancel, then import again | Second import is ignored until the status shows `Обработка отменена`, then starts a new job with a new `jobId` |
| Tap import twice quickly | One job |
| Cancel the picker | Nothing changes |
| Pick a deleted or revoked file | `SourceUnreadable`, message to select again |
| Pick a non-audio or corrupt file | `FormatUnsupported` or `SourceMalformed` |
| Export with a failing provider | Error status; stored text unchanged; repeat works |

After each case confirm the picked file is unchanged (size and modified time) and that no new audio
file appeared under the app's `files/` or `cache/` directories.

## Not covered

No foreground service: a job lives with the app process. Process death needs reselecting the file.
The picker and UI states are integrated on Petya's branch. Text-model packaging,
English and call/meeting measurements, and every ARM measurement remain open or
unmeasured. The user will perform phone acceptance later.
