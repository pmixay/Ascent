"""Independent validator for the hackathon JSON export contract.

This module has no Android or application-code dependency. It deliberately checks
the exported file, rather than the application's internal storage format.
"""

from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path
from typing import Any


ROOT_KEYS = frozenset({"transcript"})
TURN_KEYS = frozenset({"speaker_id", "start_ms", "end_ms", "text"})


class ContractError(ValueError):
    """The exported bytes do not satisfy the jury-facing contract."""


def _unique_object(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
    result: dict[str, Any] = {}
    for key, value in pairs:
        if key in result:
            raise ContractError(f"duplicate JSON key: {key!r}")
        result[key] = value
    return result


def _reject_constant(value: str) -> None:
    raise ContractError(f"non-JSON numeric value: {value}")


def validate_bytes(payload: bytes, duration_ms: int | None = None) -> int:
    """Return turn count after validating JSON fields, types, order and time bounds.

    ``duration_ms`` is the original media duration, when independently known. It
    is optional because JSON export itself does not include media metadata.
    Adjacent or overlapping speech is accepted; only start-time order is required.
    """
    if duration_ms is not None and (type(duration_ms) is not int or duration_ms < 0):
        raise ValueError("duration_ms must be a nonnegative integer")
    try:
        document = json.loads(
            payload.decode("utf-8"),
            object_pairs_hook=_unique_object,
            parse_constant=_reject_constant,
        )
    except (UnicodeDecodeError, json.JSONDecodeError) as error:
        raise ContractError(f"invalid UTF-8 JSON: {error}") from error
    if not isinstance(document, dict) or document.keys() != ROOT_KEYS:
        raise ContractError("root must contain exactly the 'transcript' array")
    turns = document["transcript"]
    if not isinstance(turns, list):
        raise ContractError("'transcript' must be an array")
    last_start = -1
    for index, turn in enumerate(turns):
        prefix = f"transcript[{index}]"
        if not isinstance(turn, dict) or turn.keys() != TURN_KEYS:
            raise ContractError(f"{prefix} must contain exactly {sorted(TURN_KEYS)}")
        speaker_id = turn["speaker_id"]
        text = turn["text"]
        start = turn["start_ms"]
        end = turn["end_ms"]
        if not isinstance(speaker_id, str) or not speaker_id.strip():
            raise ContractError(f"{prefix}.speaker_id must be a nonempty string")
        if not isinstance(text, str) or not text.strip():
            raise ContractError(f"{prefix}.text must be a nonempty string")
        # bool is a subclass of int in Python but is not a JSON integer time.
        if type(start) is not int or type(end) is not int:
            raise ContractError(f"{prefix} times must be integer milliseconds")
        if start < 0 or start >= end:
            raise ContractError(f"{prefix} must satisfy 0 <= start_ms < end_ms")
        if duration_ms is not None and end > duration_ms:
            raise ContractError(f"{prefix}.end_ms exceeds media duration {duration_ms}")
        if start < last_start:
            raise ContractError(f"{prefix}.start_ms precedes the previous turn")
        last_start = start
    return len(turns)


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("json_file", type=Path, help="exported JSON file")
    parser.add_argument("--duration-ms", type=int, help="independently measured original media duration")
    arguments = parser.parse_args(argv)
    try:
        count = validate_bytes(arguments.json_file.read_bytes(), arguments.duration_ms)
    except (OSError, ContractError, ValueError) as error:
        print(f"FAIL {arguments.json_file}: {error}", file=sys.stderr)
        return 1
    print(f"PASS {arguments.json_file}: {count} turn(s); schema, order and time bounds valid")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
