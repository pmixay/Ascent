"""Verify official AMI sources and reproduce the frozen 10-minute WAV clips.

Public corpus preparation only; this script does not run application/model inference.
Download the source URLs and manual annotation archive from the manifest into a
private source directory before running it. Keep audio out of Git artifacts.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import wave
from pathlib import Path
from zipfile import ZipFile


def digest(path: Path) -> str:
    hasher = hashlib.sha256()
    with path.open("rb") as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b""):
            hasher.update(chunk)
    return hasher.hexdigest()


def prepare(source_dir: Path, output_dir: Path, manifest_path: Path) -> None:
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    archive = source_dir / "ami_public_manual_1.6.2.zip"
    annotation = manifest["annotation_archive"]
    if archive.stat().st_size != annotation["bytes"] or digest(archive) != annotation["sha256"]:
        raise ValueError("manual annotation archive does not match the frozen manifest")
    with ZipFile(archive) as labels:
        for clip in manifest["clips"]:
            for member in clip["annotation_members"]:
                labels.getinfo(member)
    output_dir.mkdir(parents=True, exist_ok=True)
    for clip in manifest["clips"]:
        meeting = clip["meeting_id"]
        source = source_dir / f"{meeting}.Mix-Headset.wav"
        if source.stat().st_size != clip["source_bytes"] or digest(source) != clip["source_sha256"]:
            raise ValueError(f"{meeting}: source WAV does not match the frozen manifest")
        destination = output_dir / f"{meeting}-first-10min.wav"
        with wave.open(str(source), "rb") as reader:
            if (reader.getnchannels(), reader.getsampwidth(), reader.getframerate()) != (1, 2, 16000):
                raise ValueError(f"{meeting}: unexpected WAV format")
            if reader.getnframes() * 1000 // 16000 != clip["source_duration_ms"]:
                raise ValueError(f"{meeting}: unexpected source duration")
            with wave.open(str(destination), "wb") as writer:
                writer.setparams(reader.getparams())
                writer.writeframes(reader.readframes(16000 * 600))
        if digest(destination) != clip["clip_sha256"]:
            destination.unlink(missing_ok=True)
            raise ValueError(f"{meeting}: crop hash differs from the frozen manifest")
        print(f"VERIFIED {meeting}: {destination}")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source-dir", type=Path, required=True)
    parser.add_argument("--output-dir", type=Path, required=True)
    parser.add_argument("--manifest", type=Path,
                        default=Path(__file__).with_name("fixtures") / "ami_holdout_manifest.json")
    args = parser.parse_args()
    prepare(args.source_dir, args.output_dir, args.manifest)


if __name__ == "__main__":
    main()
