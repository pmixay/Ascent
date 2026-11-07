#!/usr/bin/env python3
"""CI-only: verify the actual APK signature/package and print content-free identity.

Run after check_apk.py and before artifact upload. No new artifact/report upload,
inference or signing is performed; apksigner/aapt2 come from CI's existing SDK.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import zipfile

from ci_signing import certificate_digest

PREFIX = "ASCENT_APK_IDENTITY "


class IdentityError(ValueError):
    """A content-free verification reason safe to expose in CI."""


def identity(apk: Path, kind: str, badging: str, signing: str, environment: dict[str, str]) -> dict:
    if environment.get("GITHUB_ACTIONS") != "true":
        raise IdentityError("APK identity recording is CI-only")
    sha = environment.get("GITHUB_SHA", "")
    run = environment.get("GITHUB_RUN_ID", "")
    attempt = environment.get("GITHUB_RUN_ATTEMPT", "")
    number = environment.get("GITHUB_RUN_NUMBER", "")
    repository = environment.get("GITHUB_REPOSITORY", "")
    if (not re.fullmatch(r"[0-9a-f]{40}", sha) or
            not re.fullmatch(r"[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+", repository) or
            any(not re.fullmatch(r"[1-9][0-9]*", value) for value in (run, attempt, number))):
        raise IdentityError("Missing CI revision/run identity")
    if kind not in ("compact", "offline"):
        raise IdentityError("Unsupported artifact kind")
    package = re.search(r"^package: name='([^']+)' versionCode='([0-9]+)' versionName='([^']+)'", badging, re.M)
    expected_package = "com.pmixay.ascent" + (".offline" if kind == "offline" else "")
    expected_version = f"0.1.{number}-{sha[:8]}" + ("-offline-baseline" if kind == "offline" else "")
    if not package or package.groups() != (expected_package, number, expected_version):
        raise IdentityError("APK package/version does not match the exact CI source/run")
    if re.search(r"^application-debuggable", badging, re.M):
        raise IdentityError("Downloadable APK must remain non-debuggable")
    native = re.search(r"^native-code: (.+)$", badging, re.M)
    if not native or native[1].strip() != "'arm64-v8a'":
        raise IdentityError("Downloadable APK must be ARM64-only")
    certificates = re.findall(r"^Signer #[0-9]+ certificate SHA-256 digest: ([0-9a-fA-F:]+)$", signing, re.M)
    if len(certificates) != 1:
        raise IdentityError("APK must have one verified signer")
    certificate = certificate_digest(certificates[0])
    mode = environment.get("ASCENT_SIGNING_MODE")
    if mode == "protected-key":
        if certificate != certificate_digest(environment.get("ASCENT_EXPECTED_CERT_SHA256", "")):
            raise IdentityError("APK signer differs from the independently pinned certificate")
    elif mode != "unprotected-debug" or environment.get("ASCENT_EXPECTED_CERT_SHA256"):
        raise IdentityError("Missing or inconsistent signing mode")
    if environment.get("ASCENT_REQUIRE_STABLE_SIGNING", "").lower() == "true" and mode != "protected-key":
        raise IdentityError("Stable signing required; debug identity cannot be promoted")
    with apk.open("rb") as stream:
        digest = hashlib.file_digest(stream, "sha256").hexdigest()
    result = {"schema_version": 1, "kind": kind, "source_commit": sha, "repository": repository,
              "run_id": run, "run_attempt": attempt, "package": package[1], "version_code": int(number),
              "version_name": package[3], "apk_bytes": apk.stat().st_size, "apk_sha256": digest,
              "certificate_sha256": certificate, "signing_mode": mode, "abi": "arm64-v8a",
              "release_accepted": False}
    with zipfile.ZipFile(apk) as archive:
        if kind == "offline":
            item = archive.getinfo("assets/models/bundle.json")
            if not 0 < item.file_size <= 128 * 1024:
                raise IdentityError("Invalid embedded manifest bound")
            content = archive.read(item)
            bundle = json.loads(content)
            result.update(bundle_sha256=hashlib.sha256(content).hexdigest(),
                          source_pin_sha256=bundle["source_pin_sha256"],
                          expanded_payload_bytes=sum(record["bytes"] for record in bundle["files"].values()),
                          model_files=bundle["files"], export_validation=bundle["export_validation"])
        elif any(name.startswith("assets/models/") for name in archive.namelist()):
            raise IdentityError("Compact identity must not contain embedded models")
    return result


def record(apk: Path, kind: str, build_tools: Path, environment: dict[str, str]) -> dict:
    if environment.get("GITHUB_ACTIONS") != "true":
        raise IdentityError("APK identity recording is CI-only")
    def tool(name: str, arguments: list[str]) -> str:
        process = subprocess.run([str(build_tools / name), *arguments, str(apk)],
                                 capture_output=True, text=True, timeout=180)
        if process.returncode:
            raise IdentityError(f"{name} verification failed")  # Never print tool stderr or signer DN.
        return process.stdout
    badging = tool("aapt2", ["dump", "badging"])
    signing = tool("apksigner", ["verify", "--verbose", "--print-certs"])
    result = identity(apk, kind, badging, signing, environment)
    print(PREFIX + json.dumps(result, sort_keys=True, separators=(",", ":")))
    if environment.get("GITHUB_STEP_SUMMARY"):
        with Path(environment["GITHUB_STEP_SUMMARY"]).open("a", encoding="utf-8") as summary:
            summary.write(f"### Checked {kind} ARM64 APK (internal candidate, not acceptance)\n\n"
                          f"- Source: `{result['source_commit']}`\n"
                          f"- Version: `{result['version_name']}` / `{result['version_code']}`\n"
                          f"- APK: {result['apk_bytes']:,} bytes; SHA-256 `{result['apk_sha256']}`\n"
                          f"- Certificate SHA-256: `{result['certificate_sha256']}` ({result['signing_mode']})\n"
                          f"- Embedded bundle: `{result.get('bundle_sha256', 'none')}`\n\n"
                          "Both workflow jobs and independent exact-build ARM evidence are still required.\n")
    return result


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--apk", type=Path, required=True)
    parser.add_argument("--kind", choices=("compact", "offline"), required=True)
    parser.add_argument("--build-tools", type=Path, required=True)
    args = parser.parse_args()
    try:
        record(args.apk, args.kind, args.build_tools, dict(os.environ))
    except IdentityError as error:
        raise SystemExit(f"APK identity verification failed: {error}; no verified identity was emitted") from None
    except (ValueError, KeyError, OSError, subprocess.TimeoutExpired, zipfile.BadZipFile):
        raise SystemExit("APK identity verification failed; no verified identity was emitted") from None
