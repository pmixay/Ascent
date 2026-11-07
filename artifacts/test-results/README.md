# Test result artifacts

`results.csv` records actual `25ae8eee` CI suites, the historical attempt-1
artifact blocker, and attempt-2 full collector recovery from real metadata/logs. Suite rows are
not individual phone trials or speech-quality measurements. `apk_sha256` identifies
the inspected ARM64 archive; `abi` identifies actual x86_64 test execution, not ARM
inference. Blank numeric fields mean not measured, never zero. The model digest in
the offline row is the acoustic manifest, not the three-group bundle digest.

Add one content-free row per actual case/suite after a CI or device run. The CSV
adapts the organizer template to speech processing; acoustic-transfer fields such
as transmitter distance and checksum match do not apply. Procedures are in
[QA_JUDGE_SCRIPT.md](../../docs/QA_JUDGE_SCRIPT.md); current results, historical
host metrics and limitations are in [TEST_RESULTS.md](../../docs/TEST_RESULTS.md).
Exact candidate and artifact identity: [submission.json](../submission.json).

Use `not_run`, `pass`, `fail` or `blocked` in the status column. A new `pass` after a fix gets a new row with the retest APK hash; retain the failed row. Do not include source audio, private transcripts, raw model embeddings, personal names, content-bearing logs or invented example rows. For numeric accuracy/RTF reports, attach a separate aggregate table with source/split, denominator, model/config and device hashes, then link it from `docs/TEST_RESULTS.md`.
