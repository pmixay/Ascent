# Application pipeline: import, job, result, JSON

Status, 7 November 2026: **pushed source only.** Nothing here has been compiled or run by CI or on a
device by its author, and no inspected CI run covers it. Every "Verified" cell below is empty on
purpose. Source files are in `prototypes/phone-transcriber/android/app/src/main/java/com/pmixay/ascent/`.

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

## Input formats

| Input | Handling | Limit | Verified |
| --- | --- | --- | --- |
| WAV PCM 8/16/24/32 bit, IEEE float 32, A-law, μ-law, WAVE_FORMAT_EXTENSIBLE | Streamed from the picked file; any 4 to 192 kHz rate; 1 to 8 channels averaged to mono | 2 hours | JVM tests written, not run |
| MP3, AAC (ADTS, MP4/M4A), FLAC, Ogg and others the device codecs decode | `MediaExtractor` over the picked file, `MediaCodec` decode, converted as it arrives, kept in memory as 16-bit PCM | 30 minutes (about 58 MB) | not run anywhere |
| RF64, non-seekable providers (pipes, some cloud providers), DRM, corrupt or truncated files | Rejected with a category and a Russian message; never truncated silently | | JVM and instrumentation tests written, not run |

Notes: 2 hours and 30 minutes are declared limits, not measured capacity. Averaging channels cancels a
signal that is exactly anti-phase between channels; that case is not handled. The two inference
passes read the same open descriptor at arbitrary positions, so no second read of the provider and no
spool is needed. Which MP3/AAC variants a given device decodes is unmeasured.

## What is stored, and when it expires

A job saves one encrypted conversation. For an import, `startedAtMs` is the **import time**: the
original recording date is unknown and is not invented, so the seven-day expiry runs from the import.
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

**Open (blocker for the local-LLM requirement):** nothing extracts the files yet, so on a CI-built APK the
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
The text-model packaging, the picker button and the UI states above, English and call/meeting
measurements, and every ARM measurement are open work of other owners or unmeasured.
