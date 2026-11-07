#!/usr/bin/env python3
"""Inspect the CI-built phone APK before publishing its sole download artifact."""
from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path
import re
import struct
import zipfile
import sys

sys.path.insert(0, str(Path(__file__).resolve().parent))
from prepare_embedded_models import PIN, PARAKEET_NOTICE, MAX_EMBEDDED_BYTES
from export_provenance import verify_receipt


def verify_embedded(archive, assets: Path, pin_path: Path = PIN):
    if (assets / "bundle.json").stat().st_size > 128 * 1024 or archive.getinfo("assets/models/bundle.json").file_size > 128 * 1024:
        raise ValueError("Embedded manifest exceeds the supported size")
    bundle_bytes = (assets / "bundle.json").read_bytes()
    if archive.read("assets/models/bundle.json") != bundle_bytes:
        raise ValueError("APK embedded manifest differs from the checked build input")
    bundle = json.loads(bundle_bytes)
    pin = json.loads(pin_path.read_text())
    if bundle.get("schema_version") != 2 or bundle.get("source_pin_sha256") != hashlib.sha256(pin_path.read_bytes()).hexdigest():
        raise ValueError("Embedded source pin mismatch")
    if bundle["acoustic_model_id"] != pin["acoustic_model_id"] or (
            bundle["text_repository"], bundle["text_revision"]) != (pin["text_model"]["repository"], pin["text_model"]["revision"]):
        raise ValueError("Embedded model provenance mismatch")
    expected = {"acoustic/" + name: item for name, item in pin["acoustic_files"].items()}
    expected.update({"text-model/" + name: {key: item[key] for key in ("bytes", "sha256")}
                     for name, item in pin["text_model"]["files"].items()})
    required = set(expected) | {"acoustic/manifest.json", "acoustic/smoke-report.json", "text-model/NOTICE.txt"}
    multilingual = pin["multilingual_model"]
    for key in ("repository", "revision", "upstream", "license"):
        if bundle.get("multilingual_" + key) != multilingual[key]:
            raise ValueError("Embedded multilingual provenance/license mismatch")
    expected.update({"multilingual-asr/" + name: {key: item[key] for key in ("bytes", "sha256")}
                     for name, item in multilingual["files"].items()})
    expected["multilingual-asr/NOTICE.txt"] = {"bytes": len(PARAKEET_NOTICE), "sha256": hashlib.sha256(PARAKEET_NOTICE).hexdigest()}
    required |= set(expected)
    if set(bundle["files"]) != required:
        raise ValueError("Embedded bundle missing required payloads or contains extras")
    receipt = bundle.get("export_validation")
    if not isinstance(receipt, dict):
        raise ValueError("APK is missing export validation provenance")
    verify_receipt(receipt, bundle["files"]["acoustic/manifest.json"]["sha256"])
    for name, item in expected.items():
        if bundle["files"].get(name) != item:
            raise ValueError("Embedded model hashes do not match the source pin")
    names = {"assets/models/" + name for name in required} | {"assets/models/bundle.json"}
    if {n for n in archive.namelist() if n.startswith("assets/models/")} != names:
        raise ValueError("APK embedded payload set differs from its manifest")
    total = 0
    for name, item in bundle["files"].items():
        entry = archive.getinfo("assets/models/" + name)
        if entry.file_size != item["bytes"]:
            raise ValueError("Embedded payload size mismatch")
        with archive.open(entry) as source:
            if hashlib.file_digest(source, "sha256").hexdigest() != item["sha256"]:
                raise ValueError("Embedded payload SHA-256 mismatch")
        if (name.endswith(".onnx") or name.startswith("multilingual-asr/")) and entry.compress_type != zipfile.ZIP_STORED:
            raise ValueError("Embedded ONNX and multilingual files must be stored uncompressed")
        total += entry.file_size
    if total > MAX_EMBEDDED_BYTES:
        raise ValueError("Embedded bundle exceeds the schema-2 byte budget")
    print(f"Embedded bundle SHA-256: {hashlib.sha256(bundle_bytes).hexdigest()}; expanded payload: {total:,} bytes")
    print("Offline asset baseline: text-stage pipeline integration and ARM acceptance require separate evidence.")
    return names


def inspect(apk_path: Path, mapping_path: Path, embedded_assets: Path | None = None) -> None:
    mapping = mapping_path.read_text(encoding="utf-8")
    mapped_classes = sum(bool(re.match(r"^\S+ -> \S+:$", line)) for line in mapping.splitlines())
    if "# compiler: R8" not in mapping or not mapped_classes:
        raise ValueError("Expected a nonempty R8 mapping for the optimized phone build")

    with zipfile.ZipFile(apk_path) as archive:
        entries = archive.infolist()
        if len({item.filename for item in entries}) != len(entries):
            raise ValueError("APK contains duplicate entries")
        native = [item for item in entries if item.filename.startswith("lib/") and item.filename.endswith(".so")]
        abis = {item.filename.split("/")[1] for item in native}
        if abis != {"arm64-v8a"}:
            raise ValueError(f"Phone APK must contain only ARM64 libraries, found {sorted(abis)}")
        library_names = {Path(item.filename).name for item in native}
        required = {"libonnxruntime.so", "libonnxruntime4j_jni.so", "libort_sherpa.so", "libsherpa-onnx-jni.so"}
        if not required <= library_names:
            raise ValueError(f"Required inference libraries missing: {sorted(required - library_names)}")
        if library_names.intersection({"libsherpa-onnx-c-api.so", "libsherpa-onnx-cxx-api.so"}):
            raise ValueError("Unused sherpa C/C++ wrappers must not be packaged")

        allowed = verify_embedded(archive, embedded_assets) if embedded_assets else set()
        model_suffixes = (".onnx", ".onnx.data", ".ckpt", ".pt", ".pth", ".tflite", ".safetensors", ".gguf", ".ggml")
        for item in entries:
            if item.filename in allowed:
                continue
            name = item.filename.lower()
            if name.endswith(model_suffixes) or name.startswith("assets/models/") or (
                name.startswith("assets/") and name.endswith((".bin", ".pb"))
            ):
                raise ValueError(f"Model weights must download separately: {item.filename}")

        dex = [item for item in entries if re.fullmatch(r"classes\d*\.dex", item.filename)]
        if not dex:
            raise ValueError("APK contains no DEX code")
        if any(item.compress_type != zipfile.ZIP_DEFLATED for item in native + dex):
            raise ValueError("Native libraries and DEX must be compressed in the downloadable APK")
        class_count = 0
        for item in dex:
            with archive.open(item) as source:
                header = source.read(112)
            if len(header) != 112 or not header.startswith(b"dex\n"):
                raise ValueError(f"Unexpected DEX header: {item.filename}")
            class_count += struct.unpack_from("<I", header, 96)[0]

        print(f"Phone APK: {apk_path.stat().st_size:,} bytes ({apk_path.stat().st_size / 1024**2:.2f} MiB)")
        print(f"Native libraries: {sum(item.compress_size for item in native):,} compressed bytes, ARM64 only")
        print(f"DEX: {len(dex)} files, {class_count:,} classes; R8 mapping: {mapped_classes:,} classes")
        print("Verified: compressed code/native libraries, " +
              ("pinned embedded payloads" if embedded_assets else "no model weights") + ", no unused native wrappers.")
    # Hash the actual checked file, not its later Actions ZIP container.
    with apk_path.open("rb") as source:
        print(f"APK SHA-256: {hashlib.file_digest(source, 'sha256').hexdigest()}")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--apk", type=Path, required=True)
    parser.add_argument("--mapping", type=Path, required=True)
    parser.add_argument("--embedded-assets", type=Path,
                        help="Verified asset directory for the separate offline baseline; compact checks stay unchanged.")
    args = parser.parse_args()
    inspect(args.apk, args.mapping, args.embedded_assets)


if __name__ == "__main__":
    main()
