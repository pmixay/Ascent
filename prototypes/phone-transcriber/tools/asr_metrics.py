"""Standard-library scoring and error analysis for local ASR study checkpoints.

Inputs are the aggregate-friendly JSON blocks written by ``asr_study.py infer``:
reference clips with known positions in an assembled block, plus recognized
words with CTC-derived times. Nothing here reads audio or loads a model.
Reference clip bounds include any silence inside the published clip, so errors
against them are conservative; the optional energy bounds are a labelled proxy
for speech onset/offset, not a forced alignment.
"""
from __future__ import annotations

import bisect
import collections

from evaluate_speech import edit_distance, normalize_text


def distance_function():
    """Fast Levenshtein when the pinned evaluation dependency exists, else pure Python."""
    try:
        from rapidfuzz.distance import Levenshtein
        return Levenshtein.distance
    except ImportError:
        return edit_distance


def percentile(sorted_values, fraction):
    """Linear-interpolated percentile of an ascending, nonempty list."""
    if not sorted_values:
        raise ValueError("Percentile of an empty sample")
    position = (len(sorted_values) - 1) * fraction
    low = int(position)
    high = min(low + 1, len(sorted_values) - 1)
    return sorted_values[low] + (sorted_values[high] - sorted_values[low]) * (position - low)


def interval_distance(point, reference):
    if reference["start_ms"] <= point < reference["end_ms"]:
        return 0.0
    return min(abs(point - reference["start_ms"]), abs(point - reference["end_ms"]))


def assign_words(references, words):
    """Give every recognized word to the nearest reference clip by its time midpoint."""
    starts = [reference["start_ms"] for reference in references]
    assigned = [[] for _ in references]
    for word in words:
        middle = (word["start_ms"] + word["end_ms"]) / 2
        index = bisect.bisect_right(starts, middle) - 1
        candidates = [item for item in (index, index + 1) if 0 <= item < len(references)]
        if not candidates:
            continue
        best = min(candidates, key=lambda item: interval_distance(middle, references[item]))
        assigned[best].append(word)
    for words_in_clip in assigned:
        words_in_clip.sort(key=lambda word: (word["start_ms"], word["end_ms"]))
    return assigned


def intersection_over_union(first, second):
    overlap = min(first[1], second[1]) - max(first[0], second[0])
    if overlap <= 0:
        return 0.0
    return overlap / (max(first[1], second[1]) - min(first[0], second[0]))


def clip_timing(reference, words, energy_key="30"):
    """Turn-level timing errors for one clip; None when nothing was recognized in it."""
    if not words:
        return None
    start = words[0]["start_ms"]
    end = max(word["end_ms"] for word in words)
    row = {"start_error_ms": start - reference["start_ms"], "end_error_ms": end - reference["end_ms"],
           "iou": intersection_over_union((start, end), (reference["start_ms"], reference["end_ms"]))}
    bounds = (reference.get("energy_bounds") or {}).get(energy_key)
    if bounds:
        speech = (reference["start_ms"] + bounds[0], reference["start_ms"] + bounds[1])
        row.update({"energy_start_error_ms": start - speech[0], "energy_end_error_ms": end - speech[1],
                    "energy_iou": intersection_over_union((start, end), speech)})
    return row


def summarize_timing(values):
    """Signed bias and absolute-error distribution, in milliseconds."""
    if not values:
        return {"n": 0}
    absolute = sorted(abs(value) for value in values)
    signed = sorted(values)
    count = len(values)
    return {"n": count, "median_signed_ms": round(percentile(signed, .5), 1),
            "median_abs_ms": round(percentile(absolute, .5), 1),
            "mean_abs_ms": round(sum(absolute) / count, 1),
            "p90_abs_ms": round(percentile(absolute, .9), 1),
            "p95_abs_ms": round(percentile(absolute, .95), 1),
            **{f"within_{limit}_ms": round(sum(value <= limit for value in absolute) / count, 4)
               for limit in (100, 250, 500)}}


def text_counts(reference, hypothesis, distance):
    expected, actual = normalize_text(reference), normalize_text(hypothesis)
    expected_chars, actual_chars = expected.replace(" ", ""), actual.replace(" ", "")
    return {"word_errors": distance(expected.split(), actual.split()), "words": len(expected.split()),
            "char_errors": distance(expected_chars, actual_chars), "chars": len(expected_chars)}


def add_counts(total, item):
    for key, value in item.items():
        total[key] = total.get(key, 0) + value


def rate(errors, total):
    return round(errors / total, 6) if total else None


def duration_bucket(seconds):
    return "<2s" if seconds < 2 else "2-5s" if seconds < 5 else "5-10s" if seconds < 10 else ">=10s"


def score_blocks(blocks, distance=None, energy_key="30"):
    """Aggregate transcription, timing and speed measurements over checkpoint blocks."""
    distance = distance or distance_function()
    total, block_level = {}, {}
    groups = {"speaker": {}, "emotion": {}, "clip_length": {}}
    timing = {name: [] for name in ("start_error_ms", "end_error_ms", "iou", "energy_start_error_ms",
                                    "energy_end_error_ms", "energy_iou")}
    empty_clips = clips = 0
    audio_seconds = asr_seconds = model_seconds = 0.0
    block_rtf = []
    for block in blocks:
        references, words = block["references"], block["words"]
        add_counts(block_level, text_counts(" ".join(item["text"] for item in references),
                                            " ".join(word["text"] for word in words), distance))
        for reference, assigned in zip(references, assign_words(references, words)):
            clips += 1
            counts = text_counts(reference["text"], " ".join(word["text"] for word in assigned), distance)
            add_counts(total, counts)
            seconds = (reference["end_ms"] - reference["start_ms"]) / 1000
            for kind, key in (("speaker", reference["speaker"]), ("emotion", reference.get("emotion", "?")),
                              ("clip_length", duration_bucket(seconds))):
                add_counts(groups[kind].setdefault(key, {"clips": 0}), {**counts, "clips": 1})
            row = clip_timing(reference, assigned, energy_key)
            if row is None:
                empty_clips += 1
                continue
            for name, value in row.items():
                timing[name].append(value)
        duration = block["duration_ms"] / 1000
        audio_seconds += duration
        asr_seconds += block["asr_seconds"]
        model_seconds += block.get("model_seconds", 0.0)
        block_rtf.append(block["asr_seconds"] / duration)
    return {
        "blocks": len(blocks), "clips": clips, "audio_seconds": round(audio_seconds, 3),
        "block_level": {**block_level, "wer": rate(block_level["word_errors"], block_level["words"]),
                        "cer": rate(block_level["char_errors"], block_level["chars"])},
        "clip_level": {**total, "wer": rate(total["word_errors"], total["words"]),
                       "cer": rate(total["char_errors"], total["chars"]), "clips_without_words": empty_clips},
        "groups": {kind: {key: {**value, "wer": rate(value["word_errors"], value["words"]),
                                "cer": rate(value["char_errors"], value["chars"])}
                          for key, value in sorted(mapping.items())} for kind, mapping in groups.items()},
        "timing_vs_clip_bounds": {"start": summarize_timing(timing["start_error_ms"]),
                                  "end": summarize_timing(timing["end_error_ms"]),
                                  "mean_iou": round(sum(timing["iou"]) / len(timing["iou"]), 4) if timing["iou"] else None},
        "timing_vs_energy_proxy": {"threshold_db_below_peak": energy_key,
                                   "start": summarize_timing(timing["energy_start_error_ms"]),
                                   "end": summarize_timing(timing["energy_end_error_ms"]),
                                   "mean_iou": round(sum(timing["energy_iou"]) / len(timing["energy_iou"]), 4)
                                   if timing["energy_iou"] else None},
        "speed": {"asr_seconds": round(asr_seconds, 3), "model_seconds": round(model_seconds, 3),
                  "rtf": round(asr_seconds / audio_seconds, 4) if audio_seconds else None,
                  "block_rtf_min": round(min(block_rtf), 4) if block_rtf else None,
                  "block_rtf_max": round(max(block_rtf), 4) if block_rtf else None},
    }


def align(reference, hypothesis):
    """Word-level Levenshtein alignment: list of (operation, reference index, hypothesis index)."""
    rows, columns = len(reference), len(hypothesis)
    table = [[0] * (columns + 1) for _ in range(rows + 1)]
    for row in range(rows + 1):
        table[row][0] = row
    for column in range(columns + 1):
        table[0][column] = column
    for row in range(1, rows + 1):
        for column in range(1, columns + 1):
            table[row][column] = min(table[row - 1][column] + 1, table[row][column - 1] + 1,
                                     table[row - 1][column - 1] + (reference[row - 1] != hypothesis[column - 1]))
    operations, row, column = [], rows, columns
    while row or column:
        if row and column and table[row][column] == table[row - 1][column - 1] + (reference[row - 1] != hypothesis[column - 1]):
            operations.append(("ok" if reference[row - 1] == hypothesis[column - 1] else "sub", row - 1, column - 1))
            row, column = row - 1, column - 1
        elif row and table[row][column] == table[row - 1][column] + 1:
            operations.append(("del", row - 1, None))
            row -= 1
        else:
            operations.append(("ins", None, column - 1))
            column -= 1
    return operations[::-1]


def error_runs(operations):
    """Maximal consecutive error operations, as (reference indexes, hypothesis indexes)."""
    runs, current = [], None
    for operation, ref_index, hyp_index in operations:
        if operation == "ok":
            current = None
            continue
        if current is None:
            current = ([], [])
            runs.append(current)
        if ref_index is not None:
            current[0].append(ref_index)
        if hyp_index is not None:
            current[1].append(hyp_index)
    return runs


def classify_run(reference_words, hypothesis_words, distance):
    if not hypothesis_words:
        return "deletion"
    if not reference_words:
        return "insertion"
    if "".join(reference_words) == "".join(hypothesis_words):
        return "word boundary (split or merge)"
    if len(reference_words) == len(hypothesis_words) == 1:
        word = reference_words[0]
        if len(word) >= 4 and distance(word, hypothesis_words[0]) <= 2:
            return "close form (at most 2 letter edits)"
    return "other substitution"


def word_length_bucket(word):
    return "1-3 letters" if len(word) <= 3 else "4-6 letters" if len(word) <= 6 else "7+ letters"


def seam_boundaries(block):
    return [window["core_start"] // 16 for window in block.get("asr_windows", [])[1:]]


def analyze_errors(blocks, distance=None, seam_margin_ms=500):
    """Aggregate error taxonomy; reports counts and rates, never transcripts."""
    distance = distance or distance_function()
    classes = collections.Counter()
    by_position = {name: [0, 0] for name in ("first word", "last word", "interior")}
    by_length = {name: [0, 0] for name in ("1-3 letters", "4-6 letters", "7+ letters")}
    by_seam = {"within 0.5 s of a chunk seam": [0, 0], "elsewhere": [0, 0]}
    by_clip_words = {name: [0, 0] for name in ("1-2 words", "3-5 words", "6-10 words", "11+ words")}
    substitutions = collections.Counter()
    reference_total = errors_total = 0
    for block in blocks:
        seams = seam_boundaries(block)
        references = block["references"]
        for reference, assigned in zip(references, assign_words(references, block["words"])):
            expected = normalize_text(reference["text"]).split()
            actual = normalize_text(" ".join(word["text"] for word in assigned)).split()
            if not expected:
                continue
            near_seam = any(reference["start_ms"] - seam_margin_ms <= seam <= reference["end_ms"] + seam_margin_ms
                            for seam in seams)
            seam_key = "within 0.5 s of a chunk seam" if near_seam else "elsewhere"
            clip_key = ("1-2 words" if len(expected) <= 2 else "3-5 words" if len(expected) <= 5
                        else "6-10 words" if len(expected) <= 10 else "11+ words")
            by_seam[seam_key][1] += len(expected)
            by_clip_words[clip_key][1] += len(expected)
            for index, word in enumerate(expected):
                by_length[word_length_bucket(word)][1] += 1
                by_position["first word" if index == 0 else "last word" if index == len(expected) - 1
                            else "interior"][1] += 1
            reference_total += len(expected)
            for ref_indexes, hyp_indexes in error_runs(align(expected, actual)):
                ref_words = [expected[item] for item in ref_indexes]
                hyp_words = [actual[item] for item in hyp_indexes]
                label = classify_run(ref_words, hyp_words, distance)
                classes[label] += max(len(ref_words), len(hyp_words)) if label != "insertion" else len(hyp_words)
                run_errors = max(len(ref_words), len(hyp_words))
                errors_total += run_errors
                by_seam[seam_key][0] += run_errors
                by_clip_words[clip_key][0] += run_errors
                for item in ref_indexes:
                    by_length[word_length_bucket(expected[item])][0] += 1
                    by_position["first word" if item == 0 else "last word" if item == len(expected) - 1
                                else "interior"][0] += 1
                if len(ref_words) == len(hyp_words) == 1:
                    substitutions[(ref_words[0], hyp_words[0])] += 1
    def table(mapping):
        return {key: {"errors": errors, "reference_words": words, "rate": rate(errors, words)}
                for key, (errors, words) in mapping.items()}
    return {"reference_words": reference_total, "error_words": errors_total, "error_rate": rate(errors_total, reference_total),
            "classes": dict(classes.most_common()), "by_position": table(by_position),
            "by_reference_word_length": table(by_length), "by_chunk_seam": table(by_seam),
            "by_clip_words": table(by_clip_words),
            "repeated_single_word_substitutions": [{"count": count, "reference": pair[0], "hypothesis": pair[1]}
                                                    for pair, count in substitutions.most_common(15) if count > 1]}


def compare_runs(first_blocks, second_blocks, distance=None):
    """Token-level reproducibility comparison of two inference runs on identical audio."""
    distance = distance or distance_function()
    second = {block["id"]: block for block in second_blocks}
    result = {"blocks": 0, "windows": 0, "windows_with_different_ids": 0, "frames": 0, "different_frames": 0,
              "windows_with_different_length": 0, "word_edit_distance": 0, "words": 0, "max_word_time_shift_ms": 0,
              "blocks_bitwise_identical": 0}
    for block in first_blocks:
        other = second.get(block["id"])
        if other is None:
            continue
        result["blocks"] += 1
        identical = True
        for left, right in zip(block["asr_windows"], other["asr_windows"]):
            result["windows"] += 1
            if len(left["ids"]) != len(right["ids"]):
                result["windows_with_different_length"] += 1
                identical = False
                continue
            different = sum(a != b for a, b in zip(left["ids"], right["ids"]))
            result["frames"] += len(left["ids"])
            result["different_frames"] += different
            if different:
                result["windows_with_different_ids"] += 1
                identical = False
        left_words = [word["text"] for word in block["words"]]
        right_words = [word["text"] for word in other["words"]]
        result["words"] += len(left_words)
        result["word_edit_distance"] += distance(left_words, right_words)
        if len(left_words) == len(right_words):
            shifts = [max(abs(a["start_ms"] - b["start_ms"]), abs(a["end_ms"] - b["end_ms"]))
                      for a, b in zip(block["words"], other["words"])]
            result["max_word_time_shift_ms"] = max([result["max_word_time_shift_ms"], *shifts])
        result["blocks_bitwise_identical"] += int(identical and left_words == right_words)
    return result
