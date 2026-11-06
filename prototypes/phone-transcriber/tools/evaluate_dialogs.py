#!/usr/bin/env python3
"""CI-only evaluation inputs from >2 h of checksum-pinned Russian acted dialogue.

This command never accepts application audio. It downloads the frozen public
selection and deployed model revision, runs the app's ASR/context mirrors, and
exports private CI resources for the actual Kotlin attribution implementation.
No corpus, weights, transcripts, embeddings or reports are APK artifacts.
"""
from __future__ import annotations

import argparse
from concurrent.futures import ThreadPoolExecutor, as_completed
import csv
import gzip
import hashlib
import io
import json
import math
from pathlib import Path
import re
import time
import urllib.request

from evaluate_speech import chunk_windows, infer_ids, normalize_text, timed_words
from generate_speaker_fixtures import probe_contexts
from model_pack import MODEL_ID, sha256_file, validate_manifest

RATE = 16000
FIXTURES = Path(__file__).resolve().parent / "fixtures" / "dialogs"
MANIFEST = FIXTURES / "manifest.json"
MAX_SOURCE_BYTES = 50_000_000


def write_json(path, value):
    temporary = path.with_suffix(path.suffix + ".tmp")
    temporary.write_text(json.dumps(value, ensure_ascii=False, separators=(",", ":"), allow_nan=False) + "\n",
                         encoding="utf-8")
    temporary.replace(path)


def verified(path, size, digest):
    return path.is_file() and path.stat().st_size == size and sha256_file(path) == digest


def fetch(url, path, size, digest):
    """Bounded, hash-checked public download; completed cache entries are rechecked."""
    if verified(path, size, digest):
        return path
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_suffix(path.suffix + ".part")
    for attempt in range(4):
        try:
            request = urllib.request.Request(url, headers={"User-Agent": "Ascent-public-evaluation/0.1"})
            with urllib.request.urlopen(request, timeout=30) as response, temporary.open("wb") as output:
                count = 0
                while block := response.read(1024 * 1024):
                    count += len(block)
                    if count > size:
                        raise ValueError("Downloaded file exceeds pinned byte count")
                    output.write(block)
            if not verified(temporary, size, digest):
                raise ValueError("Downloaded public evaluation file failed integrity verification")
            temporary.replace(path)
            return path
        except Exception:
            temporary.unlink(missing_ok=True)
            if attempt == 3:
                raise
            print(f"Retrying a pinned public evaluation download ({attempt + 1}/3)", flush=True)
            time.sleep(2 ** attempt)
    raise AssertionError("Unreachable retry state")


def validate_selection(manifest):
    if manifest.get("schema_version") != 1:
        raise ValueError("Unsupported dialogue selection schema")
    dataset = manifest["dataset"]
    if dataset["repository"] != "langswap/dialogs-ru-emotional-conversations" or not re.fullmatch(
            r"[0-9a-f]{40}", dataset["revision"]):
        raise ValueError("Only the pinned public Dialogs corpus is accepted")
    for key, name in (("metadata", "metadata.csv"), ("license_source", "LICENSE.md")):
        record = dataset[key]
        if (record["path"] != name or not re.fullmatch(r"[0-9a-f]{64}", record["sha256"])
                or not 0 < record["bytes"] <= 20_000_000):
            raise ValueError("Invalid frozen annotation or license record")
    model = manifest["models"]
    if (model["base_url"] != "https://ascent.arbuz.lol/models/" or model["model_id"] != MODEL_ID
            or any(not re.fullmatch(r"[0-9a-f]{64}", model[key])
                   for key in ("manifest_sha256", "download_sha256"))
            or not 0 < model["manifest_bytes"] <= 128 * 1024
            or not 0 < model["download_bytes"] <= 512 * 1024):
        raise ValueError("Invalid frozen production model location")
    paths, hashes = set(), set()
    total = 0.0
    for recording in manifest["recordings"]:
        if not re.fullmatch(r"masha_(dima|sveta)_part[0-9]+", recording["id"]):
            raise ValueError("Unexpected recording ID")
        speakers = set()
        previous = -1
        for item in recording["files"]:
            match = re.fullmatch(re.escape("wavs/" + recording["id"] + "_") + r"([0-9]+)\.wav", item["path"])
            if not match or int(match[1]) <= previous or item["path"] in paths or item["sha256"] in hashes:
                raise ValueError("Selection must contain ordered, unique source payloads")
            previous = int(match[1])
            if not re.fullmatch(r"[0-9a-f]{64}", item["sha256"]) or not 44 < item["bytes"] <= MAX_SOURCE_BYTES:
                raise ValueError("Invalid pinned source integrity record")
            if item["speaker"] not in {"M", "S", "D"} or not 0 < item["duration_seconds"] < 1800:
                raise ValueError("Invalid source speaker or duration")
            paths.add(item["path"])
            hashes.add(item["sha256"])
            speakers.add(item["speaker"])
            total += item["duration_seconds"]
        if len(speakers) < 2:
            raise ValueError("Selected dialogue must have multiple labeled speakers")
    if len(paths) != manifest["selection"]["source_files"] or total < 7200:
        raise ValueError("Evaluation requires at least two hours of unique labeled source audio")
    if abs(total - manifest["selection"]["source_seconds"]) > .001:
        raise ValueError("Source duration accounting mismatch")


def fetch_sources(manifest, cache, workers):
    dataset = manifest["dataset"]
    base = f"https://huggingface.co/datasets/{dataset['repository']}/resolve/{dataset['revision']}/"
    for name in ("metadata", "license_source"):
        item = dataset[name]
        fetch(base + item["path"], cache / item["path"], item["bytes"], item["sha256"])
    with (cache / "metadata.csv").open(encoding="utf-8", newline="") as source:
        metadata = {row["audio_path"]: row for row in csv.DictReader(source, delimiter="|")}
    files = [item for recording in manifest["recordings"] for item in recording["files"]]
    for item in files:
        row = metadata[item["path"]]
        if row["speaker_id"] != item["speaker"] or abs(float(row["duration"]) - item["duration_seconds"]) > .0001:
            raise ValueError("Pinned selection disagrees with source annotations")
        if not normalize_text(row["text"]):
            raise ValueError("Empty reference transcript")
    def download(item):
        return fetch(base + item["path"], cache / item["path"], item["bytes"], item["sha256"])
    with ThreadPoolExecutor(max_workers=workers) as pool:
        pending = [pool.submit(download, item) for item in files]
        for count, finished in enumerate(as_completed(pending), 1):
            finished.result()
            if count % 50 == 0 or count == len(files):
                print(f"Dialogs public source: verified {count}/{len(files)} unique files", flush=True)
    return metadata


def fetch_models(record, cache):
    base = record["base_url"]
    if base != "https://ascent.arbuz.lol/models/":
        raise ValueError("Unexpected production model source")
    directory = f"{record['model_id']}/{record['manifest_sha256']}/"
    downloads = f"{record['model_id']}/downloads/{record['download_sha256']}/"
    fetch(base + directory + "manifest.json", cache / "manifest.json", record["manifest_bytes"], record["manifest_sha256"])
    fetch(base + downloads + "download.json", cache / "download.json", record["download_bytes"], record["download_sha256"])
    manifest = json.loads((cache / "manifest.json").read_text())
    validate_manifest(manifest)
    index = json.loads((cache / "download.json").read_text())
    if index["schema_version"] != 1 or index["manifest_sha256"] != record["manifest_sha256"] or index["chunk_bytes"] != 4194304:
        raise ValueError("Pinned model chunk index disagrees with manifest")
    for name, item in manifest["files"].items():
        target = cache / name
        if verified(target, item["bytes"], item["sha256"]):
            continue
        partial = target.with_suffix(target.suffix + ".partial")
        partial.touch(exist_ok=True)
        offset = 0
        chunks = index["files"][name]
        with partial.open("r+b") as output:
            for number, chunk in enumerate(chunks):
                raw_size = chunk["raw_bytes"]
                if not 0 < raw_size <= 4194304 or not 0 < chunk["bytes"] <= 4259840:
                    raise ValueError("Invalid model chunk size")
                output.seek(offset)
                prefix = output.read(raw_size)
                valid_prefix = len(prefix) == raw_size and hashlib.sha256(prefix).hexdigest() == chunk["raw_sha256"]
                if not valid_prefix:
                    output.truncate(offset)
                    compressed = cache / "chunk.gz"
                    fetch(base + downloads + f"{name}.{number}.gz", compressed, chunk["bytes"], chunk["sha256"])
                    with gzip.open(compressed, "rb") as source:
                        raw = source.read(raw_size + 1)
                    if len(raw) != raw_size or hashlib.sha256(raw).hexdigest() != chunk["raw_sha256"]:
                        raise ValueError("Expanded model chunk failed verification")
                    output.seek(offset)
                    output.write(raw)
                    output.flush()
                    compressed.unlink()
                offset += raw_size
                if number % 20 == 0 or number + 1 == len(chunks):
                    print(f"Production model {name}: verified chunk {number + 1}/{len(chunks)}", flush=True)
            output.truncate(offset)
        if not verified(partial, item["bytes"], item["sha256"]):
            raise ValueError("Completed model failed pinned payload hash")
        partial.replace(target)
    return manifest


def blocks(manifest):
    """Keep original turn order; never split a source utterance across blocks."""
    target = manifest["selection"]["target_block_ms"]
    gap = manifest["selection"]["join_silence_ms"]
    for recording in manifest["recordings"]:
        current, duration, number = [], 0.0, 0
        for item in recording["files"]:
            proposed = duration + item["duration_seconds"] * 1000 + (gap if current else 0)
            if current and proposed > target:
                yield f"{recording['id']}-{number:03d}", current
                number += 1
                current, duration = [], 0.0
            duration += item["duration_seconds"] * 1000 + (gap if current else 0)
            current.append(item)
        if current:
            yield f"{recording['id']}-{number:03d}", current


def assemble(items, metadata, cache, gap_ms, np, soundfile, resample_poly):
    pieces, references = [], []
    position = 0
    for item in items:
        samples, source_rate = soundfile.read(cache / item["path"], dtype="float32", always_2d=True)
        if source_rate not in {16000, 22050, 24000, 32000, 44100, 48000, 96000} or samples.shape[1] not in {1, 2}:
            raise ValueError("Unexpected public source audio format")
        if not np.isfinite(samples).all() or abs(len(samples) / source_rate - item["duration_seconds"]) > .01:
            raise ValueError("Source sample duration does not match frozen metadata")
        mono = samples.mean(axis=1)
        divisor = math.gcd(source_rate, RATE)
        if source_rate != RATE:
            mono = resample_poly(mono, RATE // divisor, source_rate // divisor)
        # Millisecond boundaries make the Kotlin timing assertions exact. At most
        # 15 sub-millisecond resampled tail samples are omitted from each source.
        mono = mono[:len(mono) // 16 * 16]
        pcm = np.clip(np.rint(mono * 32768.0), -32768, 32767).astype("<i2")
        if pieces:
            silence = np.zeros(gap_ms * 16, dtype="<i2")
            pieces.append(silence)
            position += len(silence)
        start_ms = position // 16
        pieces.append(pcm)
        position += len(pcm)
        row = metadata[item["path"]]
        references.append({"speaker": item["speaker"], "start_ms": start_ms, "end_ms": position // 16,
                           "text": row["text"], "source_id": item["path"], "emotion": row["emotion"]})
    return np.concatenate(pieces), references


def counts(reference, hypothesis, distance=None):
    if distance is None:
        from rapidfuzz.distance import Levenshtein
        distance = Levenshtein.distance
    expected, actual = normalize_text(reference), normalize_text(hypothesis)
    if not expected:
        raise ValueError("Empty aggregate reference")
    expected_chars, actual_chars = expected.replace(" ", ""), actual.replace(" ", "")
    return {"word_errors": distance(expected.split(), actual.split()), "words": len(expected.split()),
            "char_errors": distance(expected_chars, actual_chars), "chars": len(expected_chars)}


def recognize(session, samples, tokens, block_id, np):
    result, windows = [], []
    duration_ms = len(samples) // 16
    for number, (core_start, core_end, start, end) in enumerate(chunk_windows(samples, True, np), 1):
        audio = samples[start:end]
        if len(audio) < 4000:
            audio = np.pad(audio, (0, 4000 - len(audio)))
        ids = infer_ids(session, audio, tokens, np)
        decoded = timed_words(ids, tokens, start // 16, len(audio) // 16)
        for word, begin, finish in decoded:
            if begin >= duration_ms:
                continue
            finish = min(finish, duration_ms)
            if core_start // 16 <= (begin + finish) // 2 < core_end // 16:
                result.append({"text": word, "start_ms": begin, "end_ms": finish})
        windows.append({"core_start": core_start, "core_end": core_end, "start": start, "end": end,
                        "ids": [int(value) for value in ids]})
        print(f"Dialogs {block_id}: ASR chunk {number}, {core_end / RATE:.1f}/{len(samples) / RATE:.1f}s", flush=True)
    return sorted(result, key=lambda word: word["start_ms"]), windows


def speaker_contexts(extractor, samples, words, block_id, np):
    contexts = probe_contexts(words, len(samples) // 16)
    result = []
    for index, context in enumerate(contexts):
        stream = extractor.create_stream()
        stream.accept_waveform(sample_rate=RATE, waveform=np.ascontiguousarray(
            samples[context["start_ms"] * 16:context["end_ms"] * 16]))
        stream.input_finished()
        vector = None
        if extractor.is_ready(stream):
            embedding = np.asarray(extractor.compute(stream), dtype=np.float32)
            if embedding.shape != (192,) or not np.isfinite(embedding).all():
                raise ValueError("Invalid speaker embedding on public dialogue")
            vector = embedding.tolist()
        result.append({**context, "embedding": vector})
        if index % 50 == 0 or index + 1 == len(contexts):
            print(f"Dialogs {block_id}: speaker context {index + 1}/{len(contexts)}", flush=True)
    return result


def fingerprint(manifest):
    digest = hashlib.sha256(json.dumps(manifest, sort_keys=True).encode())
    for name in ("evaluate_dialogs.py", "evaluate_speech.py", "generate_speaker_fixtures.py", "model_pack.py"):
        digest.update((Path(__file__).parent / name).read_bytes())
    digest.update((Path(__file__).parent.parent / "tests" / "requirements-dialog-evaluation.txt").read_bytes())
    return digest.hexdigest()


def checkpoint_digest(value):
    content = {key: item for key, item in value.items() if key != "checkpoint_sha256"}
    return hashlib.sha256(json.dumps(content, sort_keys=True, ensure_ascii=False,
                                     separators=(",", ":"), allow_nan=False).encode()).hexdigest()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--cache", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--threads", type=int, default=2)
    parser.add_argument("--download-workers", type=int, default=8)
    parser.add_argument("--prepare-only", action="store_true",
                        help="Verify/cache frozen public inputs and weights before the expensive inference stage.")
    args = parser.parse_args()
    if not 1 <= args.threads <= 4 or not 1 <= args.download_workers <= 16:
        raise ValueError("Unbounded evaluation parallelism")
    manifest = json.loads(MANIFEST.read_text())
    validate_selection(manifest)
    args.output.mkdir(parents=True, exist_ok=True)
    cache = args.cache / manifest["dataset"]["revision"]
    metadata = fetch_sources(manifest, cache, args.download_workers)
    model_dir = args.cache / "models" / manifest["models"]["manifest_sha256"]
    model = fetch_models(manifest["models"], model_dir)
    if args.prepare_only:
        print(f"Prepared and hash-verified {manifest['selection']['source_files']} public payloads and pinned models; "
              "speech inference has not run.", flush=True)
        return
    import numpy as np
    import onnxruntime as ort
    import sherpa_onnx
    import soundfile
    from scipy.signal import resample_poly

    tokens = json.loads((model_dir / "tokens.json").read_text())
    options = ort.SessionOptions()
    options.intra_op_num_threads = args.threads
    options.inter_op_num_threads = 1
    options.execution_mode = ort.ExecutionMode.ORT_SEQUENTIAL
    session = ort.InferenceSession(str(model_dir / "asr.onnx"), sess_options=options, providers=["CPUExecutionProvider"])
    config = sherpa_onnx.SpeakerEmbeddingExtractorConfig(model=str(model_dir / "speaker.onnx"),
                                                       num_threads=args.threads, debug=False, provider="cpu")
    if not config.validate():
        raise ValueError("Invalid production speaker model configuration")
    extractor = sherpa_onnx.SpeakerEmbeddingExtractor(config)
    cache_key = fingerprint(manifest)
    summaries = []
    totals = {"word_errors": 0, "words": 0, "char_errors": 0, "chars": 0}
    unique_sources, source_duration_ms = set(), 0
    for block_id, items in blocks(manifest):
        path = args.output / f"{block_id}.json"
        audio_file = f"{block_id}.wav"
        audio_path = args.output / audio_file
        checkpoint = None
        if path.is_file() and audio_path.is_file():
            try:
                candidate = json.loads(path.read_text())
                if (candidate.get("cache_fingerprint") == cache_key and candidate.get("id") == block_id
                        and candidate.get("checkpoint_sha256") == checkpoint_digest(candidate)
                        and [item["source_id"] for item in candidate["references"]] == [item["path"] for item in items]
                        and candidate.get("audio_sha256") == sha256_file(audio_path)):
                    checkpoint = candidate
            except (ValueError, KeyError, TypeError):
                pass
        if checkpoint is None:
            pcm, references = assemble(items, metadata, cache, manifest["selection"]["join_silence_ms"],
                                       np, soundfile, resample_poly)
            soundfile.write(audio_path, pcm, RATE, subtype="PCM_16")
            samples = pcm.astype(np.float32) / 32768.0
            words, windows = recognize(session, samples, tokens, block_id, np)
            contexts = speaker_contexts(extractor, samples, words, block_id, np)
            metrics = counts(" ".join(item["text"] for item in references), " ".join(word["text"] for word in words))
            checkpoint = {"schema_version": 1, "id": block_id, "cache_fingerprint": cache_key,
                          "duration_ms": len(pcm) // 16,
                          "source_duration_ms": sum(item["end_ms"] - item["start_ms"] for item in references),
                          "audio_file": audio_file, "audio_sha256": sha256_file(audio_path),
                          "references": references, "words": words, "contexts": contexts,
                          "asr_windows": windows, "asr_metrics": metrics}
            checkpoint["checkpoint_sha256"] = checkpoint_digest(checkpoint)
            write_json(path, checkpoint)
        for reference in checkpoint["references"]:
            if reference["source_id"] in unique_sources:
                raise ValueError("A public source utterance was counted twice")
            unique_sources.add(reference["source_id"])
        source_duration_ms += checkpoint["source_duration_ms"]
        metrics = checkpoint["asr_metrics"]
        for key in totals:
            totals[key] += metrics[key]
        print(f"Dialogs ASR {block_id}: source_ms={checkpoint['source_duration_ms']}, "
              f"words={metrics['words']}, word_errors={metrics['word_errors']}, "
              f"WER={metrics['word_errors'] / metrics['words']:.6f}, "
              f"chars={metrics['chars']}, char_errors={metrics['char_errors']}, "
              f"CER={metrics['char_errors'] / metrics['chars']:.6f}", flush=True)
        summaries.append({"id": block_id, "file": path.name})
    if source_duration_ms < 7200000 or len(unique_sources) != manifest["selection"]["source_files"]:
        raise ValueError("Actual unique source audio does not meet the two-hour requirement")
    index = {"schema_version": 1, "source_manifest_sha256": sha256_file(MANIFEST),
             "cache_fingerprint": cache_key, "source_duration_ms": source_duration_ms,
             "source_files": len(unique_sources), "tokens": tokens, "sessions": summaries,
             "asr_metrics": totals, "provenance": {"repository": manifest["dataset"]["repository"],
             "revision": manifest["dataset"]["revision"], "model_manifest_sha256": manifest["models"]["manifest_sha256"],
             "asr_sha256": model["files"]["asr.onnx"]["sha256"],
             "speaker_sha256": model["files"]["speaker.onnx"]["sha256"],
             "scope": "Russian acted dialogue clips, original turn order with synthetic gaps; clip-time attribution, not natural-conversation DER"}}
    write_json(args.output / "evaluation.json", index)
    print(f"Dialogs ASR TOTAL: source_ms={source_duration_ms}, unique_files={len(unique_sources)}, "
          f"blocks={len(summaries)}, words={totals['words']}, word_errors={totals['word_errors']}, "
          f"WER={totals['word_errors'] / totals['words']:.6f}, chars={totals['chars']}, "
          f"char_errors={totals['char_errors']}, CER={totals['char_errors'] / totals['chars']:.6f}", flush=True)
    print("Scope: fixed public evaluation only; no training, private app audio, natural-meeting DER or device-speed claim.", flush=True)


if __name__ == "__main__":
    main()
