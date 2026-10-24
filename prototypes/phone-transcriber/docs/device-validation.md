# Device validation

Run these checks against the compact ARM64 CI APK and models successfully
downloaded from the fixed Ascent catalog before
reporting the prototype's measured behavior. These trials do not establish the
production privacy or watch-capture requirements: the prototype persists WAV
audio and plaintext text, has no automatic expiry, and has no watch app.

1. Install `ascent.apk` from the short `debug-<8-character commit>.zip` artifact.
   Download the models inside the app, enable airplane mode, and record a short Russian
   conversation with two speakers. Confirm text appears, repeated turns keep the
   same speaker label, and the displayed times refer to the source recording.
2. Repeat with one speaker, a silent recording, short replies, background noise,
   a third speaker entering later, and overlapping speech. Record transcription
   errors and speaker-label mistakes; the current clustering threshold is an
   initial default, not a calibrated accuracy guarantee.
3. Run a longer recording. Measure wall time, peak process memory, battery use
   and thermal behaviour. ASR and speaker model sessions are sequential, but the
   Maven ASR runtime and sherpa's separately named bundled C runtime may both
   remain loaded. Measure their combined process memory. WAV windows are read
   from disk rather than loading the full recording into RAM.
4. Deny/revoke microphone access, background the app while recording, rotate the
   screen while processing, and cancel or interrupt a model download. Verify that
   the app releases the microphone, keeps a valid completed recording, and
   retains the previous working models after a failed or cancelled download.
5. Export TXT/JSON, reopen the app, rename a participant, and delete the current
   recording. Check that speaker names apply consistently and the exported
   `speakerId`, `startMs`, `endMs` and `text` fields match the displayed transcript.
6. Check the yellow glass interface with large font scaling and TalkBack. All
   record/stop/audio-import/model-download/cancel controls must remain legible and accessible. The visual
   glass material is implemented with translucent layers and gradients, not a
   claim of Apple's native rendering implementation.

7. Check download byte/percentage progress, cancellation and retry, a network
   interruption, insufficient storage, and an unavailable catalog. Errors must be
   visible without falling back to another server or sending audio anywhere.
   Check that invalid manifest or payload hashes cannot replace working models.
8. Exercise the R8-optimized build's first launch, recording, model download and
   transcription paths on a phone. Emulator native loading does not prove all
   reflection/JNI paths remain usable after shrinking. Record compressed APK and
   installed storage separately; native libraries are extracted on installation.

Record the phone model, Android version, available RAM, model-manifest SHA-256 and
APK commit for each result. Neither the JVM tests nor export-parity checks prove
mobile recognition speed or conversation diarization accuracy.
