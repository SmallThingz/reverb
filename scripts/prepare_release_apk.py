#!/usr/bin/env python3
"""Recompress an assembled, unsigned private APK before signing."""
from __future__ import annotations

import argparse
import json
import os
from pathlib import Path
import re
import struct
import tempfile
import zipfile

from verify_release import VCS_ENTRY, file_sha256, require, run, verify_revision

DEFLATE_HINTS = 0x06
JAR_SIGNATURE = re.compile(r"META-INF/(?:MANIFEST\.MF|[^/]+\.(?:SF|RSA|DSA|EC))", re.IGNORECASE)
ENTRY_METADATA = ("CRC", "file_size", "compress_type", "date_time", "extra", "comment",
                  "create_system", "create_version", "extract_version", "internal_attr", "external_attr", "volume", "reserved")


def check_unsigned(path: Path, archive: zipfile.ZipFile) -> None:
    names = archive.namelist()
    require(len(names) == len(set(names)), "Duplicate ZIP entries")
    require(not any(JAR_SIGNATURE.fullmatch(name) for name in names), "APK already carries a JAR signature")
    with path.open("rb") as stream:
        stream.seek(max(0, archive.start_dir - 16))
        require(stream.read(16) != b"APK Sig Block 42", "APK already carries an APK Signing Block")


def verify_repack(original: Path, candidate: Path) -> dict[str, int]:
    with zipfile.ZipFile(original) as before, zipfile.ZipFile(candidate) as after:
        check_unsigned(original, before)
        check_unsigned(candidate, after)
        require(before.comment == after.comment and before.namelist() == after.namelist(),
                "APK ZIP inventory or archive comment changed")
        require("classes.dex" in before.namelist(), "Missing classes.dex")

        changed_hints = 0
        for old, new in zip(before.infolist(), after.infolist()):
            for field in ENTRY_METADATA:
                require(getattr(old, field) == getattr(new, field), f"{old.filename}: ZIP {field} changed")
            if old.filename == "resources.arsc" or (old.filename.startswith("lib/") and old.filename.endswith(".so")):
                require(old.compress_type == zipfile.ZIP_STORED, f"{old.filename}: expected a stored entry")
            allowed_flags = DEFLATE_HINTS if old.compress_type == zipfile.ZIP_DEFLATED else 0
            require((old.flag_bits ^ new.flag_bits) & ~allowed_flags == 0,
                    f"{old.filename}: non-DEFLATE-hint ZIP flags changed")
            require(before.read(old) == after.read(new), f"{old.filename}: decoded payload changed")
            changed_hints += old.flag_bits != new.flag_bits

        return {"entries": len(before.infolist()), "deflate_hint_changes": changed_hints,
                "original_dex_packed_bytes": before.getinfo("classes.dex").compress_size,
                "candidate_dex_packed_bytes": after.getinfo("classes.dex").compress_size}


def prepare(
    original: Path, output: Path, zipalign: Path, source_revision: str,
    input_sha: str, zipalign_sha: str, expected_output_sha: str | None = None,
) -> dict[str, object]:
    require(not os.path.lexists(output), "Candidate output already exists")
    require(re.fullmatch(r"[0-9a-f]{40}", source_revision) is not None, "Expected a full source commit")
    require(file_sha256(original) == input_sha, "Input APK SHA-256 does not match")
    require(file_sha256(zipalign) == zipalign_sha, "zipalign SHA-256 does not match")
    with zipfile.ZipFile(original) as archive:
        check_unsigned(original, archive)
        require(VCS_ENTRY in archive.namelist(), "Input APK source revision metadata is missing")
        verify_revision(archive.read(VCS_ENTRY).decode("utf-8"), source_revision)

    with tempfile.TemporaryDirectory(prefix=".reverb-unsigned-", dir=output.parent) as temporary:
        candidate = Path(temporary) / "aligned.apk"
        run([str(zipalign), "-z", "-P", "16", "4", str(original), str(candidate)], output.parent)
        stats = verify_repack(original, candidate)
        run([str(zipalign), "-c", "-P", "16", "4", str(candidate)], output.parent)

        output_sha = file_sha256(candidate)
        require(expected_output_sha is None or output_sha == expected_output_sha,
                "Candidate SHA-256 does not match the reviewed recipe")
        require(file_sha256(original) == input_sha, "Input APK changed during preparation")
        require(file_sha256(zipalign) == zipalign_sha, "zipalign changed during preparation")
        size = candidate.stat().st_size
        saved = original.stat().st_size - size
        # Same-directory staging allows publishing the verified file without ever replacing an existing path.
        candidate.chmod(0o600)
        os.link(candidate, output)

    return {"source_revision": source_revision, "input_sha256": input_sha, "zipalign_sha256": zipalign_sha,
            "output_sha256": output_sha, "output_bytes": size, "saved_bytes": saved, **stats}


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("unsigned_apk", type=Path)
    parser.add_argument("output", type=Path, help="New unsigned path for the existing private signer")
    parser.add_argument("--zipalign", type=Path, required=True)
    parser.add_argument("--source-revision", required=True)
    parser.add_argument("--input-sha256", required=True)
    parser.add_argument("--zipalign-sha256", required=True)
    parser.add_argument("--expected-output-sha256")
    args = parser.parse_args()
    try:
        result = prepare(args.unsigned_apk.absolute(), args.output.absolute(), args.zipalign.absolute(),
                         args.source_revision, args.input_sha256, args.zipalign_sha256, args.expected_output_sha256)
    except (OSError, ValueError, struct.error, zipfile.BadZipFile) as exc:
        parser.exit(2, f"Unsigned APK preparation failed: {exc}\n")
    print(json.dumps(result, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
