# Timofey H01 — baseline diagnosis and access checkpoint

Observed on **7 November 2026, 13:55 MSK (10:55 UTC)**. Source reviewed:
`de6d34c75ff5adf1f2b4ee9b4c3c51bf0ab6a051`, also returned by the remote
`main` reference. Task branch: `timofey/h01-ci-baseline`.

**Diagnosis checkpoint recorded; H01 acceptance remains open.** This is the
first handoff in [Timofey's plan](TIMOFEY.md), not a passing release or a completed
CI repair. Stop here for review before starting the next bounded task.

## Inspected CI evidence

| Run / revision | Observed evidence | Consequence |
| --- | --- | --- |
| [42363045969](https://github.com/pmixay/Ascent/actions/runs/42363045969), `6a078e3`, attempt 1 | Build job `128375221951` succeeded, including R8/APK inspection and x86_64 instrumentation. Inference job `128375222132` was cancelled at 10:35:13 UTC; its cache-save post-step was skipped. Scoring was cancelled. | Working build evidence only. Cancellation does not establish an inference implementation failure. |
| Same run, attempt 2 | Started 10:52:13 UTC. At inspection, build job `128383349556` and inference job `128383349631` were in progress; inference had reached `Run frozen public-corpus inference`. | Let this attempt finish; complete cache/scoring/provenance acceptance is pending. |
| [42363246295](https://github.com/pmixay/Ascent/actions/runs/42363246295), `de6d34c`, main | Cancelled. | No successful whole-workflow result for the reviewed revision. |
| [42364309562](https://github.com/pmixay/Ascent/actions/runs/42364309562), `de6d34c`, `claude/improve-app-peter` | Cancelled. | Branch existence is not acceptance evidence. |
| [42257595048](https://github.com/pmixay/Ascent/actions/runs/42257595048), `d7759627` | Still the latest model-export run. Authenticated log inspection of job `128028818342` confirms `SSLCertVerificationError: certificate has expired` downloading `multilingual_ctc.ckpt`. Publication was skipped. | Secure checkpoint acquisition and publication remain blocked. |

The GitHub cache API returned five entries and **no dialog-inference cache**.
Attempt 1's log records a cold miss for:

```text
Linux-dialog-inference-v1-7710a6ddcc6ffbb5e69ab8dde4c3c489a24e16c9778943d9fce1ed0c6fe087d1
```

Producer and consumer use the same key expression and generated directory, with
`speech-evaluation` depending on `dialog-inference`. Source/model pins, evaluator,
reference frontend, dependency pins and mirrored ASR/context files participate
in the key. There are no broad restore prefixes. A task branch may restore a
matching default-branch cache; a cache created only on that task branch does not
bootstrap `main`. Neither cache-hit nor intended-branch recovery is verified yet.

The producer explicitly selects `public-reference`. The scorer accepts the
deployed ASR hash `9e423f2690bfaf3e0fbb81185a080bfff27c4ec5aacef5e615676fffbe2545ff`
or reference ASR hash `8bc803289f9cb5147ee95451fd9bdba219b1ecf1ddcd59a3651177c103c9eeec`,
and the same pinned speaker hash. It prints provenance and verifies source/audio
integrity and ASR/context mirrors before scoring. This is static inspection;
the completed run's actual provenance and scores still need inspection. Reference
outputs cannot certify the deployed waveform-input model or Android execution.

## Access and model availability

- Configured Git credentials report repository `push`, `pull` and `triage`
  permissions, with `admin` and `maintain` false. A branch push dry-run succeeded;
  no remote branch or CI run was created by that dry-run.
- Authenticated Actions job logs and secret **names** are readable. Repository
  secret names include `ASCENT_MODEL_DEPLOY_KEY` and `ASCENT_MODEL_HOST_KEY`.
  Their presence does not prove working SSH credentials or server access; secret
  values were not retrieved or printed. Timofey must confirm the server operator.
- A fresh HTTPS HEAD request to the exact upstream checkpoint URL still failed
  with `CERT_HAS_EXPIRED`. The exporter's pinned upstream loader uses this source.
  A secure byte-identical mirror has not been established. The official Hugging
  Face repository exposes `pytorch_model.bin`, not evidence of a byte-identical
  replacement for the pinned `.ckpt`; changing formats/models needs Andrey review.
- The fixed catalog `https://ascent.arbuz.lol/models/catalog.json` failed TLS
  negotiation from this environment, reproduced with curl (exit 35, TLS internal
  error). This is a new observation, not a repeated claim of the older NXDOMAIN
  diagnosis or proof of a global outage. No usable deployed manifest was obtained.
- The source selection pins the historical deployed model manifest, but available
  verified payloads for embedding have not been located. GitHub Releases returned
  an empty list. This does not rule out a teammate's verified private copy.
- Current Gradle configuration uses debug signing, version code 1/version 0.1.
  Stable release signing custody and an available ARM phone's OS/ABI/RAM/storage
  remain unconfirmed. No Android toolchain was installed to discover a device.
- `gh` is unavailable here; existing Git credentials plus HTTPS API requests were
  sufficient for this inspection. Python's default local CA store failed GitHub
  verification; system curl worked with certificate validation enabled.

## Remaining inference-policy conflict — explicit decision required

`AGENTS.md` authorizes the dedicated `dialog-inference` job but also says no other
CI job runs model inference. Existing required behavior contradicts that sentence:

1. Android `build` generates six-reader speaker embeddings on a fixture-cache miss.
2. Model `export` performs numerical frontend/CTC parity, public ASR measurements
   and speaker-model smoke inference before publication.

These checks must remain. The narrow proposed reconciliation is to distinguish
the authorized two-hour corpus job from the **existing fixed public/synthetic
fixture and export-validation checks**, explicitly retaining those checks without
authorizing new corpus jobs or application audio. Pasha/Timofey must confirm that
policy wording before changing inference behavior or checks. This checkpoint
records the unresolved contradiction; it does not silently grant an exception.

## Cross-component review and handoffs

The review covered the complete delivery plan and repository layout, both
workflows, application entry/orchestration, acoustic engine, model selection and
download interfaces, encrypted storage, packaging scripts, exporter/validators,
server publication and evaluation provenance. It was not an exhaustive line-by-line
audit of every algorithm/test or an executable validation.

| Area | Current source / required next handoff |
| --- | --- |
| Timofey: packaging/native | One compact ARM64 build; `check_apk.py` rejects weights. R8 and isolated Java/sherpa ORT libraries are already enforced. Add a separately checked embedded path when verified assets are available; retain compact checks. Model export triggers only for `main`/`codex/ascent-android-prototype` and matching paths, so this task branch cannot itself establish export acceptance under the current filters. |
| Timofey ↔ Pasha: readiness/leases | Reuse `ModelSelection.acquire/select/cleanup` and `ModelDownloads.refresh/observe`. Bootstrap must verify before selection, share transfer ownership, preserve leases and expose readiness without network work. Both ViewModel scheduling and Activity foreground download startup need a competition-path seam. This contract is proposed, not teammate-approved. |
| Pasha ↔ Andrey: source/engine | Engine still accepts `File`/`PcmWave`; unused `AudioImporter` copies audio to app disk and cannot serve the new import contract. The bounded URI/window interface remains a prerequisite for reachable source-safe import. |
| Pasha ↔ Petya: UI/export | Activity has microphone capture and document creation, no import picker. Export uses storage JSON and rejects empty segments. Exact competition serialization and silence/export UI remain open. |
| Pasha ↔ Timofey ↔ Andrey: text stage | Host study pins Qwen candidates and grammar contract; Android runtime and separate/versioned asset contract are absent. Acoustic manifests require an exact file set in Kotlin/Python/server. No chosen ARM-tested text runtime or complete bundled pipeline exists. |
| Privacy/storage | AES-GCM/Keystore, atomic encrypted history writes and seven-day filtering are present; backups/cleartext traffic are disabled. Microphone temporary WAV remains the documented prototype exception. These observations do not prove production no-persistence or source-granular retention. |
| Timofey ↔ Petya: acceptance | Need named ARM hardware, exact CI APK/model identities, cold offline extraction tests and complete-stage measurements. None was performed in this checkpoint. |

## Next bounded milestone: T1 secure export/input recovery

1. Inspect attempt 2 to completion with Andrey; verify cache save, scoring restore,
   explicit reference provenance and all required gates. Preserve cache-key inputs
   while it runs. Diagnose a failure only if the completed logs establish one.
2. Timofey obtains restored upstream TLS or a securely supplied byte-identical
   checkpoint with verified provenance; Andrey reviews any alternate source.
   Timofey/server operator repairs catalog TLS and verifies deployment access.
3. Resolve the policy wording above, then use the existing push-only model workflow
   on an allowed branch/reviewed integration. Do not bypass its parity checks or
   relabel the reference ONNX graph as the deployed graph.
4. Obtain Pasha's bootstrap/lifecycle seam, Petya's status expectations and a named
   ARM device before calling the kickoff contract/access checkpoint fully closed.

Only documentation and static review were performed locally. No local build,
test, lint, model export, inference or device trial was run. Workflow behavior,
models and acceptance gates are unchanged; no release gate is checked off.
