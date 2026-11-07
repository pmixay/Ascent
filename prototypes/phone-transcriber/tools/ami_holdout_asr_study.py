#!/usr/bin/env python3
"""Both recognition modes and the automatic decision on Petya's frozen AMI holdout (host).

Development machine only, never CI or application audio. Inputs are the six verified
10-minute headset-mix clips reproduced by ``tools/qa/prepare_ami_holdout.py`` and the
manual annotation archive named in ``tools/qa/fixtures/ami_holdout_manifest.json``.

Reference policy, fixed before any inference: every ``<w>`` of the four speaker word files
except ``punc="true"`` marks, including fillers and truncated words; only words whose end
time is at most 600 s; all speakers merged in start-time order. Scores are speaker-agnostic
WER/CER (overlapping talk makes the merged order ambiguous) and the start/end error of
hypothesis words matched to reference words by a longest-matching-block alignment.

Hypotheses mirror the app: GigaAM with ``AsrGain`` over ``AsrChunking`` windows; Parakeet v3
over the same windows with ``ParakeetWords``; ``LanguageProbe`` for the automatic mode.
Speaker attribution runs only in Kotlin and is not measured here.

    ami_holdout_asr_study.py CLIPS_DIR ANNOTATION_ZIP GIGAAM_DIR SHERPA_MODELS_DIR OUT.json [THREADS]

Prints aggregates only, never transcript text.
"""
from __future__ import annotations

import difflib
import json
import re
import sys
import time
import zipfile
from pathlib import Path

from asr_metrics import add_counts, distance_function, text_counts
from asr_study import WindowGainSession
from evaluate_dialogs import recognize
from evaluate_speech import chunk_windows, normalize_text
from language_probe_study import probe
from reference_asr import ReferenceSession, load_tokens

REPO = Path(__file__).resolve().parents[3]
MANIFEST = REPO / "tools" / "qa" / "fixtures" / "ami_holdout_manifest.json"
LIMIT_S = 600.0
WORD = re.compile(r'<w [^>]*?starttime="([0-9.]+)" endtime="([0-9.]+)"([^>]*)>([^<]*)</w>')


def references(archive, clip):
    import html
    words = []
    with zipfile.ZipFile(archive) as labels:
        for member in clip["annotation_members"]:
            speaker = member.split(".")[1]
            for start, end, attributes, text in WORD.findall(labels.read(member).decode("iso-8859-1")):
                if 'punc="true"' in attributes or float(end) > LIMIT_S:
                    continue
                words.append({"text": html.unescape(text), "start_ms": round(float(start) * 1000),
                              "end_ms": round(float(end) * 1000), "speaker": speaker})
    return sorted(words, key=lambda w: (w["start_ms"], w["end_ms"]))


def tokens_with_times(words):
    """Normalized tokens, each carrying the times of the word it came from."""
    out = []
    for word in words:
        for token in normalize_text(word["text"]).split():
            out.append((token, word["start_ms"], word["end_ms"]))
    return out


def timing(reference, hypothesis):
    ref, hyp = tokens_with_times(reference), tokens_with_times(hypothesis)
    matcher = difflib.SequenceMatcher(a=[t for t, _, _ in ref], b=[t for t, _, _ in hyp], autojunk=False)
    starts, ends = [], []
    for block in matcher.get_matching_blocks():
        for offset in range(block.size):
            r, h = ref[block.a + offset], hyp[block.b + offset]
            starts.append(h[1] - r[1])
            ends.append(h[2] - r[2])
    return starts, ends


def parakeet_windowed(recognizer, samples, np):
    from sherpa_asr_compare import parakeet_words
    result, duration_ms = [], len(samples) // 16
    for core_start, core_end, start, end in chunk_windows(samples, True, np):
        stream = recognizer.create_stream(); stream.accept_waveform(16000, samples[start:end]); recognizer.decode_stream(stream)
        r = stream.result
        for word in parakeet_words(list(r.tokens), list(r.timestamps), list(getattr(r, "durations", [])), start // 16, duration_ms):
            if core_start // 16 <= (word["start_ms"] + word["end_ms"]) // 2 < core_end // 16:
                result.append(word)
    return sorted(result, key=lambda w: w["start_ms"])


def summary(rows):
    total = {}
    for row in rows:
        add_counts(total, row["counts"])
    starts = sorted(abs(v) for row in rows for v in row["starts"])
    ends = sorted(abs(v) for row in rows for v in row["ends"])
    signed = sorted(v for row in rows for v in row["starts"])

    def pct(values, q):
        return values[min(len(values) - 1, int(q * (len(values) - 1) + 0.5))] if values else None

    return {"words": total["words"], "word_errors": total["word_errors"], "wer": round(total["word_errors"] / total["words"], 4),
            "cer": round(total["char_errors"] / total["chars"], 4), "matched_words": len(starts),
            "start_signed_median_ms": pct(signed, 0.5), "start_abs_median_ms": pct(starts, 0.5),
            "start_abs_p95_ms": pct(starts, 0.95), "end_abs_median_ms": pct(ends, 0.5),
            "starts_within_250ms": round(sum(v <= 250 for v in starts) / len(starts), 4) if starts else None,
            "rtf": round(sum(row["seconds"] for row in rows) / sum(row["audio_s"] for row in rows), 4)}


def main():
    import numpy as np
    import onnxruntime as ort
    import soundfile
    import sherpa_asr_compare

    clips_dir, archive, gigaam_dir, sherpa_dir, out = map(Path, sys.argv[1:6])
    threads = int(sys.argv[6]) if len(sys.argv) > 6 else 2
    manifest = json.loads(MANIFEST.read_text(encoding="utf-8"))
    tokens = load_tokens(gigaam_dir / "multilingual_vocab.txt")
    gigaam = WindowGainSession(ReferenceSession(gigaam_dir / "multilingual_ctc.onnx", threads, ort, np), np)
    sherpa_asr_compare.ROOT = sherpa_dir
    parakeet = sherpa_asr_compare.recognizer("parakeet-v3", threads)
    distance = distance_function()
    rows = {"gigaam": [], "parakeet": [], "auto": []}
    per_clip, decisions = {}, {}
    for clip in manifest["clips"]:
        meeting = clip["meeting_id"]
        samples, rate = soundfile.read(clips_dir / f"{meeting}-first-10min.wav", dtype="float32")
        assert rate == 16000 and samples.ndim == 1
        reference = references(archive, clip)
        ref_text = " ".join(w["text"] for w in reference)
        outputs = {}
        started = time.perf_counter(); words, _ = recognize(gigaam, samples, tokens, meeting, np)
        outputs["gigaam"] = (words, time.perf_counter() - started)
        started = time.perf_counter(); words = parakeet_windowed(parakeet, samples, np)
        outputs["parakeet"] = (words, time.perf_counter() - started)
        decision, probed_ms = probe(gigaam, samples, tokens, np)
        decisions[meeting] = {"decision": decision, "decided_after_ms": probed_ms}
        outputs["auto"] = outputs["parakeet" if decision == "multilingual" else "gigaam"]
        per_clip[meeting] = {"reference_words": len(reference), "speakers": len({w["speaker"] for w in reference})}
        for name, (words, seconds) in outputs.items():
            counts = text_counts(ref_text, " ".join(w["text"] for w in words), distance)
            starts, ends = timing(reference, words)
            rows[name].append({"counts": counts, "starts": starts, "ends": ends, "seconds": seconds, "audio_s": len(samples) / 16000})
            per_clip[meeting][f"{name}_wer"] = round(counts["word_errors"] / counts["words"], 4)
        print(meeting, json.dumps(per_clip[meeting]), decisions[meeting], flush=True)
    report = {"policy": "non-punctuation <w> words ending by 600 s, all speakers in start order; speaker-agnostic",
              "clips": per_clip, "automatic_mode": decisions, "total": {name: summary(r) for name, r in rows.items()}}
    text = json.dumps(report, indent=1)
    Path(out).write_text(text + "\n")
    print(text)


if __name__ == "__main__":
    main()
