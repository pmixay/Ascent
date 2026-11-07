#!/usr/bin/env python3
"""Check completed public inference provenance/integrity without model execution.

The Actions key selects a candidate cache; this check binds its contents to the
current frozen inputs before scoring. It prints no transcript or embeddings.
"""
from __future__ import annotations

import argparse
import json
from pathlib import Path

from evaluate_dialogs import MANIFEST, blocks, checkpoint_digest, fingerprint, validate_selection
from model_pack import load_json, sha256_file, SPEAKER_SHA256
from reference_asr import REFERENCE

ASR_HASHES = {
    "deployed": "9e423f2690bfaf3e0fbb81185a080bfff27c4ec5aacef5e615676fffbe2545ff",
    "public-reference": REFERENCE["files"]["multilingual_ctc.onnx"]["sha256"],
}


def verify(directory: Path) -> dict:
    manifest = load_json(MANIFEST.read_text(encoding="utf-8"))
    validate_selection(manifest)
    index = load_json((directory / "evaluation.json").read_text(encoding="utf-8"))
    if index.get("schema_version") != 1 or index.get("source_manifest_sha256") != sha256_file(MANIFEST):
        raise ValueError("Inference cache source manifest mismatch")
    provenance = index["provenance"]
    source = provenance.get("asr_source", "deployed")
    if source not in ASR_HASHES or provenance.get("asr_sha256") != ASR_HASHES[source]:
        raise ValueError("Inference cache ASR provenance mismatch")
    if provenance.get("speaker_sha256") != SPEAKER_SHA256:
        raise ValueError("Inference cache speaker provenance mismatch")
    if (provenance.get("repository") != manifest["dataset"]["repository"]
            or provenance.get("revision") != manifest["dataset"]["revision"]):
        raise ValueError("Inference cache corpus revision mismatch")
    model_manifest = "public-reference" if source == "public-reference" else manifest["models"]["manifest_sha256"]
    if provenance.get("model_manifest_sha256") != model_manifest:
        raise ValueError("Inference cache model manifest mismatch")
    if source == "public-reference" and provenance.get("asr_reference") != {
            "repository": REFERENCE["repository"], "revision": REFERENCE["revision"], "variant": "float32"}:
        raise ValueError("Inference cache reference export revision mismatch")
    expected_fingerprint = fingerprint(manifest, source)
    if index.get("cache_fingerprint") != expected_fingerprint:
        raise ValueError("Inference cache pipeline fingerprint mismatch")
    expected_blocks = list(blocks(manifest))
    expected_sessions = [{"id": identifier, "file": f"{identifier}.json"} for identifier, _ in expected_blocks]
    if index.get("sessions") != expected_sessions:
        raise ValueError("Inference cache sessions incomplete or out of order")
    total_ms = 0
    sources = set()
    totals = {key: 0 for key in ("words", "word_errors", "chars", "char_errors")}
    for identifier, items in expected_blocks:
        document = load_json((directory / f"{identifier}.json").read_text(encoding="utf-8"))
        if (document.get("id") != identifier or document.get("schema_version") != 1
                or document.get("cache_fingerprint") != expected_fingerprint
                or document.get("checkpoint_sha256") != checkpoint_digest(document)):
            raise ValueError("Inference cache session integrity/fingerprint mismatch")
        if [record["source_id"] for record in document["references"]] != [item["path"] for item in items]:
            raise ValueError("Inference cache session source selection mismatch")
        if document.get("audio_file") != f"{identifier}.wav":
            raise ValueError("Inference cache audio path mismatch")
        audio = directory / document["audio_file"]
        if audio.is_symlink() or document.get("audio_sha256") != sha256_file(audio):
            raise ValueError("Inference cache audio integrity mismatch")
        source_ms = 0
        for reference in document["references"]:
            if reference["source_id"] in sources:
                raise ValueError("Inference cache repeats a source")
            sources.add(reference["source_id"])
            start, end = reference["start_ms"], reference["end_ms"]
            if type(start) is not int or type(end) is not int or not 0 <= start < end <= document["duration_ms"]:
                raise ValueError("Inference cache source times invalid")
            source_ms += end - start
        if source_ms != document["source_duration_ms"]:
            raise ValueError("Inference cache session duration mismatch")
        total_ms += source_ms
        for key in totals:
            value = document["asr_metrics"][key]
            if type(value) is not int or value < 0:
                raise ValueError("Inference cache metric count invalid")
            totals[key] += value
    if (len(sources) != manifest["selection"]["source_files"] or index["source_files"] != len(sources)
            or total_ms < 7_200_000 or index["source_duration_ms"] != total_ms
            or index["asr_metrics"] != totals):
        raise ValueError("Inference cache incomplete or aggregate accounting mismatch")
    return {"asr_source": source, "asr_sha256": ASR_HASHES[source],
            "pipeline_fingerprint": expected_fingerprint, "source_files": len(sources),
            "source_duration_ms": total_ms, "sessions": len(expected_blocks)}


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("directory", type=Path)
    args = parser.parse_args()
    print("Verified inference cache: " + json.dumps(verify(args.directory), sort_keys=True))
