# Timofey — remaining-work reconciliation

This supersedes pending-CI/network notes for the candidate below, not the original
schedule. The user requested a full project review and completion of all independent
Timofey tasks. Review covered the product/audit/role requirements, both actual
workflows, build/native/signing configuration, model export/server schemas and
provenance, installer/recovery/leases, source/job/text/export/encrypted-storage
boundaries, measurement hooks, focused tests and QA/submission material. This is a
source/CI review, not a full security audit or physical-device validation.

## Exact merged candidate already inspected

Source: `42d4de9a62dda31920cb82ea477e88876e545301`, containing main `2133dfa`,
Andrey's AUTO probe/restart/actual-mode metadata and Timofey's timers/Parakeet
packaging. [Android CI 42406537594](https://github.com/pmixay/Ascent/actions/runs/42406537594),
attempt 1: both jobs **success**, inspected through the API and their actual logs.

| ARM64 archive | Bytes | Raw APK SHA-256 | Actions artifact |
| --- | ---: | --- | --- |
| Compact | 25,296,861 | `8c7b7d1162fb36a7b7cf464f62d53839a0953433e56c7b3f4de5ca3f663e7a90` | [debug-42d4de9a / 13587193685](https://github.com/pmixay/Ascent/actions/runs/42406537594/artifacts/13587193685) |
| Offline | 2,167,276,006 | `85ef6f4289f417a01acd9776aded88f559353237278af1cf68fb807c98dcf8e5` | [offline-baseline-42d4de9a / 13587174064](https://github.com/pmixay/Ascent/actions/runs/42406537594/artifacts/13587174064) |

- Compact job `128505367928`: tooling/JVM/lint/build/native checks and **25 distinct
  required instrumented cases executed without skips**. Offline job `128505367796`:
  ARM64 packaging checks, **two distinct required cases without skips**, actual
  x86_64 offline extraction, synthetic text inference and Parakeet recognizer creation.
- Embedded bundle SHA-256:
  `7db029bc6e7b88171db775894694be4e9d3da374a15ad29fff3de391a2ec073b`.
  Expanded payload: **2,147,012,159 bytes**. Fresh extraction payload plus 32 MiB
  reserve: **2,180,566,591 bytes**, not total install/physical peak storage.
- Exact export cache key:
  `Linux-X64-offline-export-py312-v2-3b9ec3236f890bb91edbd7919ab6473773c95de88bc2ecbb7047692d8f143e88`.
  Input fingerprint `cdd13e836cb2b1438cc9f304218af619d37c2a2241df20d5eae8f3a257e5ae5c`.
  Warm receipt preserves cold producer `dbbce95074d9338beb970c01e76dbd04273c5f52`,
  run `42404340446`, attempt 1. It does not claim a fresh export on `42d4de9`.
- Actions ZIP sizes/digests, **not APK identities**: compact **25,296,995** bytes,
  `2524f1044d67992545191516115d4ff2632e71c6350daf6ad4593f928e466d09`;
  offline **2,167,276,156** bytes,
  `d5894ca6b235526bc7fb9ab5caffd9de782ef24da7f5a53d8de4b186d8ee3e6e`.
  Both API expiries are **14 November 2026, 19:30:28 UTC** despite requested 14-day
  retention. No durable download is established by these links.

These are recovered identities for the checked merged candidate, **not** hashes
for subsequent signing/version/provenance changes. Historical `dbbce95` certifies
the cold schema-2 path; this exact merged revision certifies warm reuse/integration.
No later revision inherits green CI merely because its inputs appear unchanged.

### Actual x86_64 / API 36 storage observations

| Bytes unless stated | Fresh extraction | Old triple leased during recovery |
| --- | ---: | ---: |
| Installed APK file lengths | 2,170,587,703 | 2,170,587,703 |
| Android app/code statistic | 2,248,024,064 | 2,248,024,064 |
| Android data including cache | 2,147,844,096 | 4,294,975,488 |
| Cache component, already included above | 405,504 | 405,504 |
| Model-root logical bytes before | 0 | 2,147,011,840 |
| Model-root logical bytes after install | 2,147,012,159 | 4,294,023,999 |
| Sampled peak model-root logical bytes | 2,147,012,159 | 4,294,023,999 |
| Sampled minimum volume free bytes | 6,956,261,376 | 4,810,289,152 |
| Extraction/validation elapsed ms | 15,315 | 21,929 |
| Successful / failed samples | 62 / 0 | 45 / 0 |

The recovery baseline includes a deliberately damaged old notice. Final model
samples precede lease-aware cleanup; package queries are asynchronous observations.
These are logical/sample lower bounds, not ARM installed sizes, physical high-water
marks, end-to-end first-launch time, PSS/RSS, ASR quality or processing RTF.

## Independently completed or prepared in this follow-up

- Recovered exact merged-run/artifact/bundle identities, actual gate execution and
  storage evidence; no multi-GB APK/model download or local inference was necessary.
- Established session permissions without exposing credentials: repository API
  reports **push/pull/triage**, not admin/maintain. Secret-name inventory contains
  only `ASCENT_MODEL_DEPLOY_KEY` and `ASCENT_MODEL_HOST_KEY`; no signing key exists.
  No GitHub Releases exist. No secret values, ACLs or server settings were changed.
  Subsequent read-only environment inventory shows only `android-baseline`, not
  `android-signing`. The main protection endpoint returns 404, which establishes
  neither an accessible protection rule nor administrator access. Environment
  creation/rules and actual key configuration remain the administrator's task.
- Reconciled already integrated source/import/JSON/text/lease contracts against the
  actual code. Three model groups use a separate embedded schema 2; the acoustic
  Kotlin/Python/server schema 1 and its limits remain unchanged. Cross-language
  pin/installer/native/APK regressions are already exercised by the inspected CI.
- Added source for CI version identity, optional owner-supplied protected signing,
  actual APK package/certificate verification, content-free structured identity/
  step summaries and read-only exact-run evidence collection. Partial signing or
  wrong certificates fail closed; no key is generated and no new workflow, trigger,
  long inference job, upload artifact or release publication is introduced.
- Added synthetic CI regressions for absent/partial/required signing, private-file
  permissions/cleanup, version/ABI/debuggable/certificate mismatches, SDK failure,
  stale/missing/skipped/expired run/artifact evidence and credential-free log redirects.
  **New changes require their own CI; local work was static parsing/diff review only.**
- Prepared [release/signing/publication instructions](../RELEASE.md), corrected
  three-group/size guidance and recorded cross-role blockers below. No acceptance
  sign-off is inferred from a contract document or an unchecked role task.
- Integrated Pasha's sole branch-only commit `f3e4386` (documentation sign-off),
  preserving newer mode/lease/timing tasks. The merge was conflict-free; all of his
  application source was already present through main. No application code or
  model decision was imported under the guise of a documentation merge.
- Follow-up source/signing/evidence implementation is `071c225`; Pasha's sign-off
  merge and preserved assignments are included in pushed `a185d23ee2c4b0dd501e1749633c61aab7ed269b`.
  Its [run 42408809405](https://github.com/pmixay/Ascent/actions/runs/42408809405)
  failed in both ARM64 identity-recording steps, after successful compilation/R8/
  payload inspection. Compact shared tooling/lint/JVM checks passed, but runtime
  checks and artifact upload did not run; this is **not a passing candidate**.
  The initially generic failure message did not distinguish SDK verification from
  metadata parsing. Follow-up adds bounded content-free check diagnostics without
  printing SDK stderr, signer DN or secrets; all gates remain enforced. Its exact
  cause and corrected run must be inspected before marking the tooling verified.
  Diagnostic `198a8d1` / [42415925712](https://github.com/pmixay/Ascent/actions/runs/42415925712)
  failed after successful SDK signature verification and package/version checks:
  the numbered-signer regex parsed zero certificate lines. Upstream Android
  `ApkSignerTool` also emits SDK-range signer labels for v3.1, not only `Signer #1`.
  Follow-up explicitly parses both formats, requires the verbose single-signer
  count and one unique certificate across all ranges, and still rejects different
  rotated keys, multiple signers, source-stamp-only output and incorrect cert pins.
  New synthetic regressions cover these formats; actual corrected CI remains required.
  That SDK-label attempt `5c34010` / [42416839672](https://github.com/pmixay/Ascent/actions/runs/42416839672)
  also failed: verbose SDK verification reported one signer/one certificate digest
  line, but the human-readable text parser still matched no certificate. It did
  not establish which text-format difference caused that mismatch. The next
  change removes that formatting dependency: request `--print-certs-pem` and
  calculate SHA-256 from the verified certificate's DER bytes. One signer/unique
  certificate, size/base64 bounds and independent pinned-cert comparison remain;
  a different rotated key, missing/truncated PEM or source-stamp-only output fails.
  Actual new CI and real evidence collection still need inspection; no failed
  iteration's APK is an accepted candidate or uploaded download.

## Tasks that require another person

| Remaining gate | Required owner/action | Timofey output ready / why blocked |
| --- | --- | --- |
| Stable protected signing and release ref | Team key custodian/repository administrator supplies four `android-signing` environment secrets, deployment-branch protection rules, independent cert pin, protected ref and required-signing variable as in `docs/RELEASE.md`. | Fail-closed CI support and certificate verification prepared. No team-owned key or admin permission is available; generating/replacing one silently would not establish custody. |
| Application mode wiring | Pasha connects Auto/Russian/Other languages to the engine and leased `multilingualDirectory`, and displays actual used mode; Petya reviews UX. | Models, triple lease, AUTO engine and JNI creation gate exist. Normal ViewModel still uses default GigaAM, so packaging is not selectable-mode acceptance. |
| Full lifecycle and bounded LLM work | Pasha completes/reviews per-job text-stage time cap and actual ViewModel cancel→new job/rotation/background/kill/reopen coverage. | Stage timers and existing job/source protections are present. Current cancellation/context cap is not a total text-stage elapsed-work limit. |
| ARM inventory and runtime qualification | Petya supplies actual device/OS/ABI/RAM/storage and executes clean offline first launch, GigaAM/Parakeet/text, JSON and three-file trials; Andrey approves real ARM model/runtime evidence. | Exact candidate, hashes, setup and measurement procedure ready. x86_64 creation/text checks cannot establish ARM inference or local-LLM acceptance. |
| Device timing/memory/storage and negatives | Petya records cold/warm stages and total RTF including LLM/save/cleanup, available PSS/RSS, thermal/run conditions, extraction low-space/corruption/process-kill and repeated-job outcomes. Andrey reviews quality/config. | Volatile stage timers and content-free storage probe are implemented. No phone is attached/identified here and no measurements are invented. |
| Freeze and final exact-build QA | Pasha accepts application/lifecycle/export; Andrey signs model/runtime/config; Petya validates the identified final APK and films it. | Reviewed main is integrated and prior exact merged CI passed. Final freeze cannot precede unresolved mandatory app/ARM work; changes require new CI/retests. |
| Durable complete APK download | Petya/server operator supplies an authorized HTTPS location for the >2 GiB file, then Timofey verifies final bytes and links. | GitHub Releases requires each file under 2 GiB. Existing server/SSH publisher is model-only, not permission to upload APKs. No split APK, dropped/compressed model or arbitrary server route is substituted. |
| Submission package and video | Petya assembles the exact-build video/source/install/results links; Pasha gives ready/incomplete sign-off; all confirm final identities. | CI provenance and release checklist prepared; no video, device sign-off or submission receipt exists. |

Final acceptance boxes in `TIMOFEY.md` stay open. Independent tooling work and
inspected CI are reported separately from these missing human/device decisions.
