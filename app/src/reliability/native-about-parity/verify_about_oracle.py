#!/usr/bin/env python3
"""Read-only source pin check; no build, extraction, visibility change, or rewritten oracle."""
import hashlib
import json
import pathlib
import sys
manifest = json.loads(pathlib.Path(__file__).with_name("SOURCE.json").read_text())
root = pathlib.Path(sys.argv[1]).resolve()
for relative, expected in manifest["oracle_files"].items():
    actual = hashlib.sha256((root / relative).read_bytes()).hexdigest()
    if actual != expected:
        raise SystemExit("Released About oracle changed: " + relative)
print("Pinned released About source and five direct drawing/motion dependencies match")
