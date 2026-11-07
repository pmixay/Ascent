#!/usr/bin/env python3
"""Wait for a push workflow on one exact commit, printing only state changes.

GitHub's REST API has no blocking wait endpoint. Conditional requests and bounded
backoff replace manual polling; a failed/cancelled run never satisfies success.
Authentication comes only from GH_TOKEN or GITHUB_TOKEN, never command arguments.
"""
from __future__ import annotations

import argparse
import json
import math
import os
from pathlib import Path
import re
import time
import urllib.error
import urllib.parse
import urllib.request


def matching_run(runs: list[dict], sha: str) -> dict | None:
    return max((run for run in runs if run.get("head_sha") == sha and run.get("event") == "push"),
               key=lambda run: run["id"], default=None)


def result_code(run: dict, jobs: list[dict], required: list[str]) -> int:
    if run.get("status") != "completed" or run.get("conclusion") != "success":
        return 1
    passed = {job.get("name") for job in jobs if job.get("status") == "completed" and job.get("conclusion") == "success"}
    return 0 if set(required) <= passed else 1


class GitHub:
    def __init__(self, repo: str, token: str | None):
        self.base = f"https://api.github.com/repos/{repo}/"
        self.headers = {"Accept": "application/vnd.github+json", "User-Agent": "Ascent-CI-waiter"}
        if token:
            self.headers["Authorization"] = f"Bearer {token}"
        self.cached: dict[str, tuple[str | None, dict]] = {}
        self.poll_interval = 0.0

    def get(self, path: str, timeout: float) -> dict:
        headers = dict(self.headers)
        previous = self.cached.get(path)
        if previous and previous[0]:
            headers["If-None-Match"] = previous[0]
        try:
            with urllib.request.urlopen(urllib.request.Request(self.base + path, headers=headers), timeout=timeout) as response:
                data = json.load(response)
                self.poll_interval = max(self.poll_interval, float(response.headers.get("X-Poll-Interval", "0")))
                self.cached[path] = (response.headers.get("ETag"), data)
                return data
        except urllib.error.HTTPError as error:
            if error.code == 304 and previous:
                self.poll_interval = max(self.poll_interval, float(error.headers.get("X-Poll-Interval", "0")))
                return previous[1]
            raise


def wait(client: GitHub, workflow: str, sha: str, timeout: float, interval: float,
         required: list[str], output: Path | None = None) -> int:
    deadline = time.monotonic() + timeout
    delay = interval
    previous = None
    path = f"actions/workflows/{urllib.parse.quote(workflow, safe='')}/runs?event=push&head_sha={sha}&per_page=100"
    while time.monotonic() < deadline:
        remaining = deadline - time.monotonic()
        try:
            data = client.get(path, min(30, max(1, remaining)))
            run = matching_run(data.get("workflow_runs", []), sha)
            state = (run.get("id"), run.get("status"), run.get("conclusion")) if run else (None, "waiting for push run", None)
            if state != previous:
                print(f"{sha[:8]}: run={state[0]} status={state[1]} conclusion={state[2]}", flush=True)
                previous = state
                delay = interval
            else:
                delay = min(60, delay * 1.5)
            if run and run.get("status") == "completed":
                jobs = client.get(f"actions/runs/{run['id']}/jobs?per_page=100", min(30, max(1, deadline - time.monotonic()))).get("jobs", [])
                result = {key: run.get(key) for key in ("id", "head_sha", "html_url", "status", "conclusion", "run_attempt")}
                result["jobs"] = [{key: job.get(key) for key in ("id", "name", "status", "conclusion")} for job in jobs]
                if output:
                    output.parent.mkdir(parents=True, exist_ok=True)
                    temporary = output.with_name(output.name + ".tmp")
                    temporary.write_text(json.dumps(result, indent=2) + "\n")
                    temporary.replace(output)
                print(json.dumps(result), flush=True)
                return result_code(run, jobs, required)
        except urllib.error.HTTPError as error:
            # Retry transient infrastructure/rate limits without exposing response bodies.
            rate_limited = error.code == 403 and (error.headers.get("Retry-After") or error.headers.get("X-RateLimit-Remaining") == "0")
            if not rate_limited and error.code not in (429, 500, 502, 503, 504):
                print(f"GitHub request failed: HTTP {error.code}", flush=True)
                return 2
            retry = error.headers.get("Retry-After", "60")
            delay = max(delay, float(retry) if retry.isdigit() else 60)
            if error.headers.get("X-RateLimit-Remaining") == "0":
                delay = max(delay, float(error.headers.get("X-RateLimit-Reset", "0")) - time.time() + 1)
            print(f"GitHub HTTP {error.code}; backing off", flush=True)
        except (urllib.error.URLError, TimeoutError):
            delay = min(60, max(interval, delay * 1.5))
            print("Transient GitHub connection failure; backing off", flush=True)
        except (ValueError, OSError):
            print("Invalid GitHub response or unable to write result", flush=True)
            return 2
        time.sleep(min(max(delay, client.poll_interval), max(0, deadline - time.monotonic())))
    print(f"Timed out waiting for {sha}; no success is inferred.", flush=True)
    return 2


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repo", required=True, help="GitHub owner/repository")
    parser.add_argument("--sha", required=True, help="Full 40-character commit SHA")
    parser.add_argument("--workflow", default="android.yml")
    parser.add_argument("--timeout", type=float, default=2700)
    parser.add_argument("--interval", type=float, default=20)
    parser.add_argument("--require-job", action="append", default=[])
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()
    if not re.fullmatch(r"[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+", args.repo) or not re.fullmatch(r"[0-9a-f]{40}", args.sha):
        parser.error("Use owner/repository and a full lowercase commit SHA")
    if not math.isfinite(args.timeout) or args.timeout <= 0 or not 5 <= args.interval <= 60:
        parser.error("Timeout must be positive; interval must be between 5 and 60 seconds")
    return wait(GitHub(args.repo, os.environ.get("GH_TOKEN") or os.environ.get("GITHUB_TOKEN")),
                args.workflow, args.sha, args.timeout, args.interval, args.require_job, args.output)


if __name__ == "__main__":
    raise SystemExit(main())
