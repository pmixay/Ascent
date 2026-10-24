# Ascent roadmap

Checked boxes indicate **confirmed product decisions only**, not completed software or validated performance. Implementation and acceptance work remains unchecked.

See [README.md](README.md) and [AGENTS.md](AGENTS.md). Use existing GitHub CI exclusively for Gradle, builds, tests, lint, and build artifacts. **Never build locally.** Verify existing workflow names.

Specification version: 0.4  
Updated: 24 October 2026

## Confirmed scope

- [x] Support Android 12 and later with `minSdk 31`; choose distribution `targetSdk` separately against release-time requirements.
- [x] Support Wear OS 6.0, with the watch as primary microphone and continuous background capture in the first vertical slice.
- [x] Run Russian speech recognition, conversation speaker separation, and sound classification on the phone. Speech recognition never uses a cloud service.
- [x] Give the watch a simplified version of the app: history snippets, Rewind, AI questions and results, listening status, and sound events.
- [x] Default to **День**, showing conversations and sounds chronologically.
- [x] Never save audio. Raw and processed audio exist only as short-lived volatile processing frames, with no files, caches, databases, disk-backed retries, offline spool, or watch audio backlog.
- [x] Apply rolling seven-day retention to transcripts, summaries, sound events, and source-linked facts. Rewind uses a 30-second text ring.
- [x] Freeze the preceding 30 seconds for Rewind and send its entire, unabridged transcript to AI, without speakers, profiles, locations, sounds, or full-history context.
- [x] Prioritize speaker separation for stored day conversations independently of the simpler Rewind flow.
- [x] Infer contextual place labels only when evidence is sufficient; otherwise, show no location.
- [x] Use blue accents and readable hierarchy, without small gray text or centered-dot separators.
- [x] Start with encrypted audio buffers, authenticated encrypted transfer, encrypted text storage, listening controls, and content-free diagnostics. Microphone, DSP, and inference operations briefly require plaintext.
- [x] Permit optional OpenRouter text AI through approved zero-data-retention providers with visible scope, consent, and no downgrade. Prohibit audio uploads and content in operational logs or caches.
- [x] Use `https://ascent.arbuz.lol` as the fixed production application backend. Provide no server selector or custom server URL in the phone or watch app; manage OpenRouter configuration server-side.

## Delivery principles and dependencies

The critical path is **encrypted watch capture and transfer → local transcription and diarization → encrypted text retention and Rewind → day views**. Sounds, profiles, and places follow. Baseline protections precede private-content processing.

Maintain user-started background listening while the device permits it. Expose interruptions and actual coverage; do not promise uninterrupted operation.

Verify transport routing and buffering; encryption does not excuse underlying audio persistence. Drop unavailable frames and show gaps. Verify any nearby-device-only routing claim.

- [ ] Follow [AGENTS.md](AGENTS.md) and existing CI checks throughout every milestone; run no local Gradle, build, test, or lint commands.

## Milestone 1: Watch-to-phone capture vertical slice

**Dependency:** target devices and CI identified. **Outcome:** encrypted watch-to-phone background capture.

- [ ] Create phone and watch applications with the agreed platform baselines and explicit microphone permission flows.
- [ ] Implement user-started microphone foreground-service capture on the watch, including screen-off operation where permitted.
- [ ] Define frame identifiers, format, sequence numbers, capture timestamps, acknowledgements, and strict volatile-frame lifetimes.
- [ ] Authenticate paired devices and encrypt frames immediately in app-controlled buffers and before transfer. Define session keys, nonce handling, and rejection of unauthenticated traffic.
- [ ] Verify routing and storage behavior before private-content trials. Exclude disk-backed or offline audio delivery mechanisms.
- [ ] Limit plaintext to necessary microphone, DSP, and inference operations; promptly release buffers. Do not claim encrypted inference or guaranteed forensic erasure of managed memory.
- [ ] Drop expired or unavailable frames. Implement no audio files, persisted retries, offline spool, or watch audio backlog.
- [ ] Preserve capture order and timestamps across transfer delays and clock changes.
- [ ] Handle Bluetooth loss, phone unavailability, microphone contention, permission revocation, service termination, and low resources.
- [ ] Keep the watch as the selected source across connection gaps, discard unavailable frames, and never silently switch to the phone microphone.
- [ ] Show microphone source, actual session state, connection status, and capture gaps on both devices.
- [ ] Confirm pause and stop only after watch acknowledgement; show unreachable controls.
- [ ] Measure capture continuity, transfer latency, watch battery consumption, and thermal behavior on the selected devices.

**Acceptance criteria**

- [ ] A user-started session continues through a screen-off trial, with received frames correctly ordered on the phone.
- [ ] Connection interruption drops unavailable frames and shows missing coverage, without later audio recovery, duplicate frames, or false normal status.
- [ ] Storage and transport review finds no audio persistence; encrypted-buffer boundaries and necessary plaintext processing are documented accurately.
- [ ] Stop ends watch microphone capture; the UI distinguishes a successful stop from an unacknowledged remote request.
- [ ] Report measured battery, coverage, duration, hardware, OS versions, and limitations without unmeasured all-day guarantees.

## Milestone 2: Russian transcription and conversation speakers

**Dependency:** Milestone 1. **Outcome:** timestamped Russian conversations with speakers.

- [ ] Benchmark on-phone speech models using live Russian speech from the watch microphone, without saving audio samples.
- [ ] Include casual speech, names, numbers, quiet voices, background music, overlapping talk, and Russian speech containing foreign words.
- [ ] Produce provisional and finalized text with timestamps linked to capture time, not arrival time.
- [ ] Define conversation boundaries using pauses and continuity, with manual split and merge controls.
- [ ] Add within-conversation speaker separation, initially using editable anonymous speaker identifiers.
- [ ] Perform incremental speaker analysis within bounded volatile processing windows. Drop frames on overload; never defer analysis through persisted audio.
- [ ] Represent overlapping or uncertain speakers without forcing a confident assignment.
- [ ] Separate conversation diarization from later cross-conversation identity matching.
- [ ] Encrypt conversation timestamps, duration, coverage, transcript, speaker turns, and processing state from their first persistent write.
- [ ] Protect keys with platform-backed facilities; exclude content from logs, temporary files, crash reports, and unapproved backups.
- [ ] Evaluate consented live sessions against manually prepared reference text and speaker turns; retain no source audio.

**Acceptance criteria**

- [ ] Reviewers can trace phrases and speaker turns to capture timestamps without audio playback.
- [ ] Benchmarks report transcription errors, speaker assignment errors, latency, and phone resource use separately.
- [ ] Preserve text, speaker, and boundary corrections distinctly from generated results.
- [ ] Missing or unclear speech appears as a limitation, not invented dialogue.

## Milestone 3: Retention and the complete 30-second Rewind

**Dependency:** finalized timestamped text. **Outcome:** narrowly scoped Rewind and automatic expiry.

- [ ] Implement seven-day expiry for transcripts, summaries, sound events, and source-linked facts, using original source event time.
- [ ] Ensure reopening or reprocessing text never silently restarts retention.
- [ ] Implement the rolling 30-second text ring; retain no audio ring or reconstruction source.
- [ ] Freeze the 30 seconds before activation, accounting for watch-to-phone command delay.
- [ ] Wait for pending transcription needed to complete that interval; distinguish processing delay from unavailable audio.
- [ ] Preserve every available word chronologically; never substitute a summary or truncate to fit a request.
- [ ] Construct AI context from only the complete selected plain-text transcript, the user's question, and necessary system instructions.
- [ ] Exclude speakers, profiles, places, sounds, unrelated conversations, and history retrieval from Rewind requests.
- [ ] Prevent the spoken AI question from being appended to the fragment it is asking about.
- [ ] Provide clear incomplete-window states when listening started recently or a capture gap intersects the interval.
- [ ] Wait for pending recognition. For missing capture, show actual coverage and send all available text with an incompleteness notice, never claiming a complete interval.
- [ ] Remove expired content from views, indexes, encrypted text replicas, and pending jobs.
- [ ] Add optional remote Rewind questions with visible selected-text scope and consent before transmission.
- [ ] Configure the phone to use only `https://ascent.arbuz.lol` for server AI and route watch requests through the phone. Provide no in-app server selection, editable backend address, or direct OpenRouter connection.
- [ ] Manage OpenRouter credentials, approved models, provider routing, and privacy policy on the Ascent backend. Preserve user controls for AI enablement and text scope.
- [ ] Enforce `provider.zdr=true`, `provider.data_collection="deny"`, and a strict approved model/provider allowlist on every OpenRouter request. Fail closed without downgrade.
- [ ] Approve endpoints only with verified absence or disabling of after-request content retention, including implicit prompt caches. A ZDR label alone is insufficient.
- [ ] Set `X-OpenRouter-Cache: false` and disable cache presets. Enable account-level ZDR for all model groups; turn off Input & Output Logging, Use of Inputs/Outputs, and Broadcast.
- [ ] Disable tools and plugins. Use TLS with certificate validation, protect credentials, and exclude content from operational logs and caches. Server inference briefly uses plaintext; make no end-to-end-encrypted inference claim.

**Acceptance criteria**

- [ ] An inspectable test request contains every finalized word in a complete 30-second fixture, without unrelated context or speaker labels.
- [ ] Delayed requests select the interval before user activation, not message arrival.
- [ ] Incomplete capture is visibly identified; the app never presents a partial fragment as a complete 30 seconds.
- [ ] Expired content is inaccessible, and reprocessing cannot extend its lifetime.
- [ ] CI contract checks verify complete text payloads, routing restrictions, cache controls, and fail-closed behavior. Requested summaries may become encrypted product records, never operational cache copies.
- [ ] Review both released app interfaces for absence of server-selection controls. Verify through existing CI that backend unavailability does not switch application servers or interrupt otherwise available local processing.

## Milestone 4: Day timeline and watch views

**Dependency:** Milestones 2 and 3. **Outcome:** daily history on both devices.

- [ ] Open on **День**, with date navigation and filters such as **Этим утром**, conversations, and sounds.
- [ ] Show start time, duration, processing state, and summary; add locations after Milestone 6.
- [ ] Open a conversation on its summary, with access to timestamped text and speaker turns.
- [ ] Add optional remote conversation summaries and day or morning recaps using visibly selected text scope, consent, and the same provider restrictions. Link claims to supporting spans.
- [ ] Implement watch history snippets, conversation summaries, Rewind, question entry, AI results, capture status, and sound-event views.
- [ ] Keep watch text readable and actions large. Use separate labeled lines instead of dense metadata strings.
- [ ] Add a Rewind Tile and a discreet complication entry point, with visible tap controls as the baseline.
- [ ] Hide private snippets from ambient surfaces and show content only in the deliberate reading experience.
- [ ] Define offline watch behavior using encrypted text replicas with inherited expiry, explicit pending states, and no audio backlog.

**Acceptance criteria**

- [ ] Users can open morning conversations, read summaries and evidence, and return to the timeline.
- [ ] Both devices identify matching fragments and results without duplicate history entries.
- [ ] Disconnection, stale status, and expired content have explicit states. A disconnected Rewind never later returns an unrelated newer fragment.
- [ ] Design review confirms blue accents, readable contrast, no small gray text, and no centered-dot separators.

## Milestone 5: Sounds and speech enhancement

**Dependency:** capture, timeline, and measured budgets. **Outcome:** sound events and validated speech enhancement.

- [ ] Evaluate music, alarm, alarm-clock, siren, doorbell, and selected sound categories through on-phone classification of transient watch frames.
- [ ] Run environmental classification on an appropriate signal branch before speech-focused denoising can remove relevant sounds.
- [ ] Compare transcription and speaker quality with enhancement enabled and disabled; choose settings from measured results.
- [ ] Keep music-presence detection local. Review any future catalog lookup separately; never upload audio.
- [ ] Group repeated detections into events with start time, duration, confidence, and correction controls.
- [ ] Add configurable watch haptics and notification categories, with deduplication and quiet-hour behavior.
- [ ] Keep all sound events under the seven-day retention policy.

**Acceptance criteria**

- [ ] Report false positives, missed detections, and alert delay for each supported category.
- [ ] Noisy-speech trials demonstrate the chosen enhancement tradeoff.
- [ ] Repeated sounds do not create notification floods. Alarm detection remains supplementary information, with no emergency-reliability claim.

## Milestone 6: People and contextual places

**Dependency:** trustworthy conversations, corrections, and expiry. **Outcome:** supported profiles and cautious place labels.

- [ ] Add user-confirmed links between anonymous speakers and people, with merge, split, rename, and unlink controls.
- [ ] Store profile claims with source spans, dates, confidence, and whether they are direct statements or uncertain interpretations.
- [ ] Generate short profile summaries from supported, unexpired claims. Never treat a mentioned name as proof of speaker identity.
- [ ] Add optional remote person summaries using only selected supported text, visible scope, consent, and the approved-provider restrictions.
- [ ] Show concise person summaries and recent interactions on the watch, with full editing on the phone.
- [ ] Expire or recompute derived profile claims and summaries when their sources expire or are deleted.
- [ ] Expire voice features with their supporting source material and remove anonymous speaker records when their final conversation expires.
- [ ] Evaluate the proposal to keep manually confirmed names and empty profile shells as local metadata, without expired derived facts.
- [ ] Infer place labels from dialogue context and recurring patterns, with correction controls and an abstention threshold.
- [ ] Keep uncertain locations absent. Do not use inferred places as permission to start listening or as a reliable privacy boundary.

**Acceptance criteria**

- [ ] Every generated profile claim has inspectable support and an expiry relationship.
- [ ] Deleting or expiring a source updates dependent profiles, summaries, and indexes.
- [ ] Insufficient evidence yields no location; label corrections preserve the transcript.

## Milestone 7: Protection verification and release hardening

**Dependency:** baseline encryption implemented. Verify and extend protection without deferring earlier requirements.

- [ ] Review frame encryption, pairing, keys, local text encryption, indexes, and device-lock behavior, including failed operations.
- [ ] Review watch storage, transport, debug builds, crash reports, screen previews, and SDK behavior for unintended persistence or exposure.
- [ ] Decide whether to add encrypted text-only backups. Restoration must preserve expiry and never resurrect deleted content; audio remains excluded.
- [ ] Implement deletion across replicas and backups according to an explicit, documented policy.
- [ ] Complete device compatibility, interruption recovery, accessibility, battery, thermal, and long-session checks on the supported matrix.
- [ ] Recheck release-time permissions, foreground-service requirements, distribution policies, and target SDK requirements.
- [ ] Review future custom sound learning without retained audio, explicit longer text retention, and integrations as separate decisions.

**Acceptance criteria**

- [ ] Protection and recovery match documented claims without overstating encryption guarantees.
- [ ] Release notes state supported devices, measured operating limits, retention behavior, and transport behavior accurately.

## Decisions to resolve without stopping the plan

- [ ] Select the first phone and watch models, then define a small second-device compatibility set.
- [ ] Agree on acceptance thresholds after baseline measurement: battery budget, coverage, latency, Russian accuracy, speaker errors, and sound false-positive rate.
- [ ] Verify transport routing and absence of persistent audio buffering before private-content trials.
- [ ] Confirm approved OpenRouter endpoints, cache behavior, and no-retention enforcement before remote AI trials.
- [ ] Set volatile-frame lifetimes and overload behavior from device measurements. Longer text retention and text backup remain explicit future decisions.

## First engineering deliverable

- [ ] Deliver a CI-built phone/watch pair that starts watch capture, transfers encrypted timestamped frames in the background, shows actual status and dropped coverage, and stops reliably.
- [ ] Include routing and storage findings, coverage, latency, battery measurements, CI evidence, and the next local-transcription task.

The first deliverable must prove watch-primary capture; phone-microphone capture or a remote-control-only watch does not satisfy it.

## Primary implementation references

- [Android microphone foreground-service requirements](https://developer.android.com/develop/background-work/services/fgs/service-types#microphone)
- [Wear OS communication client types](https://developer.android.com/training/wearables/data/client-types)
- [Wear OS power and battery guidance](https://developer.android.com/training/wearables/apps/power)
- [Wear OS Tile design guidance](https://developer.android.com/design/ui/wear/guides/surfaces/tiles/bestpractices)
- [OpenRouter zero-data-retention routing](https://openrouter.ai/docs/guides/features/zdr)
- [OpenRouter response caching](https://openrouter.ai/docs/guides/features/response-caching)
