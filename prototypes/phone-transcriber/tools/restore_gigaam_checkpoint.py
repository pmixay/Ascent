#!/usr/bin/env python3
"""Restore the unchanged official GigaAM checkpoint from a verified HTTPS mirror.

An optional mirror recovers from an upstream TLS outage without disabling TLS,
changing model weights, or changing the pinned upstream source package.
"""
from __future__ import annotations

import argparse
import hashlib
import os
from pathlib import Path
import re
import tempfile
import urllib.error
import urllib.parse
import urllib.request

from model_pack import UPSTREAM_CHECKPOINT_MD5, sha256_file

FILENAME = "multilingual_ctc.ckpt"
MAX_CHECKPOINT_BYTES = 1_500_000_000


def require_https(url: str):
    if any(ord(character) <= 32 or ord(character) > 126 for character in url):
        raise ValueError("The checkpoint mirror URL must contain only escaped printable ASCII")
    try:
        parsed = urllib.parse.urlsplit(url)
        allowed = (parsed.scheme == "https" and parsed.hostname and parsed.username is None
                   and parsed.password is None and not parsed.fragment and parsed.port in (None, 443))
    except ValueError:
        raise ValueError("Malformed checkpoint mirror URL") from None
    if not allowed:
        raise ValueError("The checkpoint mirror must use HTTPS without embedded credentials or fragments")


class HttpsRedirects(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, request, response, code, message, headers, target):
        require_https(target)
        return super().redirect_request(request, response, code, message, headers, target)


def download(url: str, target: Path, size: int, digest: str, *, maximum_size: int = MAX_CHECKPOINT_BYTES):
    require_https(url)
    if type(size) is not int or not 0 < size <= maximum_size:
        raise ValueError("The pinned checkpoint byte count is outside its explicit size limit")
    if not re.fullmatch(r"[0-9a-f]{64}", digest):
        raise ValueError("The checkpoint download needs an independently pinned lowercase SHA-256")
    opener = urllib.request.build_opener(HttpsRedirects())
    request = urllib.request.Request(url, headers={"User-Agent": "Ascent-model-recovery/1"})
    actual = hashlib.sha256()
    count = 0
    try:
        with opener.open(request, timeout=60) as response, target.open("xb") as output:
            require_https(response.geturl())
            while block := response.read(1024 * 1024):
                count += len(block)
                if count > size:
                    raise ValueError("The checkpoint download exceeds its pinned byte count")
                actual.update(block)
                output.write(block)
    except urllib.error.HTTPError as error:
        raise ValueError(f"The checkpoint download failed (HTTP {error.code})") from None
    except (urllib.error.URLError, OSError):
        raise ValueError("The checkpoint download failed; check connectivity and mirror access") from None
    if count != size or actual.hexdigest() != digest:
        raise ValueError("The checkpoint download failed pinned size/SHA-256 verification")


def verify_checkpoint(path: Path, expected_sha256: str = "", expected_size: int | None = None):
    if path.is_symlink() or not path.is_file() or not 0 < path.stat().st_size <= MAX_CHECKPOINT_BYTES:
        raise ValueError("The GigaAM checkpoint is missing, linked or oversized")
    if expected_size is not None and path.stat().st_size != expected_size:
        raise ValueError("The checkpoint differs from the pinned mirror byte count")
    if expected_sha256 and sha256_file(path) != expected_sha256:
        raise ValueError("The checkpoint differs from the pinned mirror SHA-256")
    with path.open("rb") as source:
        actual_md5 = hashlib.file_digest(source, "md5").hexdigest()
    if actual_md5 != UPSTREAM_CHECKPOINT_MD5:
        raise ValueError("The checkpoint differs from the fixed official GigaAM upstream checksum")


def restore(directory: Path):
    source = os.environ.get("ASCENT_GIGAAM_CHECKPOINT_URL", "")
    digest = os.environ.get("ASCENT_GIGAAM_CHECKPOINT_SHA256", "")
    size_text = os.environ.get("ASCENT_GIGAAM_CHECKPOINT_BYTES", "")
    mirrored = bool(source or digest or size_text)
    size = None
    if mirrored:
        if not source or not re.fullmatch(r"[0-9a-f]{64}", digest) or not size_text.isascii() or not size_text.isdecimal():
            raise ValueError("Configure the complete verified GigaAM mirror URL, SHA-256 and byte count")
        size = int(size_text)
        if not 0 < size <= MAX_CHECKPOINT_BYTES:
            raise ValueError("The pinned GigaAM checkpoint byte count exceeds its explicit limit")
    target = directory / FILENAME
    if target.is_symlink():
        raise ValueError("Refusing to use a linked GigaAM checkpoint")
    if target.exists():
        verify_checkpoint(target, digest, size)
        print("Verified the unchanged official GigaAM checkpoint in the restored cache")
        return
    if not mirrored:
        print("No verified checkpoint mirror is configured; export still requires the official HTTPS source.", flush=True)
        print("If its TLS certificate is expired, configure the pinned checkpoint recovery repository settings.", flush=True)
        return
    directory.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix=".ascent-gigaam-", dir=directory) as temporary:
        candidate = Path(temporary) / FILENAME
        download(source, candidate, size, digest, maximum_size=MAX_CHECKPOINT_BYTES)
        verify_checkpoint(candidate, digest, size)
        candidate.rename(target)
    print("Restored the unchanged official GigaAM checkpoint from its size/hash-pinned HTTPS mirror")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--directory", type=Path, required=True)
    args = parser.parse_args()
    restore(args.directory)


if __name__ == "__main__":
    main()
