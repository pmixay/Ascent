#!/usr/bin/env python3
"""Host check of the automatic recognition mode (LanguageProbe.kt) on public data.

Development machine only. The probe runs GigaAM (with the app's AsrGain) over the app's
windows from the start of a recording; after each window it counts Latin (a-z) and other
letters in the words owned so far. Once at least MIN_LETTERS letters are seen, or the probed
cores reach MAX_PROBE_MS, it decides: Latin majority -> multilingual mode, otherwise Russian
and CIS languages. A recording that ends undecided stays in the Russian mode.

Samples, all fixed by rule before running:
- Russian: every assembled Dialogs block of the development and validation roles
  (constructed sequences, as in asr_study.py);
- English: consecutive AMI utterances of the frozen EN2002a (IHM and SDM) and ES2011a (IHM)
  selections joined with 0.5 s of silence into constructed blocks of at least 60 s;
- silence: 30 s of digital zero.

    language_probe_study.py CACHE MODEL_DIR AMI_DIR OUT.json [THREADS]

CACHE holds the Dialogs recordings, MODEL_DIR the pinned GigaAM reference export, AMI_DIR the
AMI shards named in the code. Prints aggregate decisions only.
"""
from __future__ import annotations

import csv
import hashlib
import io
import json
import sys
from pathlib import Path

from asr_study import WindowGainSession, load_study, role_manifest
from evaluate_dialogs import assemble, blocks
from evaluate_speech import chunk_windows, infer_ids, timed_words
from reference_asr import ReferenceSession, load_tokens

TOOLS = Path(__file__).resolve().parent
MIN_LETTERS = 30
MAX_PROBE_MS = 60_000
AMI_SHARDS = {("test", "ihm"): "ihm-test-0.parquet", ("test", "sdm"): "sdm-test-0.parquet",
              ("development", "ihm"): "ihm-validation-0.parquet"}


def letters(words):
    latin = other = 0
    for word in words:
        for char in word["text"]:
            if "a" <= char <= "z":
                latin += 1
            elif char.isalpha():
                other += 1
    return latin, other


def probe(session, samples, tokens, np):
    """Mirror of LanguageProbe + the AUTO branch of TranscriptionEngine: (decision, probed ms)."""
    owned = []
    duration_ms = len(samples) // 16
    for core_start, core_end, start, end in chunk_windows(samples, True, np):
        audio = samples[start:end]
        if len(audio) < 4000:
            audio = np.pad(audio, (0, 4000 - len(audio)))
        ids = infer_ids(session, audio, tokens, np)
        for word, begin, finish in timed_words(ids, tokens, start // 16, len(audio) // 16):
            if begin < duration_ms and core_start // 16 <= (begin + finish) // 2 < core_end // 16:
                owned.append({"text": word})
        latin, other = letters(owned)
        if latin + other >= MIN_LETTERS or core_end // 16 >= MAX_PROBE_MS:
            return ("multilingual" if latin > other else "russian_cis"), core_end // 16
    return "russian_cis", duration_ms


def dialogs_samples(cache, np, soundfile, resample_poly):
    study = load_study()
    cache = cache / study["dataset"]["revision"]
    with (cache / "metadata.csv").open(encoding="utf-8", newline="") as source:
        metadata = {row["audio_path"]: row for row in csv.DictReader(source, delimiter="|")}
    for role in ("development", "validation"):
        for block_id, items in blocks(role_manifest(study, role)):
            pcm, _ = assemble(items, metadata, cache, study["selection"]["join_silence_ms"], np, soundfile, resample_poly)
            yield f"russian/{role}", pcm.astype(np.float32) / 32768.0


def ami_samples(ami_dir, np, soundfile):
    import pyarrow.parquet as pq
    selection = json.loads((TOOLS / "fixtures" / "ami_english.json").read_text(encoding="utf-8"))
    gap = np.zeros(8000, dtype=np.float32)
    for (split, channel), shard in AMI_SHARDS.items():
        items = selection[split][channel]
        wanted = {item["id"] for item in items}
        audio = {row["audio_id"]: row["audio"]["bytes"] for row in
                 pq.read_table(ami_dir / shard, columns=["audio_id", "audio"]).to_pylist() if row["audio_id"] in wanted}
        pieces, length = [], 0
        for item in items:
            payload = audio[item["id"]]
            if hashlib.sha256(payload).hexdigest() != item["sha256"]:
                raise ValueError("AMI payload failed verification")
            samples, _ = soundfile.read(io.BytesIO(payload), dtype="float32")
            pieces += [samples, gap]
            length += len(samples) + len(gap)
            if length >= MAX_PROBE_MS * 16:
                yield f"english/{split}-{channel}", np.concatenate(pieces)
                pieces, length = [], 0


def main():
    import numpy as np
    import onnxruntime as ort
    import soundfile
    from scipy.signal import resample_poly

    cache, model_dir, ami_dir, out = map(Path, sys.argv[1:5])
    threads = int(sys.argv[5]) if len(sys.argv) > 5 else 2
    tokens = load_tokens(model_dir / "multilingual_vocab.txt")
    session = WindowGainSession(ReferenceSession(model_dir / "multilingual_ctc.onnx", threads, ort, np), np)
    results = {}

    def record(group, samples):
        pcm = np.clip(np.rint(samples * 32768.0), -32768, 32767).astype(np.float32) / 32768.0
        decision, probed_ms = probe(session, pcm, tokens, np)
        row = results.setdefault(group, {"samples": 0, "russian_cis": 0, "multilingual": 0, "probed_ms": []})
        row["samples"] += 1
        row[decision] += 1
        row["probed_ms"].append(probed_ms)

    for group, samples in dialogs_samples(cache, np, soundfile, resample_poly):
        record(group, samples)
    for group, samples in ami_samples(ami_dir, np, soundfile):
        record(group, samples)
    record("silence", np.zeros(30 * 16000, dtype=np.float32))
    for row in results.values():
        probed = sorted(row.pop("probed_ms"))
        row["probed_ms_median"] = probed[len(probed) // 2]
        row["probed_ms_max"] = probed[-1]
    text = json.dumps(results, indent=1)
    Path(out).write_text(text + "\n")
    print(text)


if __name__ == "__main__":
    main()
