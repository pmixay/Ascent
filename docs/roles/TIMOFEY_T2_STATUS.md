# Timofey T2 — embedded offline asset baseline

Current integration update (7 November): the full branch through `a989b2e` is now
merged into `claude/improve-app-peter`. The newer user policy keeps long corpus
inference/scoring off push CI. Paired acoustic/text pipeline integration is in
source, and [server publication is repaired](PETYA_SERVER_STATUS.md). Combined
revision CI and user-performed ARM/offline tests remain pending. The observations
and failures below describe earlier teammate revisions.


Branch: `timofey/t2-embedded-bootstrap`. Implementation and CI verification are
in progress. **This is an internal asset/bootstrap baseline, not a compliant
competition release.** No device acceptance or completed LLM pipeline is claimed.

## Packaging and preparation

- The existing push-only **Ascent Android CI** keeps its compact `debug-<sha>`
  artifact unchanged and adds `offline-baseline-<sha>` / `ascent-offline.apk`.
  The offline app uses `com.pmixay.ascent.offline`, so installing the internal
  baseline cannot overwrite compact-app history/model selection.
- The new job reuses the existing fixed GigaAM export validation, from pinned
  official HF checkpoint inputs, independently of catalog/TLS/publication. It
  retains numerical frontend/logit, CTC, public speech and speaker smoke checks.
  This bounded validation reuse is explicit in `AGENTS.md`; no new corpus-inference
  job or text-model quality study is added.
- Source pin: `prototypes/phone-transcriber/config/embedded-models.json`.
  Acoustic graph/token/license identities match T1. Provisional text assets use
  Andrey's ONNX Qwen 0.5B contract, including tokenizer/config, Apache licence and
  attribution notice, without new JNI libraries or llama.cpp.
- A separately versioned `models/bundle.json` hashes every actual file, including
  the validated acoustic manifest/report. Its digest is compiled into BuildConfig.
  APK inspection streams and hashes the actual archive entries and compares them
  to the build inputs/source pin. Unexpected or absent payloads fail the check.
  ONNX assets are uncompressed. No standalone weights/corpus/reports are uploaded.
- The existing acoustic schema remains unchanged across Kotlin/Python/server:
  text assets live beside, not inside, the acoustic directory. No server migration
  is necessary to install the embedded baseline.

## Android bootstrap and lease contract

`ModelDownloads.schedule/observe/startFromVisible` routes the offline build to
process-owned IO preparation, without scheduling download/update work or starting
the download foreground service. An offline-only manifest overlay removes Internet/
network-state permission and disables that service. Compact behavior is unchanged. Both model groups
are streamed with bounded buffers, space checks, exact lengths and hashes into
app-private staging, promoted into immutable revision directories, then selected.
Interrupted staging is removed on the next launch; no imported source is touched.

The existing model-selection lock/lease policy protects old acoustic **and paired
text** revisions until every job releases them. `ModelLease.directory` remains the
acoustic directory and nullable `ModelLease.textDirectory` exposes the paired text
assets to Pasha; see [callable contract](../interfaces/embedded-models.md).
Initial preparation revalidates full hashes, not a saved success
flag. Later process-local readiness also checks file size/mtime snapshots; this
is not a claim to detect an attacker preserving file metadata. Automatic updates
are disabled in the offline build for deterministic evaluation.

## Added checks / evidence boundary

- Synthetic JVM installation tests cover complete extraction, warm hash recheck,
  same-length corruption, low space, interrupted staging and old-revision safety.
- Python packaging checks cover missing/corrupt/compressed payloads, source pin
  changes, Kotlin/text pin agreement and HTTPS transport restrictions.
- The new x86_64 instrumentation check disables Wi-Fi/data, extracts actual APK
  assets and acquires both model groups. It does **not** run speech/text inference
  or establish ARM memory, speed, codec or competition acceptance.
- Final-revision CI and identities remain **pending**; the first complete baseline
  and its exact identities are recorded below. Local work is editing/static
  inspection only, no Android build/toolchain/tests/model export/inference.

### Initial inspected CI iteration

[Run 42372529303](https://github.com/pmixay/Ascent/actions/runs/42372529303),
`ad0f997`: Python packaging tests and source export/parity/speaker checks passed.
The offline asset preparer produced **1,476,434,947 expanded payload bytes**,
including actual acoustic metadata/report and text notice; bundle SHA-256
`14b601dcbe15e721f21ecbef81a45ce7f106e401266e228fba86fe1f9cca0528`.
This is that run's generated asset set, not an APK/installed-size measurement.

The workflow failed because Gradle's `java` DSL accessor shadowed the package
in `java.security.MessageDigest`. Explicit import fixed it in `5f11823`;
the failure was not skipped or converted to success. Later recovery/permission
tests need their own exact-commit run. No APK acceptance is inferred from this
initial failed iteration.

### First complete offline baseline — 5f11823

[Run 42372944155](https://github.com/pmixay/Ascent/actions/runs/42372944155),
`5f11823791983612b0d5acd7807cf0f80331b124`: **all four jobs passed** — compact
build/native checks, embedded baseline, inference-cache guards and required
two-hour scoring. Embedded job `128404618780` reused real export validation,
verified every APK payload and completed x86_64 first-launch asset extraction with
Wi-Fi/mobile data disabled. This does not measure ARM inference or execute the
application's text stage.

| Identity | Value |
| --- | --- |
| APK bytes | 1,495,589,086 (not installed size) |
| APK SHA-256 | `d0b939e1894c95e16ce5e9e6deb73b6a3d743f658295e5b2524c82d7ead02511` |
| Bundle SHA-256 | `d27d4bf4062761164eb8a18f0c68f4da1e6fcd688660a4c5e4e17f1c0fe4ed94` |
| Expanded payload bytes | 1,476,434,947 |
| Internal artifact | [offline-baseline-5f118237](https://github.com/pmixay/Ascent/actions/runs/42372944155/artifacts/13574414548) |

The artifact's 14-day retention is for team handoff only, not durable submission
publication. Final code `43c83cd` adds offline-only network-permission removal,
real-asset corruption/paired-lease recovery and interrupted-promotion regression
checks. Its compact/embedded jobs passed in run `42373911477`, but the overall
run was cancelled with inference/scoring incomplete. The subsequent main merge
also adds source-set isolation and mandatory non-skipped instrumentation XML
checks: [merge review](TIMOFEY_MERGE_REVIEW.md). Earlier job success does not
certify those stronger checks or the merged revision.

## Assigned cross-role blockers — kept open

| Owner | Required work | Role checklist |
| --- | --- | --- |
| Pasha | Integrate actual text stage after acoustic sessions close, use the paired lease, retain raw text/lexical invariants and expose real success/fallback/time; complete URI/job/export path | [PASHA.md](PASHA.md#t2-application-integration-handoff--still-required) |
| Andrey | Approve provisional ONNX runtime/assets from exact-build ARM evidence, review numerical/provenance and text invariants; provide updated immutable pins if selection changes | [ANDREY.md](ANDREY.md#t2-model-handoff-from-timofey) |
| Petya | Own actual Android/ARM cold-offline inference, extraction negatives and stage measurements on the exact CI APK; test UI readiness/errors | [PETYA.md](PETYA.md#explicit-t2-handoff-from-timofey--user-assigned) |
| Petya | Repair model-server HTTPS/catalog and authenticate changed SSH host key via trusted console before updating secret/publication; never bypass TLS/SSH checks | Same Petya checklist; [T1 server evidence](TIMOFEY_T1_STATUS.md) |

All remain qualification blockers where required. Bundling a provisional text
model does not mean the application executes it; the default engine still needs
Pasha's integration. No real Android device is available in this session.
