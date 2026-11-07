#!/usr/bin/env python3
"""Second-recognizer candidates through sherpa-onnx 2.0.0 (the runtime the app already ships).

Development machine only, never CI or application audio. Two measurements, both with the
shared text normalization of ``asr_metrics.text_counts``; digits in hypotheses are spelled out
because the references contain none:

    sherpa_asr_compare.py ami MODELS_DIR MODEL_KEY CHANNEL SHARD OUT.json [THREADS]
        frozen AMI EN2002a utterances from fixtures/ami_english.json (see ami_english_study.py)
    sherpa_asr_compare.py dialogs MODELS_DIR MODEL_KEY ROLE CACHE OUT.json [THREADS]
        Russian Dialogs clips of a frozen study role, each clip recognized on its own
    sherpa_asr_compare.py windowed MODELS_DIR ROLE CACHE CHECKPOINT_DIR [THREADS]
        the app's multilingual path: Parakeet v3 over the assembled Dialogs blocks with the
        AsrChunking windows, ParakeetWords and midpoint ownership; score the checkpoints with
        ``asr_study.py score CHECKPOINT_DIR``

MODELS_DIR holds the unpacked csukuangfj/sherpa-onnx-* model directories named in
``recognizer``; their revisions are recorded in docs/hackathon/model-report.md.
Prints aggregates only.
"""
import csv
import hashlib
import io
import json
import re
import sys
import time
from pathlib import Path

from asr_metrics import add_counts, distance_function, text_counts

TOOLS = Path(__file__).resolve().parent
FIXTURE = TOOLS / "fixtures" / "ami_english.json"
STUDY = TOOLS / "fixtures" / "dialogs" / "study_selection.json"
ROOT = None  # set from MODELS_DIR
FILLERS = {"um", "uh", "mm", "hmm", "mm-hmm", "uh-huh", "huh", "hm"}


def recognizer(key, threads):
    import sherpa_onnx as so
    if key.startswith("whisper-"):
        name = key[len("whisper-"):]
        d = ROOT / f"sherpa-onnx-whisper-{name}"
        return so.OfflineRecognizer.from_whisper(encoder=str(d / f"{name}-encoder.int8.onnx"), decoder=str(d / f"{name}-decoder.int8.onnx"),
                                                 tokens=str(d / f"{name}-tokens.txt"), language="en", task="transcribe",
                                                 num_threads=threads)
    if key == "sensevoice":
        d = ROOT / "sherpa-onnx-sense-voice-zh-en-ja-ko-yue-int8-2025-09-09"
        return so.OfflineRecognizer.from_sense_voice(model=str(d / "model.int8.onnx"), tokens=str(d / "tokens.txt"),
                                                     language="en", use_itn=False, num_threads=threads)
    if key in ("parakeet", "parakeet-v3"):
        d = ROOT / ("sherpa-onnx-nemo-parakeet-tdt-0.6b-v3-int8" if key == "parakeet-v3" else "sherpa-onnx-nemo-parakeet-tdt-0.6b-v2-int8")
        return so.OfflineRecognizer.from_transducer(encoder=str(d / "encoder.int8.onnx"), decoder=str(d / "decoder.int8.onnx"),
                                                    joiner=str(d / "joiner.int8.onnx"), tokens=str(d / "tokens.txt"),
                                                    model_type="nemo_transducer", num_threads=threads)
    if key == "moonshine":
        d = ROOT / "sherpa-onnx-moonshine-base-en-int8"
        return so.OfflineRecognizer.from_moonshine(preprocessor=str(d / "preprocess.onnx"), encoder=str(d / "encode.int8.onnx"),
                                                   uncached_decoder=str(d / "uncached_decode.int8.onnx"),
                                                   cached_decoder=str(d / "cached_decode.int8.onnx"), tokens=str(d / "tokens.txt"),
                                                   num_threads=threads)
    raise ValueError(key)


def spell_digits(text):
    from num2words import num2words
    return re.sub(r"\d+", lambda m: " " + num2words(int(m.group())) + " ", text)


def strip_fillers(text):
    return " ".join(w for w in text.lower().split() if w not in FILLERS)


def ami(key, channel, shard, out, threads):
    import numpy as np, pyarrow.parquet as pq, soundfile
    items = json.loads(FIXTURE.read_text())["test"][channel]
    wanted = {i["id"] for i in items}
    audio = {r["audio_id"]: r["audio"]["bytes"] for r in pq.read_table(shard, columns=["audio_id", "audio"]).to_pylist() if r["audio_id"] in wanted}
    t = time.perf_counter(); rec = recognizer(key, threads); load = time.perf_counter() - t
    distance = distance_function()
    totals, plain, seconds, asr, empty = {}, {}, 0.0, 0.0, 0
    for item in items:
        payload = audio[item["id"]]
        if hashlib.sha256(payload).hexdigest() != item["sha256"]:
            raise ValueError("AMI payload failed verification")
        samples, rate = soundfile.read(io.BytesIO(payload), dtype="float32")
        pcm = np.clip(np.rint(samples * 32768.0), -32768, 32767).astype(np.float32) / 32768.0
        t = time.perf_counter()
        stream = rec.create_stream(); stream.accept_waveform(16000, pcm); rec.decode_stream(stream)
        asr += time.perf_counter() - t
        seconds += len(pcm) / 16000
        hyp = spell_digits(stream.result.text)
        empty += int(not re.search(r"\w", hyp))
        add_counts(totals, text_counts(item["text"], hyp, distance))
        add_counts(plain, text_counts(strip_fillers(item["text"]), strip_fillers(hyp), distance))
    summary = {"model": key, "channel": channel, "threads": threads, "utterances": len(items), "audio_seconds": round(seconds, 1),
               "words": totals["words"], "word_errors": totals["word_errors"], "wer": round(totals["word_errors"] / totals["words"], 4),
               "cer": round(totals["char_errors"] / totals["chars"], 4),
               "wer_without_fillers": round(plain["word_errors"] / plain["words"], 4), "empty": empty,
               "rtf": round(asr / seconds, 4), "load_seconds": round(load, 1)}
    Path(out).write_text(json.dumps(summary) + "\n")
    print(json.dumps(summary), flush=True)


def dialogs(key, role, cache, out, threads):
    import numpy as np, soundfile
    from scipy.signal import resample_poly
    cache = Path(cache)
    study = json.loads(STUDY.read_text(encoding="utf-8"))
    files = [f["path"] for recording in study["roles"][role]["recordings"] for f in recording["files"]]
    with (cache / "metadata.csv").open(encoding="utf-8", newline="") as source:
        meta = {row["audio_path"]: row for row in csv.DictReader(source, delimiter="|")}
    rec = recognizer(key, threads)
    distance = distance_function(); totals = {}; seconds = asr = 0.0
    for path in files:
        samples, rate = soundfile.read(cache / path, dtype="float32")
        if samples.ndim > 1:
            samples = samples.mean(axis=1)
        if rate != 16000:
            samples = resample_poly(samples, 16000, rate).astype(np.float32)
        t = time.perf_counter()
        stream = rec.create_stream(); stream.accept_waveform(16000, samples); rec.decode_stream(stream)
        asr += time.perf_counter() - t
        seconds += len(samples) / 16000
        add_counts(totals, text_counts(meta[path]["text"], spell_digits(stream.result.text), distance))
    summary = {"model": key, "role": role, "clips": len(files), "audio_seconds": round(seconds, 1), "words": totals["words"],
               "word_errors": totals["word_errors"], "wer": round(totals["word_errors"] / totals["words"], 4),
               "cer": round(totals["char_errors"] / totals["chars"], 4), "rtf": round(asr / seconds, 4)}
    Path(out).write_text(json.dumps(summary) + "\n")
    print(json.dumps(summary), flush=True)


def parakeet_words(tokens, starts, durations, offset_ms, limit_ms):
    """Mirror of ParakeetWords.words in MultilingualRecognizer.kt."""
    def normalize(raw):
        kept = "".join(c for c in raw.replace("\u2581", " ").lower() if c.isalnum() or c in "'\u2019-").replace("\u2019", "'")
        return kept.strip("'-")
    words, text, start, end = [], "", -1, -1

    def flush():
        word = normalize(text)
        if word and 0 <= start < limit_ms:
            words.append({"text": word, "start_ms": start, "end_ms": min(max(end, start + 1), limit_ms)})

    for index, token in enumerate(tokens):
        if token.startswith((" ", "\u2581")):
            flush(); text, start, end = "", -1, -1
        text += token
        if not normalize(token):
            continue
        token_start = offset_ms + round(starts[index] * 1000)
        if len(durations):
            token_end = offset_ms + round((starts[index] + durations[index]) * 1000)
        elif index + 1 < len(starts):
            token_end = offset_ms + round(starts[index + 1] * 1000)
        else:
            token_end = token_start + 80
        if start < 0:
            start = token_start
        end = max(end, token_end, token_start + 1)
    flush()
    return words


def windowed(role, cache, output, threads):
    """Run asr_study's infer with the multilingual path in place of GigaAM recognition."""
    import asr_study
    from evaluate_speech import chunk_windows
    rec = recognizer("parakeet-v3", threads)

    class NoSession:  # asr_study opens a GigaAM session; this path never calls it
        def __init__(self, *args, **kwargs):
            pass

    def recognize_windowed(_session, samples, _tokens, _block_id, np):
        result, duration_ms = [], len(samples) // 16
        for core_start, core_end, start, end in chunk_windows(samples, True, np):
            stream = rec.create_stream(); stream.accept_waveform(16000, samples[start:end]); rec.decode_stream(stream)
            r = stream.result
            for word in parakeet_words(list(r.tokens), list(r.timestamps), list(getattr(r, "durations", [])), start // 16, duration_ms):
                if core_start // 16 <= (word["start_ms"] + word["end_ms"]) // 2 < core_end // 16:
                    result.append(word)
        return sorted(result, key=lambda w: w["start_ms"]), []

    asr_study.ReferenceSession = NoSession
    asr_study.recognize = recognize_windowed
    sys.argv = ["asr_study.py", "infer", "--cache", str(cache), "--output", str(output), "--role", role,
                "--threads", str(threads)]
    asr_study.main()


def main():
    global ROOT
    command, models = sys.argv[1], sys.argv[2]
    ROOT = Path(models)
    args = sys.argv[3:]
    threads = int(args[4]) if len(args) > 4 else 2
    if command == "ami":
        ami(args[0], args[1], args[2], args[3], threads)
    elif command == "dialogs":
        dialogs(args[0], args[1], args[2], args[3], threads)
    elif command == "windowed":
        windowed(args[0], args[1], args[2], int(args[3]) if len(args) > 3 else 2)
    else:
        raise SystemExit(__doc__)


if __name__ == "__main__":
    main()
