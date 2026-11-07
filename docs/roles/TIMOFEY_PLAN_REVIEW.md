# Timofey plan reconciliation — 7 November 2026

Baseline: fetched `main` at `f04aaa7260a0ced1293243d8ce255f8e78edec58`.
Work branch: `timofey/plan-next-incomplete`, created from that main revision.
This is a source/CI review and one T1 implementation slice, not release approval.

## Evidence inspected now

- [Android CI 42393688728](https://github.com/pmixay/Ascent/actions/runs/42393688728)
  completed **success** on the exact baseline. Compact job `128466483363` ran
  Python/server/QA tooling, app/Wear lint/builds, the bounded JVM suite, ARM64 APK
  inspection and required x86_64 instrumentation. Embedded job `128466483110`
  ran export validation, asset preparation, ARM64 APK inspection and its required
  offline extraction/recovery/synthetic text-stage instrumentation.
- [Model Export 42393688781](https://github.com/pmixay/Ascent/actions/runs/42393688781),
  job `128466416189`, completed **success** on the same commit, including export,
  numerical/speech/speaker validation and strict-SSH publication. Export was
  executed, not replaced by an ONNX output cache. This does not prove the
  checkpoint download was cold; earlier cold-source evidence remains in
  [T1 status](TIMOFEY_T1_STATUS.md).
- Read the live HTTPS catalog and independently checked metadata byte counts and
  SHA-256 using certificate-verifying system curl. Current manifest:
  `2687dcb65f2415d99bf126f270a33d13b48e1730c9ed3246b18eb71f5e5bc7e7`
  (3,295 bytes); download index:
  `dfaa930e8e58d7c1338d48cfb9c6be5219a52965999c25298f3ba015846c447d`
  (45,614 bytes, 234 chunk records). Manifest checkpoint revision/hash and
  ASR/speaker/token hashes match the frozen source pins. Payload chunks were
  **not** downloaded again; Petya's earlier full transport verification is in
  [server status](PETYA_SERVER_STATUS.md). The local Python installation's default
  CA lookup failed; no TLS bypass or trust-store modification was used.
- GitHub CLI is not installed here. Read-only public API inspection works;
  authenticated Actions administration, publication and signing permissions for
  this session have not been established. No credentials were inspected or changed.

These runs certify **main's baseline**, not this branch's new receipt checks.
No local build, test, lint, native preparation, model export or inference ran.

## Project review and ordered remaining work

Reviewed the product/audit/role contracts, both actual workflows, Gradle/native
packaging, acoustic export/schema/server publication, embedded installation and
paired leases, source/job/text/export orchestration, encrypted history, runtime
checks and QA/submission evidence. Python/JSON syntax checks are static only.
This is not a full security audit or a substitute for physical-device trials.

| Plan section, in order | Existing implementation/evidence | Remaining work |
| --- | --- | --- |
| 11:00 access / red paths | Secure HF source recovery and server repair already exist; current exact-main export/publication succeeds. Both bounded Android jobs pass. Push-only triggers and superseded-run cancellation remain. | Signing/administrative access and phone RAM/OS/ABI/storage inventory are not recorded. These require owner input, not recreated code. Lease/readiness APIs are documented; human handoff approval is not inferred. |
| T1 bootstrap / provenance | Pinned source loader, parity/speech/speaker checks and no-long-corpus-CI policy already implemented. Cache key includes export helpers, dependencies, fixtures, pins and workflow; preparation rehashes model payloads. | First code-level gap selected here: independent binding of cached validation output to those exact local inputs and its producer CI run. Implemented below; new CI still required. |
| T2 embedded packaging | Complete acoustic/Qwen assets, separate schema envelope, hashes/notices, streamed staging, space reserve, atomic promotion, recovery, paired leases and network-free readiness already implemented. Baseline CI exercises extraction/recovery and synthetic text inference. | Do not recreate this work. Cold offline ARM full-pipeline acceptance and Andrey's device-backed approval remain open. |
| T3 native / install behavior | Isolated sherpa C runtime, existing ORT text runtime, R8, required native/payload checks, synthetic corruption/space/interruption tests and false-readiness checks exist. | Actual ARM inference, process-kill/low-space device behavior and separate installed/extraction peak sizes are unmeasured. APK inspection is not installed-size evidence. |
| T4 fixed-configuration measurements | Text-stage count/fallback/time reporting and QA procedures exist. | No complete device table; decode, model load, ASR, diarization, save, total cold/warm RTF and memory/storage evidence remain. Per-stage instrumentation beyond the existing text timer is not established by the current UI. Petya performs device trials; Timofey supplies/reviews tooling. |
| T5 controlled integration | Import/JSON/source/lease/text contracts and implementation are merged on main; both main Android jobs now pass. | New changes need their own reviewed CI; model-quality approval and final exact-build evidence cannot be inferred from merge status. |
| Final release | Internal compact/offline Actions APKs exist. | `versionCode = 1` and CI-generated debug signing are not a configured stable release identity. Protected signing setup, final artifact hashes/size table, durable release archive, exact-build ARM rehearsal, video and submission remain open. No release is promoted here. |

The earlier unchecked role boxes mix implementation, coordination and acceptance.
Only the explicitly evidenced early checks are checked now. Unchecked device and
release criteria must not be reimplemented as placeholders or reported complete.

## T1 implementation in this branch

- Add `scripts/export_provenance.py`: a separate validation receipt inventories
  exact exporter/validator/source/dependency/pin/workflow bytes and frozen reader
  fixtures, fingerprints that inventory, and binds it to the output manifest hash
  and producer commit/run/attempt. It records only after the existing exporter
  succeeds in CI. It does not rerun inference or change numerical/model logic.
- Cache the receipt with the validated export. Version the exact cache key to
  `offline-export-py312-v2`; no fallback prefix or old receipt-less cache is accepted.
  A miss still runs every existing bounded export check. Warm reuse may cross
  commits only when the validation inputs are byte-identical.
- Require the receipt before asset copying/text downloads. Put it inside the
  hashed `bundle.json` envelope and revalidate it during ARM64 APK inspection.
  Missing, mismatched or stale evidence fails closed, even if the cache name
  claims a hit. Acoustic/server wire schemas, model hashes and Kotlin file sets
  remain unchanged. The receipt is CI provenance, **not** a cryptographic signature
  or evidence of ARM performance.
- Add synthetic CI regressions for cold recording/warm reuse, changed source/
  requirements/fixtures/pins, wrong manifest, malformed/missing receipt, producer
  identity, exact-key coverage and refusal before copying/downloading assets.
  APK regressions require matching embedded provenance too.

## Verification / next handoff

Local verification is limited to Python AST, JSON/YAML syntax, source/diff review
and read-only network evidence. The user requested a push and continued work:
receipt implementation `2270a40` is now pushed; its
[Android CI 42395674575](https://github.com/pmixay/Ascent/actions/runs/42395674575)
completed **success**: compact job `128472411722` and embedded job `128472411416`.
The embedded job actually executed dependency setup, export validation and receipt
recording (not skipped), establishing the cold output-cache path. Both artifact
uploads followed successful required checks. Warm receipt reuse is still pending.
No local application tests ran.

Artifacts for this T1 revision (Actions-reported ZIP sizes/digests, **not APK hashes**):

| Artifact | ZIP bytes | ZIP SHA-256 |
| --- | ---: | --- |
| [debug-2270a400](https://github.com/pmixay/Ascent/actions/runs/42395674575/artifacts/13583695505) | 25,228,679 | `1928f66d6dc5537b6842fbd776e770ef0c5bda757c38b92b4ecb30b9fae58e05` |
| [offline-baseline-2270a400](https://github.com/pmixay/Ascent/actions/runs/42395674575/artifacts/13583587938) | 1,496,576,433 | `a5c92dc50293cb7ec01ba768a197cbf90a41c77246a5548cc2cd8d22e9ffccd4` |

These are internal 14-day handoff artifacts, not durable submission releases.

Next source slice: [T3 storage measurements](TIMOFEY_T3_STATUS.md), separating
installed APK/code/data/cache, payload, sampled model-directory peaks and the
fresh extraction budget. Actual ARM and full physical-peak evidence remain open.

Next: run the existing push CI on the reviewed branch, inspect both jobs and a
subsequent exact-input warm reuse, then obtain the missing access/device inventory.
Leave Petya's ARM/QA and Andrey's model approval assigned to their existing role
checklists. Do not restore long corpus CI, alter branch triggers, relax gates or
replace physical trials with another host model study.
