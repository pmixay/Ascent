"""Dependency-free validation for the Android model-pack wire format."""
from __future__ import annotations

import hashlib
import json
from pathlib import Path
import re
import stat
import zipfile

MODEL_ID = "gigaam-multilingual-ctc-220m-2026-06"
UPSTREAM_COMMIT = "7447938d791c4f3e643386ee22c33777004293a5"
UPSTREAM_CHECKPOINT_MD5 = "5379d887c53ccd9cb95981e2a1832720"
SPEAKER_MODEL_ID = "3dspeaker-eres2netv2-2024"
SPEAKER_SHA256 = "bf1a75b9930474cf3389ef415e6e5d38ca96fea4a3a00f7e301d080a58ee2239"
MAX_FILE_BYTES = 1_500_000_000
MAX_PACK_BYTES = 2_000_000_000
REQUIRED_FILES = {
    "asr.onnx", "tokens.json", "speaker.onnx",
    "LICENSE-asr.txt", "LICENSE-speaker.txt", "smoke-report.json",
}


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def _unique_pairs(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise ValueError(f"Duplicate JSON key: {key}")
        result[key] = value
    return result


def load_json(content: str):
    return json.loads(content, object_pairs_hook=_unique_pairs)


def validate_manifest(manifest: dict) -> None:
    expected = {
        "schema_version": 1,
        "model_id": MODEL_ID,
        "sample_rate": 16000,
        "channels": 1,
        "min_samples": 4000,
        "max_samples": 400000,
        "asr_file": "asr.onnx",
        "tokens_file": "tokens.json",
        "speaker_file": "speaker.onnx",
        "speaker_model_id": SPEAKER_MODEL_ID,
        "speaker_runtime": "sherpa-onnx",
    }
    for key, value in expected.items():
        if manifest.get(key) != value or type(manifest.get(key)) is not type(value):
            raise ValueError(f"Unsupported {key}: {manifest.get(key)!r}")
    vocabulary_size = manifest.get("vocabulary_size")
    if type(vocabulary_size) is not int or not 1 < vocabulary_size < 65536:
        raise ValueError("Invalid vocabulary size")
    if manifest.get("blank_id") != vocabulary_size:
        raise ValueError("CTC blank must immediately follow the character vocabulary")
    files = manifest.get("files")
    if not isinstance(files, dict) or set(files) != REQUIRED_FILES:
        raise ValueError("Model-pack file set is not supported")
    total = 0
    for name, record in files.items():
        if not isinstance(record, dict):
            raise ValueError(f"Invalid file record: {name}")
        if not re.fullmatch(r"[0-9a-f]{64}", str(record.get("sha256", ""))):
            raise ValueError(f"Invalid SHA-256: {name}")
        size = record.get("bytes")
        if type(size) is not int or not 0 < size <= MAX_FILE_BYTES:
            raise ValueError(f"Invalid byte count: {name}")
        total += size
    if total > MAX_PACK_BYTES:
        raise ValueError("Model pack exceeds the supported size")


def validate_zip(path: Path) -> dict:
    """Validate flat paths, sizes and all hashes without extracting the archive."""
    with zipfile.ZipFile(path) as archive:
        entries = archive.infolist()
        names = [entry.filename for entry in entries]
        if len(set(names)) != len(names):
            raise ValueError("Duplicate archive entries")
        if set(names) != REQUIRED_FILES | {"manifest.json"}:
            raise ValueError("Archive must contain only the declared flat file set")
        for entry in entries:
            if entry.is_dir() or stat.S_ISLNK(entry.external_attr >> 16):
                raise ValueError("Directories and symlinks are not model files")
        if archive.getinfo("manifest.json").file_size > 128 * 1024:
            raise ValueError("Manifest is too large")
        manifest = load_json(archive.read("manifest.json").decode("utf-8"))
        validate_manifest(manifest)
        for name, record in manifest["files"].items():
            if archive.getinfo(name).file_size != record["bytes"]:
                raise ValueError(f"Size mismatch: {name}")
            digest = hashlib.sha256()
            consumed = 0
            with archive.open(name) as source:
                for block in iter(lambda: source.read(1024 * 1024), b""):
                    consumed += len(block)
                    if consumed > record["bytes"]:
                        raise ValueError(f"Expanded file exceeds declared size: {name}")
                    digest.update(block)
            if digest.hexdigest() != record["sha256"]:
                raise ValueError(f"SHA-256 mismatch: {name}")
        if manifest["files"]["tokens.json"]["bytes"] > 1024 * 1024:
            raise ValueError("Vocabulary is too large")
        tokens = load_json(archive.read("tokens.json").decode("utf-8"))
        if not isinstance(tokens, list) or len(tokens) != manifest["vocabulary_size"]:
            raise ValueError("Vocabulary size mismatch")
        if any(not isinstance(token, str) or not token for token in tokens):
            raise ValueError("Vocabulary must contain nonempty strings")
        return manifest


def validate_directory(path: Path) -> dict:
    """Validate the flat, independently downloadable model files before publication."""
    entries = list(path.iterdir())
    if {entry.name for entry in entries} != REQUIRED_FILES | {"manifest.json"}:
        raise ValueError("Directory must contain only the declared flat file set")
    if any(entry.is_symlink() or not entry.is_file() for entry in entries):
        raise ValueError("Model files must be regular files, not directories or symlinks")
    manifest_path = path / "manifest.json"
    if manifest_path.stat().st_size > 128 * 1024:
        raise ValueError("Manifest is too large")
    manifest = load_json(manifest_path.read_text(encoding="utf-8"))
    validate_manifest(manifest)
    for name, record in manifest["files"].items():
        file = path / name
        if file.stat().st_size != record["bytes"]:
            raise ValueError(f"Size mismatch: {name}")
        if sha256_file(file) != record["sha256"]:
            raise ValueError(f"SHA-256 mismatch: {name}")
    if manifest["files"]["tokens.json"]["bytes"] > 1024 * 1024:
        raise ValueError("Vocabulary is too large")
    tokens = load_json((path / "tokens.json").read_text(encoding="utf-8"))
    if not isinstance(tokens, list) or len(tokens) != manifest["vocabulary_size"]:
        raise ValueError("Vocabulary size mismatch")
    if any(not isinstance(token, str) or not token for token in tokens):
        raise ValueError("Vocabulary must contain nonempty strings")
    return manifest


def ctc_decode(labels, tokens: list[str], valid_frames: int) -> tuple[str, list[int]]:
    """Upstream charwise greedy CTC: collapse repeats before dropping blank."""
    blank_id = len(tokens)
    previous = None
    text = []
    frames = []
    for frame, token_id in enumerate(labels[:valid_frames]):
        token_id = int(token_id)
        if token_id < 0 or token_id > blank_id:
            raise ValueError("Token outside vocabulary")
        if token_id != blank_id and token_id != previous:
            text.append(tokens[token_id])
            frames.append(frame)
        previous = token_id
    return "".join(text), frames
