#!/usr/bin/env python3
"""Accept a verified model ZIP from a restricted CI SSH key and publish HTTPS files."""
from __future__ import annotations

import hashlib
import json
import os
from pathlib import Path
import shutil
import sys
import tempfile
import zipfile

from model_pack import MODEL_ID, validate_directory, validate_zip

MAX_ARCHIVE_BYTES = 2_001_048_576


def publish(source, root: Path) -> dict:
    root.mkdir(parents=True, exist_ok=True)
    incoming = root.parent / ".incoming-models"
    incoming.mkdir(mode=0o700, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix="upload-", dir=incoming) as temporary:
        staging = Path(temporary)
        archive_path = staging / "model.zip"
        total = 0
        with archive_path.open("xb") as destination:
            while block := source.read(1024 * 1024):
                total += len(block)
                if total > MAX_ARCHIVE_BYTES:
                    raise ValueError("Model archive exceeds the upload limit")
                destination.write(block)
        manifest = validate_zip(archive_path)
        expanded_bytes = sum(record["bytes"] for record in manifest["files"].values())
        if shutil.disk_usage(root).free < expanded_bytes + 64 * 1024 * 1024:
            raise ValueError("Insufficient model server storage")
        payload = staging / "payload"
        payload.mkdir()
        with zipfile.ZipFile(archive_path) as archive:
            manifest_bytes = archive.read("manifest.json")
            report = json.loads(archive.read("smoke-report.json"))
            if (report.get("status") != "passed" or report.get("speaker_runtime_tested") is not True
                    or type(report.get("speech_files_tested")) is not int or report["speech_files_tested"] < 1):
                raise ValueError("A passing real-speech and speaker smoke report is required")
            # validate_zip already permits only seven flat, regular, hash-checked files.
            for entry in archive.infolist():
                with archive.open(entry) as src, (payload / entry.filename).open("xb") as dst:
                    shutil.copyfileobj(src, dst, length=1024 * 1024)
                (payload / entry.filename).chmod(0o644)
        validate_directory(payload)
        digest = hashlib.sha256(manifest_bytes).hexdigest()
        model_root = root / MODEL_ID
        model_root.mkdir(mode=0o755, exist_ok=True)
        published = model_root / digest
        payload.chmod(0o755)
        if published.exists():
            validate_directory(published)
            if (published / "manifest.json").read_bytes() != manifest_bytes:
                raise ValueError("An immutable model revision already has different contents")
        else:
            payload.rename(published)
        catalog = {"schema_version": 1, "model_id": MODEL_ID,
                   "manifest_sha256": digest, "manifest_bytes": len(manifest_bytes)}
        descriptor, filename = tempfile.mkstemp(prefix=".catalog-", dir=root)
        temporary_catalog = Path(filename)
        try:
            with os.fdopen(descriptor, "w", encoding="utf-8") as output:
                json.dump(catalog, output, sort_keys=True)
                output.write("\n")
                output.flush()
                os.fsync(output.fileno())
            temporary_catalog.chmod(0o644)
            temporary_catalog.replace(root / "catalog.json")
        finally:
            temporary_catalog.unlink(missing_ok=True)
        return catalog


if __name__ == "__main__":
    if os.environ.get("SSH_ORIGINAL_COMMAND") != "publish-models":
        raise SystemExit("This key permits only the publish-models command")
    try:
        result = publish(sys.stdin.buffer, Path("/srv/ascent/models"))
    except (OSError, ValueError, KeyError, zipfile.BadZipFile) as error:
        raise SystemExit(f"Model publication failed: {error}") from None
    print(f"Published {result['model_id']}/{result['manifest_sha256']}")
