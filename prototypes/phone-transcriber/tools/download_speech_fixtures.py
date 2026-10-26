#!/usr/bin/env python3
"""Restore the pinned public development corpus; never accepts application audio.

Downloading public data and verifying its bytes does not run inference or tests.
The fixture manifest is reviewed in Git; this tool cannot select new recordings
or silently accept changes to the source revision, transcript, format or checksum.
"""

import argparse
import hashlib
import io
import json
from pathlib import Path
import urllib.parse
import urllib.request
import wave

FIXTURE_ROOT = Path(__file__).resolve().parent / "fixtures" / "speakers"
MAX_AUDIO_BYTES = 1_000_000


def verify(data, item):
    if len(data) != item["bytes"] or hashlib.sha256(data).hexdigest() != item["sha256"]:
        raise ValueError(f"Fixture integrity mismatch: {item['id']}")
    with wave.open(io.BytesIO(data), "rb") as source:
        if (source.getnchannels(), source.getsampwidth(), source.getframerate(),
                source.getcomptype(), source.getnframes()) != (1, 2, 16000, "NONE", item["frames"]):
            raise ValueError(f"Fixture format mismatch: {item['id']}")


def fetch(item, manifest):
    query = urllib.parse.urlencode({"dataset": manifest["dataset"], "config": "default",
                                   "split": manifest["split"], "offset": item["source_row"], "length": 1})
    with urllib.request.urlopen("https://datasets-server.huggingface.co/rows?" + query, timeout=60) as response:
        document = json.loads(response.read(128_001))
    row = document["rows"][0]
    if row["row_idx"] != item["source_row"]:
        raise ValueError("Source row differs from reviewed manifest")
    metadata = row["row"]
    if (metadata["audio_filepath"] != item["source_audio_filepath"]
            or metadata["text"] != item["reference_text"]):
        raise ValueError("Source metadata differs from reviewed manifest")
    url = metadata["audio"][0]["src"]
    parsed = urllib.parse.urlsplit(url)
    expected_prefix = f"/cached-assets/{manifest['dataset']}/--/{manifest['revision']}/--/"
    if (parsed.scheme != "https" or parsed.netloc != "datasets-server.huggingface.co"
            or not parsed.path.startswith(expected_prefix)):
        raise ValueError("Source URL does not identify the pinned dataset revision")
    with urllib.request.urlopen(url, timeout=60) as response:
        data = response.read(MAX_AUDIO_BYTES + 1)
    verify(data, item)
    return data


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output-dir", type=Path, default=FIXTURE_ROOT)
    args = parser.parse_args()
    manifest = json.loads((FIXTURE_ROOT / "manifest.json").read_text(encoding="utf-8"))
    args.output_dir.mkdir(parents=True, exist_ok=True)
    for item in manifest["fixtures"]:
        name = item["file"]
        if Path(name).name != name or not name.endswith(".wav") or not 0 < item["bytes"] <= MAX_AUDIO_BYTES:
            raise ValueError("Unsafe fixture manifest entry")
        destination = args.output_dir / name
        if destination.exists():
            verify(destination.read_bytes(), item)
            print(f"Already verified: {item['id']}")
            continue
        data = fetch(item, manifest)
        partial = destination.with_suffix(".wav.part")
        try:
            partial.write_bytes(data)
            partial.replace(destination)
        finally:
            partial.unlink(missing_ok=True)
        print(f"Downloaded and verified: {item['id']}")


if __name__ == "__main__":
    main()
