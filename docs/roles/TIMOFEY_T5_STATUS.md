# Timofey T5 — current-main integration review

**Superseding user handoff:** the user now explicitly requested Parakeet embedding,
R8/JNI safety and emulator creation smoke. Main was merged in `1f5dbe2`, preserving
both constructors' seams and timing behavior. [Current implementation/status](TIMOFEY_PARAKEET_STATUS.md).
T4 `68487f1` also passed both CI jobs in `42402862397`; the findings below are the
earlier read-only snapshot, not a claim that the new packaging has passed CI.

Read-only review after fetching `origin/main` at
`2429e141807d619e01cac35f38f3ef14f25a578b`. No main merge,
model activation, signing setup or release publication was performed here.
The current T4 task branch remains based on `f04aaa7` plus its own verified T1/T3
work and the new timing slice. Its [CI 42402862397](https://github.com/pmixay/Ascent/actions/runs/42402862397)
at `68487f1` is still running; earlier green jobs do not certify that new slice.

## Incoming ML handoff

Main now contains `8b1888a`, `e0fcc7b` and `2429e14`: host candidate comparisons,
the team's explicit second-recognizer choice and a source path for Parakeet TDT
0.6B v3. GigaAM remains the default; the new path is not packaged or selected by
the app UI. See the incoming [multilingual contract](https://github.com/pmixay/Ascent/blob/2429e14/docs/interfaces/multilingual-recognizer.md).
This is not an ARM-qualified mode or permission to substitute host metrics for it.

| Finding | Integration consequence / owner |
| --- | --- |
| Both branches modify `TranscriptionEngine`'s constructor and recognition orchestration. Incoming code adds `RecognitionMode`/directory; T4 adds an optional timings collector. | Timofey integrates only after reviewing the two seams together; keep the default GigaAM path and existing API callers, time both paths explicitly and preserve every source close/cancel/PCM cleanup. Do not blindly choose one constructor and discard the other. |
| Four Parakeet assets total **670,576,784 bytes**. Current actual embedded payload is **1,476,434,947 bytes**. Combined payload alone is **2,147,011,731 bytes**, before licence/notice additions or APK code/metadata. | This exceeds the current Kotlin `EmbeddedBundle` 2,000,000,000-byte acceptance bound by **147,011,731 bytes**. It also approaches 2 GiB before the APK overhead. Do not silently increase guards or claim an installable APK; verify Android/aapt/APK-format and device storage feasibility in CI as part of an agreed packaging change. |
| Current envelope/installer file names and paired leases cover exactly acoustic + text groups. The acoustic/server wire schema intentionally remains unchanged. | Timofey needs a reviewed versioned third-group contract: complete hashes/file set, staged immutable activation, selection/readiness, lease-aware replacement and recovery, APK required-payload checks and negative tests. Pasha must receive the actual leased directory, not a separately guessed path. |
| Source pins and CC-BY-4.0 attribution are specified upstream; no new pinned licence/notice payload or licence UI is installed by that engine-only path. | Andrey approves complete redistribution attribution/source evidence; Timofey pins/bundles it and Petya/Pasha integrate any required presentation. Do not label absent attribution complete. |
| `OfflineRecognizer` introduces additional sherpa JNI config-field lookups although it uses the already isolated runtime. | Preserve the native-isolation checks and review R8/JNI for the exact new classes. An instrumented creation probe is requested in the incoming handoff but is not yet implemented or authorized by an expanded corpus check. Keep it bounded and retain every existing check. |
| A mode chooser and selected directory are not wired by main's engine-only change. | Pasha owns explicit mode selection and truthful unavailable behavior; no automatic language identification or silent fallback. Petya owns UX and exact-build ARM/offline trials. |

The published default candidate remains GigaAM/Qwen. New main's optional mode is
an incoming integration dependency, **not** included in the current APK and not
accepted by prior default-pipeline results. This review changes no model choice,
threshold, manifest or CI inference policy. Andrey/Pasha sign-off, packaging
feasibility and exact changed-build checks are required before promotion.

## Existing release blockers

- No physical ARM timing/quality/peak-memory table or complete cold-offline
  three-file acceptance is available in this session. Petya/user executes it.
- A stable protected signing identity and increasing release version remain
  unconfigured; internal debug-signed artifacts must not be presented as that release.
- Actions artifacts expire; no durable submission archive/video/receipt is approved.
- Human handoff approval and device/access inventory are not inferred from code.

Resolve these dependencies explicitly. Do not use a green default-model workflow
to approve the new optional mode, overwrite teammates' engine changes, weaken
size/native/fixture gates or publish an untested final package.
