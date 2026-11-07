#!/usr/bin/env python3
"""CI-only: prepare APK assets from a verified export and pinned text assets.

No catalog, warm cache or server publication is needed. The acoustic export must
have passed the existing model exporter; this command never exports/runs models.
"""
from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path
import shutil
import sys
import tempfile
import urllib.request

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "tools"))
from checkpoint_source import HttpsOnlyRedirect
from model_pack import load_json, sha256_file, validate_directory, REQUIRED_FILES
from export_provenance import load_receipt

PIN = ROOT / "config/embedded-models.json"
NOTICE = ("Qwen2.5 0.5B Instruct — Qwen Team / Alibaba Cloud. Apache-2.0.\n"
          "Int8 ONNX conversion: onnx-community. See bundled LICENSE-Qwen.txt,\n"
          "config.json and tokenizer_config.json. The source repository, immutable\n"
          "revision and individual file hashes are recorded in bundle.json.\n"
          "Provisional assets; packaging is not evidence of on-device inference.\n").encode("utf-8")


def record(path):
    return {"bytes": path.stat().st_size, "sha256": sha256_file(path)}


def check_record(path, expected):
    if path.is_symlink() or not path.is_file() or record(path) != {k: expected[k] for k in ("bytes", "sha256")}:
        raise ValueError(f"Embedded payload does not match pinned source: {path.name}")


def download(url, path, expected):
    if path.is_file() and not path.is_symlink():
        try:
            check_record(path, expected)
            return
        except ValueError:
            pass
    if not url.startswith("https://"):
        raise ValueError("Embedded payload source must be HTTPS")
    path.parent.mkdir(parents=True, exist_ok=True)
    part = path.with_suffix(path.suffix + ".part")
    try:
        part.unlink(missing_ok=True)
        opener = urllib.request.build_opener(HttpsOnlyRedirect())
        with opener.open(url, timeout=120) as source, part.open("xb") as output:
            size = 0
            while chunk := source.read(1024 * 1024):
                size += len(chunk)
                if size > expected["bytes"]:
                    raise ValueError("Embedded download exceeds pinned size")
                output.write(chunk)
        check_record(part, expected)
        part.replace(path)
    finally:
        part.unlink(missing_ok=True)


def verify_acoustic(directory, pin):
    manifest = validate_directory(directory)
    if manifest["model_id"] != pin["acoustic_model_id"]:
        raise ValueError("Wrong embedded acoustic model")
    for name, expected in pin["acoustic_files"].items():
        check_record(directory / name, expected)
    report = load_json((directory / "smoke-report.json").read_text())
    if (report.get("status") != "passed" or report.get("speaker_runtime_tested") is not True
            or type(report.get("speech_files_tested")) is not int or report["speech_files_tested"] < 1
            or report.get("speaker_smoke", {}).get("status") != "passed"):
        raise ValueError("Embedded assets require actual successful export/speaker validation")
    source = manifest.get("source", {})
    if (source.get("checkpoint_format") != "huggingface-state-dict"
            or source.get("checkpoint_revision") != "2f8a57144e6ec3adfd32fe0484d9ea9913305bc8"
            or source.get("checkpoint_sha256") != "e1db43873ec5e296f229572e06e2470fc157ac9f8d4aacabda295630b9b91728"):
        raise ValueError("Embedded acoustic export provenance differs from the frozen source")
    return manifest


def prepare(acoustic, output, cache, validation_receipt, pin_path=PIN):
    pin = load_json(pin_path.read_text())
    verify_acoustic(acoustic, pin)
    receipt = load_receipt(validation_receipt, sha256_file(acoustic / "manifest.json"))
    if output.exists():
        raise FileExistsError("Refusing to replace an existing asset directory")
    output.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix=".embedded-", dir=output.parent) as tmp:
        staging = Path(tmp) / "models"
        (staging / "acoustic").mkdir(parents=True)
        (staging / "text-model").mkdir()
        for name in REQUIRED_FILES | {"manifest.json"}:
            shutil.copyfile(acoustic / name, staging / "acoustic" / name)
        text = pin["text_model"]
        for name, item in text["files"].items():
            url = item.get("url") or f"https://huggingface.co/{text['repository']}/resolve/{text['revision']}/{item['source']}"
            cached = cache / text["revision"] / name
            download(url, cached, item)
            shutil.copyfile(cached, staging / "text-model" / name)
        (staging / "text-model" / "NOTICE.txt").write_bytes(NOTICE)
        files = {str(path.relative_to(staging)): record(path) for path in sorted(staging.rglob("*")) if path.is_file()}
        bundle = {"schema_version": 1, "source_pin_sha256": sha256_file(pin_path),
                  "export_validation": receipt,
                  "acoustic_model_id": pin["acoustic_model_id"],
                  "text_repository": text["repository"], "text_revision": text["revision"],
                  "pipeline_status": "offline-assets-baseline-text-integration-and-ARM-acceptance-pending",
                  "files": files}
        (staging / "bundle.json").write_text(json.dumps(bundle, sort_keys=True, indent=2) + "\n", encoding="utf-8")
        staging.rename(output)
    print(f"Embedded bundle SHA-256: {sha256_file(output / 'bundle.json')}")
    print(f"Expanded model payload bytes: {sum(item['bytes'] for item in files.values())}")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--acoustic", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--cache", type=Path, required=True)
    parser.add_argument("--validation-receipt", type=Path, required=True)
    args = parser.parse_args()
    prepare(args.acoustic, args.output, args.cache, args.validation_receipt)
