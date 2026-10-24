# Device validation

Run these checks against the compact ARM64 CI APK and models successfully
downloaded from the fixed Ascent catalog before reporting measured behavior.
The prototype encrypts text and confirmed voice templates, filters them by
seven-day source age, and deletes its temporary WAV after processing. Temporary
audio still reaches disk, cleanup after process death waits for the next launch,
and there is no watch app. These trials do not establish the production
no-audio-persistence or watch-capture requirements.

1. Install `ascent.apk` from the short `debug-<8-character commit>.zip` artifact.
   Open the app and observe automatic model progress. Once models are ready,
   enable airplane mode, start a short Russian conversation with two speakers,
   and press stop. Confirm processing starts without another action and produces
   a connected timeline with speaker names and source-relative timestamps.
   Verify the temporary audio is removed after completion.
2. Repeat with one speaker, a silent recording, short replies, background noise,
   a third speaker entering later, and overlapping speech. Record transcription
   errors and speaker-label mistakes; the current clustering threshold is an
   initial default, not a calibrated accuracy guarantee.
3. Run a longer listening session. Measure wall time, peak process memory, battery use
   and thermal behaviour. ASR and speaker model sessions are sequential, but the
   Maven ASR runtime and sherpa's separately named bundled C runtime may both
   remain loaded. Measure their combined process memory. WAV windows are read
   from disk rather than loading the full recording into RAM.
4. Deny/revoke microphone access, background the app while listening, and rotate
   the screen while processing. Capture must stop when leaving the foreground;
   temporary audio must be removed on success, failure or cancellation. Kill the
   process during capture/processing, reopen it, and verify orphan cleanup with
   no replay/retry recording exposed. Check the 30-minute stop limit separately.
5. Create several conversations, collapse and reopen them, restart the app, and
   export one conversation as TXT/JSON. Confirm earlier conversations remain
   distinct, names apply consistently within each one, and exported `speakerId`,
   `startMs`, `endMs` and `text` fields match that conversation. Explicit exports
   are user-managed files and are outside app retention.
6. Check the yellow glass interface with large font scaling and TalkBack. All
   start/stop, conversation expansion, participant names and export controls must
   remain accessible. Check the square-period `Ascent.` wordmark, separate status
   element and status-only model display. The visual glass material uses
   translucent layers and gradients, not a
   claim of Apple's native rendering implementation.

7. Interrupt networking and kill/restart the process during a model download.
   Confirm completed chunks are rechecked and reused; only unfinished or damaged
   chunks transfer again. Background the app and observe scheduled continuation.
   Force-stop it and verify resumption after reopening, allowing for Android
   scheduling delays. Exercise insufficient storage and an unavailable catalog;
   status must remain understandable without switching servers or sending audio.
   Invalid catalog, index, compressed/raw chunk or final payload hashes must not
   replace working models. Publish a compatible revision through CI, check its
   automatic activation, and update during inference to verify the old leased
   directory remains available until processing finishes.
8. Exercise the R8-optimized build's first launch, recording, model download and
   transcription paths on a phone. Emulator native loading does not prove all
   reflection/JNI paths remain usable after shrinking. Record compressed APK and
   installed storage separately; native libraries are extracted on installation.
9. Name a sufficiently clear speaker before leaving the current app process.
   Start another conversation with that speaker and a different voice. Check
   strong recurring matches, unknown cases, similar voices and conflicting
   matches against independently annotated references. Confirm ambiguous guesses
   do not update templates. Naming a conversation after process restart changes
   its text label but cannot recover an unconfirmed voice embedding.
10. Exercise retention with controlled capture timestamps through CI fixtures and
    a device run. Expired conversations must disappear before display/export;
    their voice-template contributions must stop matching. Renaming must not
    reset capture age. Inspect ciphertext and disabled backup behavior, including
    a modified ciphertext, missing key and legacy transcript migration. Do not
    describe periodic pruning as exact-time cleanup while the process is stopped.

Record the phone model, Android version, available RAM, model-manifest SHA-256 and
APK commit for each result. Neither the JVM tests nor export-parity checks prove
mobile recognition speed or conversation diarization accuracy.
