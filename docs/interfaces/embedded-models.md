# Embedded model bootstrap and triple lease contract

T2 implementation branch: `timofey/t2-embedded-bootstrap`. Verification status:
[T2 handoff](../roles/TIMOFEY_T2_STATUS.md). This contract describes code, not a
completed Android/LLM acceptance claim.

## Build inputs and outputs

- Compact: existing default Gradle configuration and `ascent.apk`; no weights.
- Offline baseline: CI uses `-PascentEmbedded=true`, generated assets under
  `app/build/generated/offline-assets/models/` and application ID
  `com.pmixay.ascent.offline`. It produces `ascent-offline.apk` in a separate
  `offline-baseline-<commit>` artifact, never as a replacement for compact checks.
- `config/embedded-models.json` freezes graph/token/config/license hashes and
  immutable model-source revisions. `scripts/prepare_embedded_models.py` accepts
  only a verified acoustic export and pinned text/multilingual downloads. It creates a schema-2
  bundle envelope with actual per-file lengths/hashes. No current catalog data
  enters a build. `EMBEDDED_BUNDLE_SHA256` is compiled from that checked envelope.
- `models/acoustic/` contains the **unchanged** exact acoustic wire-format file
  set. `models/text-model/` contains Qwen's ONNX graph, tokenizer/config and
  licence/notice. Text files are not inserted into the acoustic manifest.
- `models/multilingual-asr/` adds the four pinned Parakeet files and attribution
  notice, all uncompressed (including `tokens.txt`). The user explicitly requested
  this addition. Schema 2 permits at most 2,200,000,000 total payload bytes; the
  acoustic/server schema and limits remain unchanged. [Pins, JNI and CI status](../roles/TIMOFEY_PARAKEET_STATUS.md).
- The receipt-aware preparer requires `--validation-receipt` from the same
  validated export. `bundle.json.export_validation` records source/dependency/
  fixture fingerprints, the acoustic manifest hash and producer CI commit/run/
  attempt. Preparation and APK inspection reject mismatched provenance. This is
  envelope metadata, not a new acoustic payload or server schema. Cold and warm
  paths passed CI, including exact merged `42d4de9`; see the
  [current evidence](../roles/TIMOFEY_REMAINING_STATUS.md).

## Application API

The existing entry points are retained:

```kotlin
ModelDownloads.schedule(context)           // starts local bootstrap in offline build
ModelDownloads.observe(context)            // local preparing / ready / error state
ModelDownloads.startFromVisible(context)   // no network service in offline build

ModelSelection.acquire(context).use { lease ->
    val acoustic = lease.directory
    val text = lease.textDirectory // nullable on compact; required for offline text stage
    val multilingual = lease.multilingualDirectory // nullable on compact; pinned Parakeet group on offline
    // Recognize and diarize using acoustic; close those native sessions.
    // TextStages.run(text, recognized.segments) closes its model before releasing lease.
}
```

One lease protects all three model groups for the whole job. A caller must not persist
paths and use them after releasing that lease. Old revisions and their text/multilingual groups
are cleaned only after the last lease closes. Model leases do not own or delete
the imported audio source.

The installer shares the model transfer mutex, copies bounded streams, checks
space and SHA-256, syncs file contents, promotes all three immutable directories and
only then saves selection. There is no model selected while the initial full
verification is pending. Failed/interrupted staging cannot create readiness;
abandoned staging is removed on the next application launch. Existing validated
revisions are not overwritten in place.

## Readiness and updates

Readiness is local, not a catalog success flag. Every process bootstrap verifies
the entire selected bundle before publishing readiness. Process-local subsequent
reads check file size/mtime and acoustic metadata; they do not continually rehash
the entire model set or claim protection against an attacker preserving all file metadata.
Reopening after external changes rechecks/prepares, and process restart always
requires full verification.

The offline application schedules no model-download or periodic-update workers,
starts no model-download service and never falls back to network if assets are
bad. Its manifest overlay removes Internet/network-state permission and disables
the download service; compact permission/transfer behavior is unchanged.
Preparation is process-owned IO, not an Activity or ViewModel child job.
Killing the process interrupts preparation; it does not continue while stopped.
The next launch recovers. No delete-model/reinstall/server-selection UI is added.

With Parakeet, pinned payloads alone total **2,147,006,228 bytes**, plus the small actual acoustic
manifest/report and attribution notices. This is a lower bound for expanded model
storage, **not** an APK size, installed size or peak requirement. Keep APK assets,
Android's install copies, extracted models, old leased revisions and the 32 MiB
extraction reserve separate in measurements. Removing extracted files cannot
remove immutable APK asset bytes. Petya measures actual devices/storage failures.

The T3 follow-up adds best-effort model-only storage samples and an asynchronous
own-package storage-stat query. It logs only aggregate sizes/build identities under
`AscentModelStorage`; the query does not gate readiness and records unavailable
values explicitly. It neither scans retained content nor changes installation
budgets. [Field definitions and measurement limits](../roles/TIMOFEY_T3_STATUS.md).

## Open acceptance dependencies

The merged ViewModel connects the sequential text stage and job/export flow.
Combined CI passed at `42d4de9` in `42406537594`; actual ARM execution remains required. Andrey
approves provisional model/runtime semantics and pins; Petya runs real ARM
offline inference, memory/time and interrupted-install checks, and retains the [completed server repair evidence](../roles/PETYA_SERVER_STATUS.md). The merged x86_64 CI check validates extraction/recovery and one synthetic
text-stage call through the leased API plus real Parakeet creation; it is not an ARM or quality measurement.
