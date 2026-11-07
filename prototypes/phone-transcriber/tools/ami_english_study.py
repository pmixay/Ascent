#!/usr/bin/env python3
"""English conversational ASR check on the AMI meeting corpus (CC-BY-4.0), utterance level.

Development machine only, never CI or application audio. Selection, frozen before any
inference in ``fixtures/ami_english.json``:

- development: every utterance of meeting ES2011a, headset (IHM) channel, used only to choose
  the recognizer level normalization;
- test: every utterance of meeting EN2002a, headset (IHM, call-like) and single distant
  microphone (SDM, meeting-like), measured once per configuration.

Each utterance is recognized on its own with the app's chunking and CTC decoder, so this
measures recognition only, not diarization. No EN2002a utterance exceeds one 20 s window, so
the per-window level normalization equals a per-utterance one here. Prints aggregates only.

    ami_english_study.py freeze --ihm-test IHM.parquet --sdm-test SDM.parquet --ihm-dev DEV.parquet
    ami_english_study.py infer --split test --channel ihm --shard IHM.parquet --model M.onnx \\
        --vocab multilingual_vocab.txt [--asr-gain peak] [--output result.json]
"""
from __future__ import annotations

import argparse
import contextlib
import hashlib
import io
import json
import os
from pathlib import Path
import time

from asr_metrics import add_counts, distance_function, text_counts
from asr_study import WindowGainSession
from evaluate_dialogs import recognize
from reference_asr import ReferenceSession, load_tokens

SELECTION = Path(__file__).resolve().parent / "fixtures" / "ami_english.json"
DATASET = {"repository": "edinburghcstr/ami", "revision": "46f28f2503e2ec48f8867a84eef356c70476beab",
           "license": "CC-BY-4.0", "shards": "{channel}/{split}-00000-of-0000N.parquet"}
MEETINGS = {"development": "ES2011a", "test": "EN2002a"}
FILLERS = {"um", "uh", "mm", "hmm", "mm-hmm", "uh-huh", "huh", "hm"}


def rows(path, meeting):
    import pyarrow.parquet as pq
    table = pq.read_table(path, columns=["meeting_id", "audio_id", "text", "speaker_id", "begin_time", "audio"])
    found = [row for row in table.to_pylist() if row["meeting_id"] == meeting]
    return sorted(found, key=lambda row: (row["begin_time"], row["audio_id"]))


def freeze(args):
    selection = {"schema_version": 1, "dataset": DATASET, "meetings": MEETINGS,
                 "rule": "All utterances of the named meeting in test/validation shard 0; fixed before inference.",
                 "development": {}, "test": {}}
    for split, channel, path in (("development", "ihm", args.ihm_dev), ("test", "ihm", args.ihm_test),
                                 ("test", "sdm", args.sdm_test)):
        selection[split][channel] = [{"id": row["audio_id"], "speaker": row["speaker_id"], "text": row["text"],
                                      "sha256": hashlib.sha256(row["audio"]["bytes"]).hexdigest()}
                                     for row in rows(path, MEETINGS[split])]
    SELECTION.write_text(json.dumps(selection, ensure_ascii=False, indent=1) + "\n", encoding="utf-8")
    print({split: {channel: len(items) for channel, items in selection[split].items()} for split in ("development", "test")})


def without_fillers(text):
    return " ".join(word for word in text.lower().split() if word not in FILLERS)


def infer(args):
    import numpy as np
    import onnxruntime as ort
    import pyarrow.parquet as pq
    import soundfile

    items = json.loads(SELECTION.read_text(encoding="utf-8"))[args.split][args.channel]
    wanted = {item["id"] for item in items}
    audio = {row["audio_id"]: row["audio"]["bytes"]
             for row in pq.read_table(args.shard, columns=["audio_id", "audio"]).to_pylist() if row["audio_id"] in wanted}
    tokens = load_tokens(args.vocab)
    started = time.perf_counter()
    session = ReferenceSession(args.model, args.threads, ort, np)
    load_seconds = time.perf_counter() - started
    if args.asr_gain == "peak":
        session = WindowGainSession(session, np)
    distance = distance_function()
    totals, plain, seconds, asr_seconds, empty, records = {}, {}, 0.0, 0.0, 0, []
    for item in items:
        payload = audio[item["id"]]
        if hashlib.sha256(payload).hexdigest() != item["sha256"]:
            raise ValueError("AMI payload failed verification")
        samples, rate = soundfile.read(io.BytesIO(payload), dtype="float32")
        if rate != 16000 or samples.ndim != 1:
            raise ValueError("Unexpected AMI audio format")
        pcm = np.clip(np.rint(samples * 32768.0), -32768, 32767).astype("<i2").astype(np.float32) / 32768.0
        started = time.perf_counter()
        with open(os.devnull, "w") as sink, contextlib.redirect_stdout(sink):
            words, _ = recognize(session, pcm, tokens, item["id"], np)
        asr_seconds += time.perf_counter() - started
        seconds += len(pcm) / 16000
        hypothesis = " ".join(word["text"] for word in words)
        empty += int(not hypothesis.strip())
        add_counts(totals, text_counts(item["text"], hypothesis, distance))
        add_counts(plain, text_counts(without_fillers(item["text"]), without_fillers(hypothesis), distance))
        records.append({"id": item["id"], "hypothesis": hypothesis})
    summary = {"split": args.split, "meeting": MEETINGS[args.split], "channel": args.channel,
               "model": args.model.name, "model_sha256": hashlib.sha256(args.model.read_bytes()).hexdigest(),
               "asr_gain": args.asr_gain, "threads": args.threads, "utterances": len(items),
               "audio_seconds": round(seconds, 1), "words": totals["words"], "word_errors": totals["word_errors"],
               "wer": round(totals["word_errors"] / totals["words"], 4),
               "cer": round(totals["char_errors"] / totals["chars"], 4),
               "wer_without_fillers": round(plain["word_errors"] / plain["words"], 4),
               "empty_hypotheses": empty, "rtf": round(asr_seconds / seconds, 4), "load_seconds": round(load_seconds, 1)}
    if args.output:
        args.output.write_text(json.dumps({"summary": summary, "utterances": records}, ensure_ascii=False, indent=1),
                               encoding="utf-8")
    print(json.dumps(summary))


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    commands = parser.add_subparsers(dest="command", required=True)
    cmd = commands.add_parser("freeze")
    for name in ("--ihm-test", "--sdm-test", "--ihm-dev"):
        cmd.add_argument(name, type=Path, required=True)
    cmd.set_defaults(run=freeze)
    cmd = commands.add_parser("infer")
    cmd.add_argument("--split", choices=sorted(MEETINGS), required=True)
    cmd.add_argument("--channel", choices=["ihm", "sdm"], required=True)
    cmd.add_argument("--shard", type=Path, required=True)
    cmd.add_argument("--model", type=Path, required=True)
    cmd.add_argument("--vocab", type=Path, required=True)
    cmd.add_argument("--threads", type=int, default=2, choices=[1, 2, 3, 4])
    cmd.add_argument("--asr-gain", default="none", choices=["none", "peak"])
    cmd.add_argument("--output", type=Path)
    cmd.set_defaults(run=infer)
    args = parser.parse_args()
    args.run(args)


if __name__ == "__main__":
    main()
