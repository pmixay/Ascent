#!/usr/bin/env python3
"""Local ASR, timestamp and speed study on public, hash-pinned Dialogs recordings.

Runs on a development machine only (never in CI, never on application audio).
It reuses the app's host mirrors for chunking, CTC word timing and word
ownership, but runs the *public reference export* of the same upstream model
family (see ``reference_asr.py``), not the deployed model pack. Checkpoints stay
outside Git; only aggregate measurements belong in documentation.

Subcommands: freeze, prepare, infer, score, analyze, compare. See
``docs/asr-baseline.md`` for the protocol and measured results.
"""
from __future__ import annotations

import argparse
from concurrent.futures import ThreadPoolExecutor, as_completed
import csv
import hashlib
import json
from pathlib import Path
import re
import time
import urllib.request

from asr_metrics import analyze_errors, compare_runs, distance_function, score_blocks
from evaluate_dialogs import FIXTURES, MANIFEST, RATE, assemble, blocks, checkpoint_digest, fetch, recognize, write_json
from reference_asr import (REFERENCE, ReferenceSession, environment_fingerprint, load_tokens, reference_url,
                           verify_reference)

STUDY = FIXTURES / "study_selection.json"
TOOLS = Path(__file__).resolve().parent
VALIDATION_RULE = ("For each speaker pair (dima, sveta), take the lowest-numbered recording that the earlier frozen "
                   "two-hour selection never referenced, and keep every available payload in numeric order. "
                   "The rule and selection were fixed before any inference on these recordings.")
PAIRS = ("dima", "sveta")
USER_AGENT = {"User-Agent": "Ascent-public-evaluation/0.1"}


def load_study():
    return json.loads(STUDY.read_text(encoding="utf-8"))


def role_manifest(study, role):
    """Shape a role's recordings like the two-hour manifest so the app-mirroring helpers apply."""
    if role not in study["roles"]:
        raise ValueError(f"Unknown role: {role}")
    return {"selection": study["selection"], "recordings": study["roles"][role]["recordings"]}


def validate_study(study, earlier):
    """Structural and disjointness checks shared by ``freeze`` and unit tests."""
    if study.get("schema_version") != 1:
        raise ValueError("Unsupported study selection schema")
    ids, paths, hashes = set(), set(), set()
    earlier_ids = {recording["id"] for recording in earlier["recordings"]}
    earlier_hashes = {item["sha256"] for recording in earlier["recordings"] for item in recording["files"]}
    roles = study["roles"]
    if set(roles) != {"development", "validation"}:
        raise ValueError("Study needs exactly a development and a validation role")
    for role, record in roles.items():
        for recording in record["recordings"]:
            if not re.fullmatch(r"masha_(dima|sveta)_part[0-9]+", recording["id"]) or recording["id"] in ids:
                raise ValueError("Unexpected or repeated recording ID")
            ids.add(recording["id"])
            if role == "validation" and (recording["id"] in earlier_ids):
                raise ValueError("Validation must not reuse an earlier frozen recording")
            previous = -1
            for item in recording["files"]:
                match = re.fullmatch(re.escape("wavs/" + recording["id"] + "_") + r"([0-9]+)\.wav", item["path"])
                if (not match or int(match[1]) <= previous or item["path"] in paths or item["sha256"] in hashes
                        or not re.fullmatch(r"[0-9a-f]{64}", item["sha256"]) or not 44 < item["bytes"] <= 50_000_000
                        or item["speaker"] not in {"M", "S", "D"} or not 0 < item["duration_seconds"] < 1800):
                    raise ValueError("Study files must be ordered, unique, hashed and labelled")
                if role == "validation" and item["sha256"] in earlier_hashes:
                    raise ValueError("Validation payload overlaps the earlier frozen selection")
                previous = int(match[1])
                paths.add(item["path"])
                hashes.add(item["sha256"])
    if [r["id"] for r in roles["development"]["recordings"]] != ["masha_dima_part1"]:
        raise ValueError("Development must remain the frozen masha_dima_part1 recording")


def tree_listing(repository, revision):
    """Sizes and LFS SHA-256 values of every published wav, from the pinned revision."""
    url = f"https://huggingface.co/api/datasets/{repository}/tree/{revision}/wavs?limit=1000"
    files = {}
    while url:
        with urllib.request.urlopen(urllib.request.Request(url, headers=USER_AGENT), timeout=60) as response:
            for entry in json.load(response):
                if entry["type"] == "file":
                    files[entry["path"]] = (entry["size"], (entry.get("lfs") or {}).get("oid"))
            link = response.headers.get("Link", "")
        match = re.search(r'<([^>]+)>;\s*rel="next"', link)
        url = match[1] if match else None
    return files


def metadata_rows(cache, dataset):
    record = dataset["metadata"]
    base = f"https://huggingface.co/datasets/{dataset['repository']}/resolve/{dataset['revision']}/"
    path = cache / dataset["revision"] / record["path"]
    fetch(base + record["path"], path, record["bytes"], record["sha256"])
    with path.open(encoding="utf-8", newline="") as source:
        return {row["audio_path"]: row for row in csv.DictReader(source, delimiter="|")}


def recording_files(recording_id, rows, tree):
    prefix = f"wavs/{recording_id}_"
    selected, omitted = [], []
    for path in sorted((p for p in rows if p.startswith(prefix)), key=lambda p: int(p[len(prefix):-4])):
        if path not in tree:
            omitted.append(path)
            continue
        size, digest = tree[path]
        if not digest:
            raise ValueError(f"Published payload lacks an LFS hash: {path}")
        selected.append({"path": path, "bytes": size, "sha256": digest, "speaker": rows[path]["speaker_id"],
                         "duration_seconds": float(rows[path]["duration"])})
    return selected, omitted


def freeze(args):
    earlier = json.loads(MANIFEST.read_text())
    dataset = earlier["dataset"]
    rows = metadata_rows(args.cache, dataset)
    tree = tree_listing(dataset["repository"], dataset["revision"])
    development = next(r for r in earlier["recordings"] if r["id"] == "masha_dima_part1")
    for item in development["files"]:
        if tree.get(item["path"]) != (item["bytes"], item["sha256"]):
            raise ValueError("The published tree disagrees with the earlier frozen development hashes")
    used = {recording["id"] for recording in earlier["recordings"]}
    available = {pair: sorted({int(m[1]) for p in rows if (m := re.match(rf"wavs/masha_{pair}_part([0-9]+)_", p))})
                 for pair in PAIRS}
    chosen = [f"masha_{pair}_part{min(n for n in available[pair] if f'masha_{pair}_part{n}' not in used)}"
              for pair in PAIRS]
    recordings, omitted = [], {}
    for recording_id in chosen:
        files, missing = recording_files(recording_id, rows, tree)
        recordings.append({"id": recording_id, "files": files})
        omitted[recording_id] = missing
    seconds = {role: round(sum(item["duration_seconds"] for r in recs for item in r["files"]), 3)
               for role, recs in (("development", [development]), ("validation", recordings))}
    study = {"schema_version": 1,
             "dataset": {key: dataset[key] for key in ("repository", "revision", "metadata", "license_source")},
             "reference_model": REFERENCE,
             "selection": {"target_block_ms": earlier["selection"]["target_block_ms"],
                           "join_silence_ms": earlier["selection"]["join_silence_ms"],
                           "validation_rule": VALIDATION_RULE, "source_seconds": seconds,
                           "validation_omitted_unavailable_paths": omitted},
             "roles": {"development": {"recordings": [development]}, "validation": {"recordings": recordings}}}
    validate_study(study, earlier)
    STUDY.write_text(json.dumps(study, ensure_ascii=False, separators=(",", ":")) + "\n", encoding="utf-8")
    print(f"Frozen {STUDY.name}: development {seconds['development']} s, validation {seconds['validation']} s "
          f"from {chosen}; omitted unavailable payloads: { {k: len(v) for k, v in omitted.items()} }")


def prepare(args):
    study = load_study()
    dataset = study["dataset"]
    rows = metadata_rows(args.cache, dataset)
    base = f"https://huggingface.co/datasets/{dataset['repository']}/resolve/{dataset['revision']}/"
    cache = args.cache / dataset["revision"]
    model_dir = args.cache / "reference-model" / REFERENCE["revision"]
    for name, record in REFERENCE["files"].items():
        fetch(reference_url(name), model_dir / name, record["bytes"], record["sha256"])
        print(f"Reference model file verified: {name}", flush=True)
    items = [item for role in args.roles for recording in study["roles"][role]["recordings"] for item in recording["files"]]
    for item in items:
        if not rows[item["path"]]["text"].strip():
            raise ValueError("Empty reference transcript")
    with ThreadPoolExecutor(max_workers=args.download_workers) as pool:
        pending = [pool.submit(fetch, base + item["path"], cache / item["path"], item["bytes"], item["sha256"])
                   for item in items]
        for count, finished in enumerate(as_completed(pending), 1):
            finished.result()
            if count % 100 == 0 or count == len(items):
                print(f"Public recordings verified: {count}/{len(items)}", flush=True)


def energy_bounds(clip, np, margins=(25, 30, 35)):
    """Speech onset/offset proxy: first/last 10 ms frame within ``margin`` dB of the clip's loud level."""
    frame = RATE // 100
    count = len(clip) // frame
    if count < 5:
        return {}
    energy = np.mean(np.square(clip[:count * frame].astype(np.float64).reshape(count, frame)), axis=1)
    level = 10 * np.log10(energy + 1e-12)
    floor, peak = float(np.percentile(level, 5)), float(np.percentile(level, 95))
    if peak - floor < 12.0:
        return {}
    bounds = {}
    for margin in margins:
        active = np.nonzero(level > max(peak - margin, floor + 6.0))[0]
        if active.size:
            bounds[str(margin)] = [int(active[0]) * 10, int(active[-1] + 1) * 10]
    return bounds


PREPROCESSORS = {"none": lambda samples, np: samples}


class TimedSession:
    """Adds up model-side time (features plus encoder) around the session's ``run``."""

    def __init__(self, inner):
        self.inner, self.seconds = inner, 0.0

    def run(self, outputs, feeds):
        started = time.perf_counter()
        try:
            return self.inner.run(outputs, feeds)
        finally:
            self.seconds += time.perf_counter() - started


def code_fingerprint(study):
    digest = hashlib.sha256(json.dumps(study, sort_keys=True).encode())
    for name in ("asr_study.py", "asr_metrics.py", "reference_asr.py", "evaluate_dialogs.py", "evaluate_speech.py",
                 "generate_speaker_fixtures.py"):
        digest.update((TOOLS / name).read_bytes())
    return digest.hexdigest()


def infer(args):
    import numpy as np
    import onnxruntime as ort
    import soundfile
    from scipy.signal import resample_poly
    from generate_speaker_fixtures import variants

    study = load_study()
    dataset = study["dataset"]
    cache = args.cache / dataset["revision"]
    model_dir = args.cache / "reference-model" / REFERENCE["revision"]
    verify_reference(model_dir)
    with (cache / "metadata.csv").open(encoding="utf-8", newline="") as source:
        metadata = {row["audio_path"]: row for row in csv.DictReader(source, delimiter="|")}
    tokens = load_tokens(model_dir / "multilingual_vocab.txt")
    inner = ReferenceSession(model_dir / "multilingual_ctc.onnx", args.threads, ort, np)
    session = TimedSession(inner)
    environment = environment_fingerprint(ort, np, args.threads)
    fingerprint = code_fingerprint(study)
    manifest = role_manifest(study, args.role)
    args.output.mkdir(parents=True, exist_ok=True)
    preprocess = PREPROCESSORS[args.preprocess]
    done = 0
    for block_id, items in blocks(manifest):
        if args.max_blocks and done >= args.max_blocks:
            break
        done += 1
        path = args.output / f"{block_id}.json"
        if path.is_file() and not args.force:
            try:
                existing = json.loads(path.read_text())
                if existing.get("checkpoint_sha256") == checkpoint_digest(existing) and existing.get("code_fingerprint") == fingerprint \
                        and existing.get("threads") == args.threads and existing.get("condition") == args.condition \
                        and existing.get("preprocess") == args.preprocess:
                    continue
            except (ValueError, KeyError):
                pass
        pcm, references = assemble(items, metadata, cache, study["selection"]["join_silence_ms"], np, soundfile, resample_poly)
        clean = pcm.astype(np.float32) / 32768.0
        for reference in references:
            reference["energy_bounds"] = energy_bounds(clean[reference["start_ms"] * 16:reference["end_ms"] * 16], np)
        audio = clean
        if args.condition != "clean":
            audio = next(item for name, item in variants(clean, block_id, np) if name == args.condition)
            audio = np.clip(np.rint(audio * 32768.0), -32768, 32767).astype("<i2").astype(np.float32) / 32768.0
        audio = preprocess(audio, np).astype(np.float32)
        session.seconds = 0.0
        started = time.perf_counter()
        words, windows = recognize(session, audio, tokens, block_id, np)
        elapsed = time.perf_counter() - started
        record = {"schema_version": 1, "kind": "asr-study-block", "id": block_id, "role": args.role,
                  "condition": args.condition, "preprocess": args.preprocess, "threads": args.threads,
                  "reference_model": {"repository": REFERENCE["repository"], "revision": REFERENCE["revision"],
                                      "onnx_sha256": REFERENCE["files"]["multilingual_ctc.onnx"]["sha256"]},
                  "environment": environment, "code_fingerprint": fingerprint,
                  "audio_sha256": hashlib.sha256(pcm.tobytes()).hexdigest(), "duration_ms": len(pcm) // 16,
                  "asr_seconds": round(elapsed, 4), "model_seconds": round(session.seconds, 4),
                  "references": references, "words": words, "asr_windows": windows}
        record["checkpoint_sha256"] = checkpoint_digest(record)
        write_json(path, record)
        print(f"{block_id}: audio {record['duration_ms'] / 1000:.1f} s, ASR {elapsed:.1f} s, "
              f"RTF {elapsed / (record['duration_ms'] / 1000):.3f}", flush=True)


def load_blocks(directory):
    result = []
    for path in sorted(Path(directory).glob("*.json")):
        record = json.loads(path.read_text())
        if record.get("kind") != "asr-study-block":
            continue
        if record.get("checkpoint_sha256") != checkpoint_digest(record):
            raise ValueError(f"Checkpoint integrity mismatch: {path.name}")
        result.append(record)
    if not result:
        raise ValueError("No study checkpoints found")
    return result


def print_summary(summary):
    block, clip = summary["block_level"], summary["clip_level"]
    print(f"blocks={summary['blocks']} clips={summary['clips']} audio={summary['audio_seconds']} s")
    print(f"WER block-level {block['word_errors']}/{block['words']} = {block['wer'] * 100:.4f}%, "
          f"CER {block['char_errors']}/{block['chars']} = {block['cer'] * 100:.4f}%")
    print(f"WER clip-level  {clip['word_errors']}/{clip['words']} = {clip['wer'] * 100:.4f}%, "
          f"CER {clip['cer'] * 100:.4f}%, clips without words={clip['clips_without_words']}")
    for kind, mapping in summary["groups"].items():
        print(f"by {kind}: " + "; ".join(f"{key}: {v['word_errors']}/{v['words']} ({v['wer'] * 100:.2f}%)"
                                         for key, v in mapping.items() if v["words"]))
    for name in ("timing_vs_clip_bounds", "timing_vs_energy_proxy"):
        print(name, json.dumps(summary[name], ensure_ascii=False))
    print("speed", json.dumps(summary["speed"]))


def score(args):
    summary = score_blocks(load_blocks(args.checkpoints), distance_function(), args.energy_threshold)
    print_summary(summary)
    if args.output:
        args.output.write_text(json.dumps(summary, ensure_ascii=False, indent=1) + "\n", encoding="utf-8")


def analyze(args):
    print(json.dumps(analyze_errors(load_blocks(args.checkpoints), distance_function()), ensure_ascii=False, indent=1))


def compare(args):
    print(json.dumps(compare_runs(load_blocks(args.first), load_blocks(args.second), distance_function()), indent=1))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    cmd = commands.add_parser("freeze", help="Write fixtures/dialogs/study_selection.json from pinned public metadata.")
    cmd.add_argument("--cache", type=Path, required=True)
    cmd.set_defaults(run=freeze)
    cmd = commands.add_parser("prepare", help="Download and hash-verify the reference model and selected recordings.")
    cmd.add_argument("--cache", type=Path, required=True)
    cmd.add_argument("--roles", nargs="+", choices=["development", "validation"], default=["development"])
    cmd.add_argument("--download-workers", type=int, default=8)
    cmd.set_defaults(run=prepare)
    cmd = commands.add_parser("infer", help="Run ASR for one role and write checkpoints.")
    cmd.add_argument("--cache", type=Path, required=True)
    cmd.add_argument("--output", type=Path, required=True)
    cmd.add_argument("--role", choices=["development", "validation"], required=True)
    cmd.add_argument("--threads", type=int, default=2, choices=[1, 2, 3, 4])
    cmd.add_argument("--condition", default="clean", choices=["clean", "gain035", "noise20db", "channel"])
    cmd.add_argument("--preprocess", default="none", choices=sorted(PREPROCESSORS))
    cmd.add_argument("--max-blocks", type=int, default=0)
    cmd.add_argument("--force", action="store_true")
    cmd.set_defaults(run=infer)
    cmd = commands.add_parser("score", help="WER/CER, timing and speed from checkpoints (aggregates only).")
    cmd.add_argument("checkpoints", type=Path)
    cmd.add_argument("--energy-threshold", default="30", choices=["25", "30", "35"])
    cmd.add_argument("--output", type=Path)
    cmd.set_defaults(run=score)
    cmd = commands.add_parser("analyze", help="Error taxonomy from checkpoints (counts and rates only).")
    cmd.add_argument("checkpoints", type=Path)
    cmd.set_defaults(run=analyze)
    cmd = commands.add_parser("compare", help="Token-level reproducibility comparison of two checkpoint directories.")
    cmd.add_argument("first", type=Path)
    cmd.add_argument("second", type=Path)
    cmd.set_defaults(run=compare)
    args = parser.parse_args()
    args.run(args)


if __name__ == "__main__":
    main()
