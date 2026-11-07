# Petya implementation and acceptance handoff

Updated 7 November 2026. Work branch: `codex/petya-open-work`, created from fetched
main `2133dfa` in a clean worktree. PR #4 is merged historical evidence, not this
candidate. The user will install and test the phone APK later; device acceptance,
quality measurements and the demonstration video remain open.

## Current implementation

- Integrated Timofey through `42d4de9`: schema-2 embedded acoustic/text/Parakeet
  groups, verified provenance, triple leases, storage diagnostics and exclusive
  processing timings. His exact revision passed [CI 42406537594](https://github.com/pmixay/Ascent/actions/runs/42406537594).
- Integrated Pasha through `00bb1ab`: remembered recognition mode, job snapshot,
  missing-model refusal and actual-used mode. Resolved the overlap with timing
  state and replaced the temporary null directory with the protected job lease's
  `multilingualDirectory`. No model path is guessed or downloaded for compact builds.
- Added the Russian `Язык` picker before import/phone capture: `Авто` by default,
  `Русский`, `Другие языки`. Selection is disabled during recording, processing
  and watch reception. Compact builds explain that Auto uses GigaAM only and
  refuse a fixed multilingual job. The last result's actual recognizer mode is
  shown separately from the next choice; this label is volatile, not old-history metadata.
- Reviewed the T4 presentation seam: total/media duration and completed RTF remain
  visible; exclusive stages expand on request. Failed/cancelled jobs show partial
  elapsed time without completed RTF. Counters remain in memory, outside history,
  logs and competition JSON. Text-stage success/fallback status stays independent.
- Preserved model readiness separate from transfer activity, readable Parakeet
  attribution, source-safe import, cancellation cleanup, exact raw-word JSON,
  encrypted text history, watch ownership and seven-day expiry.

Combined source CI and its exact artifact identities are tracked in
[PR #5](https://github.com/pmixay/Ascent/pull/5). The existing required optimized-activity
case now exercises language selection and recreation through accessibility actions;
JVM cases cover partial/unknown-duration timing presentation. No local build/test,
new corpus job or two-hour CI evaluation is introduced. Exact candidate evidence
belongs in [TEST_RESULTS.md](../TEST_RESULTS.md).

## Remaining work by priority

| Priority | Work | Next evidence |
| --- | --- | --- |
| P0 | Exact-build ARM cold-offline inference with local text model and all selected modes | User-deferred phone trial. Use the new combined APK, not an earlier teammate APK. |
| P1 | Preparation/storage and negative/lifecycle matrix | Follow the [judge script](../QA_JUDGE_SCRIPT.md). Record actual storage categories, cancellation, corruption, low space and process-kill recovery. |
| P1 | Independent quality and timing measurements | Use frozen references and record denominators, device conditions, model/APK identities and actual used mode. AMI has now been scored on the host; it is not an unseen future validation set. Russian call references and a Russian meeting holdout remain missing. |
| P2 | Final signing, durable APK, exact-build video and submission | Final release identity and user device evidence are required. The [storyboard](../../artifacts/demo/README.md) is prepared; no video or submission receipt exists. |

Model-server repair/publication is complete; [server evidence](PETYA_SERVER_STATUS.md).
Do not repeat it or mark the deferred phone cases passing because CI succeeds.

## Phone handoff

Use [installation instructions](../INSTALLATION.md) and the same-run artifact
identities in [TEST_RESULTS.md](../TEST_RESULTS.md). The schema-2 offline package
is separate from compact and contains three model groups. First-launch model
payload alone exceeds 2.14 GB; this is not the full installed/peak space need.
Never substitute the older 1.49 GB two-group APK's identity.

Run three unrelated files without microphone permission or clearing data. Test
Russian with `Русский`, English with `Другие языки`, then Auto with each language;
record the actual last-result mode. Mixed speech is a separate unqualified case.
Validate each exported JSON independently. Include silence, picker cancellation,
malformed input, cancel then another job, rotation/background/reopen and export
failure. Force-stop during processing: reopening reports interruption once,
retains committed history and requires reselecting the unchanged source.

Expand `Показать время по этапам` after completion. RTF includes queue, model
initialization, text work, save and cleanup; extraction and microphone capture
are separate. Failed local text processing is still a compliance blocker even
when raw words and a completed acoustic result are retained. Collect PSS/RSS and
thermal/device evidence separately; stage counters do not measure memory.
