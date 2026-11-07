# Competition input and output protocol

The [jury brief](hackathon/hackathon-brief-2026-11.pdf) requires a phone APK that accepts a user-selected recording, processes it locally, and exports JSON. The [written clarifications](hackathon/jury-clarifications.md) add offline model availability, Russian as the first language, English as the second, and both call-like and meeting-like cases. This document describes the evaluator-facing contract; implementation and device support still require verification.

## Input

Choose an audio file through Android's document picker. The file remains owned by its provider. Processing must read the selected content URI without replacing, deleting or retaining a copy of the source in app storage. The pipeline must preserve timing relative to the beginning of that original media, including leading silence and pauses. Current source handles selected PCM WAV variants and delegates other audio formats to device-supported Android decoders; seekability and codec support depend on the source/provider and device. Supported formats, channel layouts, rates and duration limits are recorded from actual release/device trials in [LIMITATIONS.md](LIMITATIONS.md). A format named in the test matrix is a test obligation, not proof it works.

Three unrelated files are processed one after another. The test does not grant microphone permission and disables networking before a fresh first launch. Canceling the picker leaves the prior result intact. The remembered phone language
choice defaults to `Авто`; select `Русский` or `Другие языки` before import for
fixed-mode trials. The status shows the actual recognizer for the last result.
Timing/mode metadata stays outside the exact competition JSON. See
[installation](INSTALLATION.md) for compact/offline differences.

## Output

Use the system file picker to save UTF-8 JSON. The root has exactly one key, `transcript`, whose value is an array. Each speech turn has exactly these fields:

| Field | Type | Meaning |
| --- | --- | --- |
| `speaker_id` | string | Stable anonymous participant identifier within this input. Display-name edits do not change it. |
| `start_ms` | integer | Turn start measured from the original input's beginning. |
| `end_ms` | integer | Turn end measured from the same origin. |
| `text` | string | Recognized words for the turn. |

Example of the required shape:

```json
{"transcript":[{"speaker_id":"speaker_1","start_ms":1250,"end_ms":4380,"text":"Добрый день!"}]}
```

For silence, export `{"transcript":[]}`. Turns are ordered by nondecreasing start time; simultaneous or overlapping speech may have overlapping intervals. For each nonempty turn, `0 <= start_ms < end_ms <= original media duration`. The brief does not authorize extra JSON keys. Source provenance and confidence remain internal. CTC-derived boundaries are approximate until measured against reference boundaries.

The independent checker is [validate_transcript.py](../tools/qa/validate_transcript.py). Run it against a file copied from the device, with an independently measured duration when available:

```sh
python3 tools/qa/validate_transcript.py exported.json --duration-ms 30000
```

This checks schema and basic timing semantics. It cannot prove that the words, speakers, identities, model outputs or original audio were correct. Compare exported content to independently prepared reference annotations in [TEST_RESULTS.md](TEST_RESULTS.md).

