#!/usr/bin/env python3
"""Inspect the CI-built phone APK before publishing its sole download artifact."""
from __future__ import annotations

import argparse
from pathlib import Path
import re
import struct
import zipfile


def inspect(apk_path: Path, mapping_path: Path) -> None:
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

        model_suffixes = (".onnx", ".onnx.data", ".ckpt", ".pt", ".pth", ".tflite", ".safetensors", ".gguf", ".ggml")
        for item in entries:
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
        print("Verified: compressed code/native libraries, no model weights, no unused native wrappers.")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--apk", type=Path, required=True)
    parser.add_argument("--mapping", type=Path, required=True)
    args = parser.parse_args()
    inspect(args.apk, args.mapping)


if __name__ == "__main__":
    main()
