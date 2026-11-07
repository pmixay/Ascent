# Ascent engineering contract

## Current task and scoped precedence — 6 November 2026

The current user supplied the Neuro-stenographer competition specification and
requested an audit and four-person delivery plan. Read
[docs/HACKATHON_AUDIT.md](docs/HACKATHON_AUDIT.md) and
[docs/HACKATHON_PLAN.md](docs/HACKATHON_PLAN.md) for this work. The competition APK
must contain the complete usable offline model set, expose audio import and export
the exact `transcript` / `speaker_id` / `start_ms` / `end_ms` / `text` JSON contract.
Resolve the explicit local-LLM requirement; ASR alone must not be relabeled as a
text LLM without organizer clarification. All application processing, including
text processing, stays on Android for the competition release.

For that scope these newer requirements take precedence over older instructions
below forbidding APK model weights, exposing only microphone capture, or putting
watch delivery first. The older optional remote-AI plan is outside this release.
The no-local-build rule, existing workflow/check integrity, encrypted storage and
honest evidence reporting remain in force. Imported user-owned audio is a source
to read, not permission for an app recording archive or deletion of the source.
No additional app-owned audio-persistence exception is introduced by this plan.
The older watch-first production requirements remain a separate future track.
The plan is not implementation evidence. Reconcile its identified CI-inference
policy inconsistency explicitly before changing inference behavior or checks.

Read [README.md](README.md) for the product and [roadmap.md](roadmap.md) for delivery order. Follow the latest user requirements and keep all three documents consistent when changing the plan. Planned capabilities are not completed implementation. Write project documentation in English; the app's user-facing language remains Russian.

## Review prototype scope

The current user request publishes the phone transcription prototype for review under `prototypes/phone-transcriber/`, with the corrected Ascent name. Stopping listening automatically transcribes a temporary WAV and deletes it after processing or failure; abandoned audio is removed on the next launch after process death. Multiple conversations and confirmed voice templates use encrypted local storage and source-based seven-day retention filtering. There is no Wear OS app. Temporary audio still reaches disk, and expiry cleanup cannot run while the app process is stopped, so this does not establish the production no-persistence or full retention guarantees. Preserve the production requirements below and do not mark their acceptance work complete or extend the prototype exception into production.

The user authorizes downloading at least two hours of labeled public multi-speaker
speech for evaluation. Licensed public benchmark audio may be cached on the development machine or in GitHub CI;
never substitute application recordings or upload evaluation files as artifacts.
Freeze source selection and report unique source duration, annotation quality,
transcription errors, speaker errors and inferred assignments. Reassembled dialogue
turns must be identified as constructed sequences, not original continuous recordings.
Run public-corpus model inference on the development machine for algorithm improvement; do not run model inference in GitHub CI. Keep Android builds and executable application tests in GitHub CI under the build-only rule below. The CI evaluation job may score a previously completed inference cache.
Use measured development errors to improve the algorithms, then validate the
selected changes without adjusting them to validation scores. Before baseline
inference, freeze `masha_dima_part1` as the Dialogs development subset and reserve
`masha_sveta_part1`, `masha_sveta_part2`, and `masha_dima_part2` for validation.
This split separates recordings, not all speakers: M appears in both subsets,
D appears in development and validation, and S appears only in validation.
Keep source selection, model weights and existing six-reader regression gates
fixed; report development and validation results separately. Do not enroll the
benchmark actors into application voice memory.

## Hackathon verification build

The jury's [task brief](docs/hackathon/hackathon-brief-2026-11.pdf) and
[written clarifications](docs/hackathon/jury-clarifications.md) define a separate
deliverable: an Android APK that experts install and score automatically. It
applies to the phone application only. The scoped precedence at the top of this file
and the [delivery plan](docs/HACKATHON_PLAN.md) govern which older rules it
overrides; this section states the jury-specific requirements and the exceptions
below. Outside this scope sections 1 to 6 are unchanged. It does not make any
production milestone complete and does not extend the prototype's temporary-audio
exception to production. Everything below is a requirement, not an implemented
feature, until an inspected CI run or device result says otherwise.

- **Embedded model.** The verification APK contains the speech models so that
  verification needs no network. Source the weights from verified model-export
  output and check their hashes at build time. The compact network APK keeps weights
  outside the APK. The jury accepts either two APKs or one APK whose built-in model
  the user can delete. Downloading a model from the fixed catalog is only a UX
  extra and is not needed for verification. Deleting an extracted embedded model does
  not shrink the installed APK's immutable assets, so do not promise reclaimed space;
  a second lightweight APK is the way to offer a smaller install. Deleting an embedded model is the one
  model control this build may add; archive import, reinstall and server selection
  stay prohibited. Publishing a verification APK means extending the existing
  **Ascent Android CI** workflow with every current check kept, push-only triggers
  unchanged and an inspected run; do not create a replacement workflow.
- **Audio import.** Besides recording from the interface, the app imports a recording
  from device storage for analysis. Read the user-selected content stream and decode
  it in memory. Do not copy the file into app storage, caches or temporary directories,
  and add no playback, save-clip or audio-export action. Import is a prototype and
  hackathon exception to the listening-only interface and is not a production feature.
  Expect recordings that resemble both real-time calls and meetings. The jury says
  test cases differ between these scenarios, so both are a direct scoring criterion.
- **JSON export.** Export an explicit user-managed file whose structure matches the
  brief: a `transcript` array of objects with string `speaker_id`, integer `start_ms`
  and `end_ms` in milliseconds, and string `text`. Every phrase carries a participant
  label. The brief defines no provenance field, so keep inferred attribution internal
  and add no extra keys until the jury confirms that additional fields are tolerated.
  CTC-derived times remain approximate.
- **Offline local processing.** Recognition, diarization and text processing run on
  the device and the core task works without Internet. Do not use cloud APIs, remote
  servers or prepared transcripts, and do not call the application backend or
  OpenRouter from this build. Internet permission stays limited to model catalog
  downloads.
- **Local LLM.** The brief requires a local LLM. None is selected or implemented. Choose
  it from measured on-device evidence, give it a real task in the processing pipeline,
  and never present a placeholder or remote call as satisfying this requirement.
- **Languages.** Russian is the first priority and English the second for speech input.
  English quality is unmeasured; report it separately and keep every Russian gate.
  The interface remains Russian.
- **Target and scoring.** Experts use an ARM emulator, and the delivered APK is ARM64.
  The existing x86_64 variant stays a CI check only; ARM-emulator behavior is
  unverified until observed. The jury scores WER against reference transcripts and
  processing time as real-time factor (RTF), with weights: transcription 40,
  diarization 25, timestamps 10, speed 10, usability 10, defense 5. It does not
  measure device load, though load may be discussed in the defense.
- **Report material.** Provide the APK, source, installation and usage instructions,
  architecture and model description, limitations, the team's own test results and a
  demonstration video in a repository that follows
  `https://github.com/grigorevmp/hacathon_template`. That template has not been
  inspected. Report WER and RTF measured on our own data separately from the
  constructed Dialogs evaluation and never state an expected jury score.

## 1. Build only through GitHub CI

- **Do not build on the local machine.** GitHub CI performs builds. The requested initial workflows are **Ascent Android CI** (`.github/workflows/android.yml`, job **Build and validate Android app**) and **Ascent Model Export** (`.github/workflows/model-pack.yml`, job **Export and validate offline models**); the fetched base repository had no workflows.
- Do not run local Gradle, compilation, assemble, build, test, or lint tasks. Do not generate APK/AAB files or download Android SDKs or build toolchains. Do not invoke a wrapper, script, IDE action, or alternate tool that performs these operations indirectly.
- Local work may include editing, reading configuration, reviewing diffs, and strictly non-building static checks. Check what a command does before running it.
- Inspect the repository's actual GitHub workflow files, triggers, commands, and required checks before using CI. Do not invent workflow names or assume a pipeline exists in a supplied document-only workspace.
- Use the existing workflow. Do not create a replacement, weaken checks, bypass CI, or fall back to a local build unless the user explicitly changes this instruction.
- Both workflows trigger only on pushes; do not add a separate pull-request or manual-dispatch trigger. Model export filters relevant model/export/server paths.
- Ascent Android CI publishes only `debug-<8-character commit>.zip`, containing the compact ARM64 `ascent.apk`. Use R8 code/resource optimization and keep model weights outside this compact network APK; the separate verification APK that embeds weights is described in the Hackathon verification section and is not yet published by any workflow. The separately optimized x86_64 emulator variant must pass its checks but is not a download artifact. Keep test and lint diagnostics in the workflow console; do not upload report artifacts. Build dependency caches may reduce repeated work but must preserve verification and required checks.
- Report CI as not run or unverified unless an actual run and its result have been inspected. When available, identify the run and commit. Static review does not establish a successful build or passing tests.

- The app automatically downloads models from `https://ascent.arbuz.lol/models/catalog.json` using a `dataSync` foreground service started while the activity is visible. Keep an ongoing progress notification, preserve transfer ownership when the activity closes, and reconnect promptly through validated-network callbacks and per-request retries. WorkManager remains the fallback for process recovery and approximate daily checks; background jobs must not attempt an unrestricted foreground-service start. Independent gzip chunks represent up to 4 MiB each of expanded data; verify compressed and expanded hashes, resume only verified prefixes, and verify complete files before activation. Check for compatible updates on open and through approximate daily background work. Keep the current revision usable until verification succeeds and protect revisions held by active inference leases. Show model status without install, reinstall, cancel, archive-import or server-selection controls; only the hackathon verification build may add removal of its embedded model. Internet permission for weights does not authorize audio uploads. Model CI may use a private archive solely as authenticated deployment transport.

## 2. Preserve the product architecture

Keep the model status panel separate from conversation status. Validated local
selection determines readiness; network update activity is secondary. Observe
selection changes independently of progress, and publish transfer state only
while that operation owns the transfer lock. Stale saved success never proves
that model files still exist.

During audio processing show one stable loading indicator. The model panel shows
the latest successfully checked revision and elapsed time since that successful
check; failed update attempts do not erase it or falsely advance the timestamp.
Retain initial download progress. Completed transcripts use participant labels
for every word, including best-effort assignments when evidence is insufficient.
Keep inferred attribution distinguishable internally and exclude it from voice
enrollment evidence; absence of an unidentified-speaker label is not confidence.

- Support Android 12+ phones and Wear OS 6.0 watches. Keep minimum OS support separate from release-time target SDK requirements.
- Continuous background listening through the watch microphone belongs in the first release and first capture milestone. The user explicitly enables a visible listening session; pause, stop, interruptions, and connection state must be accurate.
- The phone performs speech recognition (Russian first priority, English second), conversation diarization, speech enhancement, and sound classification locally. The watch captures, timestamps, transfers, and displays phone-produced results. Do not replace primary watch capture with a remote-control-only design or silently switch microphones.
- Prioritize reliable live capture and early conversation speaker separation. Default to the Russian-language `Day` experience with a chronological history.

## 3. Never persist audio

- Never save raw, encoded, encrypted, enhanced, or otherwise processed audio on either device, a backend, or a provider. This includes files, databases, temporary directories, caches, logs, diagnostics, backups, and retry queues. Encryption does not make audio persistence acceptable.
- Provide listening controls, not audio recording, save-clip, replay, download, or export features. Source inspection opens retained text, never an audio recording. The only exception is the prototype and hackathon audio import described above, which decodes a user-selected file in memory without copying it into application storage.
- Use only short-lived volatile audio frames with explicit duration, byte, and lifetime limits. Encrypt application-owned queued buffers and authenticate and encrypt watch-to-phone transport. Keep cleartext exposure to the brief capture and local inference operations that require it.
- Ordinary local recognition requires cleartext samples during processing. Do not claim recognition directly over ciphertext, universal control of OS/driver buffers, or guaranteed erasure of every memory copy. Minimize copies and lifetimes and document actual protections.
- Choose transport without durable audio queues or remote audio routing. Never send audio to OpenRouter, the application backend, or cloud speech/sound services.
- On disconnection, overload, or expiry, discard frames beyond the short volatile budget and show the resulting coverage gap. No disk spool, deferred audio upload, or promise of later recovery is allowed.
- Diarization and sound detection must consume the live, bounded stream before frames expire. Do not require retained recordings for a later processing pass. Keep acoustic windows and audio-bearing model state ephemeral; encoding or feature extraction is not an audio-storage exception.

## 4. Text-only Rewind and encrypted local history

- Rewind holds text only. Freeze the transcript corresponding to the 30 seconds preceding activation, using capture timestamps. Do not retain a 30-second audio recording for this feature.
- Give AI the complete frozen text and the user's question, with necessary application instructions. Do not substitute a summary, silently truncate, add speaker labels, or retrieve people, locations, sound analysis, or other history.
- Distinguish pending recognition from lost capture. Wait for available in-flight recognition or clearly identify an incomplete interval. Never invent missing words or label partial coverage complete.
- Encrypt local transcripts, summaries, sound-event metadata, profile claims, indexes, and sensitive caches from the initial implementation. Encryption is a baseline, not a later optional milestone. Protect keys using platform facilities; keep content out of diagnostics.
- Apply rolling seven-day expiry from source capture time. Reprocessing, synchronization, opening, or bookmarking text must not reset it. Trace derived claims and AI results to sources and remove or recompute them when sources expire or are deleted, including watch copies and retrieval indexes.
- Do not preserve expired information through profile summaries, hidden caches, backups, or delayed synchronization. Persist only explicitly permitted non-audio data.

## 5. Optional remote AI is text-only and fail-closed

- Use `https://ascent.arbuz.lol` as the fixed production application backend. Do not add server selection, editable backend URLs, or custom/self-hosted-server connection settings to either app. Watch AI requests go through the phone, and the phone uses this backend.
- Keep OpenRouter credentials, model selection, approved provider routing, and retention policy server-side. Do not add direct client-to-OpenRouter calls or in-app provider/server configuration. User controls may enable or disable server AI and select text scope. If the backend is unavailable, report that state without switching application servers.
- The hackathon verification build has no remote AI path; its text processing is local.
- Remote AI through OpenRouter requires transparent user opt-in. Explain what selected text leaves the device. Local transcription, diarization, and sound classification remain local.
- Keep provider API keys out of source control, client configuration, and APKs. Use a minimal authenticated backend with server-held secrets and no content logging or persistence. Reject audio payloads. Verify TLS on both network legs.
- Pin a reviewed model ID, require `provider.zdr: true` and `provider.data_collection: "deny"`, and restrict approved provider endpoints through `provider.only`. Do not allow an unapproved model, provider, route, or retention downgrade as a fallback.
- Set `X-OpenRouter-Cache: false`; do not enable response-cache presets. Require account ZDR across model groups. Keep `InputOutputLogging`, `UseInputsOutputs`, and `Broadcast` off. Disable plugins and tools unless separately reviewed and authorized.
- ZDR alone is insufficient: its definition can permit provider in-memory caching. Reject or disable routes that retain content after the request. Verify the exact endpoint's current policy and controls before use; if requirements cannot be established, do not send the text.
- No prompt/response logging, content caches, training use, analytics payloads, or persistent request retries on the backend or provider route. Never describe encryption in transit as proof of zero retention.

Check current official [ZDR](https://openrouter.ai/docs/guides/features/zdr), [response caching](https://openrouter.ai/docs/guides/features/response-caching), and [data collection](https://openrouter.ai/docs/guides/privacy/data-collection) documentation before implementing these controls.

## 6. Evidence, interface, and completion reporting

- The requested speaker-attribution target is strictly above 75% overall accuracy. Count unknown and missing attribution as errors, use a one-to-one mapping of anonymous labels to reference speakers, and report the evaluation denominator and per-speaker/condition results. Keep development regressions separate from evaluation speakers. A constructed audiobook score cannot establish natural-conversation accuracy or replace device validation; do not weaken existing stricter checks to meet the new target.
- Hackathon measurements (WER, RTF, diarization, timestamps, English) are reported with their denominators and conditions, separately from production acceptance and from constructed Dialogs results. Keep their tasks unchecked until an inspected run or device trial supports them.
- Link people facts and summaries to retained transcript evidence. Preserve uncertainty and attribution. A mentioned name is not speaker identity; do not invent facts.
- Show contextual locations only with sufficient evidence. Otherwise omit the location field. Conversation topics alone do not prove physical location.
- Use warm yellow accents and translucent glass surfaces inspired by Apple Intelligence, readable high-contrast text, and accessible controls. Render the in-app wordmark as `Ascent.` with a square period. The phone prototype has a separate status element and collapsible conversation timelines with connected dots, names and timestamps. Consecutive phrases attributed to the same known speaker share one timeline element, including across pauses; retain phrase boundaries for timer-icon pause durations and show elapsed seconds. Mark CTC-derived times as approximate. Stopping listening triggers processing without a separate transcribe or audio-delete action. No small gray text or U+00B7 separators. Keep watch views concise and Rewind free of speaker division.
- Keep implementation and acceptance tasks unchecked until supported by actual evidence. In change reports, separate edits and static review from verified CI and device results. Never claim a local build, CI run, or audio/privacy guarantee that was not established.
