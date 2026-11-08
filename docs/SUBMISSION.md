# Competition submission handoff

**Latest demo update:** [real offline ARM64 video](../artifacts/demo/ascent-offline-arm64-25ae8eee.mp4)
now demonstrates this exact attempt-2 APK on Android 16/API 36 ARM64 emulator.
Russian import, two-reader attribution, Qwen 6/6 phrases/zero fallback and actual
JSON save/independent schema validation succeeded offline. [Scope/evidence](../artifacts/demo/README.md).
The file is constructed public regression input, not fresh natural quality data.
Additional offline device runs on 8 November verified WAV, MP3 and M4A imports
end-to-end (each 6/6 phrases, zero fallback, validated JSON export).
The Google Drive APK was **fully re-downloaded and its SHA-256 matches the local
CI APK exactly**. Earlier missing-demo/ARM/remote-hash statements below are
historical for those specific scenarios; English/Parakeet, three-file/physical-device
and broader acceptance remain open. No final release sign-off or organizer receipt
is inferred.

Prepared 8 November 2026; latest user deadline **09:00 MSK**. Earlier four-person
20:00 schedules are historical. This handoff reuses the implemented pipeline and
existing measurements; no new model study, local build or invented device result.

**Status: internal candidate with all seven report materials present, not an
accepted release.** Documents and inspected CI evidence are available, together
with a recorded offline-pipeline ARM64 demonstration, a supplied prototype
recording and a Google Drive APK link whose full bytes were re-downloaded and
hash-matched. Organizer submission and receipt, plus the existing release gates,
are not done. This document is not authorization to promote an unqualified
build, replace a signing key or upload to an arbitrary server.

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
| User-published APK URL | [Google Drive](https://drive.google.com/file/d/1lC7Li-dU0CfahqT3Pn8S-8aXor2poaVA/view?usp=sharing); **fully re-downloaded 8 Nov 2026: 2,167,298,546 bytes, SHA-256 matches the local CI APK** |
| Prototype demonstration | [Supplied MP4](../artifacts/demo/ascent-prototype-2026-11-08.mp4), 26 s, no audio; text model unavailable; filmed APK unknown |
| Offline candidate demonstration | **[ascent-offline-arm64-25ae8eee.mp4](../artifacts/demo/ascent-offline-arm64-25ae8eee.mp4)** — exact candidate, ARM64 Android 16 emulator, offline, Qwen 6/6, JSON export validated; [evidence](../artifacts/demo/arm64-evidence.json) |
| Submission receipt | **Not recorded** |

**Latest user handoff:** the verified local copy of this CI-built APK was
uploaded to **Google Drive** (link above) and independently re-downloaded in
full — byte count and SHA-256 match the local CI APK.
The user supplied a prototype video, now included with observed limitations in
[the demo record](../artifacts/demo/README.md); it shows the compact path and a
missing text model, not the offline candidate. The assistant then recorded the
real offline-pipeline video on an authorized ARM64 emulator with the exact
candidate APK. No new local app build was needed.

Source/API/log inspection was read-only. Identities above originated in CI's
SDK-verified `ASCENT_APK_IDENTITY` and GitHub artifact API. The attempt-2 ZIP was
then downloaded, extracted and **both ZIP and raw APK SHA-256 verified locally**.
The upload-ready file is `/Users/timofey/Downloads/ascent-offline-25ae8eee.apk`.
This is the existing CI APK, not a local build; it was subsequently installed and
executed on an ARM64 Android 16 emulator (see the demo evidence above). The ZIP
hash is **not** the raw APK hash. [Machine-readable record](../artifacts/submission.json).

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
| APK | [Google Drive link and identity above](#candidate-identity), full-file hash re-verified | Published for the jury; stable-signing/release acceptance remains a separate internal gate |
| Source | Pinned source above; [source map](../src/README.md) | Documentation/video handoff is separate from the APK source pin |
| Installation / usage | [INSTALLATION.md](INSTALLATION.md) | Follow actual installed behavior if later evidence changes it |
| Architecture / models | [ARCHITECTURE.md](ARCHITECTURE.md), [model report](hackathon/model-report.md), [configuration](../config/README.md) | No new model discovery; real ARM model/runtime approval still open |
| Limitations | [LIMITATIONS.md](LIMITATIONS.md) | Preserve unmeasured conditions and any reproduced failures |
| Own test results | [TEST_RESULTS.md](TEST_RESULTS.md), [content-free case rows](../artifacts/test-results/results.csv) | Existing host/CI/ARM results are supplied honestly; do not invent phone WER/RTF |
| Video | [Offline ARM64 demo](../artifacts/demo/ascent-offline-arm64-25ae8eee.mp4) + [evidence](../artifacts/demo/arm64-evidence.json); prototype clip retained separately | Recorded and published; no further video is required by the brief |

## Remaining operator handoff

1. **APK/signing:** key custodian and repository administrator resolve the existing
   stable-signing gate using [RELEASE.md](RELEASE.md). No key is generated/replaced
   here. If identity changes, repeat the applicable build/acceptance handoff.
2. **Actual Android execution/video — done:** the identified APK was executed on
   a native ARM64 Android 16 emulator (airplane mode before first launch) and the
   offline pipeline recorded: import, Qwen 6/6 with zero fallback, participants,
   actual JSON save and independent validation. WAV, MP3 and M4A imports were
   each verified end-to-end offline. [Evidence](../artifacts/demo/arm64-evidence.json).
   Physical-phone behavior remains untested.
3. **APK hosting — done for jury purposes:** the user published the complete
   verified CI-built APK on Google Drive, the link is in README and the manifest,
   and the full file was independently re-downloaded with a matching SHA-256 and
   byte count. Durable long-term hosting beyond the jury handoff, and GitHub
   Releases' under-2-GiB limit for this >2 GiB file, remain release-stage concerns,
   not jury prerequisites. Do not split the APK, drop models or compress
   mandatory assets to work around hosting limits.
4. **Final decision/submission:** the manifest URLs, actual video build/device and
   evidence above now reflect real results. The remaining step is external: send
   the repository link through the organizer's channel and record the receipt.
   No organizer account/channel access or submission confirmation is supplied here.

All seven jury report materials are present in this repository. The remaining
open items are release-stage gates (stable signing, physical-phone and broader
quality acceptance) and the organizer submission itself; none of them may be
reported as passed without actual evidence.

## Copyable handoff message

> Ascent — offline Android conversation transcription. Source and full report:
> https://github.com/pmixay/Ascent . Candidate source: `25ae8eee`
> (CI run 42420616552, attempt 2, both jobs passed, 25/four required cases).
> APK (2.17 GB, SHA-256 `5fe9f857db4bbce6bb20ee36d2edb525a764e5cc38a0b34499bc5005846a0e4d`,
> re-downloaded and hash-verified):
> https://drive.google.com/file/d/1lC7Li-dU0CfahqT3Pn8S-8aXor2poaVA/view?usp=sharing
> All seven report materials (APK, source, installation, architecture/models,
> limitations, own tests, video) are in the repository. The demo video shows the
> exact APK offline on an ARM64 Android 16 emulator: file import, Russian ASR,
> two participants, local Qwen processing (6/6 phrases, no fallback) and a JSON
> export that passes an independent schema validator; WAV, MP3 and M4A imports
> were each verified end-to-end offline. Known limits: demo input is a constructed
> public two-reader clip; English/Parakeet, physical-phone behavior and
> natural-conversation WER are unmeasured; the build carries a debug signature.
> This is a submitted candidate with stated limits, not a claim of completed
> production qualification.

Replace or extend this message only with results that actually exist. Do not
claim physical-device, English or organizer-receipt evidence that was not obtained.
