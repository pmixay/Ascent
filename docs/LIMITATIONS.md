# Known limits and release verification gaps

Latest evidence: the exact candidate completed one Russian constructed public
two-reader import offline on ARM64 Android 16 emulator, including Qwen 6/6 with
zero fallback and actual validated JSON save. [Video and scoped evidence](../artifacts/demo/README.md).
Earlier unmeasured-ARM/offline-demo statements below are superseded for this one
scenario only. It does not establish physical-phone, English/Parakeet, three-file,
natural quality, long-run, lifecycle or final signing/release acceptance.

These are source-inspected prototype limits and unmeasured competition risks. Device behavior may differ. Update this page from exact build and device evidence before submission.

Current documented source: `25ae8eee`. [Submission identity](SUBMISSION.md) records
actual green CI, raw APK/model hashes and the remaining handoff; documentation
updates do not manufacture a new tested build. The candidate uses internal debug
signing, has a user-supplied Drive APK link pending remote hash verification, lacks
an identified offline-demo video, and has no recorded full spoken
ARM execution. CI attempt 2 now provides both temporary artifacts and passed the
actual full evidence collector. Attempt 1's removed artifact is not the current
download; ephemeral debug signing changed hashes despite the same source/version.
The supplied repository video shows compact/prototype microphone transcription
and explicitly unavailable text-model assets. It is not exact-build evidence of
offline imported-audio/local-LLM completion; its APK/device identity is unknown.

## Source-inspected prototype behavior

- The schema-2 offline baseline includes acoustic/text/Parakeet groups; model payload alone is 2,147,011,731 bytes plus metadata. APK install copies, extraction reserve, physical allocation and RAM are separate. No exact phone storage or ARM performance claim is available.
- Phone import/capture defaults to remembered `Авто`; its early probe may choose the wrong recognizer, especially for mixed or changing languages. Use `Русский` / `Другие языки` for explicit comparison. Compact has no Parakeet assets and refuses fixed multilingual; its Auto stays on GigaAM. The actual-used mode and timings apply only to the last job in memory, not every saved conversation.
- Timing total includes queue, model initialization, text, save and cleanup, excludes extraction/capture, and reports RTF only for completed positive-duration jobs. RTF does not prove successful local text processing, quality, memory use or thermal stability.
- The local Qwen stage is bundled and called sequentially, not missing integration.
  Its soft post-load budget is `max(30 s, mediaDuration / 2)`, checked between
  phrases. Model loading and an in-flight native phrase can overrun it. Failed,
  unavailable and timed-out edits retain raw words and are reported; persistent
  text-stage failure remains a local-LLM compliance defect, not a successful pass.
  The punctuation instruction was studied on Russian; English punctuation quality
  is unmeasured even though the recognizer and text adapter accept English words.

- The compact APK contains no model weights. It downloads verified chunks from a fixed HTTPS catalog, so a fresh offline first launch is not the required verification release.
- The new picker path accepts seekable WAV PCM 8/16/24/32, float32, A-law/μ-law, 1–8 channels and input rates from 4 to 192 kHz. It uses Android's media decoder for other audio tracks, but MP3/AAC/M4A and other container support depends on the device/provider and is still unverified. WAV above two hours, compressed input above 30 minutes or a missing declared decoder duration is rejected; unsupported/nonseekable providers may fail. The old, disk-copying `AudioImporter` has been removed from this worktree; source inspection alone does not establish device/provider behavior.
- Competition JSON writes the `transcript` contract, raw recognized words and
  anonymous IDs, with approximate times clamped to source bounds. Source/JVM tests
  and the actual x86_64 silent ViewModel export passed. That is not validation of
  a real spoken device export or correct recognized content/speakers/times.
- Word boundaries are derived from CTC frames and remain approximate. Speaker attribution can be inferred when acoustic evidence is weak. Unknown or inferred labels are not confirmed identity.
- Microphone capture and the compressed URI decoder cap audio at 30 minutes; the integrated WAV adapter caps at two hours. This may change with the source adapter; verify the final declared maximum and show a clear over-limit error.
- The phone prototype writes a temporary microphone WAV. A normal completion attempts deletion and next launch removes abandoned audio, but process death or stopped-app retention timing cannot establish production no-persistence guarantees.
- The new Wear OS 6.0 companion is source-only until CI builds and a Galaxy Watch7 SM-L305F trial. It requires a bonded phone and its direct Bluetooth receiver service. The connect/Ready exchange has a 10-second timeout. Connection loss stops watch capture; dropped 250 ms frames are reported and never replayed. The source uses one waiting encrypted frame, a 2.5-second age limit and bounded phone processing windows. The phone currently assigns speaker IDs independently for each processed watch window, so one person's label is not stable across a long session. Frames arriving while phone inference is busy may be rejected and shown as gaps. Packet loss, power use, microphone foreground behavior and phone/watch timing have not been measured on devices.

## Remaining internal acceptance and hardening (not all literal jury prerequisites)

The brief requires a working app, own test results and a real video; it does not
prescribe this entire expanded matrix, two phones, memory measurements or a new
two-hour corpus. The following are existing project acceptance/hardening items,
not additional required jury report fields. They remain unverified, not waived.

- First-install offline activation, exact model set and hashes, cold extraction time, free-space requirement, APK/installed size and stable signing identity.
- Russian and English WER, call-like/meeting-like and similar-voice speaker errors, short-turn behavior, overlap limitations and independently checked timing error.
- Full processing RTF including the local text model, cold/warm runtime, peak PSS/RSS, long-input behavior and repeated-job resource trend on ARM64.
- Picker cancellation, denied microphone permission, unreadable/invalid input, low storage, export-provider failure and source preservation.
- Rotation, background/return, screen-off, process death, cancellation latency and interrupted model install recovery.
- Large-font/TalkBack usability and the final delivery URL/video matching the exact APK hash.
- Galaxy Watch7 screen-off capture, Bluetooth drop/reconnect, pause/stop acknowledgement, clock/sample offset, gap display, latest text return and battery/thermal use with the Samsung Android 17 phone.

The independent matrix in [QA_JUDGE_SCRIPT.md](QA_JUDGE_SCRIPT.md) and register in [TEST_RESULTS.md](TEST_RESULTS.md) determine which limits are verified versus still open. Do not turn the old constructed Dialogs numbers into natural-conversation, ARM or jury results.
