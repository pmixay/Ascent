# Petya implementation and acceptance handoff

Updated 7 November 2026. Work branch: `claude/improve-app-peter`, PR [#4](https://github.com/pmixay/Ascent/pull/4).
The user will install the CI APK and perform phone acceptance later. No device
measurement, quality score or demonstration video is claimed by these changes.

| Priority | Assignment | Current outcome / next evidence |
| --- | --- | --- |
| P0 | Finish locally started interrupted-import handling | Atomic content-free marker and once-only interrupted notice implemented; regression cases submitted to Android CI. Existing encrypted history and original input stay separate from the marker. |
| P0 | Repair model publication, assigned by Timofey | This host owns the domain's resolved address. HTTPS responds. The ED25519 pin was obtained from this server's host public-key file. Restricted CI key restored; arbitrary SSH commands rejected; protected repository secrets updated. Existing push-created Model Export run [42369233159, attempt 2](https://github.com/pmixay/Ascent/actions/runs/42369233159/attempts/2) rerun without changing checks. Publication passed; every live compressed/expanded payload hash was independently verified. See [server evidence](PETYA_SERVER_STATUS.md). |
| P0 | Integrate Pasha's callable UI/job API | Picker, stage progress, cancellation and job errors use the application API. Stable source identity/expiry, watch locks, codec ring and raw JSON words are preserved. Build/lint/JVM checks passed at `6734bfd`. Its new emulator evidence command failed because the runner split multiline arguments; the invocation is corrected and the new exact revision must pass the 25-case gate. Real phone validation remains required. |
| P0 | Cold offline ARM/local-LLM acceptance | User will test on a phone. Timofey's draft packaging PR #3 had failing instrumentation gates at reviewed revision `082113c`; newer teammate revisions are separate and not merged here; this branch's compact APK needs an initial model download and has no packaged text model. It cannot satisfy a fresh offline/local-LLM release gate. |
| P1 | Negative/lifecycle/storage cases and timing/memory | Prepared in [QA_JUDGE_SCRIPT.md](../QA_JUDGE_SCRIPT.md); no phone execution here. Record exact APK/model hashes and every outcome in [TEST_RESULTS.md](../TEST_RESULTS.md). |
| P1 | Embedded preparation/error UI review | UI now derives network availability from the installed manifest permission. A build without Internet permission shows local readiness and disabled automatic updates, with no online check age. The compact build retains its download/update state. A green integrated embedded candidate and phone trial remain required. |
| P1 | Held-out references and measurements | Existing [custody protocol](../HOLDOUT_CUSTODY.md) remains authoritative. AMI annotations need review, DataHive sources need references, and the Russian meeting input is missing. Do not present source preparation as measured app quality. |
| P2 | Submission and exact-build video | Existing installation, model/architecture/limitations material and [storyboard](../../artifacts/demo/README.md) are prepared. Final durable offline APK, phone evidence, video and submission receipt remain open. |

## Phone handoff

For an immediate compact-prototype trial, use the checked `debug-<commit>`
artifact from the successful Android run for this branch. Verify its hash against
the artifact record. Allow the initial acoustic-model download, then disable
networking and test import/result/export. That trial is distinct from a **fresh
offline installation** of a future embedded release.

Run three unrelated files without clearing app data. Include silence with
`{"transcript":[]}`, denied microphone permission, picker cancellation, malformed
input, cancel then a new job, rotation/background/reopen and output-provider
failure. Force-stop during processing: reopening must show the interrupted notice
once and retain committed history; reselect the unchanged source to retry. Repeat
import of one retained source must preserve its original seven-day expiry.

The progress percentage describes the current stage only. During cancellation,
wait for the native call and cleanup to finish. The displayed text-stage status
must say unavailable when the text weights are absent; that is not local-LLM
compliance. Record cold/warm elapsed time, media duration, RTF, device/OS/ABI/RAM,
free storage and peak memory only when actually measured.

Do not use an earlier Pasha-only build, historical host study or successful model
export to mark the merged application, ARM inference or the final package passing.
