# Competition test protocol and results

No fresh offline ARM install, three-file sequence, held-out quality run or demonstration video has been recorded for this checkout. The table below is an execution register, not a claim of passing tests. Fill it only from an inspected CI run or a physical-device trial on a named APK/model revision.

| Evidence | Status | APK commit / SHA-256 | Model manifest / SHA-256 | Device / Android / ABI | Evidence link or failure |
| --- | --- | --- | --- | --- | --- |
| Current branch Android build and required checks | not run | — | — | GitHub CI | Inspect all applicable jobs on the final commit. |
| Fresh first launch with all networks disabled | not run | — | — | ARM64 phone | Record model activation. |
| Three unrelated imports and JSON exports | not run | — | — | ARM64 phone | Follow [QA_JUDGE_SCRIPT.md](QA_JUDGE_SCRIPT.md). |
| Silence and independent export validation | not run | — | — | ARM64 phone | Include exact empty array. |
| Negative/lifecycle/repeated-use matrix | not run | — | — | ARM64 phone | Record each case ID. |
| Second ARM64 device or emulator | not run | — | — | — | Record hardware and OS. |
| Final demonstration of delivered APK | not run | — | — | ARM64 phone | Link video in `artifacts/demo/README.md`. |
| AMI English meeting source/crop integrity | pass (data prep only) | — | — | Development machine | Six source/crop hashes and manual annotation archive matched the [frozen manifest](../tools/qa/fixtures/ami_holdout_manifest.json). No inference or score. |
| DataHive Russian call source integrity | pass (data prep only) | — | — | Development machine | Six original MP3 byte counts/hashes match the [source manifest](../tools/qa/fixtures/datahive_ru_source_manifest.json); manual references and inference are pending. |
| Galaxy Watch7 capture and direct Bluetooth phone flow | not run | — | — | SM-L305F Wear OS 6.0 + Samsung Android 17 | Follow watch cases in [QA_JUDGE_SCRIPT.md](QA_JUDGE_SCRIPT.md); record phone/watch APK hashes separately. |

The old constructed Russian Dialogs evaluation and newer development ASR study remain documented in the [audit](HACKATHON_AUDIT.md) and [ASR baseline](../prototypes/phone-transcriber/docs/asr-baseline.md). They are not a fresh judge-style phone result. Original reserved Dialogs recordings were inspected during tuning and are diagnostic data. Six AMI English meeting excerpts are frozen by source/crop hash before inference, but their references and app behavior are unreviewed. Six DataHive Russian calls are source-frozen but have no supplied transcripts; a Russian meeting-like holdout is still missing. Keep predictions from any untouched set unseen until model/config selection is fixed. [HOLDOUT_CUSTODY.md](HOLDOUT_CUSTODY.md) records source candidates and the freeze procedure.

For each actual trial, record case ID, priority, status (`pass`, `fail`, `blocked`, `not run`), APK/model hashes, device/OS/ABI/RAM/free storage, authorized input source/hash/license, format/duration, steps, expected and observed behavior, content-free error evidence, timings, owner, fix commit and retest outcome. Store content-free per-case rows in [results.csv](../artifacts/test-results/results.csv); private references stay outside the repository. Include a failed case even if later fixed, linked to its retest.

Report quality with denominators and splits: reference words and S/D/I for WER; cpWER and one-to-one per-recording speaker mapping; unknown/inferred fractions; time-aligned DER only when references and overlap/collar policy support it; matched/unmatched turn counts, boundary median/p95; full processing time, media duration and `RTF = processing_seconds / media_seconds`; cold/warm times, peak PSS/RSS and device thermal state. Report Russian, English, telephone-like and meeting-like results separately. Do not report a jury score estimate.

The standalone JSON checker validates schema, UTF-8, exact fields, integer millisecond bounds and start-time order. It does not judge transcript accuracy. Its tests belong in the existing Android CI Python checks; local Android builds and application tests are prohibited by [AGENTS.md](../AGENTS.md).
