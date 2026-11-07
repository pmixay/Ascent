# Timofey T1 — CI input and model-export recovery

Task branch: `codex/ascent-android-prototype`. This existing workflow-allowed branch
was unused remotely when created; `main` was not changed. The initial
implementation is commit `46f00e92acdcd93137d67b6f3eefbc3ded800fd7`.
The earlier [diagnosis](TIMOFEY_H01_STATUS.md) is a historical snapshot, superseded
by the implementation evidence below. **Server work is explicitly excluded by
the user; full H01/release acceptance is not claimed.**

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
same commit: the complete build/validation job `128387801174` passed. This includes
the new Python tests and the unchanged lint/JVM/R8/ARM64 APK/x86_64 checks. Its
separate cold inference/scoring completion was still pending when this draft
was recorded; do not cancel it with another push.

## Final verification pending

The added cache verifier and content-free export identity logging still need
their committed-revision CI run. Verify task-branch restore/scope, producer and
consumer checks, no-skip Kotlin scoring and all Android build checks on that exact
revision. The earlier export cannot certify changed export tooling; inspect its
new run as well, even though server publication is expected to remain blocked.

## Excluded / unresolved

- Server publication: DNS now resolves to `5.83.140.123`, but HTTPS fails before
  returning the catalog. CI sees a different SSH ED25519 host key than the pinned
  secret. Do not replace the pin from an unauthenticated scan. Local SSH connection
  timed out before authentication. The user excluded further server work.
- Andrey's review of the HF export's numerical/provenance evidence remains a
  human handoff; passing retained parity is not proof of byte equality to the
  historical deployed pack. The frozen dialogue scorer's model hashes were not
  changed.
- T2–T5, embedded assets, import/JSON/text runtime integration, signing/release,
  ARM inference and device measurements are outside this T1 implementation.

No local build, application test, lint, model export, inference or Android toolchain
preparation was run. Local verification is limited to diffs, source/AST checks
and read-only CI/network evidence. No deployment credentials are stored in source.
