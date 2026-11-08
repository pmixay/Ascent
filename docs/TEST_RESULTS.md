# Competition test protocol and results

## New real ARM64 demo evidence — 8 November 2026

After the source/CI handoff below, the user explicitly authorized local emulator
setup and a real UI demonstration, not app compilation or automated test tasks.
The existing attempt-2 APK ran on native ARM64 Android 16/API 36 on M1 Pro,
six GiB guest RAM/four cores, airplane mode before first launch. Exact source,
APK/bundle identities match `25ae8eee` above. **Observed:** fresh extraction 10.320 s;
35.670 s constructed public two-reader input; two participants displayed;
Qwen 6/6 phrases with zero fallback, displayed text time 20.8 s; total 46.965 s,
RTF 1.317. Actual six-turn JSON saved through the system picker passed independent
schema/order/time-bound validation; source hash unchanged. One PSS/RSS sample
during formatting was 1,433,580/1,571,080 KiB, not peak memory.

[Real video and recording boundaries](../artifacts/demo/README.md),
[content-free evidence](../artifacts/demo/arm64-evidence.json). This is a known
constructed regression-source demo, not fresh natural quality validation. No WER,
DER, English/Parakeet inference, three-file, physical phone or broad lifecycle
acceptance follows. Earlier "no ARM execution/video" rows below are historical
and superseded only for this observed Russian offline scenario.

## Current submission summary — source 25ae8eee

Inspected 8 November 2026 (MSK): actual GitHub API metadata and authenticated job
logs, without local builds, application tests or corpus inference. Subsequently,
the attempt-2 artifact was downloaded and both ZIP/raw APK hashes verified locally;
the file is ready in `~/Downloads/ascent-offline-25ae8eee.apk`, not installed/run.
The current application source is
[`25ae8eeeef2f96e9784c886a8c96ed4bce11c32e`](https://github.com/pmixay/Ascent/tree/25ae8eeeef2f96e9784c886a8c96ed4bce11c32e),
not the older branch identities in the historical ledger below.

| Current evidence | Observed result | Scope / limitation |
| --- | --- | --- |
| [Android CI 42420616552](https://github.com/pmixay/Ascent/actions/runs/42420616552), run 156, attempt **2** | Both required jobs succeeded | Separately initiated rerun replaced attempt-1 artifacts; source/version unchanged, debug signatures changed |
| Compact job `128554620091` | Shared tooling, JVM/lint/build, APK inspection and x86_64 runtime checks succeeded; **25 required cases executed without skips** | Count confirmed from actual `Verified instrumentation XML` log output |
| Embedded job `128554620245` | ARM64 payload/signature inspection and offline x86_64 runtime succeeded; **four required cases executed without skips** | Cold extraction/recovery plus one synthetic Qwen call, Parakeet creation, silent ViewModel import/export and cancellation/restart/background checks; not speech-quality inference |
| Offline raw APK | 2,167,298,546 bytes; SHA-256 `5fe9f857db4bbce6bb20ee36d2edb525a764e5cc38a0b34499bc5005846a0e4d` | Attempt-2 CI SDK-verified identity, not attempt 1's APK |
| Available offline ZIP | 2,167,298,696 bytes; SHA-256 `d17d0abd84d6558227fde5d9b73ea09cd4a6bfe3b9ec36d45a18147742067f95` | [Artifact 13594571558](https://github.com/pmixay/Ascent/actions/runs/42420616552/artifacts/13594571558), expires 14 November, 22:54:39 UTC; temporary, not durable public publication |
| Signing | Actual offline certificate `d6cd89c07d2e4babaa6b082b8107214da4e1e1ea0a62ea0a94f2a127b0765575`; `unprotected-debug` | Signature checked in CI; stable owner-key acceptance not established |
| Full evidence collector | **Pass on actual attempt 2** | Unchanged `collect_ci_evidence.inspect` checked both jobs/artifacts, exact APK identity, 25/four executions and two matching storage records; TLS verification used system CA trust |
| Real ARM/offline speech, local-LLM feasibility and phone WER/RTF | **Not established** | x86_64 success and the supplied compact-prototype video do not prove these conditions |
| Supplied prototype screen recording | **Observed, limited demo** | [26-second silent MP4](../artifacts/demo/ascent-prototype-2026-11-08.mp4) shows capture, one-speaker result and JSON button, but explicitly reports text model unavailable; exact APK/device/ABI unknown; not an offline acceptance pass |

The four embedded methods are mandatory in the existing workflow and passed:
`coldBootstrapSelectsBothGroupsWithoutDownloadWork`,
`parakeetRecognizerLoadsPinnedFilesWithMinifiedJniConfiguration`,
`viewModelCancelDoubleTapAndBackgroundLeaveOneCoherentJobAndANewJobStartsAfterwards`,
`viewModelSilentImportCompletesAndExportsAnEmptyTranscriptWithoutTouchingTheSource`.
Their fixtures are synthetic and do not establish natural call/meeting quality.
Rotation/process-kill and real provider behavior are not inferred from the
ViewModel-only synthetic coverage. Wear lint/assembly are included in compact CI;
watch pairing/capture acceptance is still unverified.

### Existing measured speech results (not new candidate/phone measurements)

These published studies are reused, not rerun. The [model report](hackathon/model-report.md)
holds exact model sources, normalization, splits, conditions and fuller tables.

| Measurement | Existing result | Evidence boundary |
| --- | --- | --- |
| Historical constructed Russian Dialogs, 1,134 clips / 15,740 words / 7,232.6 s | WER 2.79% (439/15,740), cpWER 10.28% (1,618/15,740); strict/completed clip-time attribution 89.90%/95.31% | Historical CI `42363045969`, public reference ASR export and cached Kotlin attribution; not natural-conversation DER, not current `AsrGain`/ARM quality; old validation is diagnostic |
| Separate Russian validation study, 959 clips / 10,053 words | WER 4.30% (432/10,053) | x86 host, reference export; recordings frozen before that study, not a new unseen set for subsequent tuning |
| Simulated telephone Russian validation | WER 4.63% | Host channel simulation, not real Android call integration |
| English continuous AMI meetings, six 10-min excerpts / 8,539 reference words | GigaAM WER 32.32% (2,760/8,539); Parakeet v3 28.62% (2,444/8,539) | Host, headset mix with overlaps; no app diarization score; now inspected, not an unseen future holdout |
| AMI matched-word starts, Parakeet v3 | Absolute median/p95 80/240 ms; 96.2% within 250 ms | Host, matched-word annotation comparison, not every phrase boundary or phone timing |
| Text model, 120 development clips / 2,115 words / 1,067.59 s | Sentence-end F1 0.456, capitals 88.98%; added host RTF 0.081 | Qwen presentation-only task; not a WER improvement or ARM runtime measurement |
| Recognition speed on Russian study | Host RTF about 0.069, float32, development | Excludes decoding/model loading/diarization/Qwen/save; **not total app RTF** |

No minimum new corpus size, two-device report or numerical WER/RTF pass threshold
is specified by the brief. Existing own tests are reportable with these boundaries;
unmeasured required behavior and existing internal acceptance gates stay open.
No new benchmark was run to fill this table.

### Actual x86_64/API 36 storage observations from the current run

| Bytes unless specified | Fresh extraction | Recovery with old triple leased |
| --- | ---: | ---: |
| Installed x86_64 APK file lengths | 2,170,610,323 | 2,170,610,323 |
| Android reported app/code bytes | 2,248,077,312 | 2,248,077,312 |
| Android reported data including cache | 2,147,840,000 | 2,818,449,408 |
| Model-root logical bytes before | 0 | 2,147,011,852 |
| Model-root logical bytes after / sampled peak | 2,147,012,171 | 4,294,024,023 |
| Extraction/validation elapsed ms | 9,604 | 23,531 |
| Successful / failed samples | 38 / 0 | 63 / 0 |

These observations are asynchronous package statistics and sampled logical lower
bounds, not exact physical high-water allocation, ARM installed size or RAM.
The payload-plus-reserve figure 2,180,566,603 bytes is a budget, not a measured
phone free-space requirement. See [full candidate identity](SUBMISSION.md) and
[machine-readable submission record](../artifacts/submission.json).

## Historical execution register and remaining device cases

No fresh offline ARM install, three-file sequence or held-out quality run has been
recorded for this checkout. A prototype video was subsequently supplied, not an
identified offline-candidate trial; its scope is recorded above. The historical
table below is not a claim of passing device tests. Fill results only from actual
inspected evidence tied to its observed build and conditions.

| Evidence | Status | APK commit / SHA-256 | Model manifest / SHA-256 | Device / Android / ABI | Evidence link or failure |
| --- | --- | --- | --- | --- | --- |
| Current main Android and export/publication checks | pass (baseline only) | `f04aaa7`; APK hash not collected in this review | Live acoustic manifest `2687dcb65f2415d99bf126f270a33d13b48e1730c9ed3246b18eb71f5e5bc7e7`; not the embedded bundle hash | GitHub CI x86_64; ARM64 APK inspection | [Android 42393688728](https://github.com/pmixay/Ascent/actions/runs/42393688728), [Model Export 42393688781](https://github.com/pmixay/Ascent/actions/runs/42393688781). Both Android jobs and export/publication passed. No physical ARM trial. |
| Cached-export validation receipt follow-up | pass (cold and exact warm paths) | Cold `2270a40` / `62fbe3f`, warm `d9776cd` | Pinned payloads and embedded validation receipt checked | GitHub CI x86_64; ARM64 APK inspection; no local executable tests | Cold [42395674575](https://github.com/pmixay/Ascent/actions/runs/42395674575), warm [42400311459](https://github.com/pmixay/Ascent/actions/runs/42400311459), both jobs passed. [Exact warm receipt/producer and raw APK identities](roles/TIMOFEY_T4_STATUS.md#already-verified-before-this-slice). |
| Separate installed-package/model storage diagnostics | pass (CI only) | `62fbe3f`; ARM64 offline APK SHA-256 `e2b63de5a759f1aa7a83ae16ba0e6ee54fb5355b28f734e205a2e6d02ff71622` | Bundle `ef0378fb122baaec876bf9e5a02bd2dc91a9252eb007b3a29407abfd7a5be878` | Installed x86_64 emulator / API 36; separate ARM64 archive inspection | [Run 42396663795](https://github.com/pmixay/Ascent/actions/runs/42396663795), jobs `128475396232` / `128475396006`, both success. [Actual measured table](roles/TIMOFEY_T3_STATUS.md#inspected-ci-measurements--62fbe3f). Samples are logical lower bounds, not physical peaks; ARM and full RTF/memory remain open. |
| Current Petya follow-up / phone candidate | See exact-run checks in PR | `codex/petya-open-work` / PR #5 head | Same-run identities in PR | GitHub CI x86_64 / ARM APK inspection; phone deferred | [PR #5](https://github.com/pmixay/Ascent/pull/5) tracks the current workflow outcome, required-case execution and checked compact/offline APK and bundle identities. Historical runs below do not replace the head's evidence. |
| Real ViewModel cancellation integration | failed before cleanup-context fix | `570e9c7` | Synthetic silence with installed models | GitHub CI x86_64; ARM unmeasured | [Pasha run 42415482292](https://github.com/pmixay/Ascent/actions/runs/42415482292), job `128532506629`: exact cancellation-status assertion exposed a cleanup warning after returning to the cancelled dispatcher. Follow-up protects the caller context across IO cleanup and retains the assertion, adding interruption-marker removal. Current combined evidence stays in PR #5. |
| Combined signing/provenance integration | both jobs failed at signer parsing | `6b7f47f` | Same three-group pinned model set | GitHub CI; no local executable tests | [Run 42413293380](https://github.com/pmixay/Ascent/actions/runs/42413293380): both APK checks and SDK signature verification completed, then the parser refused the signer output. Follow-up accepts supported numbered, SDK-range and Build Tools 37 scheme-prefixed labels only with one signer, one certificate across ranges and the protected pin retained. [Actual SDK format](https://android.googlesource.com/platform/tools/apksig/+/d177d78e4649d08499d90e6cbf9ca47d77c9b865). |
| Petya mode/timing integration iteration | compact fail; embedded pass | `b1eaa0b` | `887e90af1860dd0b5504a3e07c8dcb20178756bd4b379f30a8277e115f554a85` | GitHub CI x86_64 | [Run 42408927517](https://github.com/pmixay/Ascent/actions/runs/42408927517): builds/shared checks passed; both required embedded cases passed. Compact's new accessible picker interaction used provider text-search, unsupported by Compose. Follow-up traverses virtual children with exact-label matching; assertions are retained. Not a phone acceptance result. |
| Petya accessible mode-selection iteration | compact fail; embedded pass | `d75f086` | schema-2 three-group bundle | GitHub CI x86_64 | [Run 42410278517](https://github.com/pmixay/Ascent/actions/runs/42410278517): node traversal reaches the first mode change; the compact test then cannot reach the third dialog choice. Follow-up checks the open dialog and scrolls its real accessibility container before selecting off-screen options. Both embedded cases passed again. |
| Integrated dependency baseline | pass (CI only) | `42d4de9` | schema 2 | GitHub CI x86_64 / ARM APK inspection | [Run 42406537594](https://github.com/pmixay/Ascent/actions/runs/42406537594), both jobs succeeded. Does not verify the new Petya source. |
| Historical merged Android candidate / exact identity | pass (bounded CI only) | `42d4de9`; offline raw APK `85ef6f4289f417a01acd9776aded88f559353237278af1cf68fb807c98dcf8e5` | Bundle `7db029bc6e7b88171db775894694be4e9d3da374a15ad29fff3de391a2ec073b` | x86_64 / API 36 runtime; ARM64 archive inspection only | [42406537594](https://github.com/pmixay/Ascent/actions/runs/42406537594), jobs `128505367928` / `128505367796`, both success; 25/2 required cases actually executed. [Exact APK/ZIP/storage evidence and blockers](roles/TIMOFEY_REMAINING_STATUS.md). No ARM approval. |
| CI version, signature and protected-key/evidence tooling | failed at APK identity recording | `a185d23`; never reuse the earlier APK's hash | Acoustic/text/Parakeet weights unchanged | [Run 42408809405](https://github.com/pmixay/Ascent/actions/runs/42408809405): both jobs stopped at the new identity check. Integration follow-up is tracked in PR #5 | [Release procedure](RELEASE.md). No signing key supplied; synthetic CI tests cannot establish actual protected-key signing. All original bounded checks remain required. |
| APK identity diagnostic iteration | failed before parser integration | `198a8d1` | Existing payload checks passed | ARM64 archive/SDK verification; runtime and uploads did not execute | [42415925712](https://github.com/pmixay/Ascent/actions/runs/42415925712): both jobs found zero numbered-signer certificate lines. Integrated parsing supports legacy numbered/SDK-range and Build Tools 37 scheme-prefixed labels, requiring one signer, one certificate across ranges and the protected pin. Current combined evidence is in PR #5. |
| SDK-range text parser iteration | fail; PEM/DER hashing follow-up pending CI | `5c34010` | Existing payload checks passed | ARM64 archive/SDK verification; no runtime/uploads | [42416839672](https://github.com/pmixay/Ascent/actions/runs/42416839672): both jobs reported one signer/one digest line but no parsed certificate. The next check requests PEM and hashes actual DER, removing human-readable fingerprint/label dependencies while retaining single-signer/certificate and pinned-key gates. |
| Historical Timofey identity candidate and real evidence collection | pass (internal debug candidate only) | `91e2598`; offline raw APK `05f7f46be043669cd70a72b339841ccf40cad107e8e7a567c7fe526cbf5884c6` | Bundle `a21ab274dfa969d8019eb9e6aabe7d139c891399473fcc203ebadc9bc5fd42d6`; weights unchanged | ARM64 signature/archive checks; actual x86_64/API 36 runtime; read-only real GitHub evidence collection | [42417416875](https://github.com/pmixay/Ascent/actions/runs/42417416875): both jobs success, 25/two required cases executed. Collector succeeded on actual API/logs. [Exact version/certificate/APK/ZIP/storage record](roles/TIMOFEY_REMAINING_STATUS.md#historical-verified-candidate--91e2598). No key supplied; protected-key signing and ARM/final acceptance remain open. |
| Bounded CI optimization and tokenizer integration | compact pass; embedded fail before tokenizer fix | `fa69075` | — | GitHub CI x86_64 | [Run 42386120641](https://github.com/pmixay/Ascent/actions/runs/42386120641): shared Python/tooling tests, Android/Wear lint/builds, JVM suite and all 25 required compact runtime cases passed. Directory-only export, validation and explicit cache saves passed. Embedded ARM64 packaging passed; synthetic text startup exposed Android rejecting `UNICODE_CHARACTER_CLASS`. Follow-up uses explicit Unicode whitespace classes; no test is skipped. |
| Combined optimized-instrumentation iteration | fail (before boundary fix) | `0f54ffb` | — | GitHub CI x86_64 | [Run 42382620664](https://github.com/pmixay/Ascent/actions/runs/42382620664), compact job `128433324084`: R8 removed AudioSources, a default transcript constructor and WorkManager APIs used across the APK boundary. Targeted debug/test API rules added; required execution gates retained. |
| Acoustic model export and strict-SSH publication | pass | export `a83fdff` | `759890e4b6c3f6c640f117fc7b624a5dd8667a89284d991c0fd6681b76ec84c5` | GitHub CI + HTTPS server | [Attempt 2](https://github.com/pmixay/Ascent/actions/runs/42369233159/attempts/2), job `128424780303`; all 234 compressed chunks and six expanded payload hashes independently verified. [Evidence](roles/PETYA_SERVER_STATUS.md). |
| Combined codec/service iteration | fail (before follow-up) | `6f66cc2` | — | GitHub CI x86_64 | [Run 42383364630](https://github.com/pmixay/Ascent/actions/runs/42383364630), compact job `128435503791`: FLAC extractor already returned PCM, valid encoder-priming PTS was negative, and work-testing needed the shared coroutine factory ABI. The embedded job `128435503568` also failed on a removed `kotlinx.coroutines.BuildersKt` before bootstrap. Follow-up handles extractor PCM with the same bounded ring and preserves coroutine public APIs. |
| Exclusive stage timing / total RTF hooks | pass (bounded CI checks only) | `68487f1` | Models/thresholds unchanged | JVM suite and existing instrumented text probe; no ARM trial | [42402862397](https://github.com/pmixay/Ascent/actions/runs/42402862397), jobs `128494207680` / `128494208032`: both success, 25 compact cases and one required embedded case executed. Full ARM pipeline, PSS/RSS and cold/warm measurements remain open. |
| Explicit Parakeet packaging and R8 creation smoke | pass (bounded CI; ARM open) | `dbbce95074d9338beb970c01e76dbd04273c5f52`; raw APK hash uncollected | Schema 2; existing acoustic/Parakeet pins unchanged; bundle digest uncollected | ARM64 archive inspection; actual x86_64 install and minified recognizer creation | [42404340446](https://github.com/pmixay/Ascent/actions/runs/42404340446), jobs `128498687976` / `128498687716` both success. Both named embedded methods are mandatory. [Evidence limits](roles/TIMOFEY_PARAKEET_STATUS.md#evidence-status): follow-up artifact/log retrieval failed due to network errors; mode UI/ARM quality/speed remain open. |
| Fresh first launch with all networks disabled | not run | — | — | ARM64 phone | Record model activation. |
| Three unrelated imports and JSON exports | not run | — | — | ARM64 phone | Follow [QA_JUDGE_SCRIPT.md](QA_JUDGE_SCRIPT.md). |
| Silence and independent export validation | not run | — | — | ARM64 phone | Include exact empty array. |
| Negative/lifecycle/repeated-use matrix | not run | — | — | ARM64 phone | Record each case ID. |
| Second ARM64 device or emulator | not run | — | — | — | Record hardware and OS. |
| Final demonstration of delivered offline APK | not run | — | — | — | Supplied prototype clip has no exact APK identity and says text model unavailable; see `artifacts/demo/README.md`. |
| AMI English meeting host ASR/probe evaluation | pass (host only) | main `2133dfa`; no APK | GigaAM / Parakeet / Auto, report pins | Development machine | Six frozen 10-minute meetings; [denominators and results](hackathon/model-report.md#second-recognizer-english-and-multilingual-candidates-host). Aggregate results now inspected; not a new unseen set or phone result. |
| AMI English meeting source/crop integrity | pass (data prep only) | — | — | Development machine | Six source/crop hashes and manual annotation archive matched the [frozen manifest](../tools/qa/fixtures/ami_holdout_manifest.json). No inference or score. |
| DataHive Russian call source integrity | pass (data prep only) | — | — | Development machine | Six original MP3 byte counts/hashes match the [source manifest](../tools/qa/fixtures/datahive_ru_source_manifest.json); manual references and inference are pending. |
| Galaxy Watch7 capture and direct Bluetooth phone flow | not run | — | — | SM-L305F Wear OS 6.0 + Samsung Android 17 | Follow watch cases in [QA_JUDGE_SCRIPT.md](QA_JUDGE_SCRIPT.md); record phone/watch APK hashes separately. |

The old constructed Russian Dialogs evaluation and newer development ASR study remain documented in the [audit](HACKATHON_AUDIT.md) and [ASR baseline](../prototypes/phone-transcriber/docs/asr-baseline.md). They are not a fresh judge-style phone result. Original reserved Dialogs recordings were inspected during tuning and are diagnostic data. Six AMI English meeting excerpts are frozen by source/crop hash before inference, with one inspected host ASR/probe study now recorded; manual reference review and APK behavior remain open. Six DataHive Russian calls are source-frozen but have no supplied transcripts; a Russian meeting-like holdout is still missing. Keep predictions from any untouched set unseen until model/config selection is fixed. [HOLDOUT_CUSTODY.md](HOLDOUT_CUSTODY.md) records source candidates and the freeze procedure.

For each actual trial, record case ID, priority, status (`pass`, `fail`, `blocked`, `not run`), APK/model hashes, device/OS/ABI/RAM/free storage, authorized input source/hash/license, format/duration, steps, expected and observed behavior, content-free error evidence, timings, owner, fix commit and retest outcome. Store content-free per-case rows in [results.csv](../artifacts/test-results/results.csv); private references stay outside the repository. Include a failed case even if later fixed, linked to its retest.

Report quality with denominators and splits: reference words and S/D/I for WER; cpWER and one-to-one per-recording speaker mapping; unknown/inferred fractions; time-aligned DER only when references and overlap/collar policy support it; matched/unmatched turn counts, boundary median/p95; full processing time, media duration and `RTF = processing_seconds / media_seconds`; cold/warm times, peak PSS/RSS and device thermal state. Report Russian, English, telephone-like and meeting-like results separately. Do not report a jury score estimate.

The standalone JSON checker validates schema, UTF-8, exact fields, integer millisecond bounds and start-time order. It does not judge transcript accuracy. Its tests belong in the existing Android CI Python checks; local Android builds and application tests are prohibited by [AGENTS.md](../AGENTS.md).
