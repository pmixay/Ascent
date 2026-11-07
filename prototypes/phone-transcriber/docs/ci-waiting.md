# CI waiting and reuse

From `prototypes/phone-transcriber`, after pushing:

```sh
python3 scripts/wait_for_ci.py \
  --repo pmixay/Ascent --sha "$(git rev-parse HEAD)" \
  --workflow android.yml \
  --require-job 'Build and validate Android app' \
  --require-job 'Build and validate embedded offline baseline' \
  --output /tmp/ascent-ci-result.json
```

Supply `GH_TOKEN` or `GITHUB_TOKEN` through the environment. No token goes in
command arguments or output. The condition is a completed successful push run
for that exact commit, with all named jobs completed successfully. Exit codes
are 0 for success, 1 for failed/cancelled/skipped or missing required checks,
and 2 for timeout or API/output errors. Timeout defaults to 45 minutes.

This CLI owns the waiting loop and prints state changes. GitHub REST has no
blocking workflow-wait operation: it uses conditional ETag requests, 20–60 second
backoff, server polling intervals and rate-limit delays. A webhook receiver is
needed for completely event-driven notification. See
[GitHub's REST guidance](https://docs.github.com/en/rest/using-the-rest-api/best-practices-for-using-the-rest-api).

The compact job runs shared Python/server/tooling checks, Android/Wear lint,
the complete bounded JVM suite, ARM64 APK inspection and the 25 required runtime
methods. The offline job independently builds its ARM64 variant, verifies its
manifest and every bundled payload, then executes offline extraction/recovery
and one synthetic text-model phrase. R8 and strict non-skipped execution gates
remain enabled. Shared Python tests, pure installer JVM tests and application
lint do not repeat in the offline job.

An exact cache key covers the acoustic exporter, helper source, frozen speaker
fixtures, model pins, export dependencies, preparation script and workflow. No
fallback cache prefix is accepted. On a miss the full existing parity, CTC,
public-speech and speaker validations run. On every hit, preparation rechecks
all pinned lengths/hashes, the successful smoke report and frozen checkpoint
provenance before copying assets. Save occurs before Android checks so an
unrelated app failure does not discard valid export work. Native AAR reuse also
retains checksum verification. No extra deployment ZIP is created for embedding;
the model-server workflow still creates and validates its deployment transport.

Long Dialogs inference/scoring/cache-bootstrap jobs remain absent. Gradle also
excludes `TwoHourSpeechEvaluationTest` when `CI=true`, even if evaluation inputs
are accidentally supplied. Opt-in development evaluation stays available.
No build, test, lint, model export or inference runs on the local editing host.
Phone/ARM and corpus-quality acceptance still need separate evidence.
