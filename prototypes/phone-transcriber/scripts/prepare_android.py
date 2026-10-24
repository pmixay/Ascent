#!/usr/bin/env python3
"""Fetch sherpa and isolate its C runtime from the Maven ONNX Runtime JNI library."""
from __future__ import annotations

import hashlib
import io
import copy
from pathlib import Path
import struct
import urllib.request
import zipfile

VERSION = "2.0.0"
URL = f"https://github.com/k2-fsa/sherpa-onnx/releases/download/v{VERSION}/sherpa-onnx-{VERSION}.aar"
SHA256 = "f0f8716982d27cb4ce695e3934018b1342d9d3d6fdb6ef1f1658fe8bad50b4fc"
ROOT = Path(__file__).resolve().parents[1]
OUTPUT = ROOT / "android/app/libs" / f"sherpa-onnx-{VERSION}-isolated-ort.aar"
ORIGINAL_RUNTIME = "libonnxruntime.so"
ISOLATED_RUNTIME = "libort_sherpa.so"
ARCHITECTURES = ("arm64-v8a", "armeabi-v7a", "x86", "x86_64")
SHERPA_LIBRARIES = ("libsherpa-onnx-jni.so",)
UNUSED_SHERPA_LIBRARIES = ("libsherpa-onnx-c-api.so", "libsherpa-onnx-cxx-api.so")


def dynamic_strings(data: bytes) -> list[tuple[int, int, bytes]]:
    """Read DT_NEEDED/DT_SONAME strings from little-endian ELF32/64 load segments.

    Section headers are deliberately unnecessary: Android libraries can be stripped.
    Offsets and segment ranges are checked before any metadata is rewritten.
    """
    if len(data) < 16 or data[:4] != b"\x7fELF" or data[4] not in (1, 2) or data[5:7] != b"\x01\x01":
        raise ValueError("Expected a little-endian ELF32/64 library")
    is64 = data[4] == 2

    def unpack(format_: str, offset: int) -> tuple:
        if offset < 0 or offset + struct.calcsize(format_) > len(data):
            raise ValueError("ELF metadata is outside the file")
        return struct.unpack_from(format_, data, offset)

    header = unpack("<HHIQQQIHHHHHH" if is64 else "<HHIIIIIHHHHHH", 16)
    if header[0] != 3:
        raise ValueError("Expected an ELF shared object")
    phoff, phsize, phnum = header[4], header[8], header[9]
    phformat = "<IIQQQQQQ" if is64 else "<IIIIIIII"
    if not 0 < phnum < 1024 or phsize < struct.calcsize(phformat) or phoff + phnum * phsize > len(data):
        raise ValueError("Invalid ELF program headers")
    loads = []
    dynamic = []
    for index in range(phnum):
        ph = unpack(phformat, phoff + index * phsize)
        kind = ph[0]
        offset, address, size = (ph[2], ph[3], ph[5]) if is64 else (ph[1], ph[2], ph[4])
        if kind not in (1, 2):
            continue
        if offset + size > len(data):
            raise ValueError("ELF segment is outside the file")
        if kind == 1:
            loads.append((address, offset, size))
        else:
            dynamic.append((offset, size))
    if len(dynamic) != 1:
        raise ValueError("Expected one ELF dynamic segment")
    dynformat = "<qQ" if is64 else "<iI"
    dynsize = struct.calcsize(dynformat)
    offset, size = dynamic[0]
    entries = []
    for position in range(offset, offset + size - dynsize + 1, dynsize):
        tag, value = unpack(dynformat, position)
        if tag == 0:
            break
        entries.append((tag, value))
    else:
        raise ValueError("ELF dynamic segment has no terminator")
    string_addresses = [value for tag, value in entries if tag == 5]
    string_sizes = [value for tag, value in entries if tag == 10]
    if len(string_addresses) != 1 or len(string_sizes) != 1 or string_sizes[0] <= 0:
        raise ValueError("Expected one bounded ELF dynamic string table")
    address, size = string_addresses[0], string_sizes[0]
    mappings = [offset + address - start for start, offset, length in loads
                if start <= address and address + size <= start + length]
    if len(mappings) != 1:
        raise ValueError("ELF string table is not contained in one load segment")
    base = mappings[0]
    result = []
    for tag, relative in entries:
        if tag not in (1, 14):
            continue
        if relative >= size:
            raise ValueError("ELF library name is outside its string table")
        end = data.find(b"\0", base + relative, base + size)
        if end < 0:
            raise ValueError("Unterminated ELF library name")
        result.append((tag, base + relative, data[base + relative:end]))
    return result


def isolate_runtime(data: bytes, *, is_runtime: bool) -> bytes:
    """Rename only the runtime SONAME or its consumers' DT_NEEDED entry in place.

    The shorter name preserves file size, segment offsets and Android page alignment.
    It also updates a version-need filename if it shares the same dynamic string.
    No machine instructions or exported symbol names are changed.
    """
    original, replacement = ORIGINAL_RUNTIME.encode(), ISOLATED_RUNTIME.encode()
    if len(replacement) > len(original):
        raise ValueError("The isolated runtime name must fit the original string")
    expected_tag = 14 if is_runtime else 1
    references = [(tag, offset) for tag, offset, value in dynamic_strings(data) if value == original]
    if len(references) != 1 or references[0][0] != expected_tag:
        raise ValueError("Unexpected ONNX Runtime SONAME/NEEDED layout")
    offset = references[0][1]
    rewritten = bytearray(data)
    rewritten[offset:offset + len(original)] = replacement.ljust(len(original), b"\0")
    return bytes(rewritten)


def repackage(data: bytes, output: Path) -> None:
    """Preserve the JNI binding and its isolated C runtime for every bundled ABI.

    Maven's ORT Android JNI library does not export the C API required by sherpa.
    Do not remove that C runtime or resolve the collision with Gradle pickFirst.
    The separate C/C++ API wrappers are unused by the app and omitted only after
    confirming that the retained JNI/runtime libraries do not depend on them.
    """
    with zipfile.ZipFile(io.BytesIO(data)) as source:
        names = set(source.namelist())
        required = {"classes.jar"} | {f"jni/{arch}/{library}" for arch in ARCHITECTURES
                                     for library in (ORIGINAL_RUNTIME, *SHERPA_LIBRARIES, *UNUSED_SHERPA_LIBRARIES)}
        if len(names) != len(source.infolist()) or not required <= names:
            raise ValueError(f"Unexpected AAR layout: missing {required - names}")
        if any(name.endswith("/" + ISOLATED_RUNTIME) for name in names):
            raise ValueError("Isolated runtime name already exists in the source AAR")
        omitted = {f"jni/{arch}/{library}" for arch in ARCHITECTURES for library in UNUSED_SHERPA_LIBRARIES}
        output.parent.mkdir(parents=True, exist_ok=True)
        temporary = output.with_suffix(".tmp")
        try:
            with zipfile.ZipFile(temporary, "w", zipfile.ZIP_DEFLATED) as target:
                for entry in source.infolist():
                    if entry.filename in omitted:
                        continue
                    content = source.read(entry)
                    rewritten_entry = copy.copy(entry)
                    if entry.filename in required and entry.filename.endswith(".so"):
                        dependencies = {value.decode("ascii") for tag, _, value in dynamic_strings(content) if tag == 1}
                        if dependencies.intersection(UNUSED_SHERPA_LIBRARIES):
                            raise ValueError("A retained native library requires an omitted sherpa wrapper")
                        is_runtime = entry.filename.endswith("/" + ORIGINAL_RUNTIME)
                        content = isolate_runtime(content, is_runtime=is_runtime)
                        if is_runtime:
                            rewritten_entry.filename = entry.filename.removesuffix(ORIGINAL_RUNTIME) + ISOLATED_RUNTIME
                    target.writestr(rewritten_entry, content)
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
