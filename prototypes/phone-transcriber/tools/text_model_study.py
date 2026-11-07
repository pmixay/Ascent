#!/usr/bin/env python3
"""Local feasibility study for the competition's on-device text model (plan H07/P2).

The candidate's only job is punctuation and capitalization over recognized words.
Every output is validated: after the shared lexical normalization it must contain
exactly the input words in the same order, otherwise the raw ASR text is kept.
Accepted outputs are projected back onto the original words, so word identity,
order, speaker labels and timestamps cannot change.

Inputs are recognized words from ``asr_study.py infer`` development checkpoints;
reference punctuation comes from the public Dialogs transcripts. This runs on a
development machine only. It measures host CPU speed, not ARM phone speed, and
it does not integrate or package any model.
"""
from __future__ import annotations

import argparse
import json
from pathlib import Path
import re
import time

from evaluate_speech import normalize_text

MARKS = {",": "comma", ".": "period", "?": "question", "!": "exclamation"}
TERMINAL = {"period", "question", "exclamation"}
INSTRUCTION = ("Расставь знаки препинания и заглавные буквы в тексте распознанной русской речи. "
               "Нельзя добавлять, удалять, переставлять или заменять слова. "
               "Верни только исправленный текст без пояснений.")


def word_labels(text):
    """Per word: (normalized word, capitalized, mark after it) from punctuated text."""
    labels = []
    for match in re.finditer(r"[^\W_]+(?:[-'][^\W_]+)*|[,.?!…]", text):
        token = match.group(0)
        if token in MARKS or token == "…":
            if labels and labels[-1][2] is None:
                labels[-1] = (labels[-1][0], labels[-1][1], MARKS.get(token, "period"))
            continue
        for part in normalize_text(token).split():
            labels.append((part, token[:1].isupper(), None))
    return labels


def validate_output(source_words, output):
    """Return projected labels when the output preserves every input word, else None."""
    cleaned = re.sub(r"<think>.*?</think>", "", output, flags=re.S).strip()
    labels = word_labels(cleaned)
    if [word for word, _, _ in labels] != normalize_text(" ".join(source_words)).split():
        return None
    return labels


def render(source_words, labels):
    """Apply capitalization/marks to the original recognized words; word identity is untouched."""
    if len(labels) != len(source_words):
        return " ".join(source_words)  # a word split by normalization cannot be projected safely
    pieces = []
    for word, (_, capital, mark) in zip(source_words, labels):
        token = word[:1].upper() + word[1:] if capital else word
        pieces.append(token + {"comma": ",", "period": ".", "question": "?", "exclamation": "!"}.get(mark, ""))
    return " ".join(pieces)


def score_marks(reference_labels, predicted_labels):
    """Mark-level precision/recall counts and capitalization agreement over aligned words."""
    counts = {kind: {"tp": 0, "fp": 0, "fn": 0} for kind in ("comma", "terminal", "question")}
    capital_correct = 0
    for (_, ref_cap, ref_mark), (_, hyp_cap, hyp_mark) in zip(reference_labels, predicted_labels):
        capital_correct += int(ref_cap == hyp_cap)
        for kind, test in (("comma", lambda m: m == "comma"), ("terminal", lambda m: m in TERMINAL),
                           ("question", lambda m: m == "question")):
            expected, actual = test(ref_mark), test(hyp_mark)
            counts[kind]["tp"] += int(expected and actual)
            counts[kind]["fp"] += int(actual and not expected)
            counts[kind]["fn"] += int(expected and not actual)
    return counts, capital_correct


def f1(item):
    precision = item["tp"] / (item["tp"] + item["fp"]) if item["tp"] + item["fp"] else 0.0
    recall = item["tp"] / (item["tp"] + item["fn"]) if item["tp"] + item["fn"] else 0.0
    return round(2 * precision * recall / (precision + recall), 4) if precision + recall else 0.0


def clip_inputs(checkpoints, limit):
    """Clips whose recognized words equal the reference words, so punctuation can be scored."""
    from asr_metrics import assign_words

    result = []
    for path in sorted(Path(checkpoints).glob("*.json")):
        block = json.loads(path.read_text())
        if block.get("kind") != "asr-study-block":
            continue
        for reference, assigned in zip(block["references"], assign_words(block["references"], block["words"])):
            words = [word["text"] for word in assigned]
            reference_labels = word_labels(reference["text"])
            if words and [w for w, _, _ in reference_labels] == normalize_text(" ".join(words)).split():
                seconds = (reference["end_ms"] - reference["start_ms"]) / 1000
                result.append({"words": words, "reference": reference_labels, "seconds": seconds})
    return result[:limit] if limit else result


def run(args):
    from llama_cpp import Llama

    clips = clip_inputs(args.checkpoints, args.limit)
    started = time.perf_counter()
    model = Llama(model_path=str(args.model), n_ctx=1024, n_threads=args.threads, n_batch=256,
                  seed=0, verbose=False)
    load_seconds = time.perf_counter() - started
    totals = {kind: {"tp": 0, "fp": 0, "fn": 0} for kind in ("comma", "terminal", "question")}
    raw = {kind: {"tp": 0, "fp": 0, "fn": 0} for kind in ("comma", "terminal", "question")}
    valid = capital = words = 0
    generation_seconds = audio_seconds = 0.0
    tokens_out = 0
    for clip in clips:
        text = " ".join(clip["words"])
        prompt = text + (" /no_think" if args.no_think else "")
        begin = time.perf_counter()
        reply = model.create_chat_completion(
            messages=[{"role": "system", "content": INSTRUCTION}, {"role": "user", "content": prompt}],
            temperature=0.0, top_k=1, max_tokens=min(768, 6 * len(clip["words"]) + 32), seed=0)
        generation_seconds += time.perf_counter() - begin
        audio_seconds += clip["seconds"]
        tokens_out += reply["usage"]["completion_tokens"]
        labels = validate_output(clip["words"], reply["choices"][0]["message"]["content"] or "")
        fallback = [(normalize_text(w), False, None) for w in clip["words"]]
        if labels is not None:
            valid += 1
        counts, correct = score_marks(clip["reference"], labels or fallback)
        raw_counts, _ = score_marks(clip["reference"], fallback)
        for kind in totals:
            for key in ("tp", "fp", "fn"):
                totals[kind][key] += counts[kind][key]
                raw[kind][key] += raw_counts[kind][key]
        capital += correct
        words += len(clip["reference"])
    summary = {"model": args.model.name, "threads": args.threads, "clips": len(clips), "words": words,
               "valid_outputs": valid, "fallbacks": len(clips) - valid,
               "f1_with_fallback": {kind: f1(item) for kind, item in totals.items()},
               "f1_raw_asr": {kind: f1(item) for kind, item in raw.items()},
               "capitalization_accuracy": round(capital / words, 4) if words else None,
               "load_seconds": round(load_seconds, 2), "generation_seconds": round(generation_seconds, 2),
               "completion_tokens": tokens_out, "audio_seconds": round(audio_seconds, 2),
               "incremental_rtf": round(generation_seconds / audio_seconds, 4) if audio_seconds else None}
    print(json.dumps(summary, ensure_ascii=False))
    if args.output:
        args.output.write_text(json.dumps(summary, ensure_ascii=False, indent=1) + "\n", encoding="utf-8")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("checkpoints", type=Path)
    parser.add_argument("--model", type=Path, required=True)
    parser.add_argument("--threads", type=int, default=2, choices=[1, 2, 3, 4])
    parser.add_argument("--limit", type=int, default=0)
    parser.add_argument("--no-think", action="store_true", help="Append Qwen3's /no_think switch.")
    parser.add_argument("--output", type=Path)
    run(parser.parse_args())


if __name__ == "__main__":
    main()
