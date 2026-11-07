# Competition architecture

Source baseline: [`25ae8eee`](https://github.com/pmixay/Ascent/tree/25ae8eeeef2f96e9784c886a8c96ed4bce11c32e).
This describes implemented code, not completed ARM acceptance. Exact CI/APK
identity and evidence boundaries are in [SUBMISSION.md](SUBMISSION.md) and
[TEST_RESULTS.md](TEST_RESULTS.md).

## Data flow

```text
Android document picker                           Phone microphone (prototype)
  -> read selected URI in place                    -> temporary WAV
  -> AudioSources -> bounded mono 16 kHz PCM windows
  -> one serialized ConversationViewModel job + three-group model lease
  -> chosen recognizer:
       Russian: GigaAM CTC / AsrGain / CTC timed words
       Other languages: Parakeet TDT / token-to-word timing
       Auto: early GigaAM script probe, full Parakeet restart if selected
  -> quiet-seam chunking / overlap-aware word stitching
  -> ERes2NetV2 embeddings / speaker clustering / inferred attribution
  -> TranscriptSegmenter -> source-relative timed phrases
  -> acoustic sessions and audio source close
  -> Qwen2.5 0.5B punctuation/case -> independent word-preservation validator
  -> encrypted conversation history
  -> UI / TXT presentation or exact raw-word competition JSON
  -> source/model handles close, temporary microphone WAV cleanup
```

Audio is not sent to an inference server. No prewritten transcripts are used by
the application. Public reference transcripts belong only to evaluation tooling.

## Application boundaries

Paths below are relative to
[`android/app/src/main/java/com/pmixay/ascent`](../prototypes/phone-transcriber/android/app/src/main/java/com/pmixay/ascent/).

| Component | Responsibility |
| --- | --- |
| `MainActivity.kt`, `GlassUi.kt` | Russian Compose interface, document picker/save picker, permission handoff, language selector, progress/cancel, readable turns and volatile timing panel. |
| `ConversationViewModel.kt`, `ProcessingJob.kt`, `PendingImport.kt` | One job at a time, frozen recognition mode, stale-callback protection, cancellation, non-cancellable save/cleanup and content-free process-interruption marker. |
| `AudioSource.kt`, `WavAudio.kt`, `CodecAudioWindowSource.kt` | Read user-owned URI without a persistent audio copy; seekable WAV conversion or bounded Android codec ring with original time mapping. Unsupported providers/formats fail explicitly. |
| `TranscriptionEngine.kt`, `AsrChunking.kt`, `CtcDecoder.kt`, `CtcChunkStitcher.kt`, `MultilingualRecognizer.kt`, `LanguageProbe.kt` | Local recognition, bounded windows, seam reconciliation, approximate timed words and heuristic automatic language mode. |
| `SpeakerSpeechSupport.kt`, `SpeakerClusterer.kt`, `SpeakerAttribution.kt`, `TranscriptSegmenter.kt` | Speech contexts, voice embeddings, clustering, participant assignment and phrase grouping. Inferred assignments are distinguished internally and excluded from enrollment evidence. |
| `OnnxTextModel.kt`, `QwenTokenizer.kt`, `TextPunctuator.kt`, `TextStage.kt` | Real local LLM calls for punctuation/case, raw-word preservation, reported fallback/timeout status and sequential resource release. |
| `ConversationStore.kt`, `VoiceProfileStore.kt` | Android Keystore-backed AES-256-GCM, fresh nonces, authenticated filenames and atomic replacement. Source-based seven-day prototype retention is conversation-granular. |
| `CompetitionJsonExporter.kt` | Dedicated `transcript` serializer, stable anonymous IDs, integer milliseconds, raw recognized words, no internal metadata and empty-array silence export. Internal history serialization remains separate. |
| `ProcessingMeasurements.kt` | Exclusive elapsed stage counters and completed RTF, held in memory rather than history, logs or jury JSON. |

## Models and runtimes

| Stage | Bundled model | Licence | Runtime |
| --- | --- | --- | --- |
| Russian/CIS ASR | GigaAM Multilingual CTC 220M, float32 | MIT | ONNX Runtime Android 1.31.0 |
| English/multilingual ASR | NVIDIA Parakeet TDT 0.6B v3, int8 | CC-BY-4.0; upstream attribution is bundled and displayed | sherpa-onnx 2.0.0 / isolated ONNX Runtime 1.28.2 |
| Speaker embeddings | 3D-Speaker ERes2NetV2 | Apache-2.0 | sherpa-onnx 2.0.0 with isolated ONNX Runtime 1.28.2; also used for Parakeet |
| Text LLM | Qwen2.5 0.5B Instruct, int8 ONNX plus tokenizer | Apache-2.0 | ONNX Runtime Android 1.31.0 |

Parakeet uses the same sherpa-onnx runtime listed for speaker embeddings.
Exact source revisions, sizes and hashes are in the
[embedded pins](../prototypes/phone-transcriber/config/embedded-models.json),
[configuration map](../config/README.md) and [model report](hackathon/model-report.md).
No weights were trained or fine-tuned for this submission.

The app defaults to remembered `Авто`; the low-level engine's omitted-mode default
remains GigaAM. Fixed multilingual mode refuses missing assets instead of silently
switching. Auto is an early script heuristic, not continuous language detection.

### Meaningful local LLM task

Qwen scores punctuation/capitalization choices over the recognized words, without
free-form rewriting. Input is split into 64-word blocks and model calls use
24-token blocks. A separate lexical validator preserves words, numbers, negation
and order; speaker IDs and times are copied unchanged. The UI/TXT use presentation
text, while competition JSON uses `recognizedText` (raw ASR words).

The soft post-load budget is `max(30 seconds, 50% of media duration)`, checked
between phrases; model loading and one in-flight phrase can exceed it. Failures,
unavailable assets and timeout fallbacks are reported. A completed acoustic job
with a failed/unavailable text stage does not establish local-LLM acceptance.

## Offline packaging and storage

The competition candidate is `com.pmixay.ascent.offline`, ARM64, Android 12+.
`EmbeddedModels` / `EmbeddedModelInstaller` verify the pinned schema-2 bundle,
stream assets to staging, check space/hashes, atomically promote immutable model
directories and activate only a complete three-group selection. `ModelSelection`
leases acoustic, text and multilingual directories for the whole job; active
revisions are protected during replacement/recovery. ONNX and required Parakeet
text assets remain uncompressed in the APK. Licences/notices travel with assets.

The offline manifest removes Internet/network-state permissions and disables
model downloads. First-use readiness depends on local extraction, not a catalog.
The separate compact `com.pmixay.ascent` APK contains no weights; its fixed HTTPS
model downloader and `server/` static publisher are not computation backends and
do not satisfy the competition's embedded-model requirement.

Imported audio is read, never overwritten/deleted or copied into app storage by
the import path. Microphone capture deliberately retains the documented prototype
temporary-WAV exception; cleanup can wait until next launch after process death.
Do not describe that as production zero persistence. Backup is disabled.

## Lifecycle and evidence limits

Jobs belong to the ViewModel, not a transcription foreground service. Rotation
retains that ViewModel; ordinary backgrounding does not deliberately cancel an
import, but Android can kill the process. On reopen an interruption marker prompts
reselection; committed history remains. No audio recovery or native-call hard
deadline is promised. Full lifecycle/device behavior remains unverified beyond
the bounded synthetic x86_64 CI cases. See [limitations](LIMITATIONS.md).

## Separate future product track

The repository also contains a Wear OS 6.0 preview: watch microphone -> bounded
AES-GCM frames -> bonded secure Bluetooth RFCOMM -> phone inference -> text return.
Its shared protocol is under `android/shared/`; phone and watch source are not
proof of paired-device operation. It is not required to use the competition APK.
Rewind, sound recognition, grounded profiles/locations and optional remote text AI
are future product requirements, not shipped competition claims.
