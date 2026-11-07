#!/usr/bin/env python3
"""Require actual non-skipped instrumentation cases, not just Gradle success.

Content-free console output only; never upload reports as artifacts.
"""
from __future__ import annotations

import argparse
from collections import Counter
from pathlib import Path
import subprocess
import xml.etree.ElementTree as ET
import zipfile


def verify(root: Path, required: list[str]) -> int:
    if not required:
        raise ValueError("At least one required instrumentation case must be declared")
    found = set()
    executed = set()
    reports = sorted(root.rglob("*.xml"))
    print(f"Instrumentation result root: {root}; XML reports: {len(reports)}", flush=True)
    for path in reports:
        document = ET.parse(path)
        print(f"Instrumentation report: {path.relative_to(root)}", flush=True)
        print(f"XML element counts: {dict(Counter(element.tag for element in document.iter()))}", flush=True)
        for suite in document.iter("testsuite"):
            print(f"Suite counters: { {key: suite.get(key) for key in ('tests', 'failures', 'errors', 'skipped', 'time')} }", flush=True)
        for case in document.iter("testcase"):
            key = f"{case.get('classname', '')}#{case.get('name', '')}"
            found.add(key)
            if case.find("failure") is not None or case.find("error") is not None:
                raise ValueError(f"Instrumentation test failed: {key}")
            if case.find("skipped") is None:
                executed.add(key)
    missing = set(required) - executed
    if missing:
        skipped = missing & found
        # Infrastructure-only identities; never dump captured stdout or app content.
        print(f"Discovered instrumentation cases: {sorted(found)}", flush=True)
        raise ValueError(f"Required instrumentation cases did not execute successfully: {sorted(missing)}; skipped={sorted(skipped)}")
    print(f"Verified instrumentation XML: {len(executed)} distinct executed cases; {len(required)} required cases passed without skips.")
    return len(executed)


def diagnose_device(required: list[str]) -> None:
    """CI-only synthetic-test diagnostics; no app/corpus report contents or artifacts."""
    classes = {key.split("#", 1)[0] for key in required}
    for path in Path("app/build/outputs/apk/androidTest").rglob("*.apk"):
        with zipfile.ZipFile(path) as archive:
            dex = [archive.read(name) for name in archive.namelist() if name.endswith(".dex")]
            print(f"Test APK: {path}; dex files: {len(dex)}", flush=True)
            for name in sorted(classes):
                descriptor = ("L" + name.replace(".", "/") + ";").encode()
                print(f"Test descriptor {name}: present={any(descriptor in data for data in dex)}", flush=True)
    # The emulator holds only CI synthetic fixtures, never user audio or history.
    for command in (["adb", "shell", "pm", "list", "instrumentation"],
                    ["adb", "logcat", "-d", "-s", "AndroidRuntime:E", "TestRunner:I", "AndroidJUnitRunner:V"]):
        print(f"Instrumentation diagnostics: {' '.join(command)}", flush=True)
        try:
            subprocess.run(command, timeout=30, check=False)
        except (OSError, subprocess.TimeoutExpired) as error:
            print(f"Diagnostic unavailable: {type(error).__name__}", flush=True)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("root", type=Path)
    parser.add_argument("--require", action="append", required=True, dest="required",
                        help="Exact original test class#method that must pass without skipping.")
    parser.add_argument("--diagnose-device", action="store_true", help="CI only: inspect test APK and runner logs on missing evidence.")
    args = parser.parse_args()
    try:
        verify(args.root, args.required)
    except (ValueError, ET.ParseError):
        if args.diagnose_device:
            diagnose_device(args.required)
        raise
