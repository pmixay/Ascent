#!/usr/bin/env python3
"""Fetch the pinned speaker runtime; use the app's single ONNX Runtime shared library."""
from __future__ import annotations

import hashlib
import io
from pathlib import Path
import urllib.request
import zipfile

VERSION = "2.0.0"
URL = f"https://github.com/k2-fsa/sherpa-onnx/releases/download/v{VERSION}/sherpa-onnx-{VERSION}.aar"
SHA256 = "f0f8716982d27cb4ce695e3934018b1342d9d3d6fdb6ef1f1658fe8bad50b4fc"
ROOT = Path(__file__).resolve().parents[1]
OUTPUT = ROOT / "android/app/libs" / f"sherpa-onnx-{VERSION}-shared-ort.aar"


def repackage(data: bytes, output: Path) -> None:
    """Keep JNI bindings, remove duplicate ORT .so; newer ORT preserves the C ABI.

    Do not use Gradle pickFirst: that could select an older ORT for the Java binding.
    """
    with zipfile.ZipFile(io.BytesIO(data)) as source:
        names = set(source.namelist())
        required = {"classes.jar", "jni/arm64-v8a/libsherpa-onnx-jni.so", "jni/x86_64/libsherpa-onnx-jni.so"}
        if not required <= names:
            raise ValueError(f"Unexpected AAR layout: missing {required - names}")
        output.parent.mkdir(parents=True, exist_ok=True)
        temporary = output.with_suffix(".tmp")
        try:
            with zipfile.ZipFile(temporary, "w", zipfile.ZIP_DEFLATED) as target:
                for entry in source.infolist():
                    if entry.filename.endswith("/libonnxruntime.so"):
                        continue
                    target.writestr(entry, source.read(entry))
            temporary.replace(output)
        finally:
            temporary.unlink(missing_ok=True)


def main() -> None:
    cache = ROOT / ".cache" / f"sherpa-onnx-{VERSION}.aar"
    cache.parent.mkdir(parents=True, exist_ok=True)
    if cache.is_file() and hashlib.sha256(cache.read_bytes()).hexdigest() == SHA256:
        data = cache.read_bytes()
    else:
        with urllib.request.urlopen(URL, timeout=120) as response:
            data = response.read(100 * 1024 * 1024 + 1)
        if hashlib.sha256(data).hexdigest() != SHA256:
            raise ValueError("Upstream AAR checksum mismatch; refusing to build")
        cache.write_bytes(data)
    repackage(data, OUTPUT)
    print(f"Verified speaker runtime ready: {OUTPUT}")


if __name__ == "__main__":
    main()
