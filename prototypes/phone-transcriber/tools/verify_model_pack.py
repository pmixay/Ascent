#!/usr/bin/env python3
"""Verify an exported model pack; no ML dependencies or network required."""
import argparse
from pathlib import Path

from model_pack import validate_zip

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("pack", type=Path)
args = parser.parse_args()
manifest = validate_zip(args.pack)
print(f"Verified {manifest['model_id']} ({len(manifest['files'])} hashed files)")
