# Competition submission handoff

Prepared 8 November 2026; latest user deadline **09:00 MSK**. Earlier four-person
20:00 schedules are historical. This handoff reuses the implemented pipeline and
existing measurements; no new model study, local build or invented device result.

**Status: incomplete internal candidate.** Documents and inspected CI evidence
are available, together with a supplied prototype recording. Durable APK publication,
an offline-pipeline demonstration, final acceptance
and organizer receipt are not. This document is not authorization to promote an
unqualified build, replace a signing key or upload to an arbitrary server.

## Candidate identity

| Item | Inspected value |
| --- | --- |
| Application source | [`25ae8eeeef2f96e9784c886a8c96ed4bce11c32e`](https://github.com/pmixay/Ascent/tree/25ae8eeeef2f96e9784c886a8c96ed4bce11c32e) |
| CI | [42420616552](https://github.com/pmixay/Ascent/actions/runs/42420616552), run 156, attempt **2**, both jobs successful; actual full read-only collector succeeded |
| Actual required executions | 25 compact, four offline, without skips, confirmed in actual job logs |
| Download now | [offline-baseline-25ae8eee ZIP](https://github.com/pmixay/Ascent/actions/runs/42420616552/artifacts/13594571558); temporary, may require GitHub sign-in/read access |
| ZIP bytes / SHA-256 | 2,167,298,696 / `d17d0abd84d6558227fde5d9b73ea09cd4a6bfe3b9ec36d45a18147742067f95` |
| ZIP expiry | 14 November 2026, 22:54:39 UTC (actual repository retention: seven days) |
| APK inside ZIP | `ascent-offline.apk`, **2,167,298,546 bytes** |
| Raw APK SHA-256 | `5fe9f857db4bbce6bb20ee36d2edb525a764e5cc38a0b34499bc5005846a0e4d` |
| Package / platform | `com.pmixay.ascent.offline`, Android 12+ / ARM64 |
| Version | `0.1.156-25ae8eee-offline-baseline`, code 156 |
| Signer certificate SHA-256 | `d6cd89c07d2e4babaa6b082b8107214da4e1e1ea0a62ea0a94f2a127b0765575` |
| Signing mode | `unprotected-debug`, **not** team stable-signing acceptance |
| Generated embedded bundle SHA-256 | `8b2ff5619d632f78d5c3473615026d2b032de156c381f3b99f3d77d164d0c792` |
| Acoustic manifest SHA-256 | `f04b401ac91a240ce8e8dbee16ffaba6eea2f0fef0287feba79d8ecb69fe28d2` |
| Expanded embedded payload | 2,147,012,171 bytes; not total installed/peak storage |
| Durable APK URL | **Pending authorized operator** |
| Prototype demonstration | [Supplied MP4](../artifacts/demo/ascent-prototype-2026-11-08.mp4), 26 s, no audio; text model unavailable; filmed APK unknown |
| Offline candidate demonstration | **Pending**; do not assign this candidate's hash to the prototype video |
| Submission receipt | **Not recorded** |

**Latest user handoff:** prepare a verified local copy of this CI-built APK; the
user will upload it to **Google Drive** and supply the public link for README.
The user supplied a prototype video, now included with observed limitations in
[the demo record](../artifacts/demo/README.md). It shows the compact path and a
missing text model, not the offline candidate. Petya's offline-pipeline recording
remains needed. Drive publication is not complete merely because an owner is
assigned. No new local app build is needed.

Source/API/log inspection was read-only. Identities above originated in CI's
SDK-verified `ASCENT_APK_IDENTITY` and GitHub artifact API. The attempt-2 ZIP was
then downloaded, extracted and **both ZIP and raw APK SHA-256 verified locally**.
The upload-ready file is `/Users/timofey/Downloads/ascent-offline-25ae8eee.apk`.
This is the existing CI APK, not a local build; it was not installed or executed.
The ZIP hash is **not** the raw APK hash. [Machine-readable record](../artifacts/submission.json).

Documentation edits after the source pin do not change this candidate's identity.
Any later application/runtime/asset/signing change needs new CI and affected
acceptance evidence; do not silently substitute another APK under these hashes.

### Rerun reconciliation

Attempt 1 initially exposed only offline in the inspected inventory. Its artifact
later returned 404 during download. A separately initiated rerun (not started by
this session) completed as attempt **2**, replacing both artifacts. Current compact
artifact is [13594382036](https://github.com/pmixay/Ascent/actions/runs/42420616552/artifacts/13594382036).
The unchanged full `collect_ci_evidence.py` inspection succeeded against attempt 2,
including both artifacts, 25/four executions and matching storage records. Python
used the system CA trust store with TLS verification enabled. Both APKs have new
debug certificates/digests despite unchanged source/version; this table and the
manifest identify attempt 2 only. No CI/artifact requirement was weakened.

The embedded validation receipt retains successful export producer `ad2ef22`,
run `42419555513`, attempt 1. That run was later cancelled; the successful export
receipt is not a claim that its whole workflow passed. Model graph pins remain
unchanged. The complete consuming candidate's workflow above did pass.

## Literal jury requirements versus internal acceptance

The [brief](hackathon/hackathon-brief-2026-11.pdf), pages 9–11 and 14–21, plus
[clarifications](hackathon/jury-clarifications.md), require an Android application,
local LLM, embedded usable models, audio import, ASR, automatic speakers, phrase
times, display, file/JSON export, offline on-device processing, and seven report
materials. Russian comes first, English second; call-like and meeting-like input
both matter. Experts evaluate on ARM. No cloud computation or prepared answers.

The written requirements do not prescribe a separate physical-phone test report,
two devices, a two-hour fresh study, a particular production-key management system,
specific WER/RTF pass thresholds, RAM/thermal measurement or watch delivery. These
are not newly assigned literal jury prerequisites. Nevertheless the application's
required behavior must really work; existing project CI/privacy/signing/ARM gates
remain open until supported, and no unrun check can be called passed.

## Seven report materials

| Required material | Prepared location | Remaining action |
| --- | --- | --- |
| APK | Candidate link and identity above | Authorized durable whole-file host and final promotion after existing gates |
| Source | Pinned source above; [source map](../src/README.md) | Documentation/video handoff is separate from the APK source pin |
| Installation / usage | [INSTALLATION.md](INSTALLATION.md) | Follow actual installed behavior if later evidence changes it |
| Architecture / models | [ARCHITECTURE.md](ARCHITECTURE.md), [model report](hackathon/model-report.md), [configuration](../config/README.md) | No new model discovery; real ARM model/runtime approval still open |
| Limitations | [LIMITATIONS.md](LIMITATIONS.md) | Preserve unmeasured conditions and any reproduced failures |
| Own test results | [TEST_RESULTS.md](TEST_RESULTS.md), [content-free case rows](../artifacts/test-results/results.csv) | Existing host/CI results are supplied honestly; do not invent phone WER/RTF |
| Video | [Prototype MP4 and offline recording script](../artifacts/demo/README.md) | Record the offline pipeline; supplied recording explicitly lacks local text assets and has no APK identity |

## Remaining operator handoff

1. **APK/signing:** key custodian and repository administrator resolve the existing
   stable-signing gate using [RELEASE.md](RELEASE.md). No key is generated/replaced
   here. If identity changes, repeat the applicable build/acceptance handoff.
2. **Actual Android execution/video:** on an available ARM phone or ARM Android
   emulator, execute the identified APK, not a desktop recognizer. Use the short
   video script; report any failed/unavailable Qwen stage. No Android/ADB, attached
    device was available in the document-preparation session. The subsequently
    supplied prototype video reports an unavailable text model, so it is not
    evidence of the offline candidate's full pipeline.
3. **APK hosting:** the user selected Google Drive and will upload the complete
   verified local CI-built APK, then supply a publicly accessible download link.
   Add that actual link to README and the manifest and check external access;
   a private Drive URL or upload intention is not completed publication.
   More generally, an authorized operator supplies a durable HTTPS destination
   for the complete >2 GiB APK. GitHub Releases cannot hold this single file.
   The restricted model publisher cannot upload APKs. Do not split the APK, drop
   models, compress mandatory assets, change that publisher or choose an unapproved
   third party beyond the user's selected Drive handoff. Download the published
   bytes and compare raw APK hash/length. Petya owns the actual video capture.
4. **Final decision/submission:** update manifest URLs, actual video build/device,
   evidence and approval state only from real results. Verify external access,
   send the repository link through the organizer's channel and record the receipt.
   No organizer account/channel access or submission confirmation is supplied here.

If gates are still open at the deadline, communicate an **incomplete candidate**,
not a compliant accepted release. Missing evidence is not silently converted to pass.

## Copyable handoff message (incomplete candidate)

> Ascent — offline Android conversation transcription. Source and report:
> https://github.com/pmixay/Ascent . Candidate source: `25ae8eee`.
> The offline ARM64 APK, exact identity and temporary download are listed in
> `docs/SUBMISSION.md`; both Android CI jobs passed with 25/four required cases.
> ASR, speaker attribution and Qwen text processing are implemented locally;
> full ARM spoken-audio acceptance is unverified. A prototype video is supplied;
> the offline-pipeline recording and durable APK hosting are pending, and
> final release acceptance remain pending. Existing host measurements are labeled
> separately from Android evidence. This is an incomplete candidate, not a claim
> of completed qualification.

Replace pending statements only after actual completion. Do not send a message
claiming video/publication/ARM acceptance when those links/results do not exist.
