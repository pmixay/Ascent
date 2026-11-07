# Timofey — explicit Parakeet embedded-model handoff

The user explicitly requested four Parakeet assets in the embedded APK,
uncompressed, build-time hashes, CC-BY-4.0 attribution, R8/JNI safety and a real
instrumented recognizer-creation smoke test. This supersedes the earlier
[read-only integration blocker](TIMOFEY_T5_STATUS.md) as an implementation task,
not as automatic acceptance of the new size/runtime on ARM.

## Source integration

- Merged `origin/main` `2429e141807d619e01cac35f38f3ef14f25a578b` into the task
  branch in `1f5dbe2`. Constructor conflicts preserve Andrey's recognition mode
  and directory seam plus Timofey's optional stage timers. Existing default
  callers use GigaAM; the ViewModel now supplies `timings` by name. Parakeet
  session loading is measured in the ASR-load bucket when a caller selects it.
- The previous T4 source `68487f1` passed both jobs in
  [run 42402862397](https://github.com/pmixay/Ascent/actions/runs/42402862397), compact
  `128494207680` and embedded `128494208032`. Its 25 compact cases and original
  embedded timing/text/bootstrap case actually executed. This does **not**
  certify the merged/new Parakeet code.

## Payload and packaging

`config/embedded-models.json` pins `csukuangfj/sherpa-onnx-nemo-parakeet-tdt-0.6b-v3-int8`
at `ed5d745623472f97cfc2e6c2916ee248798fc5a8`; Kotlin pins are unchanged from Andrey:

| File under `assets/models/multilingual-asr/` | Bytes | SHA-256 |
| --- | ---: | --- |
| `encoder.int8.onnx` | 652,282,294 | `1afbe69c1ffdabee94755f42e4daf1d93549131a3da11356bda84fcef0f2c479` |
| `decoder.int8.onnx` | 11,845,274 | `0222a86147299d4dee90edf95b6d13827edb42ad8b7c9cf0b2582f030937c0b5` |
| `joiner.int8.onnx` | 6,355,277 | `cbd02f3696e9944970111fb439d9c3fd365a80374565f13f2b9c7007d253fc98` |
| `tokens.txt` | 93,939 | `d58544679ea4bc6ac563d1f545eb7d474bd6cfa467f0a6e2c1dc1c7d37e3c35d` |

Downloads and warm source caches are size/hash checked before copying. APK
inspection independently streams actual ZIP entries and compares source pins,
hashes, file set and immutable provenance. The four files and `NOTICE.txt` must
be `ZIP_STORED`, including `tokens.txt`; embedded Gradle config sets `noCompress`
for ONNX and TXT. Compact model-weight rejection remains intact.

The bundled notice credits NVIDIA, links the original model and CC-BY-4.0 legal
code, identifies int8 ONNX conversion/distributor and immutable revision, and
disclaims endorsement. The model panel also shows a readable Russian attribution
line with source/license URLs. License evidence was inspected on the official
[NVIDIA model card](https://huggingface.co/nvidia/parakeet-tdt-0.6b-v3).

Four new model files add **670,576,784 bytes**, plus a small attribution notice.
With the earlier actual payload, raw models alone total **2,147,011,731 bytes**.
The embedded-only envelope is therefore explicitly schema **2**, with a bounded
**2,200,000,000-byte** total budget and the existing individual-file limits; the
old acoustic/server schema and 2,000,000,000-byte limit are **not** relaxed.
New parsing requires exact Parakeet file identities. APK creation/signing/install
at the enlarged size still needs actual CI: no ZIP64/tooling failure is waived,
no required files are silently compressed/removed and no alternate artifact flow
is substituted. Storage estimates are not measured installed sizes.

## Installation and application seam

All three groups stream into one staging operation with space checks and hashes.
Three same-filesystem promotions finish **before** selecting readiness. Missing,
partial or corrupt third-group data cannot make a two-group revision ready.
Interrupted promotion and orphan cleanup retain active leases. Each old revision's
acoustic/text/multilingual directories remain together until its lease closes.
Process bootstrap revalidates the complete bundle; subsequent metadata snapshots
cover all three groups. The new lease property is:

```kotlin
ModelSelection.acquire(context).use { lease ->
    TranscriptionEngine(lease.directory, RecognitionMode.MULTILINGUAL,
        lease.multilingualDirectory).transcribeWithSpeakers(source, onProgress)
    // Close acoustic sessions before text inference; keep this lease through text.
}
```

Compact leases return a null multilingual directory; there is no fallback or
download/server/schema extension for this group. Pasha still owns explicit mode
selection and user-facing unavailable behavior. Packaging is not proof that the
normal UI invokes the new mode, and it is not an ARM quality claim.

## R8 and bounded emulator check

The inspected sherpa 2.0.0 `OfflineRecognizer.kt` JNI configuration includes
`Offline*` model/recognizer/stream/result classes plus `FeatureConfig`,
`HomophoneReplacerConfig` and nested `QnnConfig` (even with QNN unused). Targeted
keep rules preserve their class/member names. Existing Java ORT and isolated
sherpa C runtime remain separate; no `pickFirst`, broad app keep or disabling R8.

The existing offline test class gains
`parakeetRecognizerLoadsPinnedFilesWithMinifiedJniConfiguration`. It acquires the
real triple lease, rechecks four actual file hashes and attribution, then creates
and closes **the real `ParakeetRecognizer`** in the optimized emulator APK.
It performs no corpus inference or prepared-transcript check. The XML gate requires
both the original bootstrap/text/corruption method **and** the new smoke method.
JUnit method ordering preserves the original genuine cold-start assertion; the
new method can also prepare models when run alone. Emulator data storage increases
from 8 to 12 GiB for the enlarged APK plus leased old/new extraction copies;
on-phone insufficient-space and peak-allocation behavior remain unmeasured.

Synthetic packaging/installer checks cover source-pin agreement, missing/corrupt/
compressed token payloads, required native keep/test gates, third-promotion crash
recovery and leased-triple corruption. They run only in existing GitHub CI.

## Evidence status

Parakeet implementation `dbbce95074d9338beb970c01e76dbd04273c5f52` is now
**CI-verified** in [run 42404340446](https://github.com/pmixay/Ascent/actions/runs/42404340446),
attempt 1. Both jobs completed successfully:

- Compact `128498687976`: existing shared tooling/JVM/lint/build/native/runtime
  checks retained; compact APK still rejects model weights.
- Embedded `128498687716`: schema-2 asset preparation and enlarged ARM64 archive
  inspection/build, actual x86_64 install/bootstrap/text/lease recovery and the
  additional required minified Parakeet creation smoke all passed. The XML gate
  requires both named methods; a successful empty/skip report cannot satisfy it.

The exact-run waiter returned success for both required jobs. Follow-up attempts
to retrieve the detailed logs/artifact metadata failed with local DNS/transport
errors. Therefore the exact new APK byte count, raw APK SHA-256, generated bundle
digest and download artifact ID are **not collected in this handoff**; do not fill
them with the older candidate's values or the approximate 670 MB growth estimate.

No local build, application test, Android dependency preparation, model export
or inference was run. Actual ARM/offline validation remains Petya/user's task;
Pasha still wires the mode chooser. Signing identity, durable publication and
release approval stay open. The earlier 1.49 GB default-model APK is not this new
candidate; green emulator creation does not measure ASR quality or phone speed.
