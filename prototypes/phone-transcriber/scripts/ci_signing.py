#!/usr/bin/env python3
"""Prepare an owner-supplied signing key in the CI runner's private temporary area.

Never generates a release identity. Absent credentials keep the internal debug
baseline; partial credentials or required-but-absent signing fail closed.
"""
from __future__ import annotations

import argparse
import base64
import binascii
import os
from pathlib import Path
import re
import tempfile

SECRET_NAMES = ("ASCENT_SIGNING_KEYSTORE_BASE64", "ASCENT_SIGNING_STORE_PASSWORD",
                "ASCENT_SIGNING_KEY_ALIAS", "ASCENT_SIGNING_KEY_PASSWORD")


def certificate_digest(value: str) -> str:
    digest = value.replace(":", "").lower()
    if not re.fullmatch(r"[0-9a-f]{64}", digest):
        raise ValueError("Configure the independently verified signing certificate SHA-256")
    return digest


def properties_value(value: str) -> str:
    # Java Properties uses ISO-8859-1 plus UTF-16 escapes, including surrogate pairs.
    encoded = value.encode("utf-16-be")
    return "".join(f"\\u{int.from_bytes(encoded[i:i + 2], 'big'):04x}" for i in range(0, len(encoded), 2))


def prepare(environment: dict[str, str]) -> str:
    if environment.get("GITHUB_ACTIONS") != "true":
        raise ValueError("Signing preparation is CI-only")
    required = environment.get("ASCENT_REQUIRE_STABLE_SIGNING", "false").lower()
    if required not in ("", "true", "false"):
        raise ValueError("ASCENT_REQUIRE_STABLE_SIGNING must be true or false")
    credentials = [environment.get(name, "") for name in SECRET_NAMES]
    expected = environment.get("ASCENT_SIGNING_CERT_SHA256", "")
    configured = any(credentials) or bool(expected)
    if configured and not (all(credentials) and expected):
        raise ValueError("Signing configuration is incomplete; refusing a debug-signing downgrade")
    output = Path(environment["GITHUB_ENV"])
    if not configured:
        if required == "true":
            raise ValueError("Stable signing is required but no owner-supplied key is configured")
        with output.open("a", encoding="utf-8") as stream:
            stream.write("ASCENT_SIGNING_MODE=unprotected-debug\n")
        return "unprotected-debug"
    digest = certificate_digest(expected)
    if len(credentials[0]) > 2 * 1024 * 1024:
        raise ValueError("Signing keystore exceeds the supported bound")
    try:
        key = base64.b64decode(credentials[0], validate=True)
    except (ValueError, binascii.Error):
        raise ValueError("Signing keystore must be valid base64") from None
    if not 1 <= len(key) <= 1024 * 1024:
        raise ValueError("Signing keystore is empty or oversized")
    root = Path(environment["RUNNER_TEMP"]).resolve(strict=True)
    directory = Path(tempfile.mkdtemp(prefix="ascent-signing-", dir=root))
    properties = directory / "signing.properties"
    try:
        for path, payload in ((directory / "identity.keystore", key), (properties, (
                "storeFile=" + properties_value(str(directory / "identity.keystore")) + "\n" +
                "storePassword=" + properties_value(credentials[1]) + "\n" +
                "keyAlias=" + properties_value(credentials[2]) + "\n" +
                "keyPassword=" + properties_value(credentials[3]) + "\n").encode("ascii"))):
            descriptor = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
            with os.fdopen(descriptor, "wb") as stream:
                stream.write(payload)
        # Only paths/public identity enter GITHUB_ENV; passwords remain in mode-0600 files.
        with output.open("a", encoding="utf-8") as stream:
            stream.write(f"ASCENT_SIGNING_PROPERTIES={properties}\nASCENT_SIGNING_MODE=protected-key\n"
                         f"ASCENT_EXPECTED_CERT_SHA256={digest}\n")
    except BaseException:
        for path in (properties, directory / "identity.keystore"):
            path.unlink(missing_ok=True)
        directory.rmdir()
        raise
    return "protected-key"


def cleanup(environment: dict[str, str]) -> None:
    if not environment.get("ASCENT_SIGNING_PROPERTIES"):
        return
    path = Path(environment["ASCENT_SIGNING_PROPERTIES"])
    root = Path(environment["RUNNER_TEMP"]).resolve(strict=True)
    directory = path.parent
    if (path.name != "signing.properties" or directory.parent != root or
            not directory.name.startswith("ascent-signing-") or directory.is_symlink()):
        raise ValueError("Refusing cleanup outside the owned CI signing directory")
    for owned in (path, directory / "identity.keystore"):
        owned.unlink(missing_ok=True)
    directory.rmdir()


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("action", choices=("prepare", "cleanup"))
    args = parser.parse_args()
    try:
        if args.action == "prepare":
            print(f"CI signing mode: {prepare(dict(os.environ))}; not release acceptance")
        else:
            cleanup(dict(os.environ))
    except (ValueError, KeyError, OSError):
        # Do not include exception values, keystore paths, passwords or tool output.
        raise SystemExit("CI signing preparation/cleanup failed; check the complete protected configuration") from None
