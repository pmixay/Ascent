# Timofey T3 — separate storage measurements

Work branch: `timofey/plan-next-incomplete`. This follows the receipt-aware T1
push `2270a40` ([Android CI 42395674575](https://github.com/pmixay/Ascent/actions/runs/42395674575),
both jobs **passed**, including actual cold export/receipt generation). T3 implementation
`62fbe3f4a36361dbd37b35299f2bd8cf2fc928de` also passed both jobs in
[Android CI 42396663795](https://github.com/pmixay/Ascent/actions/runs/42396663795).
No physical-device or final acceptance is claimed. Measured emulator values follow.

## Implemented measurement path

The offline bootstrap samples **only the app's model directory** before copying,
at most once every 250 ms during copying, and after installation. It counts file
lengths without reading contents, does not follow symlinks, and records failed
samples as missing rather than zero. The bounded record holds counts only, not
paths, filenames, source URIs, transcripts, audio, embeddings or a sample history.

After successful extraction/validation, a separate IO coroutine queries Android's
own-package `StorageStatsManager`. Its query does not gate model selection,
readiness or inference. Platform refusal produces `unavailable`, not an invented
installed size; diagnostics failure cannot invalidate working models. No new
permissions, UI actions, remote calls or diagnostic files are added. See the
[official API permission contract](https://developer.android.com/reference/android/app/usage/StorageStatsManager):
own-package queries do not need usage-access permission.

One JSON record is emitted under log tag `AscentModelStorage`, with:

| Field | Meaning / evidence boundary |
| --- | --- |
| `source_commit`, `bundle_sha256`, `apk_abi`, `device_abis`, `android_api` | Compiled CI source revision, checked bundle identity and execution conditions. Source is `unknown` outside a CI-provided revision; no signing or ARM quality claim follows. |
| `installed_apk_files_bytes` | Logical lengths of installed base/split APK files. The Actions artifact ZIP size is a different measurement. |
| `installed_app_bytes` | Android's own-package app/code storage statistic, including platform-accounted installed code/libraries. Do not add APK length to this value. |
| `installed_data_including_cache_bytes`, `installed_cache_bytes` | Android's data statistic and its cache component. **Do not add cache again.** Includes aggregate app data, not only models. |
| `expanded_payload_bytes` | Exact manifest-listed payload bytes copied/verified by the installer; not physical filesystem allocation. |
| `copied_asset_bytes`, `preparation_kind` | Distinguishes extraction from fully verified warm reuse; warm reuse copies zero asset bytes. |
| `extraction_and_validation_elapsed_ms` | Time from opening the bundled descriptor through installer validation/copying and the final sample. Includes sampling overhead; excludes later selection/cleanup, the asynchronous package-storage query and all acoustic/text processing. Not total first-launch time or RTF. |
| `model_directory_logical_bytes_before`, `model_directory_logical_bytes_after_install` | Model-root file lengths, including old revisions/staging present at each observation. The final observation precedes selection's lease-aware cleanup. |
| `sampled_peak_model_directory_logical_bytes` | Largest observed model-root length, **a sampled lower bound**, not an exact physical disk high-water mark. May include abandoned staging before recovery. |
| `sampled_minimum_volume_free_bytes`, `volume_free_bytes_before`, `volume_free_bytes_after_install` | Available space on the model volume. Other processes and filesystem accounting also affect these values; their difference is not app-only consumption. |
| `fresh_extraction_payload_and_reserve_bytes` | Installer's required new free-space budget: payload plus the existing 32 MiB reserve. This is a requirement estimate, not an observed peak. Does not include APK installation, metadata/slack or pre-existing leased revisions. |
| `successful_samples`, `failed_samples`, `peak_scope`, `storage_query_started_elapsed_ms` | Completeness, scope and boot-relative query-start marker. Missing values are JSON `null`, never measured zero. |

Only the latest content-free record stays in process memory. No content-bearing
telemetry or persistent diagnostic cache is introduced. Compact downloads and
application inference/model choices are unchanged.

## CI and physical-device procedure

The existing required offline instrumentation method now waits for the real
own-package storage query, checks a successful fresh extraction and matching
source/bundle/ABI, and requires nonzero installed APK/app/payload sizes. It still
checks network-disabled bootstrap, actual synthetic text inference, corruption
recovery and paired-lease safety. R8 remains enabled; only the new instrumentation
API boundary is retained. Successful CI prints only this content-free log tag.
Synthetic JVM tests cover sampling/throttling/cleanup peaks, missing observations,
symlink exclusion and separating the reserve budget from observations.

Petya/user device collection, using a CI-built APK and an **already available**
ADB installation (do not install a local Android toolchain):

1. Record exact CI run, commit, APK SHA-256 and bundle SHA-256, phone/OS/ABI/RAM and
   free storage. Install offline and open with networking disabled as prescribed
   in [QA_JUDGE_SCRIPT.md](../QA_JUDGE_SCRIPT.md).
2. Wait for readiness, then collect only the dedicated aggregate record:

   ```sh
   adb logcat -d -s AscentModelStorage:I '*:S'
   ```

   With multiple devices use ADB's explicit `-s DEVICE` selector. Match the source,
   bundle and ARM64 ABI; do not reuse an older build's line. Copy numerical fields
   and conditions into the test ledger, not unrestricted logcat dumps.
3. Distinguish cold `extracted` from `verified-reuse`. Reopening a still-running
   prepared process does not perform another preparation or produce a new sample.
   Any process-restart trial and initial clean-install trial remain separate cases.
4. Keep actual physical allocation/high-water evidence, low-space/process-kill
   negatives and the full processing-stage/RTF/memory table **open** until measured.
   This storage instrumentation is not the missing full benchmark harness.

The x86_64 CI measurement is about its installed emulator APK, **not** the
downloadable ARM64 artifact or a phone. No existing device/release gate is checked
merely because measurement tooling exists.

## Inspected CI measurements — 62fbe3f

Compact job `128475396232` and embedded job `128475396006` both succeeded.
The embedded job's console contains actual storage records after the required
instrumentation method passed. Source/bundle/ABI identities were checked by the
test, not inferred from an uploaded file. Export/receipt generation executed on
this revision too; the changed workflow correctly invalidated the older cache.

### Downloadable ARM64 APK — inspection only

| Item | Measured identity |
| --- | --- |
| Offline APK bytes | 1,496,578,255 |
| Offline APK SHA-256 | `e2b63de5a759f1aa7a83ae16ba0e6ee54fb5355b28f734e205a2e6d02ff71622` |
| Embedded bundle SHA-256 | `ef0378fb122baaec876bf9e5a02bd2dc91a9252eb007b3a29407abfd7a5be878` |
| Expanded payload bytes | 1,476,434,947 |
| Compact APK bytes | 25,231,345 |

These are actual APK-file figures from packaging steps, not Actions ZIP sizes.
No ARM installation or inference follows from these inspection measurements.

### Installed x86_64 emulator variant — Android API 36

Both records below identify `62fbe3f`, the bundle above and `apk_abi=x86_64`.
The emulator's advertised secondary ARM ABI is **not** ARM execution evidence.
The second case deliberately damages the old notice while a paired lease is
held, then re-extracts without deleting that leased revision.

| Measurement (bytes unless stated) | Fresh extraction | Corruption recovery with old lease |
| --- | ---: | ---: |
| Installed base/split APK lengths | 1,499,889,980 | 1,499,889,980 |
| Android app/code statistic | 1,577,127,936 | 1,577,127,936 |
| Android data, including cache | 1,477,242,880 | 2,953,781,248 |
| Cache component (already included above) | 421,888 | 421,888 |
| Model-root logical bytes before | 0 | 1,476,434,628 |
| Model-root logical bytes after install | 1,476,434,947 | 2,952,869,575 |
| Sampled peak model-root logical bytes | 1,476,434,947 | 2,952,869,575 |
| Sampled minimum volume free bytes | 4,084,113,408 | 2,607,362,048 |
| Successful / failed samples | 41 / 0 | 38 / 0 |
| Extraction/validation time (ms, not total readiness or RTF) | 10,025 | 14,968 |

Both copied 1,476,434,947 asset bytes. The shared fresh-extraction payload-plus-
reserve budget is 1,509,989,379 bytes; it is not either observed peak. The old
corrupted notice explains the recovery baseline's slightly smaller logical size.
The final observations precede lease-aware cleanup. Android's asynchronous app
data query and model-only observations are separate measurements, not guaranteed
atomic snapshots. No maximum-duration or phone performance claim is made.

Follow-up: print raw checked APK SHA-256 in **both** packaging paths (compact
previously reported only its size; offline already printed a digest). Synthetic
archive regressions ensure a failed integrity/ABI/weight gate emits no verified
identity. This follow-up changes no export inputs; its next push is the intended
warm-receipt validation. Its actual result must be inspected separately.
