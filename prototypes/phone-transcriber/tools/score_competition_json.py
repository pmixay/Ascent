#!/usr/bin/env python3
"""Score the app's competition JSON exports against hand-made references in the same format.

For the team's own device recordings (plan H09/H10): every reference is a file with the brief's
structure, `{"transcript": [{"speaker_id", "start_ms", "end_ms", "text"}]}`, written by a
person who listened to the recording. A manifest lists the recordings:

    {"items": [{"name": "call-01", "condition": "ru-call", "hypothesis": "call-01.json",
                "reference": "call-01.ref.json", "duration_ms": 61000, "processing_ms": 9100}]}

Paths are relative to the manifest. Only aggregate numbers are printed, never transcript text.

Metrics, all with their denominators:
- WER / CER: speaker-agnostic, text in time order, shared normalization (lowercase, `ё` as `е`,
  punctuation removed).
- cpWER: per-speaker text with the one-to-one speaker mapping that minimizes word errors.
- Speaker time accuracy: reference speech time covered by the mapped predicted speaker, with
  the one-to-one mapping that maximizes overlap; unmapped or missing speech is an error.
- Segment boundaries: each reference segment is matched to the predicted segment with the
  highest intersection over union (at least 0.5); start and end errors of matched pairs. The
  app joins consecutive phrases of one speaker, so unmatched segments are reported, not hidden.
- RTF: processing time divided by recording duration, when both are given.
"""
from __future__ import annotations

import argparse
from itertools import permutations
import json
from pathlib import Path

from evaluate_speech import edit_distance, normalize_text

KEYS = {"speaker_id", "start_ms", "end_ms", "text"}
MAX_EXACT_SPEAKERS = 7


def load_transcript(data, duration_ms=None):
    """Validate the brief's structure strictly and return segments sorted by time."""
    if not isinstance(data, dict) or set(data) != {"transcript"} or not isinstance(data["transcript"], list):
        raise ValueError("expected exactly {\"transcript\": [...]}")
    segments = []
    for index, item in enumerate(data["transcript"]):
        if not isinstance(item, dict) or set(item) != KEYS:
            raise ValueError(f"segment {index}: expected keys {sorted(KEYS)}")
        if not isinstance(item["speaker_id"], str) or not item["speaker_id"] or not isinstance(item["text"], str):
            raise ValueError(f"segment {index}: speaker_id and text must be strings")
        start, end = item["start_ms"], item["end_ms"]
        if any(isinstance(v, bool) or not isinstance(v, int) for v in (start, end)) or not 0 <= start <= end:
            raise ValueError(f"segment {index}: start_ms and end_ms must be integers with 0 <= start <= end")
        if duration_ms is not None and end > duration_ms:
            raise ValueError(f"segment {index}: end_ms exceeds the recording duration")
        segments.append(item)
    return sorted(segments, key=lambda s: (s["start_ms"], s["end_ms"]))


def words(segments):
    return normalize_text(" ".join(s["text"] for s in segments)).split()


def best_mapping(reference_ids, predicted_ids, gain):
    """One-to-one mapping predicted -> reference maximizing the summed gain."""
    if not reference_ids or not predicted_ids:
        return {}
    if max(len(reference_ids), len(predicted_ids)) <= MAX_EXACT_SPEAKERS:
        best, chosen = None, {}
        if len(predicted_ids) <= len(reference_ids):
            for refs in permutations(reference_ids, len(predicted_ids)):
                total = sum(gain(r, p) for r, p in zip(refs, predicted_ids))
                if best is None or total > best:
                    best, chosen = total, dict(zip(predicted_ids, refs))
        else:
            for preds in permutations(predicted_ids, len(reference_ids)):
                total = sum(gain(r, p) for r, p in zip(reference_ids, preds))
                if best is None or total > best:
                    best, chosen = total, dict(zip(preds, reference_ids))
        return chosen
    pairs = sorted(((gain(r, p), r, p) for r in reference_ids for p in predicted_ids), reverse=True)
    chosen, used = {}, set()
    for _, r, p in pairs:  # greedy fallback for unusually many speakers
        if p not in chosen and r not in used:
            chosen[p] = r
            used.add(r)
    return chosen


def speakers(segments):
    return sorted({s["speaker_id"] for s in segments})


def cp_errors(reference, predicted):
    ref_ids, hyp_ids = speakers(reference), speakers(predicted)
    ref_words = {r: words([s for s in reference if s["speaker_id"] == r]) for r in ref_ids}
    hyp_words = {p: words([s for s in predicted if s["speaker_id"] == p]) for p in hyp_ids}
    cost = {(r, p): edit_distance(ref_words[r], hyp_words[p]) for r in ref_ids for p in hyp_ids}
    # Gain of a pair: errors saved compared with leaving both unmatched.
    mapping = best_mapping(ref_ids, hyp_ids, lambda r, p: len(ref_words[r]) + len(hyp_words[p]) - cost[r, p])
    errors = sum(cost[r, p] for p, r in mapping.items())
    errors += sum(len(ref_words[r]) for r in ref_ids if r not in mapping.values())
    errors += sum(len(hyp_words[p]) for p in hyp_ids if p not in mapping)
    return errors


def union(intervals):
    merged = []
    for start, end in sorted(intervals):
        if merged and start <= merged[-1][1]:
            merged[-1][1] = max(merged[-1][1], end)
        else:
            merged.append([start, end])
    return merged


def overlap(start, end, intervals):
    return sum(max(0, min(end, b) - max(start, a)) for a, b in intervals)


def speaker_time(reference, predicted):
    hyp = {p: union([(s["start_ms"], s["end_ms"]) for s in predicted if s["speaker_id"] == p]) for p in speakers(predicted)}
    ref_ids = speakers(reference)
    shared = {(r, p): sum(overlap(s["start_ms"], s["end_ms"], hyp[p]) for s in reference if s["speaker_id"] == r)
              for r in ref_ids for p in hyp}
    mapping = best_mapping(ref_ids, list(hyp), lambda r, p: shared[r, p])
    total = sum(s["end_ms"] - s["start_ms"] for s in reference)
    correct = sum(shared[r, p] for p, r in mapping.items())
    return correct, total


def boundaries(reference, predicted, threshold=0.5):
    starts, ends, unmatched = [], [], 0
    for ref in reference:
        best, match = 0.0, None
        for hyp in predicted:
            inter = max(0, min(ref["end_ms"], hyp["end_ms"]) - max(ref["start_ms"], hyp["start_ms"]))
            span = max(ref["end_ms"], hyp["end_ms"]) - min(ref["start_ms"], hyp["start_ms"])
            iou = inter / span if span else 0.0
            if iou > best:
                best, match = iou, hyp
        if match is None or best < threshold:
            unmatched += 1
        else:
            starts.append(abs(match["start_ms"] - ref["start_ms"]))
            ends.append(abs(match["end_ms"] - ref["end_ms"]))
    return starts, ends, unmatched


def percentile(values, fraction):
    if not values:
        return None
    ordered = sorted(values)
    return ordered[min(len(ordered) - 1, int(fraction * (len(ordered) - 1) + 0.5))]


def score_item(reference, predicted, duration_ms=None, processing_ms=None):
    ref_words, hyp_words = words(reference), words(predicted)
    ref_chars, hyp_chars = "".join(ref_words), "".join(hyp_words)
    correct, total = speaker_time(reference, predicted)
    starts, ends, unmatched = boundaries(reference, predicted)
    return {"reference_words": len(ref_words), "word_errors": edit_distance(ref_words, hyp_words),
            "reference_chars": len(ref_chars), "char_errors": edit_distance(ref_chars, hyp_chars),
            "cp_word_errors": cp_errors(reference, predicted),
            "reference_speakers": len(speakers(reference)), "predicted_speakers": len(speakers(predicted)),
            "speech_ms": total, "speaker_correct_ms": correct,
            "reference_segments": len(reference), "matched_segments": len(starts), "unmatched_segments": unmatched,
            "start_errors_ms": starts, "end_errors_ms": ends,
            "duration_ms": duration_ms, "processing_ms": processing_ms}


SUMMED = ("reference_words", "word_errors", "reference_chars", "char_errors", "cp_word_errors", "speech_ms",
          "speaker_correct_ms", "reference_segments", "matched_segments", "unmatched_segments")


def summarize(items):
    total = {key: sum(item[key] for item in items) for key in SUMMED}
    starts = [v for item in items for v in item["start_errors_ms"]]
    ends = [v for item in items for v in item["end_errors_ms"]]
    timed = [item for item in items if item["duration_ms"] and item["processing_ms"] is not None]

    def rate(errors, base):
        return round(total[errors] / total[base], 4) if total[base] else None

    total.update({
        "recordings": len(items),
        "WER": rate("word_errors", "reference_words"), "CER": rate("char_errors", "reference_chars"),
        "cpWER": rate("cp_word_errors", "reference_words"),
        "speaker_time_accuracy": rate("speaker_correct_ms", "speech_ms"),
        "speaker_count_exact": sum(i["reference_speakers"] == i["predicted_speakers"] for i in items),
        "start_error_median_ms": percentile(starts, 0.5), "start_error_p95_ms": percentile(starts, 0.95),
        "end_error_median_ms": percentile(ends, 0.5), "end_error_p95_ms": percentile(ends, 0.95),
        "timed_recordings": len(timed),
        "RTF": round(sum(i["processing_ms"] for i in timed) / sum(i["duration_ms"] for i in timed), 4) if timed else None,
    })
    return total


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("manifest", type=Path)
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()
    manifest = json.loads(args.manifest.read_text(encoding="utf-8"))
    root = args.manifest.parent
    scored, by_condition = {}, {}
    for entry in manifest["items"]:
        duration = entry.get("duration_ms")
        reference = load_transcript(json.loads((root / entry["reference"]).read_text(encoding="utf-8")), duration)
        predicted = load_transcript(json.loads((root / entry["hypothesis"]).read_text(encoding="utf-8")), duration)
        item = score_item(reference, predicted, duration, entry.get("processing_ms"))
        scored[entry["name"]] = item
        by_condition.setdefault(entry.get("condition", "unspecified"), []).append(item)
    report = {"per_recording": {name: {k: v for k, v in item.items() if not k.endswith("_errors_ms")}
                                for name, item in scored.items()},
              "per_condition": {c: summarize(items) for c, items in sorted(by_condition.items())},
              "total": summarize(list(scored.values()))}
    text = json.dumps(report, ensure_ascii=False, indent=1)
    print(text)
    if args.output:
        args.output.write_text(text + "\n", encoding="utf-8")


if __name__ == "__main__":
    main()
