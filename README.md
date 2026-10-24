# Ascent

The product plan below remains the production target. The separate
[Ascent v0.1 phone transcription prototype](prototypes/phone-transcriber/README.md) is present
for review: listening stops into automatic local transcription, then the temporary
WAV is deleted. It keeps multiple encrypted text conversations with a seven-day
retention filter and has no Wear OS app. Temporary audio still reaches disk, so
this does not satisfy the production no-audio-persistence requirement. Its CI and model-export
results must be inspected before treating an artifact as verified. The compact
phone APK uses R8 optimization and automatically downloads compressed model
chunks from the fixed Ascent host, resuming interrupted transfers. The interface
shows model status; no weights or manual model archive belong in the APK flow.

Russian speech memory for Android and Wear OS.

Documentation is in English. Interface labels and example prompts below are
English descriptions of the planned Russian-language app.

**Planned product: the watch is the primary continuous microphone. The phone converts speech to text locally. Ascent never saves audio. Optional AI features send selected text through OpenRouter under a verified no-retention policy.**

Ascent turns everyday speech and relevant sounds into a searchable seven-day history. It provides a text view of the last 30 seconds, conversation summaries, a day timeline, and editable information about people and familiar places.

Specification version: 0.5

Updated: 24 October 2026

Status: product and implementation plan. These are requirements, not claims that the software or its protections are already implemented.

See [roadmap.md](roadmap.md) for task checkboxes and acceptance criteria. Contributors and coding agents must follow [AGENTS.md](AGENTS.md), including its prohibition on local builds.

## 1. Confirmed product decisions

| Area | Decision |
| --- | --- |
| Phone compatibility | Android 12 and newer. |
| Watch platform | Wear OS 6.0 as the initial supported baseline. |
| Primary audio source | Watch microphone, continuously active in the background during an enabled listening session. |
| Watch scope | A core part of the first implementation, with simplified versions of useful phone functions. |
| Local processing | The phone performs Russian speech-to-text, conversation speaker separation, speech enhancement, and sound classification on device. |
| Server AI | OpenRouter may process text for recap, conversation and day summaries, people summaries, and questions about a selected fragment. |
| Application backend | Fixed production endpoint: `https://ascent.arbuz.lol`. Neither the phone nor the watch app offers server selection or a custom server URL. |
| Language | Russian speech, transcripts, summaries, and AI answers. Russian is the initial interface language. |
| First engineering priority | Reliable encrypted watch-to-phone audio transport, local transcription, and speaker separation for stored conversations. |
| Default mode | `Day`. |
| Default retention | Rolling seven days for encrypted transcripts, summaries, sound-event records, and source-linked derived information. |
| Audio retention | None. No raw, enhanced, encoded, or encrypted audio is written to persistent storage. |
| Recording features | No recording, saved clips, audio playback, audio export, or audio archive. Listening controls remain available. |
| Rewind | A rolling 30-second **text** window that can be frozen, read, and used for AI questions. |
| Rewind AI context | The complete, unabridged transcript of that fragment, the current question, and necessary application instructions. |
| Rewind complexity | Plain speech recognition and questions, without speaker labels, profiles, locations, or sound enrichment. |
| Security baseline | Encryption during listening and transport, encrypted local text storage, on-device speech processing, and restricted remote AI are initial requirements. |
| Visual design | Warm yellow accents, translucent glass surfaces and a luminous control inspired by Apple Intelligence; the in-app wordmark is `Ascent.` with a square period. Readable text, no small gray text, and no middle-dot separators. |
| Builds | Use the existing GitHub CI. Do not build the application on a local machine. |

This version supersedes the earlier saved-clip feature, audio rewind buffer, disconnected audio spool, phone-only AI requirement, and deferral of baseline encryption.

## 2. Phone and watch responsibilities

| Function | Phone | Watch |
| --- | --- | --- |
| Continuous microphone input | An optional explicitly selected live source | Primary responsibility |
| Audio security and transport | Authenticate the watch, decrypt frames just in time, process, and discard | Timestamp, protect short-lived frames, and stream over an authenticated encrypted connection |
| Russian transcription | Run local speech recognition | Display phone-produced text |
| Conversation speakers | Perform local diarization and store timestamped text turns | Display concise conversation information |
| Rewind | Freeze the text interval and manage a scoped AI request | Trigger rewind, read text, ask, and view answers |
| History | Full seven-day timeline and conversation details | Recent entries, summaries, and selected details |
| People | Full profiles, corrections, and source evidence | Short profile summaries and recent interactions |
| Sounds | Classify locally and store event metadata | Show selected events and deliver haptic notifications |
| Remote AI | Select text, enforce policy, request AI, and store the returned text locally | Initiate requests through the phone and display results |
| Settings | Retention, encryption, and server-AI enablement and text-scope controls; no server selection | Essential listening, connection, and notification controls; no server selection |

The watch handles live input and transport. It does not keep an offline audio backlog or send audio to an AI service. The phone remains the processing and history hub.

## 3. Functions

### 3.1 Continuous watch listening

The user starts listening through a visible watch action. Once enabled, the watch continues taking microphone input while the user leaves the app, returns to the watch face, or the display becomes inactive.

Requirements:

- A microphone foreground service owns live input independently of the watch screen.
- The default active mode is `Day`.
- Listening state is visible, with accessible pause and stop actions.
- Frames carry session identity, capture timestamps, sequence information, and source-device identity.
- Missing input, processing delay, connection loss, and interrupted listening appear as explicit states or gaps.
- Pause stops microphone access, discards pending audio frames, and clears the transient rewind view. Retained text history remains until deletion or expiry.
- A queued server request is canceled before submission when its source is cleared; already transmitted text cannot be recalled.
- Reboot and system-interruption recovery use supported permission and startup flows.

“Always-on” describes operation during an enabled session. Battery exhaustion, microphone contention, permission changes, and system interruptions must be visible. Continuous watch input is a first-milestone requirement.

Android documents microphone foreground-service requirements and startup restrictions. See [foreground service types](https://developer.android.com/develop/background-work/services/fgs/service-types), [startup restrictions](https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start), and [Wear OS microphone input](https://developer.android.com/training/wearables/user-input/voice).

### 3.2 Russian speech-to-text on device

The phone produces chronological Russian text with punctuation and timestamps. Evaluate names, abbreviations, numbers, dates, negation, noise, and Russian speech containing English terms.

Use a local model that accepts live frames without writing audio to a temporary file. Separate provisional text from finalized text. Corrections update affected summaries and indexes without silently rewriting a fragment already frozen for a question.

Cloud STT is prohibited, including fallback after a local model failure. Downloading model weights is separate from processing user content. The app must remain able to transcribe when Internet access is unavailable, provided the watch-to-phone connection and required local model are available.

Android's generic `SpeechRecognizer` may use remote services and is documented as unsuitable for continuous recognition; it is not the default continuous engine. See [SpeechRecognizer](https://developer.android.com/reference/android/speech/SpeechRecognizer).

The review prototype uses GigaAM Multilingual 220M CTC through ONNX Runtime and ERes2NetV2 speaker embeddings through sherpa-onnx. Models download automatically in verified, compressed chunks from the fixed Ascent host. WorkManager resumes interrupted transfers and checks for compatible model revisions on app open and approximately daily; Android scheduling can delay background work. Processing remains local once models are installed. Production watch-stream processing still requires measured Russian accuracy, memory behavior, speaker attribution and latency on supported devices.

### 3.3 Speaker separation for conversations

Speaker separation is an early core function for retained text conversations. Process voices locally as frames arrive and store timestamped text turns under provisional labels such as `Speaker 1` and `Speaker 2`.

Diarization must operate within the bounded live processing window. Choose an incremental approach that does not require keeping an entire conversation's audio for later analysis. Once frames are discarded, audio-based reprocessing is unavailable.

Support manual correction of text speaker assignments and naming. Later, suggest recurring-person matches with an unknown state and confirmation before uncertain identity merges.

Keep these concepts separate:

- Diarization: which speech segments belong to different voices.
- Voice matching: similarity to a previously represented voice.
- Identity: the person the user confirms for a voice.

Speaker embeddings are sensitive derived data, not anonymous data. If used, keep them encrypted on device, link them to source text intervals, expire them with those sources, and never send them to OpenRouter. Do not keep audio examples for a profile.

The separate prototype implements speech-constrained clustering and local voice-template enrollment when the user names a sufficiently clear speaker in the current session. Strong matches can reuse that name in later conversations; automatic guesses never train their own templates. Unconfirmed templates stay in memory. See the [speaker research and limitations](prototypes/phone-transcriber/docs/speaker-diarization.md). This does not complete the production streaming or accuracy acceptance work.

Diarization must not block rewind.

### 3.4 Simple 30-second text rewind

`Last 30 seconds` is a lightweight view of recently recognized speech. Its rolling buffer contains text and timestamps, never audio.

1. Capture the user's tap time and select the preceding 30 seconds of the current session.
2. Freeze that text interval so incoming speech cannot move the selected fragment.
3. Display every recognized word in chronological order, without speaker labels.
4. Let the user ask a question or return to live listening.

For every question, supply the **entire frozen fragment text**. Do not replace it with a summary, search hits, a truncated excerpt, or broader history. The model receives that fragment, the current question, and application instructions; previous answers, other conversations, profiles, sound tags, and inferred locations are not appended.

Example questions:

- `What was just said?`
- `What time was mentioned?`
- `What needs to be done?`
- `Explain this phrase.`

Rewind is independent of diarization. It offers no audio replay or save control.

Wait for in-flight recognition for the selected interval before submitting it. Clearly show pending text and actual gaps. If input started less than 30 seconds ago, show the available duration. A physically incomplete interval can be queried using all available text with an explicit incomplete-fragment notice.

There is no hidden 30-second audio reserve to recover missing words. When a live frame exceeds its processing deadline, discard it and mark the gap. A watch tap uses capture time, with clock mapping between devices; transport delay does not expand the selected interval.

The fragment stays available during the active view or question, subject to source expiry. Closing or pausing clears the transient copy. The same words may already exist in the encrypted seven-day conversation history.

### 3.5 Seven-day timeline and history

The phone timeline combines conversations, sound episodes, and listening gaps. Conversation cards show:

- Start time and duration.
- A short title.
- Participants or provisional speaker labels.
- A contextual place only when sufficiently supported.
- A short summary when opened.

Details contain the summary, source-linked transcript, participants, and associated sound events. Support split, merge, rename, and text corrections for imperfect conversation boundaries.

Provide `This morning`, `Today`, `Yesterday`, and `Last 7 days`. Show the resolved time range for natural-language filters. Use capture timestamps and the time zone at capture; delivery time must not move an event.

### 3.6 Recap and summaries

Provide three related AI functions:

- **Conversation recap:** topics, decisions, requests, commitments, dates, and unresolved questions from a selected conversation.
- **Period summary:** a selected morning, day, or other interval within retention.
- **People summary:** a concise view of source-supported information and recent interactions for a selected person.

Each substantive statement must link to supporting text. Distinguish direct statements from reports about someone else and preserve uncertainty. Recompute or remove outputs when supporting text changes, expires, or is deleted.

These functions may run on a server through the restricted OpenRouter path in Section 7. Automatic summaries require the user to enable that feature and its text scope. If server AI is disabled or unavailable, local transcription, speakers, sound events, and history continue working.

### 3.7 Sound recognition

The phone locally detects music, alarms, alarm clocks, sirens, doorbells, and ringing phones from transient frames. Treat speech and sounds as overlapping information.

Store only event metadata: category, start and end, confidence, and user correction. Group repeated detections into episodes rather than producing many cards for one sustained sound.

Provide category-specific notifications, restrained watch haptics, quiet hours, and corrections. Detection reliability must be measured.

Music detection is part of the plan. Song-title identification is a separate later idea that must work without uploading or retaining audio. Custom alarm learning can retain an approved local derived representation, not an audio sample. See [MediaPipe Audio Classifier](https://developers.google.com/edge/mediapipe/solutions/audio/audio_classifier/android).

### 3.8 Speech enhancement

Use noise reduction in the speech-processing branch. Keep a minimally processed live branch for sound detection so speech cleanup does not remove the events being detected.

Both branches operate in bounded volatile memory. Neither writes enhanced or original audio to storage. Measure recognition and speaker accuracy on real watch input. Here, noise cancellation means captured-speech enhancement.

### 3.9 People profiles

Provide confirmed names, short summaries, recent conversations, and dated facts supported by retained text.

Each extracted fact has a subject, source passage, timestamp, attribution, and review status. A mentioned name does not automatically identify the speaker. Do not infer sensitive characteristics or personality from a voice.

Support naming, merging, splitting, correcting, and forgetting profiles. Remote people summaries receive only relevant text and necessary pseudonymous identifiers, not voice features or the entire people database. Local speaker processing supplies attribution.

User-entered names and profile shells may remain as user-managed metadata. Inferred facts, voice features, summaries, and supporting evidence expire with their sources. Long-term inferred memory needs a separate explicit retention decision.

### 3.10 Contextual locations

Infer semantic places such as home, office, café, or car from conversation context, repeated patterns, and confirmed examples. A conversation topic alone does not establish physical location.

Show a place only when evidence is sufficiently reliable; otherwise omit it. Allow correction and explain its supporting evidence.

GPS and nearby-network signals remain a separate future decision. Place inference cannot enable listening or override privacy settings. Remote inference, if later added, must follow the same scoped text-only policy.

### 3.11 Simplified watch app

Include:

- Listening, pause, stop, connection, and pending-processing states.
- The last 30 seconds as plain text, with AI questions and short answers.
- Recent conversations, recaps, and period summaries.
- Recent sound events and selected haptic alerts.
- Concise people information from the phone.
- Opening the corresponding phone item.

Phone-produced text returns over the authenticated encrypted link. Any retained watch text cache is encrypted and follows source expiry. Long editing flows remain on the phone. Hide private conversation text from the watch face and ambient display by default.

## 4. Audio lifetime and seven-day retention

### Audio is never persisted

Live speech recognition necessarily handles samples in RAM. Ascent permits only the short-lived working frames needed for immediate transport and local inference.

- Never write audio to files, databases, caches, temporary directories, logs, crash attachments, backups, disk-backed message queues, or server storage.
- Encrypt app-owned queued frames immediately after microphone read and before transfer. Use authenticated encryption and ephemeral session keys.
- Microphone APIs, local decoding, DSP, and inference briefly require plaintext samples. Minimize copies and lifetime, then discard or overwrite accessible buffers.
- Encryption does not make saving audio acceptable. Encrypted audio files and offline audio queues are also prohibited.
- Each processing branch has a bounded memory budget and maximum frame age. Release a frame after its required consumers finish or when its deadline expires.
- Do not hold audio for rewind, later speaker analysis, replay, retraining, or network recovery.
- Verify SDK and runtime behavior, including temporary files and diagnostics. Do not claim that application code can guarantee forensic erasure from every OS or hardware buffer.

### Retained information

The default is a rolling **168-hour window** from source capture time. Processing, synchronizing, opening, or summarizing an item must not reset expiry.

| Data | Default behavior |
| --- | --- |
| Raw, encoded, or enhanced audio | Live volatile processing only. Never persisted. |
| Rewind text | Rolling 30 seconds; a frozen copy lasts for its active use, bounded by source expiry. |
| Transcripts and speaker turns | Encrypted locally; expire after seven days. |
| Recaps, period and people summaries | Encrypted locally; expire with supporting text. |
| Sound-event records | Encrypted metadata; expire after seven days. |
| Search indexes, text embeddings, local derived caches | Encrypted; remove or recompute when the source expires or is deleted. |
| Inferred person facts, local voice features, and place evidence | Encrypted; expire with their supporting sources. |
| User-entered profile names and settings | Encrypted user-managed metadata may remain. No inferred fact is preserved through this exception. |
| Watch text caches | Encrypted and bounded; expiry no later than the corresponding phone source. |
| Server prompts and responses | Used only for the active request. No content persistence, content logging, or reusable content cache. |

Hide expired information immediately from reads and AI retrieval, then complete cleanup. A conversation crossing the retention boundary must not keep expired passages because newer ones remain.

Deletion cascades to dependent information and watch caches. Reject late AI responses for deleted, changed, or expired sources; they cannot restore old content. Recompute multi-source summaries from remaining evidence or remove them. Delayed synchronization must not resurrect deleted data.

## 5. Capture, encryption, and disconnected behavior

The live path is:

**Watch microphone -> protected transient frames -> authenticated encrypted watch-to-phone link -> local speech, speaker, and sound processing -> encrypted text and event history.**

Rewind reads finalized text independently of speaker processing. Optional server AI starts only after text exists.

Pair devices explicitly, authenticate both ends, protect against replay and frame reordering, and rotate ephemeral session keys. A shared Bluetooth pairing alone is not the complete application security design.

Use a direct local connection for audio and prevent cloud-relay fallback. Wear OS Data Layer can route through Google-owned servers; its encryption does not by itself prove that raw audio stays between the user's devices. Validate the selected transport on supported hardware. See [Data Layer overview](https://developer.android.com/training/wearables/data/overview) and [client types](https://developer.android.com/training/wearables/data/client-types).

Disconnected behavior:

- The watch may keep the enabled microphone session alive while attempting reconnection, but undeliverable frames expire within the short volatile transport budget.
- Do not store an offline backlog or backfill missed speech.
- Show `No connection to phone` and an accurate coverage gap.
- The phone microphone does not activate automatically. An optional live source change requires deliberate selection.
- If the phone cannot process frames in time, discard overdue frames and show the gap instead of saving them.
- A process restart cannot restore audio. Recovery resumes with new frames.
- Distinguish watch-to-phone connectivity from Internet access: Internet loss affects server AI, not otherwise available local STT.
- Do not queue delayed start commands that might unexpectedly enable microphone access later.

Record the measured memory cap, age cap, latency, and battery cost for each supported device pair.

## 6. Platform and implementation direction

- Phone: Android 12+ means a proposed `minSdk` of 31.
- Watch: Wear OS 6.0 is the initial baseline and is based on Android 16.
- Minimum, target, and compile SDKs are separate decisions; supporting Android 12 does not freeze the target SDK at 31.
- Proposed UI: Kotlin, Jetpack Compose, and Compose for Wear OS.
- Proposed storage: an encrypted local structured database with source relationships and explicit expiry timestamps.
- Use replaceable local model components and a separate remote text-AI adapter.
- An audio ingest module must have no persistent-audio writer or cloud audio-upload path.
- Keep listening, transcription, diarization, sound classification, text history, and remote summarization independently schedulable.

See [Wear OS 6 changes](https://developer.android.com/training/wearables/versions/6/changes) and [SDK declarations](https://developer.android.com/guide/topics/manifest/uses-sdk-element).

## 7. Server AI through OpenRouter

### Fixed Ascent backend

The production application backend will be hosted at **`https://ascent.arbuz.lol`**. This is the default and only application-server address used by the released app.

- The phone sends enabled text-AI requests to this backend. Watch requests go through the phone.
- Neither app provides a server picker, editable backend address, custom server URL, or self-hosted-server connection setting.
- OpenRouter credentials, model selection, approved provider routing, and retention controls are managed server-side. The apps do not connect directly to OpenRouter or offer provider/server configuration.
- Users can enable or disable server AI and control the text scope without choosing a server.
- If the backend is unavailable, show that server AI is unavailable and keep local functions working. Do not switch to another application server automatically.

The prototype uses `/models/catalog.json` on this host for static model downloads. That distribution service is separate from the planned text-AI gateway; its existence does not establish remote AI functionality. Model export, deployment and connectivity require inspected run evidence.

### Scope and user control

Server AI is optional and text-only. Before enabling it, explain which text leaves the device, its purpose, the routing service, and the retention policy. A concise user-facing explanation can say:

> Speech recognition runs on your phone. Audio is never saved or sent to the cloud. For this AI feature, selected text is sent over a secure connection.

Show the selected fragment or conversation scope and give automatic recaps their own enable control. Rewind always sends the entire frozen text, as requested, without extra history. Other functions send only the relevant retained text.

User content is evidence, never instructions to change settings, reveal unrelated history, or trigger tools. Keep transcript content separate from application instructions.

### Encryption and trust boundary

Require HTTPS with certificate validation for the phone-to-backend, backend-to-OpenRouter, and provider connections. Disable cleartext networking and permissive trust managers. Protect responses as well as requests.

A minimal stateless Ascent gateway at `https://ascent.arbuz.lol` holds the OpenRouter service key, authenticates clients, and enforces routing policy. Do not embed a shared service key in an APK, repository, analytics event, or CI log.

The gateway must stream requests without persistent bodies, disk-backed queues, content logs, observability payloads, or response caches. Its proxies, WAF, hosting, and error-reporting configuration are part of the same requirement. Device-side pending work should reference local source IDs and recheck consent and expiry before sending.

TLS protects data in transit. The gateway, OpenRouter, and selected inference provider can access plaintext needed to process a request. This architecture is not end-to-end encryption that hides text from the inference provider. Do not describe it that way.

### Required OpenRouter policy

Use a dedicated application configuration with:

- ZDR enforcement for every permitted model group at account or guardrail level.
- `provider.zdr: true` and `provider.data_collection: "deny"` on every request.
- Pin an explicitly approved model ID and restrict approved provider endpoints through `provider.only`; exclude unknown endpoint variants.
- `provider.allow_fallbacks: false` for the initial integration. A later retry may select another explicitly approved route with every restriction preserved.
- `X-OpenRouter-Cache: false` on every request and no response-cache-enabled preset.
- Input & Output Logging, OpenRouter Use of Inputs/Outputs, Broadcast, and other content logging or sharing disabled.
- No external tools, web search, file storage, or plugins in these requests.
- No remote audio, speaker embeddings, contact databases, or hidden conversation history.

If no approved route is available, report AI as unavailable. Do not drop privacy controls, use an unreviewed model, or fall back to cloud STT.

OpenRouter documents separate [provider-routing controls](https://openrouter.ai/docs/guides/routing/provider-selection), [ZDR enforcement](https://openrouter.ai/docs/guides/features/zdr), and [response caching](https://openrouter.ai/docs/guides/features/response-caching). Request-level ZDR is not a substitute for disabling response caching.

### Verify retention beyond a label

OpenRouter's ZDR definition permits some provider-side in-memory prompt caching. Ascent's requirement is stricter: no content retained for reuse after the request. Approve an endpoint only after verifying that caching is absent or can be disabled and that its actual contract and configuration meet the policy. If that cannot be verified, leave it unavailable. See [ZDR caching behavior](https://openrouter.ai/docs/guides/features/zdr) and [prompt caching](https://openrouter.ai/docs/guides/best-practices/prompt-caching).

“No training” alone is insufficient. Review the exact endpoint, content logging, abuse-monitoring retention, and any exceptions. Do not assume that every model exposed by OpenRouter qualifies.

OpenRouter distinguishes prompt content from operational metadata such as token counts and latency. Its documentation also describes anonymous prompt categorization. Disclose applicable metadata and processing, and do not promise that no operational information exists anywhere. See [data collection](https://openrouter.ai/docs/guides/privacy/data-collection) and [input/output logging](https://openrouter.ai/docs/guides/features/input-output-logging).

Provider approval is an implementation gate, not a claim that a provider has already been selected or independently audited.

## 8. Privacy implementation priorities

### Required from the first applicable milestone

- No persisted audio, encrypted live transport, and local STT.
- Encrypted transcripts, summaries, indexes, facts, settings, and watch text caches, with protected device keys.
- Exclude private content and keys from automatic backups until a separately designed backup feature exists.
- Visible listening state, immediate pause, retention, cascading deletion, and accurate gap reporting.
- No content in logs, crash reports, analytics, notification previews, or server retry queues.
- The complete server-AI policy before the first remote request.
- Accurate descriptions of both implemented protections and remaining limitations.

Use platform key protection; see [Android Keystore](https://developer.android.com/privacy-and-security/keystore). Encryption at rest complements deletion and retention; it does not replace them.

### Later hardening

App locking, advanced key recovery, separately designed encrypted backup, stronger device-integrity controls, and independent security review may follow. They cannot defer the baseline requirements above.

Apple Intelligence inspires the visual treatment. Ascent must describe its own verified guarantees and use original assets.

## 9. Design system

Use warm yellow as the main accent, cream surfaces and dark readable text. Translucent layers and luminous controls draw on Apple Intelligence. On the watch and in dark mode, use dark surfaces with high-contrast light text and restrained yellow accents. Preserve legibility over every glass surface.

- No small gray text.
- No middle-dot separators.
- Aim for 16–18 sp reading text on the phone and readable watch typography with system scaling.
- Use reliable touch targets and concise watch layouts.
- Put time, duration, source, and place on separate labeled lines when needed.
- Pair status colors with explicit labels.
- Keep `Last 30 seconds` and pause/stop in predictable positions.
- Use listening terminology in the product. Do not present audio recording, playback, clip-saving, or export actions.
- Make local processing and a pending server-AI request understandable without displaying protocol details.

Phone destinations: `Today`, `History`, `People`, and `Search`. The watch provides shorter versions and essential listening controls.

## 10. Delivery and development workflow

The first production slice must use the watch as the live microphone and the phone for processing. The phone-only review prototype does not complete that slice. [roadmap.md](roadmap.md) defines the sequence and acceptance gates.

**Do not build locally. GitHub CI is the build and executable-validation environment.** This branch adds the requested [Ascent Android CI](.github/workflows/android.yml) and [Ascent Model Export](.github/workflows/model-pack.yml) workflows for the review prototype; the fetched base repository had no workflows. Inspect each actual run and its commit before reporting success. Use these checks, do not weaken them or replace them with local builds, and do not claim an unobserved green result. Both workflows trigger only on pushes; model export additionally filters relevant source paths. Ascent Android CI publishes only `debug-<8-character commit>.zip` containing the compact ARM64 `ascent.apk`; test and lint diagnostics remain in the workflow console, without report artifacts. The optimized x86_64 emulator build is checked but never uploaded. Model CI deploys verified weights to the fixed HTTPS catalog through restricted SSH; users download them inside the app. Local document edits and static inspection are allowed. See [AGENTS.md](AGENTS.md).

Later ideas include personal vocabulary, source-linked commitment tracking, conversation preparation, comparing decisions, local custom-sound enrollment, and deliberately configured longer-term text memory. Audio recording or storage is not a later extension of this plan.

## 11. Remaining engineering choices

Implementation still needs measured device pairs, battery and latency budgets, strict volatile-frame limits, local model selection, transport validation, speaker thresholds, and a verified OpenRouter model/endpoint configuration.

These choices do not reopen continuous watch input, on-device STT, zero audio persistence, simple full-text rewind, seven-day text history, or the CI-only build rule.
