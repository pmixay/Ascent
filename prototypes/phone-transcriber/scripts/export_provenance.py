#!/usr/bin/env python3
"""Bind CI-validated acoustic exports to their exact validation inputs.

No inference, downloads or application data. The receipt is cache metadata,
not a signature: its trust boundary is the push CI job which runs the exporter.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import sys

ROOT = Path(__file__).resolve().parents[1]
REPOSITORY = ROOT.parents[1]
sys.path.insert(0, str(ROOT / "tools"))
from model_pack import load_json, sha256_file, validate_directory

PREFIX = "prototypes/phone-transcriber/"
# Keep these in the exact Actions cache key too; the CI regression checks enforce it.
INPUTS = (".github/workflows/android.yml",) + tuple(PREFIX + name for name in (
    "tools/export_model_pack.py", "tools/model_pack.py", "tools/checkpoint_source.py",
    "tools/evaluate_speech.py", "tools/generate_speaker_fixtures.py",
    "tools/requirements-export.txt", "tools/requirements-gigaam.txt",
    "config/embedded-models.json", "scripts/prepare_embedded_models.py",
    "scripts/export_provenance.py",
))
FIXTURES = PREFIX + "tools/fixtures/speakers"


def input_records(repository: Path = REPOSITORY) -> dict:
    fixtures = repository / FIXTURES
    paths = [repository / name for name in INPUTS]
    speech = sorted(fixtures.rglob("*"))
    if not any(path.suffix == ".wav" for path in speech):
        raise ValueError("Frozen speaker validation fixtures are missing")
    paths += [path for path in speech if not path.is_dir()]
    records = {}
    for path in sorted(paths):
        if path.is_symlink() or not path.is_file():
            raise ValueError("Export validation inputs must be regular files")
        records[path.relative_to(repository).as_posix()] = {
            "bytes": path.stat().st_size, "sha256": sha256_file(path),
        }
    return records


def fingerprint(records: dict) -> str:
    return hashlib.sha256(json.dumps(records, sort_keys=True, separators=(",", ":")).encode()).hexdigest()


def describe(manifest_sha256: str, producer: dict, repository: Path = REPOSITORY) -> dict:
    records = input_records(repository)
    return {"schema_version": 1, "inputs": records, "inputs_sha256": fingerprint(records),
            "manifest_sha256": manifest_sha256, "producer": producer}


def verify_receipt(receipt: dict, manifest_sha256: str, repository: Path = REPOSITORY) -> None:
    if type(receipt.get("schema_version")) is not int or receipt["schema_version"] != 1:
        raise ValueError("Unsupported export validation receipt")
    records = input_records(repository)
    if receipt.get("inputs") != records or receipt.get("inputs_sha256") != fingerprint(records):
        raise ValueError("Export validation inputs differ from the current source/dependencies/fixtures")
    if receipt.get("manifest_sha256") != manifest_sha256:
        raise ValueError("Export validation receipt belongs to a different model manifest")
    producer = receipt.get("producer")
    if (not isinstance(producer, dict)
            or not re.fullmatch(r"[0-9a-f]{40}", str(producer.get("commit", "")))
            or any(not re.fullmatch(r"[1-9][0-9]*", str(producer.get(key, ""))) for key in ("run_id", "run_attempt"))):
        raise ValueError("Export validation receipt is missing its CI producer identity")


def load_receipt(path: Path, manifest_sha256: str, repository: Path = REPOSITORY) -> dict:
    if path.is_symlink() or not path.is_file() or path.stat().st_size > 128 * 1024:
        raise ValueError("Missing or oversized export validation receipt; regenerate the exact cache")
    receipt = load_json(path.read_text(encoding="utf-8"))
    if not isinstance(receipt, dict):
        raise ValueError("Export validation receipt must be an object")
    verify_receipt(receipt, manifest_sha256, repository)
    print(f"Verified export inputs SHA-256: {receipt['inputs_sha256']}; "
          f"producer commit={receipt['producer']['commit']}; run={receipt['producer']['run_id']} "
          f"attempt={receipt['producer']['run_attempt']}")
    return receipt


def record(directory: Path, output: Path, repository: Path = REPOSITORY) -> None:
    if os.environ.get("GITHUB_ACTIONS") != "true":
        raise ValueError("Record export validation only after a successful exporter in GitHub CI")
    if output.exists() or output.is_symlink():
        raise FileExistsError("Refusing to replace export validation evidence")
    validate_directory(directory)
    receipt = describe(sha256_file(directory / "manifest.json"), {
        "commit": os.environ.get("GITHUB_SHA", ""), "run_id": os.environ.get("GITHUB_RUN_ID", ""),
        "run_attempt": os.environ.get("GITHUB_RUN_ATTEMPT", ""),
    }, repository)
    verify_receipt(receipt, sha256_file(directory / "manifest.json"), repository)
    with output.open("x", encoding="utf-8") as destination:
        destination.write(json.dumps(receipt, sort_keys=True, indent=2) + "\n")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--acoustic", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    record(args.acoustic, args.output)
