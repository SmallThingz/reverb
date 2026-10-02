#!/usr/bin/env python3
"""Verify a release APK against its clean, exact tagged source before publication."""
from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path
import re
import struct
import subprocess
import sys
import zipfile

PACKAGE = "app.smallthingz.reverb"
SIGNER = "3866bc43d3dd58bc0cfa9c716a93aaa6f34abdfaa7315a3b924079397d1decfe"
VCS_ENTRY = "META-INF/version-control-info.textproto"
ALLOWED_BLOCKS = {0x7109871A, 0xF05368C0, 0x1B93AD61, 0x42726577}


def require(condition: bool, message: str) -> None:
    if not condition:
        raise ValueError(message)


def verify_revision(text: str, expected: str) -> None:
    revisions = re.findall(r'^\s*revision:\s*"([0-9a-f]+)"\s*$', text, re.MULTILINE)
    require(revisions == [expected], f"APK VCS revision does not match tag: {revisions}")
    require('local_root_path: "$PROJECT_DIR"' in text, "APK contains unexpected VCS root metadata")


def signing_block_ids(apk: Path) -> set[int]:
    """Read the actual APK Signing Block, including non-ZIP dependency metadata."""
    with apk.open('rb') as f:
        f.seek(0, 2)
        length = f.tell()
        require(length >= 22, "APK is truncated")
        f.seek(max(0, length - 65557))
        tail = f.read()
        eocd = tail.rfind(b'PK\x05\x06')
        require(eocd >= 0 and eocd + 22 <= len(tail), "Missing ZIP end record")
        comment_length = struct.unpack_from('<H', tail, eocd + 20)[0]
        require(eocd + 22 + comment_length == len(tail), "Invalid ZIP end record")
        cd_size, cd_offset = struct.unpack_from('<II', tail, eocd + 12)
        require(cd_offset + cd_size == length - len(tail) + eocd, "Invalid ZIP directory geometry")
        require(cd_offset >= 32, "Missing APK Signing Block")
        f.seek(cd_offset - 24)
        footer = f.read(24)
        block_size = struct.unpack_from('<Q', footer)[0]
        require(footer[8:] == b'APK Sig Block 42', "Missing APK Signing Block footer")
        require(24 <= block_size <= cd_offset - 8, "Invalid APK Signing Block size")
        f.seek(cd_offset - block_size - 8)
        require(struct.unpack('<Q', f.read(8))[0] == block_size, "APK Signing Block sizes differ")
        remaining = block_size - 24
        ids: set[int] = set()
        while remaining:
            require(remaining >= 12, "Truncated APK Signing Block entry")
            size = struct.unpack('<Q', f.read(8))[0]
            require(4 <= size <= remaining - 8, "Invalid APK Signing Block entry size")
            block_id = struct.unpack('<I', f.read(4))[0]
            require(block_id not in ids, "Duplicate APK Signing Block entry")
            ids.add(block_id)
            f.seek(size - 4, 1)
            remaining -= 8 + size
        return ids


def verify_block_policy(ids: set[int]) -> None:
    require(0x7109871A in ids, "APK Signature Scheme v2 is missing")
    require(0x504B4453 not in ids, "F-Droid-rejected AGP dependency metadata is present")
    require(not ids - ALLOWED_BLOCKS, f"Unapproved APK signing blocks: {ids - ALLOWED_BLOCKS}")


def run(args: list[str], root: Path) -> str:
    result = subprocess.run(args, cwd=root, text=True, stdout=subprocess.PIPE,
                            stderr=subprocess.STDOUT, check=False)
    require(result.returncode == 0, f"{args[0]} failed ({result.returncode}):\n{result.stdout}")
    return result.stdout


def verify(tag: str, apk: Path, tools: Path) -> dict[str, object]:
    root = Path(__file__).resolve().parent.parent
    require(re.fullmatch(r'v\d+\.\d+\.\d+(?:-[0-9A-Za-z.-]+)?', tag) is not None,
            "Expected a version tag such as v0.1.2-rc1")
    commit = run(['git', 'rev-parse', f'{tag}^{{commit}}'], root).strip()
    require(run(['git', 'rev-parse', 'HEAD'], root).strip() == commit, "HEAD does not match tag")
    require(not run(['git', 'status', '--porcelain', '--untracked-files=all'], root).strip(),
            "Release source must be clean")
    initial_hash = hashlib.sha256(apk.read_bytes()).hexdigest()
    with zipfile.ZipFile(apk) as z:
        names = z.namelist()
        require(len(names) == len(set(names)), "Duplicate ZIP entries")
        require(VCS_ENTRY in names, "APK VCS metadata is missing")
        verify_revision(z.read(VCS_ENTRY).decode('utf-8'), commit)
        require(z.testzip() is None, "APK ZIP integrity check failed")
    ids = signing_block_ids(apk)
    verify_block_policy(ids)
    signature = run([str(tools / 'apksigner'), 'verify', '--verbose', '--print-certs', str(apk)], root)
    certs = re.findall(r'Signer #\d+ certificate SHA-256 digest: ([0-9a-f]+)', signature)
    require(certs == [SIGNER], f"Unexpected signing certificate: {certs}")
    require('Verified using v2 scheme (APK Signature Scheme v2): true' in signature,
            "APK v2 signature verification failed")
    run([str(tools / 'zipalign'), '-c', '-P', '16', '4', str(apk)], root)
    badging = run([str(tools / 'aapt2'), 'dump', 'badging', str(apk)], root)
    info = re.search(r"^package: name='([^']+)' versionCode='([^']+)' versionName='([^']+)'",
                     badging, re.MULTILINE)
    require(info is not None, "Missing APK package/version metadata")
    package, code, name = info.groups()
    require(package == PACKAGE, f"Unexpected package: {package}")
    require(name == tag[1:], f"APK version {name} does not match {tag}")
    source = (root / 'app/build.gradle').read_text()
    expected_code = re.search(r'^def appVersionCode = (\d+)$', source, re.MULTILINE)
    require(expected_code is not None and code == expected_code.group(1), "APK version code differs from source")
    require('application-debuggable' not in badging, "Release APK is debuggable")
    require('android.permission.INTERNET' not in badging, "Unexpected network permission")
    require(run(['git', 'rev-parse', 'HEAD'], root).strip() == commit, "HEAD changed during verification")
    require(not run(['git', 'status', '--porcelain', '--untracked-files=all'], root).strip(),
            "Source changed during verification")
    require(hashlib.sha256(apk.read_bytes()).hexdigest() == initial_hash, "APK changed during verification")
    return {'tag': tag, 'commit': commit, 'package': package, 'versionName': name,
            'versionCode': int(code), 'sha256': initial_hash, 'signerSha256': SIGNER,
            'signingBlocks': [f'0x{x:08x}' for x in sorted(ids)], 'zipalign16KiB': True,
            'vcsRevisionMatchesTag': True, 'debuggable': False, 'networkPermission': False}


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('tag')
    parser.add_argument('apk', type=Path)
    parser.add_argument('--build-tools', required=True, type=Path)
    args = parser.parse_args()
    try:
        result = verify(args.tag, args.apk.resolve(), args.build_tools.resolve())
    except (OSError, ValueError, zipfile.BadZipFile) as exc:
        print(f'Release verification failed: {exc}', file=sys.stderr)
        return 1
    print(json.dumps(result, indent=2))
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
