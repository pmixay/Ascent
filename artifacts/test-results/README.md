# Test result artifacts

`results.csv` has a header only. Add one content-free row per executed case after a CI or physical-device run. The CSV adapts the organizer template to speech processing; acoustic-transfer fields such as transmitter distance and checksum match do not apply here. The case definitions and acceptance procedure are in [QA_JUDGE_SCRIPT.md](../../docs/QA_JUDGE_SCRIPT.md); the evidence register and metric definitions are in [TEST_RESULTS.md](../../docs/TEST_RESULTS.md).

Use `not_run`, `pass`, `fail` or `blocked` in the status column. A new `pass` after a fix gets a new row with the retest APK hash; retain the failed row. Do not include source audio, private transcripts, raw model embeddings, personal names, content-bearing logs or invented example rows. For numeric accuracy/RTF reports, attach a separate aggregate table with source/split, denominator, model/config and device hashes, then link it from `docs/TEST_RESULTS.md`.

