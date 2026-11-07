# Timofey T2 — embedded offline asset baseline

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
the download foreground service. Compact behavior is unchanged. Both model groups
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
- CI results and APK/bundle hashes: **pending**. Local work is editing/static
  inspection only, no Android build/toolchain/tests/model export/inference.

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
