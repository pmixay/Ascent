# Timofey T1 — CI input and model-export recovery

Current integration update (7 November): the full branch through `a989b2e` is now
merged into `claude/improve-app-peter`. The newer user policy keeps long corpus
inference/scoring off push CI. Paired acoustic/text pipeline integration is in
source, and [server publication is repaired](PETYA_SERVER_STATUS.md). Combined
revision CI and user-performed ARM/offline tests remain pending. The observations
and failures below describe earlier teammate revisions.


Task branch: `codex/ascent-android-prototype`. This existing workflow-allowed branch
was unused remotely when created; `main` was not changed. The initial
implementation is commit `46f00e92acdcd93137d67b6f3eefbc3ded800fd7`.
The earlier [diagnosis](TIMOFEY_H01_STATUS.md) is a historical snapshot, superseded
by the implementation evidence below. **Server work is explicitly excluded by
the user; full H01/release acceptance is not claimed.**

**Non-server T1 implementation is verified at `a83fdff`: all Android jobs passed,
including the added guards and 18 new regression tests. Model export/validation
passed; only its excluded server publication step failed.** No T2 work is
included. Later documentation commits do not change this tested code revision.

## Implemented

- Added `tools/checkpoint_source.py`: byte-count/SHA-256-pinned official
  `ai-sage/GigaAM-Multilingual`, revision
  `2f8a57144e6ec3adfd32fe0484d9ea9913305bc8`, via verified HTTPS. Downloads use
  staging/atomic promotion, bounded reads and retries, and rehash warm caches.
  HTTP downgrade redirects, partial data and hash mismatches are rejected.
- Added `--checkpoint-source huggingface` to the existing exporter/workflow.
  Only four reviewed config classes map to the already pinned upstream GigaAM
  code. The loader uses `weights_only=True`, strict tensor loading, and no
  downloaded Python/`trust_remote_code`. The original CDN loader remains available.
- Preserved waveform frontend/logit numerical parity, CTC text/frame checks,
  public ASR measurements, model-pack integrity and real speaker smoke checks.
  The manifest identifies the actual HF serialization and hashes; it never
  assigns the CDN checkpoint MD5 or historical deployed ONNX hash to new bytes.
- Explicitly reconciled `AGENTS.md`: the dedicated public two-hour job remains
  the only long-corpus inference job; the existing fixed six-reader fixture and
  model-export numerical/speech/speaker validations are retained. No permission
  to process application audio or publish evaluation/model Actions artifacts was
  added.
- Added an independent, inference-free cache verifier for both producer and
  consumer. It checks source revision/selection, the two accepted ASR hashes,
  speaker identity, reference-export revision, Python pipeline fingerprint,
  ordered complete sessions, session checksums, WAV hashes and aggregate
  duration/counts. Failed verification fails the job; missing data never becomes
  a successful skip. Regression tests cover cold/warm transport, corruption,
  incomplete/stale caches, alternate source provenance and path substitution.

Cache-key expressions and every existing inference-key input are unchanged from
`6a078e3`. Kotlin mirrors participate in that Actions key; the independent
verifier also checks the generator's Python fingerprint. Added verification code
does not change predictions or require generating a different cache. Existing
push events, workflow/job names, model-workflow branch allowlist, R8/native
integrity and x86_64 instrumentation checks remain in place.

## Inspected evidence

### Complete cold inference → cache → scoring on main

[Run 42363045969, attempt 2](https://github.com/pmixay/Ascent/actions/runs/42363045969/attempts/2),
commit `6a078e36c764f8e0d61c8083f1b3c2f6a44844cc`: **all three jobs succeeded**.

- Inference job `128383349631` logged a cache miss, generated all 27 sessions and
  saved the exact cache at 11:16:35 UTC on 7 November.
- Scoring job `128388516944` restored that cache and executed the Kotlin scorer;
  it was not skipped. Both strict/completed attribution gates remain >75%.
- Cache API entry `10160226517` belongs to `refs/heads/main`, 179,851,239 bytes.
  Key:
  `Linux-dialog-inference-v1-7710a6ddcc6ffbb5e69ab8dde4c3c489a24e16c9778943d9fce1ed0c6fe087d1`.
- Scorer explicitly reported `public-reference`, ASR SHA-256
  `8bc803289f9cb5147ee95451fd9bdba219b1ecf1ddcd59a3651177c103c9eeec`.

| Kotlin-scored scope | Source ms | Unique clips | Reference words | Word errors | cp word errors | Strict attribution | Completed attribution |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| Development | 2,866,068 | 336 | 5,572 | 158 | 284 | 88.9982% | 97.5109% |
| Original diagnostic subset (log still calls it `validation`) | 4,366,580 | 798 | 10,168 | 281 | 1,334 | 90.4917% | 93.8630% |
| Total | 7,232,648 | 1,134 | 15,740 | 439 | 1,618 | 89.8999% | 95.3085% |

Total WER is 439/15,740 = 2.7891%, CER 640/81,890 = 0.7815%, cpWER
1,618/15,740 = 10.2795%; inferred clip time is 6.1834%. The generator's pre-Kotlin
cached ASR tally is separately 458/15,740 words and 765/81,890 characters; it is
not substituted for the Kotlin-reconstructed/stitched result. These are constructed
acted dialogue sequences, not original continuous meetings, natural DER, fresh
holdout results, deployed-model measurements or phone speed. No quality tuning
was performed on any subset in this task.

### Secure export recovery

[Model Export 42367250167](https://github.com/pmixay/Ascent/actions/runs/42367250167),
commit `46f00e9`, job `128387801081`:

- Cold hash-pinned HF weight/config acquisition and strict upstream loading passed.
- Nine new transport/config tests and four server publication tests passed.
- Five frontend/logit/CTC parity cases passed: minimum silence, 1 s noise,
  49,873-sample noise, 25 s noise and public speech.
- Public ASR measurement step completed and speaker smoke produced a finite,
  nonzero 192-dimensional embedding.
- Export/pack validation completed; deployment ZIP SHA-256:
  `7068c6b06ccddfc95a229aafc5a7c01817cc83fa682f057d0f480694f260a63e`.
  This is the deployment transport hash, not an APK/model payload hash.
- **Workflow failed only at publication:** strict SSH checking rejected the
  changed server host key. The verified transport was not published or uploaded
  as an Actions artifact. A passing export step is not a passing whole workflow.

[Android CI 42367250170](https://github.com/pmixay/Ascent/actions/runs/42367250170),
same commit: **all three jobs passed**. Build/validation job `128387801174` includes
the new Python tests and unchanged lint/JVM/R8/ARM64 APK/x86_64 checks. Inference
job `128387801407` logged a second cold miss on the task branch and saved its
own cache at 11:38:12 UTC. Scoring job `128393056274` restored it and passed,
reporting the same aggregate counts as main above. This demonstrates independent
branch-scope recovery, not just reuse of main's warm cache. The branch cache API
entry is `10160681659`, 179,851,594 bytes, with the same exact key.

### Added guards and exact export identity — commit a83fdff

Implementation revision: `a83fdfffbcceb731d76330717c973aefedc4e51d`.
[Android CI 42369233100](https://github.com/pmixay/Ascent/actions/runs/42369233100)
restored the task-branch cache. Producer guard job `128393704106` and consumer/
scoring job `128393740994` passed, including the new verification steps and
required, non-skipped Kotlin scoring. Both independent checks reported:

```text
asr_source: public-reference
pipeline_fingerprint: b5bf78bcd8f5a4d5054eb57aaf23f5edf6b00addadb20ee714bc03a03a7e3acf
source_files: 1134
source_duration_ms: 7232648
sessions: 27
```

Scores and denominators are unchanged from the cold runs. The complete Android
build job `128393704273` also passed: all 18 new loader/cache regression tests,
existing Python/server checks, lint/JVM checks, R8/ARM64 compact APK inspection
and optimized x86_64 instrumentation. **The whole Android workflow succeeded.**
This is not an ARM inference/device acceptance claim.

[Model Export 42369233159](https://github.com/pmixay/Ascent/actions/runs/42369233159),
job `128393704447`, verified another cold HF download, all retained parity/speech/
speaker checks and the deployment archive. Only SSH publication failed, again.
The new content-free identity logging establishes:

| File | Bytes | SHA-256 |
| --- | ---: | --- |
| `asr.onnx` | 885,826,856 | `9e423f2690bfaf3e0fbb81185a080bfff27c4ec5aacef5e615676fffbe2545ff` |
| `speaker.onnx` | 71,441,526 | `bf1a75b9930474cf3389ef415e6e5d38ca96fea4a3a00f7e301d080a58ee2239` |
| `tokens.json` | 535 | `f3c52d56529a595c9de0c2b2f2fd95553ef8834861e6d2ee720a1626bcd01a8a` |

Manifest SHA-256 for this run:
`593eceb422076a5c13eb2c6192e4ddc6924eb1eb29173b030cb0bc5f6c564551`.
Both acoustic graph hashes **match the historical deployed hashes pinned in the
scorer**. This establishes exported graph byte identity, despite the distinct
HF input serialization; it does not establish original `.ckpt` byte identity,
publication, APK packaging or successful Android model execution. The manifest
also contains creation/provenance/report data, so its digest is run-specific.

## Excluded / unresolved

- Server publication: DNS now resolves to `5.83.140.123`, but HTTPS fails before
  returning the catalog. CI sees a different SSH ED25519 host key than the pinned
  secret. Do not replace the pin from an unauthenticated scan. Local SSH connection
  timed out before authentication. The user excluded further server work.
- Andrey's review of the export evidence remains a human handoff. The graph hashes
  now demonstrate acoustic graph identity to the historical pins, not full-pack
  manifest equality. The frozen dialogue scorer's model hashes were not changed.
- T2–T5, embedded assets, import/JSON/text runtime integration, signing/release,
  ARM inference and device measurements are outside this T1 implementation.

No local build, application test, lint, model export, inference or Android toolchain
preparation was run. Local verification is limited to diffs, source/AST checks
and read-only CI/network evidence. No deployment credentials are stored in source.
