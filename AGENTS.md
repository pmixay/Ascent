# Ascent engineering contract

Read [README.md](README.md) for the product and [roadmap.md](roadmap.md) for delivery order. Follow the latest user requirements and keep all three documents consistent when changing the plan. Planned capabilities are not completed implementation. Write project documentation in English; the app's user-facing language remains Russian.

## Review prototype scope

The current user request publishes the prepared phone transcription prototype for review under `prototypes/phone-transcriber/`, with the corrected Ascent name. Its WAV files, plaintext transcript storage, missing expiry, and absent watch app are documented in its README. These are prototype limitations, not completed production protections. Preserve the production requirements below and do not mark their acceptance work complete or extend the prototype exception into production.

## 1. Build only through GitHub CI

- **Do not build on the local machine.** GitHub CI performs builds. The requested initial workflows are **Ascent Android CI** (`.github/workflows/android.yml`, job **Build and validate Android app**) and **Ascent Model Export** (`.github/workflows/model-pack.yml`, job **Export and validate offline models**); the fetched base repository had no workflows.
- Do not run local Gradle, compilation, assemble, build, test, or lint tasks. Do not generate APK/AAB files or download Android SDKs or build toolchains. Do not invoke a wrapper, script, IDE action, or alternate tool that performs these operations indirectly.
- Local work may include editing, reading configuration, reviewing diffs, and strictly non-building static checks. Check what a command does before running it.
- Inspect the repository's actual GitHub workflow files, triggers, commands, and required checks before using CI. Do not invent workflow names or assume a pipeline exists in a supplied document-only workspace.
- Use the existing workflow. Do not create a replacement, weaken checks, bypass CI, or fall back to a local build unless the user explicitly changes this instruction.
- Ascent Android CI publishes only the APK download ZIP. Keep test and lint diagnostics in the workflow console; do not upload report artifacts. Build dependency caches may reduce repeated work but must preserve verification and required checks.
- Report CI as not run or unverified unless an actual run and its result have been inspected. When available, identify the run and commit. Static review does not establish a successful build or passing tests.

## 2. Preserve the product architecture

- Support Android 12+ phones and Wear OS 6.0 watches. Keep minimum OS support separate from release-time target SDK requirements.
- Continuous background listening through the watch microphone belongs in the first release and first capture milestone. The user explicitly enables a visible listening session; pause, stop, interruptions, and connection state must be accurate.
- The phone performs Russian speech recognition, conversation diarization, speech enhancement, and sound classification locally. The watch captures, timestamps, transfers, and displays phone-produced results. Do not replace primary watch capture with a remote-control-only design or silently switch microphones.
- Prioritize reliable live capture and early conversation speaker separation. Default to the Russian-language `Day` experience with a chronological history.

## 3. Never persist audio

- Never save raw, encoded, encrypted, enhanced, or otherwise processed audio on either device, a backend, or a provider. This includes files, databases, temporary directories, caches, logs, diagnostics, backups, and retry queues. Encryption does not make audio persistence acceptable.
- Provide listening controls, not audio recording, save-clip, replay, download, or export features. Source inspection opens retained text, never an audio recording.
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
- Remote AI through OpenRouter requires transparent user opt-in. Explain what selected text leaves the device. Local transcription, diarization, and sound classification remain local.
- Keep provider API keys out of source control, client configuration, and APKs. Use a minimal authenticated backend with server-held secrets and no content logging or persistence. Reject audio payloads. Verify TLS on both network legs.
- Pin a reviewed model ID, require `provider.zdr: true` and `provider.data_collection: "deny"`, and restrict approved provider endpoints through `provider.only`. Do not allow an unapproved model, provider, route, or retention downgrade as a fallback.
- Set `X-OpenRouter-Cache: false`; do not enable response-cache presets. Require account ZDR across model groups. Keep `InputOutputLogging`, `UseInputsOutputs`, and `Broadcast` off. Disable plugins and tools unless separately reviewed and authorized.
- ZDR alone is insufficient: its definition can permit provider in-memory caching. Reject or disable routes that retain content after the request. Verify the exact endpoint's current policy and controls before use; if requirements cannot be established, do not send the text.
- No prompt/response logging, content caches, training use, analytics payloads, or persistent request retries on the backend or provider route. Never describe encryption in transit as proof of zero retention.

Check current official [ZDR](https://openrouter.ai/docs/guides/features/zdr), [response caching](https://openrouter.ai/docs/guides/features/response-caching), and [data collection](https://openrouter.ai/docs/guides/privacy/data-collection) documentation before implementing these controls.

## 6. Evidence, interface, and completion reporting

- Link people facts and summaries to retained transcript evidence. Preserve uncertainty and attribution. A mentioned name is not speaker identity; do not invent facts.
- Show contextual locations only with sufficient evidence. Otherwise omit the location field. Conversation topics alone do not prove physical location.
- Use warm yellow accents and translucent glass surfaces inspired by Apple Intelligence, readable high-contrast text, and accessible controls. No small gray text or U+00B7 separators. Keep watch views concise and Rewind free of speaker division.
- Keep implementation and acceptance tasks unchecked until supported by actual evidence. In change reports, separate edits and static review from verified CI and device results. Never claim a local build, CI run, or audio/privacy guarantee that was not established.
