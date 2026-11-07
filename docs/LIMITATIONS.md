# Known limits and release verification gaps

These are source-inspected prototype limits and unmeasured competition risks. Device behavior may differ. Update this page from exact build and device evidence before submission.

## Source-inspected prototype behavior

- The compact APK contains no model weights. It downloads verified chunks from a fixed HTTPS catalog, so a fresh offline first launch is not the required verification release.
- The new picker path accepts seekable WAV PCM 8/16/24/32, float32, A-law/μ-law, 1–8 channels and input rates from 4 to 192 kHz. It uses Android's media decoder for other audio tracks, but MP3/AAC/M4A and other container support depends on the device/provider and is still unverified. WAV above two hours, compressed input above 30 minutes or a missing declared decoder duration is rejected; unsupported/nonseekable providers may fail. The old, disk-copying `AudioImporter` has been removed from this worktree; source inspection alone does not establish device/provider behavior.
- The new competition JSON serializer writes the `transcript` contract and handles an empty conversation in source. Its Android export and real device files still need independent validation.
- Word boundaries are derived from CTC frames and remain approximate. Speaker attribution can be inferred when acoustic evidence is weak. Unknown or inferred labels are not confirmed identity.
- Microphone capture and the compressed URI decoder cap audio at 30 minutes; the integrated WAV adapter caps at two hours. This may change with the source adapter; verify the final declared maximum and show a clear over-limit error.
- The phone prototype writes a temporary microphone WAV. A normal completion attempts deletion and next launch removes abandoned audio, but process death or stopped-app retention timing cannot establish production no-persistence guarantees.
- The new Wear OS 6.0 companion is source-only until CI builds and a Galaxy Watch7 SM-L305F trial. It requires a bonded phone and its direct Bluetooth receiver service. The connect/Ready exchange has a 10-second timeout. Connection loss stops watch capture; dropped 250 ms frames are reported and never replayed. The source uses one waiting encrypted frame, a 2.5-second age limit and bounded phone processing windows. The phone currently assigns speaker IDs independently for each processed watch window, so one person's label is not stable across a long session. Frames arriving while phone inference is busy may be rejected and shown as gaps. Packet loss, power use, microphone foreground behavior and phone/watch timing have not been measured on devices.

## Measurements needed for a credible release

- First-install offline activation, exact model set and hashes, cold extraction time, free-space requirement, APK/installed size and stable signing identity.
- Russian and English WER, call-like/meeting-like and similar-voice speaker errors, short-turn behavior, overlap limitations and independently checked timing error.
- Full processing RTF including the local text model, cold/warm runtime, peak PSS/RSS, long-input behavior and repeated-job resource trend on ARM64.
- Picker cancellation, denied microphone permission, unreadable/invalid input, low storage, export-provider failure and source preservation.
- Rotation, background/return, screen-off, process death, cancellation latency and interrupted model install recovery.
- Large-font/TalkBack usability and the final delivery URL/video matching the exact APK hash.
- Galaxy Watch7 screen-off capture, Bluetooth drop/reconnect, pause/stop acknowledgement, clock/sample offset, gap display, latest text return and battery/thermal use with the Samsung Android 17 phone.

The independent matrix in [QA_JUDGE_SCRIPT.md](QA_JUDGE_SCRIPT.md) and register in [TEST_RESULTS.md](TEST_RESULTS.md) determine which limits are verified versus still open. Do not turn the old constructed Dialogs numbers into natural-conversation, ARM or jury results.

