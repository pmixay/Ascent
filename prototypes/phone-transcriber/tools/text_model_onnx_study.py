#!/usr/bin/env python3
"""Host measurement of the provisional Android text stage (Qwen2.5 0.5B int8 ONNX, single pass).

This mirrors `TextPunctuator.kt` and `OnnxTextModel.kt` line for line: the same byte-level BPE,
instruction prefix, boundary queries, decision rule and block size with a carried key/value
cache. It scores the same 120 development clips as `text_model_study.py`, taking recognized
words from an `evaluate_dialogs.py` output directory. It runs on a development machine only and
measures host CPU speed, not ARM phone speed.

Dynamic int8 quantization makes logits depend on how many tokens one call contains, so the
block size changes results slightly; `--block 24` is the Android setting.
"""
from __future__ import annotations

import argparse
import json
from pathlib import Path
import time
import unicodedata

import numpy as np

from evaluate_speech import normalize_text
from text_model_study import INSTRUCTION, f1, score_marks, word_labels

MARKS = [",", ".", "?", "!"]
NAMES = {",": "comma", ".": "period", "?": "question", "!": "exclamation"}
TERMINAL = {".", "?", "!"}
MAX_WORDS = 64


def byte_chars():
    printable = list(range(33, 127)) + list(range(161, 173)) + list(range(174, 256))
    chars, extra = {}, 0
    for byte in range(256):
        if byte in printable:
            chars[byte] = chr(byte)
        else:
            chars[byte] = chr(256 + extra)
            extra += 1
    return chars


def split_pattern(data):
    """The Split regex of the file's pre-tokenizer (Qwen2.5 and Qwen3.5 differ in `\\p{M}`)."""
    steps = data["pre_tokenizer"].get("pretokenizers", [data["pre_tokenizer"]])
    return next(step["pattern"]["Regex"] for step in steps if step["type"] == "Split")


class Tokenizer:
    """Byte-level BPE as in QwenTokenizer.kt (requires the `regex` package for \\p{L})."""

    def __init__(self, path):
        import regex
        data = json.loads(Path(path).read_text(encoding="utf-8"))
        self.split = regex.compile(split_pattern(data))
        self.chars = byte_chars()
        self.vocab = data["model"]["vocab"]
        self.ranks = {}
        for index, merge in enumerate(data["model"]["merges"]):
            self.ranks.setdefault(merge if isinstance(merge, str) else " ".join(merge), index)
        self.special = {token["content"]: token["id"] for token in data["added_tokens"]}

    def encode(self, text):
        ids = []
        for match in self.split.finditer(unicodedata.normalize("NFC", text)):
            parts = [self.chars[b] for b in match.group(0).encode("utf-8")]
            while len(parts) > 1:
                best, at = None, -1
                for index in range(len(parts) - 1):
                    rank = self.ranks.get(parts[index] + " " + parts[index + 1])
                    if rank is not None and (best is None or rank < best):
                        best, at = rank, index
                if best is None:
                    break
                parts[at:at + 2] = [parts[at] + parts[at + 1]]
            ids += [self.vocab[part] for part in parts]
        return ids


class OnnxTextModel:
    def __init__(self, model, prefix, threads, block):
        import onnxruntime as ort
        options = ort.SessionOptions()
        options.intra_op_num_threads, options.inter_op_num_threads = threads, 1
        self.session = ort.InferenceSession(str(model), options, providers=["CPUExecutionProvider"])
        inputs = {i.name: i for i in self.session.get_inputs()}
        self.past_names = sorted(name for name in inputs if name.startswith("past_"))
        outputs = {o.name for o in self.session.get_outputs()}
        self.present = {name: present_name(name, outputs) for name in self.past_names}
        self.block = block
        # Symbolic dimensions: batch is 1, a growing sequence starts empty; fixed state sizes stay.
        empty = {}
        for name in self.past_names:
            shape = [1 if i == 0 else (d if isinstance(d, int) else 0) for i, d in enumerate(inputs[name].shape)]
            dtype = np.float16 if "float16" in inputs[name].type else np.float32
            empty[name] = np.zeros(shape, dtype)
        self.other = {name for name in inputs} - set(self.past_names) - {"input_ids", "attention_mask", "position_ids", "num_logits_to_keep"}
        if self.other:
            raise ValueError(f"unsupported inputs {sorted(self.other)}")
        self.position_rank = len(inputs["position_ids"].shape) if "position_ids" in inputs else 0
        self.keep_logits = "num_logits_to_keep" in inputs
        self.cache = self.chained(prefix, empty, 0, {}, {})
        self.prefix_length = len(prefix)

    def forward(self, ids, past, past_length):
        count = len(ids)
        positions = np.arange(past_length, past_length + count, dtype=np.int64)[None]
        if self.position_rank == 3:  # multimodal rotary positions: the same text positions on all three axes
            positions = np.repeat(positions[None], 3, axis=0)
        feeds = {"input_ids": np.array([ids], np.int64),
                 "attention_mask": np.ones((1, past_length + count), np.int64), **past}
        if self.position_rank:
            feeds["position_ids"] = positions
        if self.keep_logits:  # logits for every token of the block
            feeds["num_logits_to_keep"] = np.array(count, np.int64)
        names = [o.name for o in self.session.get_outputs()]
        return dict(zip(names, self.session.run(None, feeds)))

    def chained(self, tokens, past, length, wanted, scores):
        offset = 0
        while offset < len(tokens):
            count = min(self.block, len(tokens) - offset)
            result = self.forward(tokens[offset:offset + count], past, length)
            for position in range(offset, offset + count):
                if position in wanted:
                    scores[position] = [result["logits"][0, position - offset, i] for i in wanted[position]]
            past = {name: result[self.present[name]] for name in self.past_names}
            length += count
            offset += count
        return past

    def next_token_scores(self, tokens, wanted):
        scores = {}
        self.chained(tokens, self.cache, self.prefix_length, wanted, scores)
        return scores


def present_name(past, outputs):
    """Output that carries the next state for a `past_*` input (KV cache, conv or recurrent state)."""
    for candidate in ("present." + past.removeprefix("past_key_values."), "present" + past.removeprefix("past"),
                      past.replace("past_", "present_", 1), past.replace("past", "present", 1)):
        if candidate in outputs:
            return candidate
    raise ValueError(f"no output for {past}")


def instruction_prefix(tokenizer):
    sp = tokenizer.special
    return ([sp["<|im_start|>"]] + tokenizer.encode("system\n" + INSTRUCTION) + [sp["<|im_end|>"]]
            + tokenizer.encode("\n") + [sp["<|im_start|>"]] + tokenizer.encode("user\n"))


class TextPunctuator:
    def __init__(self, tokenizer, model, assistant_opening="assistant\n"):
        self.tokenizer, self.model, self.opening = tokenizer, model, assistant_opening
        self.im_start, self.im_end = tokenizer.special["<|im_start|>"], tokenizer.special["<|im_end|>"]
        self.marks = [tokenizer.encode(mark)[0] for mark in MARKS]

    def encode_with_special(self, text):
        import regex
        ids = []
        for chunk in regex.split(r"(<think>|</think>)", text):
            ids += [self.tokenizer.special[chunk]] if chunk in self.tokenizer.special else (self.tokenizer.encode(chunk) if chunk else [])
        return ids

    def styles(self, words):
        result = []
        for start in range(0, len(words), MAX_WORDS):
            styled = self.block(words[start:start + MAX_WORDS])
            if result and result[-1][1] in TERMINAL:
                styled[0] = (True, styled[0][1])
            result += styled
        return result

    def block(self, words):
        t = self.tokenizer
        user = t.encode(" ".join(words)) + [self.im_end] + t.encode("\n") + [self.im_start] + self.encode_with_special(self.opening)
        answer, starts, lower, upper = [], [], [], []
        for index, word in enumerate(words):
            lead = "" if index == 0 else " "
            tokens = t.encode(lead + word)
            starts.append(len(answer))
            answer += tokens
            lower.append(tokens[0])
            upper.append(t.encode(lead + word[:1].upper() + word[1:])[0])
        tokens, base = user + answer, len(user) - 1
        wanted = {base: [lower[0], upper[0]]}
        for index in range(len(words)):
            last = index + 1 == len(words)
            wanted[base + (len(answer) if last else starts[index + 1])] = (
                [self.im_end] if last else [lower[index + 1], upper[index + 1]]) + self.marks
        scores = self.model.next_token_scores(tokens, wanted)
        styled = []
        for index in range(len(words)):
            last = index + 1 == len(words)
            start = scores[base + starts[index]]
            boundary = scores[base + (len(answer) if last else starts[index + 1])]
            continuation = boundary[0] if last else max(boundary[0], boundary[1])
            best = -1
            for mark in range(len(MARKS)):
                value = boundary[len(boundary) - len(MARKS) + mark]
                if value > continuation and (best < 0 or value > boundary[len(boundary) - len(MARKS) + best]):
                    best = mark
            styled.append((bool(start[1] > start[0]), MARKS[best] if best >= 0 else None))
        return [(True, s[1]) if i > 0 and styled[i - 1][1] in TERMINAL else s for i, s in enumerate(styled)]


def clips(output, limit):
    """First development clips whose recognized words equal the reference words."""
    from asr_metrics import assign_words
    result = []
    for path in sorted(Path(output).glob("masha_dima_part1-*.json")):
        block = json.loads(path.read_text())
        for reference, assigned in zip(block["references"], assign_words(block["references"], block["words"])):
            words = [word["text"] for word in assigned]
            labels = word_labels(reference["text"])
            if words and [w for w, _, _ in labels] == normalize_text(" ".join(words)).split():
                result.append({"words": words, "reference": labels,
                               "seconds": (reference["end_ms"] - reference["start_ms"]) / 1000})
    return result[:limit]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("output", type=Path, help="evaluate_dialogs.py output directory")
    parser.add_argument("--model", type=Path, required=True)
    parser.add_argument("--tokenizer", type=Path, required=True)
    parser.add_argument("--threads", type=int, default=2)
    parser.add_argument("--block", type=int, default=24)
    parser.add_argument("--limit", type=int, default=120)
    parser.add_argument("--no-thinking-opening", action="store_true",
                        help="Open the answer with an empty think block (Qwen3.5 non-thinking chat template).")
    args = parser.parse_args()
    tokenizer = Tokenizer(args.tokenizer)
    started = time.perf_counter()
    opening = "assistant\n<think>\n\n</think>\n\n" if args.no_thinking_opening else "assistant\n"
    stage = TextPunctuator(tokenizer, OnnxTextModel(args.model, instruction_prefix(tokenizer), args.threads, args.block), opening)
    setup = time.perf_counter() - started
    totals = {kind: {"tp": 0, "fp": 0, "fn": 0} for kind in ("comma", "terminal", "question")}
    capital = words = 0
    seconds = audio = 0.0
    data = clips(args.output, args.limit)
    for clip in data:
        begin = time.perf_counter()
        labels = [(w, c, NAMES.get(m)) for w, (c, m) in zip(clip["words"], stage.styles(clip["words"]))]
        seconds += time.perf_counter() - begin
        audio += clip["seconds"]
        counts, correct = score_marks(clip["reference"], labels)
        capital += correct
        words += len(clip["reference"])
        for kind in totals:
            for key in totals[kind]:
                totals[kind][key] += counts[kind][key]
    print(json.dumps({"model": args.model.name, "threads": args.threads, "block": args.block, "clips": len(data),
                      "words": words, "f1": {k: f1(v) for k, v in totals.items()},
                      "capitalization_accuracy": round(capital / words, 4) if words else None,
                      "setup_seconds": round(setup, 2), "seconds": round(seconds, 2), "audio_seconds": round(audio, 2),
                      "incremental_rtf": round(seconds / audio, 4) if audio else None}))


if __name__ == "__main__":
    main()
