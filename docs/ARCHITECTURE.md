# Phone and watch architecture and competition integration

This is a source-level map for reviewers. It describes the current module and identifies work still required for the verification release. It is not a claim that the offline judge flow has passed.

```text
Android document picker / microphone
  -> MainActivity + ConversationViewModel
  -> audio source / bounded PCM windows
  -> GigaAM CTC speech recognition + CTC word timing
  -> speech contexts / ERes2NetV2 embeddings
  -> speaker clustering and word attribution
  -> timed turns and encrypted local conversation history
  -> explicit JSON export via Android document provider

model manifest -> verified file selection / inference lease
fixed model catalog -> checked chunk download (prototype path)
GitHub Android CI -> optimized ARM64 APK + x86_64 instrumentation

Galaxy Watch7 microphone -> 250 ms PCM frames -> AES-GCM encrypted frames
  -> direct bonded-device secure Bluetooth RFCOMM -> phone WatchCompanionService
  -> bounded encrypted 8-second windows -> local phone inference -> text result
  -> encrypted phone history + latest text sent to watch
```

The implementation lives under [the Android module](../prototypes/phone-transcriber/android/). `MainActivity.kt` owns Android picker and permission handoff; `GlassUi.kt` renders the Russian interface; `ConversationViewModel.kt` holds conversation state, processing orchestration and save/export operations. `AudioWindowSource.kt` selects the source path: `PcmWave.kt` reads seekable PCM WAV and resamples to mono 16 kHz in bounded windows; `CodecAudioWindowSource.kt` uses Android `MediaExtractor`/`MediaCodec` for other device-decodable audio tracks, with a bounded PCM ring and source timestamp mapping. `TranscriptionEngine.kt`, `CtcDecoder.kt`, `SpeakerSpeechSupport.kt`, `SpeakerClusterer.kt` and `SpeakerAttribution.kt` perform local recognition and diarization. `CompetitionTranscriptJson.kt` serializes the separate jury export. `ConversationStore.kt` and `VoiceProfileStore.kt` protect retained text/voice metadata with Android Keystore-backed AES-GCM; the prototype's retention filter is conversation-granular, based on source start time. `ModelDownloads.kt`, `ModelDownloadService.kt` and `ModelPackImporter.kt` manage the fixed catalog and verified installed model revision. `server/` publishes models; it is not a recognition backend.

The baseline source model identifiers are `gigaam-multilingual-ctc-220m-2026-06` and `3dspeaker-eres2netv2-2024` in `prototypes/phone-transcriber/tools/model_pack.py`; ASR uses Android ONNX Runtime and speaker embeddings use a separately isolated sherpa-onnx runtime. No local text LLM is selected in the audited source. The planned constrained text stage should restore punctuation/case while rejecting lexical, speaker-ID and timing changes; this remains a mandatory integration/evidence gate. Model weights, tokenizer, hashes, license notices and native dependencies must be pinned and bundled in the verification APK. [HACKATHON_PLAN.md](HACKATHON_PLAN.md) records the contract.

The current compact prototype expects the model pack from the fixed HTTPS catalog. A fresh offline first launch of that APK cannot serve as verification-build evidence. At the audited baseline, the unused `AudioImporter` copied selected WAV bytes to app-owned files. The new picker path reads a user-owned URI through `AudioWindowSource` without creating an app recording archive for that import; device/provider behavior still requires inspection. The microphone prototype creates a temporary WAV and deletes it after processing or next launch. This is a documented prototype exception and does not establish the older product's no-persistence requirement.

The competition path must run speech, speaker separation and text processing on Android without calling a backend. The microphone/watch-first production plan in [roadmap.md](../roadmap.md) is a separate future product track. Review an installed CI APK and real exported files before marking any competition gate complete.

The new watch module under [android/wear](../prototypes/phone-transcriber/android/wear/) is a separate Wear OS 6.0 companion. `MainActivity.kt` presents Russian capture, pause, stop, connection and gap controls. `WatchCaptureService.kt` starts a visible microphone foreground service from the watch UI, connects directly to a bonded phone over secure Bluetooth RFCOMM, and exchanges framed packets defined in `android/shared/.../CompanionWire.kt`. The initial connect/Ready exchange is bounded by a 10-second socket timeout. The shared wire code encrypts each 250 ms PCM16/16 kHz frame with AES-GCM using a fresh per-session key and sequence-based nonce. Within each continuous capture run, the watch anchors the first frame to the monotonic clock and advances later frame offsets by captured samples; pause/resume starts a new anchor, and dropped frames still consume their source-time offset. The watch keeps one queued encrypted frame with a 2.5-second age limit, drops unavailable audio, and sends explicit gap/state packets. The phone service accepts authenticated frames, keeps bounded encrypted windows in memory, runs local inference and returns text. The watch source is separate from file import. This is a source-level description; watch capture, routing, power and interruption behavior still need the Galaxy Watch7 / Samsung Android 17 trial.

