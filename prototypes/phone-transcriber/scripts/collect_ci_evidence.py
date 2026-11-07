#!/usr/bin/env python3
"""Read exact-run CI evidence without downloading APKs, weights or application data.

Requires the structured APK identity emitted by the current Android workflow.
An earlier green run or artifact alone never qualifies. This is read-only GitHub
inspection, not an Android build/test or release-promotion operation.
"""
from __future__ import annotations

import argparse
import json
import os
from pathlib import Path
import re
import urllib.error
import urllib.parse
import urllib.request

from record_apk_identity import PREFIX

JOBS = {"Build and validate Android app": ("compact", 25),
        "Build and validate embedded offline baseline": ("offline", 4)}


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, request, fp, code, message, headers, newurl):
        return None


class GitHub:
    def __init__(self, repository: str, token: str | None):
        self.base = f"https://api.github.com/repos/{repository}/"
        self.headers = {"Accept": "application/vnd.github+json", "User-Agent": "Ascent-CI-evidence"}
        if token:
            self.headers["Authorization"] = "Bearer " + token
        self.opener = urllib.request.build_opener(NoRedirect())

    def get(self, path: str) -> dict:
        with self.opener.open(urllib.request.Request(self.base + path, headers=self.headers), timeout=40) as response:
            content = response.read(4 * 1024 * 1024 + 1)
        if len(content) > 4 * 1024 * 1024:
            raise ValueError("GitHub metadata exceeds inspection bound")
        return json.loads(content)

    def logs(self, job_id: int) -> str:
        try:
            response = self.opener.open(urllib.request.Request(
                self.base + f"actions/jobs/{job_id}/logs", headers=self.headers), timeout=40)
        except urllib.error.HTTPError as error:
            if error.code != 302:
                raise
            location = error.headers["Location"]
            target = urllib.parse.urlsplit(location)
            if target.scheme != "https" or not target.hostname or target.username or target.password:
                raise ValueError("Refusing unsafe log redirect") from None
            # Signed blob requests must NEVER receive the GitHub bearer credential.
            # Reject further redirects rather than accidentally permitting a TLS downgrade.
            response = self.opener.open(urllib.request.Request(location, headers={
                "User-Agent": "Ascent-CI-evidence"}), timeout=60)
        with response:
            content = response.read(32 * 1024 * 1024 + 1)
        if len(content) > 32 * 1024 * 1024:
            raise ValueError("GitHub log exceeds inspection bound")
        return content.decode("utf-8", errors="strict")


def inspect(client: GitHub, repository: str, sha: str) -> dict:
    runs = client.get(f"actions/workflows/android.yml/runs?event=push&head_sha={sha}&per_page=100")
    run = max((item for item in runs.get("workflow_runs", []) if item.get("head_sha") == sha
               and item.get("event") == "push"), key=lambda item: item["id"], default=None)
    if not run or run.get("status") != "completed" or run.get("conclusion") != "success":
        raise ValueError("No completed successful exact-commit push run")
    run_id, attempt = run["id"], run["run_attempt"]
    job_data = client.get(f"actions/runs/{run_id}/attempts/{attempt}/jobs?per_page=100")
    jobs = job_data.get("jobs", [])
    if job_data.get("total_count", len(jobs)) != len(jobs):
        raise ValueError("Incomplete job inventory")
    if any(job.get("status") != "completed" or job.get("conclusion") != "success" for job in jobs):
        raise ValueError("An applicable workflow job did not pass")
    artifacts_data = client.get(f"actions/runs/{run_id}/artifacts?per_page=100")
    artifacts = artifacts_data.get("artifacts", [])
    if artifacts_data.get("total_count", len(artifacts)) != len(artifacts):
        raise ValueError("Incomplete artifact inventory")
    result = {"schema_version": 1, "repository": repository, "source_commit": sha, "run_id": run_id,
              "run_attempt": attempt, "run_url": run["html_url"], "ci_status": "success",
              "release_accepted": False, "candidates": {}}
    for name, (kind, count) in JOBS.items():
        matches = [job for job in jobs if job["name"] == name]
        if len(matches) != 1:
            raise ValueError("Required job is missing or ambiguous")
        job = matches[0]
        log = client.logs(job["id"])
        identities = [json.loads(line.split(PREFIX, 1)[1]) for line in log.splitlines() if PREFIX in line]
        if len(identities) != 1:
            raise ValueError("Expected one structured checked-APK identity per job")
        record = identities[0]
        if (record.get("schema_version") != 1 or record.get("source_commit") != sha or
                record.get("kind") != kind or record.get("repository") != repository or
                record.get("run_id") != str(run_id) or record.get("run_attempt") != str(attempt) or
                record.get("release_accepted") is not False or record.get("abi") != "arm64-v8a"):
            raise ValueError("APK evidence does not match the exact run/commit/kind")
        for field in ("apk_sha256", "certificate_sha256"):
            if not re.fullmatch(r"[0-9a-f]{64}", record.get(field, "")):
                raise ValueError("Invalid APK/signature digest")
        if type(record.get("apk_bytes")) is not int or record["apk_bytes"] <= 0:
            raise ValueError("Missing APK byte count")
        if record.get("signing_mode") not in ("unprotected-debug", "protected-key"):
            raise ValueError("Unknown signing provenance")
        executions = re.findall(r"Verified instrumentation XML: ([0-9]+) distinct executed cases; "
                                r"([0-9]+) required cases passed without skips\.", log)
        if not executions or any(int(value) < count for value in executions[-1]):
            raise ValueError("Required actual instrumentation evidence is missing")
        artifact_name = ("debug-" if kind == "compact" else "offline-baseline-") + sha[:8]
        matches = [artifact for artifact in artifacts if artifact["name"] == artifact_name]
        if len(matches) != 1:
            raise ValueError("Required artifact missing or ambiguous")
        artifact = matches[0]
        if (artifact.get("expired") is not False or artifact.get("workflow_run", {}).get("head_sha") != sha
                or artifact.get("size_in_bytes", 0) <= 0
                or not re.fullmatch(r"sha256:[0-9a-f]{64}", artifact.get("digest", ""))):
            raise ValueError("Artifact expired, unidentified or not bound to the exact source")
        record.update(job_id=job["id"], executed_cases=int(executions[-1][0]),
                      executed_required_cases=int(executions[-1][1]),
                      artifact={key: artifact[key] for key in
                                ("id", "name", "size_in_bytes", "digest", "expires_at")})
        if kind == "offline":
            if not re.fullmatch(r"[0-9a-f]{64}", record.get("bundle_sha256", "")):
                raise ValueError("Missing embedded-bundle identity")
            storage = []
            for line in log.splitlines():
                if "AscentModelStorage: {" in line:
                    sample = json.loads(line.split("AscentModelStorage: ", 1)[1])
                    if (sample.get("source_commit") != sha or sample.get("bundle_sha256") != record["bundle_sha256"]
                            or sample.get("apk_abi") != "x86_64"):
                        raise ValueError("Storage sample belongs to a different build/bundle/ABI")
                    storage.append(sample)
            if len(storage) != 2:
                raise ValueError("Expected fresh and leased-recovery emulator storage records")
            record["x86_64_storage_observations"] = storage
        result["candidates"][kind] = record
    modes = {record["signing_mode"] for record in result["candidates"].values()}
    if len(modes) != 1 or (modes == {"protected-key"} and len({
            record["certificate_sha256"] for record in result["candidates"].values()}) != 1):
        raise ValueError("Compact/offline signing configuration differs")
    return result


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repo", required=True)
    parser.add_argument("--sha", required=True)
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()
    if (not re.fullmatch(r"[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+", args.repo) or
            not re.fullmatch(r"[0-9a-f]{40}", args.sha)):
        parser.error("Use owner/repository and a full lowercase commit SHA")
    try:
        report = inspect(GitHub(args.repo, os.environ.get("GH_TOKEN") or os.environ.get("GITHUB_TOKEN")), args.repo, args.sha)
        content = json.dumps(report, sort_keys=True, indent=2) + "\n"
        if args.output:
            # Never overwrite an earlier evidence record or source file silently.
            with args.output.open("x", encoding="utf-8") as output:
                output.write(content)
        print(content, end="")
    except (ValueError, KeyError, OSError, urllib.error.URLError):
        raise SystemExit("Exact-run evidence collection failed; no release acceptance is inferred") from None
