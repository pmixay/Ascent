# Ascent hackathon audit — 6 November 2026

## 1. Conclusion and evidence boundary

Ascent has a substantial local speech-processing prototype worth extending. It is not currently ready for the supplied competition acceptance procedure. The immediate blockers are the missing reachable audio-import flow, incompatible exported JSON, absence of bundled models, unresolved literal local-LLM requirement, and incomplete release validation.

Audited source: `d77596272ae7facaa2736ed31a8d112b5e1a696d` on `main`. This review inspected the repository structure, application pipeline, model distribution, native packaging, evaluation code, test coverage, documentation, supplied PDF text, organizer clarifications and public GitHub Actions job results. No local application build, executable application test, inference experiment or device trial was performed. Findings below distinguish observed code, inspected remote results and risks requiring reproduction.

At audit time the checkout contained 136 non-Git files, including 24 production Kotlin files, 28 JVM Kotlin files (including helpers), four Android instrumentation files, 15 Python files and 28 public WAV fixtures. File counts are not a quality score.

The implementation plan and assignments are in [HACKATHON_PLAN.md](HACKATHON_PLAN.md).

## 2. Authoritative requirements

### Source interpretation

| Source | Meaning for this project |
| --- | --- |
| `Хахатон Ноябрь 2026.pdf`, pp. 8–11 | Android; local recognition, diarization and text processing; offline operation; mandatory audio import and local LLM; no remote runtime computation or prepared-answer substitution. |
| Same PDF, pp. 14–16 | Imported local audio or in-app capture; exact JSON fields; experts install an APK, import three recordings, process and export them. |
| Same PDF, pp. 17–19 | Basic requirements versus optional improvements: preprocessing, pause filtering, punctuation, progress, playback, noise/overlap handling, optimization and editing. These examples do not make every advanced feature mandatory. |
| Same PDF, pp. 20–22 | APK, source, instructions, architecture/model descriptions, limitations, own test results and demonstration video; repository submission; six weighted scoring categories. |
| Organizer messages supplied by the user and the new [repository transcription of jury clarifications](hackathon/jury-clarifications.md), 6 November | APK must contain an embedded model, support import and specification-compatible JSON. Russian first, English second. Test cases for call-like speech and moderate-pace meetings are both a direct criterion, not a UX bonus. ARM evaluation is expected; the jury explicitly asks why evaluation would be limited to an emulator. Device load may be discussed at defense but will not be separately measured during their check. |
| `Видеоаналитика в эксплуатации.pdf` | Supporting methods: define applicability, measure difficult negatives, evaluate latency/misses and separate development from evaluation. No video feature is required for Ascent. |
| `ИИ агенты.pdf` | Supporting engineering guidance: observations, predeclared acceptance, measured benefit, human understanding. No agent runtime is required inside Ascent. |
| `Баланс-Платформа_МИФИ.pdf` | Sponsor/platform presentation, not an additional application specification. |

The jury clarifies that both call-like and meeting-like test cases matter as a direct criterion. This requires handling both recording conditions; it does not by itself explicitly require Android telephone-call interception or live call integration. Batch import is mandatory, and streaming is an unresolved interpretation to confirm rather than assume absent. Test the call-like case on ARM hardware rather than limiting proof to a desktop/x86 emulator.

### Scoring priorities

| Criterion | Points | Primary owner |
| --- | ---: | --- |
| Transcription accuracy | 40 | Andrey |
| Diarization accuracy | 25 | Andrey |
| Timestamp accuracy | 10 | Andrey |
| Processing speed | 10 | Timofey, with Andrey |
| Operability and UX | 10 | Pasha and Petya |
| Defense | 5 | Pasha, with all four |

The specification gives no absolute WER, DER, timestamp or RTF pass threshold. The repository's >75% attribution threshold is an existing internal requirement, not an organizer-provided scoring formula.

### Requirement coverage

| ID | Requirement | Current evidence | Status / needed work |
| --- | --- | --- | --- |
| R01 | Installable Android application | Compose application; `minSdk=31`; ARM64 downloadable build. Current build job passed. | Implemented with limited verified platform coverage; validate actual ARM devices. |
| R02 | First-install offline operation with embedded models | `check_apk.py:33–39` rejects weights; startup schedules network installation. | **Blocker:** bundle the complete working model set, not just one unused model. |
| R03 | Import an audio file | `AudioImporter.kt` exists but has no callers; no `OpenDocument` flow. | **Blocker:** connect UI to ingest and pipeline; define/decode accepted formats. |
| R04 | Local speech recognition | `TranscriptionEngine.kt:29–85`: GigaAM CTC through ONNX Runtime. | Implemented; real-phone Russian/English accuracy and RTF unmeasured. |
| R05 | Automatic speaker separation | ERes2NetV2 embeddings, bounded anchors, clustering and word attribution. | Implemented with limited evidence; rapid short turns, similar voices and overlap remain risks. |
| R06 | Start/end timestamps | CTC frame-derived times; source-relative milliseconds internally. | Implemented approximately; no verified boundary-error benchmark. |
| R07 | Readable transcript | Compose connected timeline and editable speaker names. | Implemented; large-transcript usability and accessibility require device checks. |
| R08 | Exact JSON export | Export uses storage serializer with `segments`, `speakerId`, `startMs`, `endMs`. | **Blocker:** separate competition serializer with `transcript`, `speaker_id`, `start_ms`, `end_ms`, `text`. |
| R09 | Local LLM | ASR and speaker embedding models are present; no text LLM runtime/model found. | **Unresolved mandatory requirement:** obtain written interpretation; provision a meaningful local text-model stage by default. |
| R10 | No cloud processing | Current application network code is model GET download; server is static publication. | Consistent in reviewed code. Preserve this; the old planned OpenRouter path is incompatible with this submission. |
| R11 | Russian first, English second | Multilingual model name, Russian fixtures and UI. | Russian controlled evidence exists; English and code-switch accuracy need a separate measured slice. |
| R12 | Different conversational conditions | Constructed actor dialogues and reader regressions exist. | Add continuous natural dialogue, short turns, meeting and telephone-channel cases. |
| R13 | Submission materials | Extensive prototype docs, no competition-ready installation flow/video/results package. | Prepare the final judge-facing repository and durable APK access. |
| R14 | Team understands its solution | No evidence can be inferred from code or subscriptions. | Each person demonstrates and explains their own subsystem and failure modes. |

Required export example, exactly as in PDF p. 15:

```json
{
  "transcript": [
    {
      "speaker_id": "speaker_1",
      "start_ms": 1250,
      "end_ms": 4380,
      "text": "Добрый день!"
    }
  ]
}
```

Use integer milliseconds from the beginning of the input media, stable anonymous speaker IDs within that input, UTF-8 text, deterministic ordering and no storage/provenance fields in this minimal contract. Preserve richer provenance in encrypted internal records. A silent input should be exportable as `{"transcript":[]}` rather than fail the UI export flow.

## 3. Current architecture

```text
MainActivity / GlassUi
    -> ConversationViewModel
       -> AudioRecorder -> cache/pending-audio/<uuid>.wav
       -> ModelSelection lease
       -> TranscriptionEngine / PcmWave
          1. GigaAM CTC: ~20 s cores, up to 1.25 s context per side
          2. CTC word stitching
          3. Speech-context extraction and energy quality gating
          4. ERes2NetV2 embeddings via sherpa-onnx
          5. SpeakerClusterer -> SpeakerAttribution -> text segments
       -> ConversationStore / VoiceProfileStore (AES-GCM, Android Keystore)
       -> TXT or internal-format JSON export
       -> finally: temporary WAV cleanup

ModelDownloadService + WorkManager
    -> fixed HTTPS catalog -> verified resumable gzip chunks
    -> immutable local model revision -> atomic selection + active leases

GitHub model workflow -> verified model export -> SSH publisher -> Caddy static host
```

At the audited revision there was one Android application module and no Wear OS app, watch transport, Rewind, local text LLM, sound classifier or application AI backend. The current branch now adds source-level audio import, jury JSON export and a Wear OS 6.0 companion preview; their CI/device evidence is still pending. `server/` distributes models; it does not perform recognition.

### Existing strengths to preserve

- Real local model inference, not a UI mock or cloud wrapper.
- Bounded PCM window reads instead of loading an entire recording into one array.
- Sequential ASR/speaker sessions and explicit native-resource release.
- Quiet seam selection and overlap-aware duplicate-word reconciliation.
- Separation between uncertain attribution and voice-enrollment evidence; predictions do not train themselves.
- Encrypted history with fresh AES-GCM nonces, authenticated filenames, atomic writes and Keystore protection; application backup disabled.
- Verified model provenance/hashes, download resume, old-revision preservation and inference leases.
- Extensive focused regressions, real-speaker fixtures and evidence limitations already documented.
- R8 optimization and isolated native runtimes instead of an unsafe `pickFirst` workaround.
- Source-linked seven-day text/voice retention. This is conversation-granular prototype retention, not the complete production policy.

### Engineering limitations and risks

| Finding | Evidence / impact | Priority |
| --- | --- | --- |
| Importer is unreachable and narrow | Only PCM16, mono, 16 kHz WAV accepted; other rates/channels/codecs rejected. Import requires no microphone permission, but current UI is microphone-first. | P0 |
| Export contract is incompatible | `ConversationViewModel.kt:240–250`, `ConversationStore.kt:152–167`. Generic JSON existence is insufficient for automated scoring. | P0 |
| No empty-result export | `exportTranscript` returns on empty segments; `GlassUi.kt:416–424` has no export action for silence. | P0 contract edge |
| Fresh offline install cannot transcribe | Model readiness depends on an installed download, and no bundled weights exist. DNS lookup of the fixed host failed from this audit environment; global outage is not established. | P0 |
| LLM semantics unresolved | GigaAM CTC is an ASR model, ERes2NetV2 is an embedding model. Neither should silently be called a text LLM. | P0 decision and integration |
| Processing ownership is UI-lifecycle-bound | Work uses `viewModelScope`; recording stops when leaving foreground. Only model download has a foreground service. `FLAG_KEEP_SCREEN_ON` is not robust job ownership. | P1 stability |
| Progress callbacks are discarded | `TranscriptionEngine` emits messages, but ViewModel supplies `{ }`; UI shows one indefinite status. There is no user-facing processing-cancel action. | P1 UX |
| Native cancellation is not guaranteed immediate | Interrupt checks surround `session.run` and embedding calls, not their native execution. Test cancel latency and avoid premature resource release. | P1 stability |
| Approximate word timings | `CtcDecoder.kt:15` spreads frames over input duration; emissions are not phonetic boundaries. Final input is padded to 4,000 samples. Validate short tails and timestamp clipping before alleging a specific timing bug. | P1 scored quality |
| Short replies can be absorbed | 320 ms evidence floor, longer seed evidence, 1.8 s context windows and best-effort completion create a known new-speaker versus fragmentation tradeoff. | P1 scored quality |
| Spectral behavior changes with sample richness | Spectral discovery begins at 160 anchors; shorter recordings use linkage. Long-dialogue improvements cannot be assumed for short evaluator files. | P1 scored quality |
| Speaker count capped at 12 | Explicit `SpeakerClusterer` default; output completion cannot prove the correct count. | Document and test |
| No learned VAD or overlap separation | ASR-dependent contexts and energy heuristics; no independent speech/overlap segmentation stage. Missed ASR speech cannot later acquire correct speaker evidence. | P1 experiments; overlap is P2 |
| Large models and multiple runtimes | FP32 model set documented at approximately 1 GB expanded; Java ORT plus sherpa's renamed C runtime coexist. Actual peak memory unknown. | P1 device measurement |
| All embeddings/words span the session | PCM reads are bounded, but words, contexts and embeddings accumulate. Attribution scans windows for words; bounded anchors do not bound every data structure or all CPU work. | Profile before optimizing |
| 30-minute hard limit | `AudioRecorder` and `PcmWave` enforce it; input lengths in judge tests are unknown. | Clarify and validate |
| Storage failure blocks use | Initialization error leaves history unavailable; no user-facing corrupt-history recovery was found. Whole history is one encrypted JSON document capped at 32 MiB. | P1 recovery; avoid unnecessary DB rewrite |
| Audio persistence differs from old production promises | Current microphone capture writes WAV; unused importer copies audio twice through temporary/stored files. Finally-block deletion is not zero persistence and can be interrupted. | Preserve honest prototype scope; do not reuse importer blindly |
| Retention is coarser than production design | Entire conversation is filtered by start time; source-partial expiry and full derived-data cascades are not implemented. | Later production track |
| Accessibility/design drift | Some 11–15 sp secondary text remains despite old design rules. Long same-speaker UI turns contain many non-lazy phrase children. | P1 usability check; cosmetic redesign is lower priority |

These risks are not all reproduced failures. Do not label a potential OOM, cancellation race or slow rendering path as an observed crash without a device trace.

## 4. Inspected CI and reproducibility

Public GitHub API results inspected on 6 November 2026:

| Run | Commit | Observed result |
| --- | --- | --- |
| [42257595068 — Android CI](https://github.com/pmixay/Ascent/actions/runs/42257595068) | `d7759627` | Workflow **failure**. `Build and validate Android app` **success**, including Python checks, lint/JVM tests, ARM64 assembly/inspection and x86_64 instrumentation. `Score cached two-hour Russian speech evaluation` failed at `Require a completed local-improvement inference cache`. That long job was later removed; this is a historical snapshot. |
| [42257595048 — Model Export](https://github.com/pmixay/Ascent/actions/runs/42257595048) | `d7759627` | **Failure** at `Export and validate downloadable models`; publication skipped. Public annotation only states exit code 1. Exact export failure requires full logs; no specific dependency/model cause is established. |
| [40393180456 — Android CI](https://github.com/pmixay/Ascent/actions/runs/40393180456) | `cf0dec87` | Earlier branch workflow success. |
| [40391014542 — Android CI](https://github.com/pmixay/Ascent/actions/runs/40391014542) | `409bb5f8` | Earlier build and cached-evaluation jobs both success. |
| [40292806058 — Model Export](https://github.com/pmixay/Ascent/actions/runs/40292806058) | `c4cd4f3a` | Earlier model-export workflow success. |

The current Android run published `debug-d7759627`, API-reported archive size **24,358,922 bytes**. This is the artifact ZIP size, not a measured APK/installed-model size. The APK was not downloaded or installed in this audit. Artifact publication currently occurs in the build job independently of the failing evaluation job, so the existence of the artifact does not mean all release gates passed.

### CI issues to address

1. **Superseded.** The former two-hour job had restore-only inference data and failed on a cache miss. The user removed it, together with the inference job, on 7 November 2026 because the long path made push CI too slow. Run the two-hour evaluation on a development machine; do not restore it as a required CI gate.
2. **Superseded.** The former evaluation cache key depended on pipeline/evaluator files, so legitimate edits invalidated it. Development-machine runs should still record model, source and pipeline provenance.
3. **Resolved on 7 November 2026.** `AGENTS.md` now states that long public-corpus inference and scoring stay off CI, while the narrowly scoped six-reader fixture and model-export numerical/speech/speaker checks remain. Those validations are not permission to move the long dialogue study into CI.
4. Model manifest parsers are rigid across Kotlin, Python, server validation and tests: exact model IDs and file sets. Adding a text LLM/VAD or a quantized revision requires coordinated contracts, not copying another file into an existing directory.
5. Evaluation hard-codes the current ASR/speaker hashes. New models need separately versioned baselines and provenance, not modified expected scores that hide regressions.
6. Keep existing workflows, push triggers, R8/native checks and no-local-build policy. Final publication must require all applicable jobs on the same revision. Public long-lived submission links must outlast the current 14-day Actions artifact retention.

## 5. What the quality numbers establish

Repository-reported results associated with inspected successful historical CI:

| Measurement | Value | Scope |
| --- | ---: | --- |
| Source selection | 1,134 clips, 3 actors, >2 h | Acted studio dialogue reconstructed into approximately 5-minute blocks with inserted gaps. |
| Cached WER / CER | 2.78% / 0.78% | Fixed Russian selection; not a phone benchmark. |
| Joint cpWER | 10.26% | Speaker-attributed word streams under one-to-one label permutation. |
| Strict / completed clip-time attribution | 89.90% / 95.31% | Clip-time includes internal silence; **not DER**. Completion includes inferred labels. |
| Historical 800 ms reply result | 33.33% | Earlier reader-disjoint controlled evaluation at `7f38ec2`; not a measured score of current HEAD. It identifies why a separate short-turn gate matters. |
| Later local ASR rerun | WER 2.90%, CER 0.94% | Same source/model hashes but different outputs; runtime cause unresolved in repository documentation. |

The original reserved recordings were inspected to diagnose a female/female merge and influenced subsequent changes. They are diagnostic/regression data now. `roadmap.md` and parts of the scorer still refer to reserved validation, contradicting newer evaluation documentation. A new held-out recording/speaker set is required before claiming generalization.

No current evidence establishes ARM inference RTF, peak RSS/PSS, natural-conversation DER, timestamp MAE/p95, English quality, overlap handling, or end-to-end cold-install offline completion.

## 6. Scope conflicts to resolve in the delivery overlay

- **Embedded models:** new organizer requirement supersedes the old compact/no-weights rule for the competition APK. Keep optimization and integrity checks; replace the weight-absence check with a required-payload check.
- **Input:** audio import supersedes the old prototype's capture-only UI. Read the selected user-owned file; do not delete it or turn import into a permanent app recording archive.
- **Cloud text:** the competition prohibits cloud text processing. Old optional OpenRouter features are outside this release; no server AI should be added to satisfy “LLM”.
- **Watch-first delivery:** this is an older product goal, not a prerequisite of the supplied phone-audio competition. It must not block the competition critical path.
- **Audio handling:** external source files necessarily pre-exist. Prefer direct URI decoding and bounded volatile windows, with a second source read for diarization where needed. Any new app-owned disk audio copy would extend the current prototype exception and needs an explicit scoped decision; import is not blanket authorization to retain recordings.
- **Replay and editing:** playback is an optional slide example, not a requirement. Avoid making it a dependency under the existing audio policy. Text corrections can be optional and must not contaminate raw benchmark outputs.

The referenced [organizer repository template](https://github.com/grigorevmp/hacathon_template) was inspected. It has `src/`, `docs/`, `config/`, `artifacts/demo/` and `artifacts/test-results/`. Its `PROTOCOL.md` and CSV headers describe an acoustic file-transfer task. Adapt those placeholders to this speech task rather than introducing irrelevant transmitter/receiver/distance requirements. Map the existing source location from `src/README.md` if a move would destabilize CI.

## 7. Decisions still requiring external information

Pasha owns obtaining answers without blocking independent implementation:

1. Does “local LLM” mean a distinct text language model, and what useful role must it perform? Until clarified, plan a real on-device text model, not an ASR relabeling.
2. What are the input codecs, sample rates, channel layouts, maximum lengths/sizes and expected speaker counts?
3. Which Android versions, ARM ABI, available RAM and delivery-size limits will be used? “ARM” alone does not prove ARM64 only, although the existing artifact is ARM64.
4. What normalization, diarization metric, timestamp tolerances and RTF timing boundary will judges use? How will overlapping speech be represented/scored?
5. What is the submission deadline and when is the APK frozen?
6. Which physical ARM phones can the four teammates use immediately?

Until answers arrive: retain Android 12+/ARM64 as the declared baseline, prioritize Russian, add English smoke coverage, implement WAV PCM conversion plus common Android-decoded audio formats, and measure complete user-visible processing time. Document assumptions instead of calling them organizer guarantees.
