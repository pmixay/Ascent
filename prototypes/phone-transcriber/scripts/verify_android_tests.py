#!/usr/bin/env python3
"""Require actual non-skipped instrumentation cases, not just Gradle success.

Content-free console output only; never upload reports as artifacts.
"""
from __future__ import annotations

import argparse
from pathlib import Path
import xml.etree.ElementTree as ET


def verify(root: Path, required: list[str]) -> int:
    if not required:
        raise ValueError("At least one required instrumentation case must be declared")
    found = set()
    executed = set()
    for path in sorted(root.rglob("*.xml")):
        document = ET.parse(path)
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
        raise ValueError(f"Required instrumentation cases did not execute successfully: {sorted(missing)}; skipped={sorted(skipped)}")
    print(f"Verified instrumentation XML: {len(executed)} distinct executed cases; {len(required)} required cases passed without skips.")
    return len(executed)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("root", type=Path)
    parser.add_argument("--require", action="append", required=True, dest="required",
                        help="Exact original test class#method that must pass without skipping.")
    args = parser.parse_args()
    verify(args.root, args.required)
