#!/usr/bin/env python3
"""Verify a release APK against its clean, exact tagged source before publication."""
from __future__ import annotations

import argparse
from collections.abc import Iterable
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
NATIVE_LIBRARY = 'libreverb_file_move.so'
# ELF class and e_machine for each shipped Android ABI.
NATIVE_ABIS = {
    'arm64-v8a': (2, 183),
    'armeabi-v7a': (1, 40),
    'riscv64': (2, 243),
    'x86': (1, 3),
    'x86_64': (2, 62),
}
ELF_LAYOUTS = {
    1: (struct.Struct('<HHIIIIIHHHHHH'), struct.Struct('<8I')),
    2: (struct.Struct('<HHIQQQIHHHHHH'), struct.Struct('<IIQQQQQQ')),
}
NATIVE_PAGE_SIZE = 16 * 1024
REQUIRED_ERROR_ALLOCATIONS = {
    b'CatalogFirstPaintUnavailableException',
    b'ConfiguredRecordingDirectoryUnavailableException',
}
ERROR_ALLOCATION = re.compile(
    rb'^[0-9a-f]+:[^|\n]*\|[0-9a-f]+: new-instance v\d+, Lapp/smallthingz/reverb/([^;\n]+);'
)


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
        tail = f.read(min(length, 65557))
        eocd = tail.rfind(b'PK\x05\x06')
        # EOCD magic can occur inside a valid ZIP comment. Only a complete
        # record whose comment ends at EOF and directory ends at the record
        # is a candidate, including when the comment contains a full header.
        while eocd >= 0:
            if eocd + 22 <= len(tail):
                comment_length = struct.unpack_from('<H', tail, eocd + 20)[0]
                if eocd + 22 + comment_length == len(tail):
                    cd_size, cd_offset = struct.unpack_from('<II', tail, eocd + 12)
                    if cd_offset + cd_size == length - len(tail) + eocd:
                        break
            eocd = tail.rfind(b'PK\x05\x06', 0, eocd)
        require(eocd >= 0, "Missing or invalid ZIP end record/directory geometry")
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



def verify_signer_output(signature: str, *, scheme: str = 'v2') -> None:
    require(scheme in ('v2', 'v3'), 'Unsupported signature verification scheme')
    counts = re.findall(r'^Number of signers:\s*(\d+)\s*$', signature, re.MULTILINE)
    require(counts == ['1'], f"Expected exactly one APK signer: {counts}")
    # Build Tools 37 changed "Signer #1" to the scheme-specific "V2 Signer:".
    certs = re.findall(
        r'^(?:Signer #\d+|V\d+(?:\.\d+)? Signer):? certificate SHA-256 digest: ([0-9a-fA-F]{64})\s*$',
        signature, re.MULTILINE,
    )
    require(bool(certs) and {cert.lower() for cert in certs} == {SIGNER},
            f"Unexpected signing certificate: {certs}")
    prefix = f'Verified using {scheme} scheme (APK Signature Scheme {scheme}): '
    require([line[len(prefix):] for line in signature.splitlines() if line.startswith(prefix)] == ['true'],
            f"APK {scheme} signature verification failed")


def run(args: list[str], root: Path) -> str:
    result = subprocess.run(args, cwd=root, text=True, stdout=subprocess.PIPE,
                            stderr=subprocess.STDOUT, check=False)
    require(result.returncode == 0, f"{args[0]} failed ({result.returncode}):\n{result.stdout}")
    return result.stdout


def verify_apk_signatures(apk: Path, tools: Path, root: Path, ids: set[int]) -> None:
    # Verify the supported platform range first. Build Tools 37 may select v3
    # without checking v2 on minSdk 28+, so a separate v2-only range is mandatory.
    signature = run([str(tools / 'apksigner'), 'verify', '--verbose', '--print-certs', str(apk)], root)
    verify_signer_output(signature, scheme='v3' if 0xF05368C0 in ids else 'v2')
    v2_signature = run([str(tools / 'apksigner'), 'verify', '--verbose', '--print-certs',
                        '--min-sdk-version', '24', '--max-sdk-version', '27', str(apk)], root)
    verify_signer_output(v2_signature)


def verify_elf_load_alignment(archive: zipfile.ZipFile, entry: zipfile.ZipInfo, abi: str) -> None:
    """Check the ELF program headers inside the APK, independently of ZIP alignment."""
    name = entry.filename
    elf_class, expected_machine = NATIVE_ABIS[abi]
    elf_header, program_header = ELF_LAYOUTS[elf_class]
    with archive.open(entry) as source:
        ident = source.read(16)
        require(len(ident) == 16 and ident[:4] == b'\x7fELF', f"Invalid ELF identification: {name}")
        require(ident[4:7] == bytes((elf_class, 1, 1)), f"Wrong ELF class, byte order or version: {name}")
        header = source.read(elf_header.size)
        require(len(header) == elf_header.size, f"Truncated ELF header: {name}")
        kind, machine, version, _, phoff, _, _, ehsize, phentsize, phnum, _, _, _ = elf_header.unpack(header)
        require(kind == 3 and machine == expected_machine and version == 1,
                f"Wrong ELF shared object type, machine or version for {abi}: {name}")
        table_size = phnum * phentsize
        word_size = 4 if elf_class == 1 else 8
        # Android's linker requires a naturally aligned program header table of at most 64 KiB.
        require(ehsize == 16 + elf_header.size and phentsize == program_header.size
                and 0 < table_size <= 64 * 1024 and phoff >= ehsize and phoff % word_size == 0
                and phoff + table_size <= entry.file_size,
                f"Invalid ELF program header table: {name}")
        source.seek(phoff)
        has_load = False
        for index in range(phnum):
            data = source.read(phentsize)
            require(len(data) == phentsize, f"Truncated ELF program header: {name}")
            fields = program_header.unpack(data)
            if fields[0] != 1:  # PT_LOAD
                continue
            has_load = True
            if elf_class == 1:
                _, offset, address, _, filesz, memsz, _, alignment = fields
            else:
                _, _, offset, address, _, filesz, memsz, alignment = fields
            segment = f"{name} PT_LOAD header {index}"
            require(alignment >= NATIVE_PAGE_SIZE and (alignment & (alignment - 1)) == 0,
                    f"{segment} alignment {alignment:#x} is not a power of two of at least 16 KiB")
            require((address - offset) % alignment == 0,
                    f"{segment} file offset and virtual address are incongruent")
            require(filesz <= memsz and offset + filesz <= entry.file_size,
                    f"{segment} has invalid file or memory bounds")
        require(has_load, f"ELF has no PT_LOAD segments: {name}")


def verify_native_libraries(archive: zipfile.ZipFile) -> list[str]:
    libraries: dict[str, list[zipfile.ZipInfo]] = {}
    for entry in archive.infolist():
        name = entry.filename
        if not (name.startswith('lib/') and name.endswith('.so')):
            continue
        match = re.fullmatch(r'lib/([^/]+)/([^/]+\.so)', name)
        require(match is not None, f"Unexpected native library path: {name}")
        libraries.setdefault(match[1], []).append(entry)
    missing = sorted(NATIVE_ABIS.keys() - libraries.keys())
    unexpected = sorted(libraries.keys() - NATIVE_ABIS.keys())
    require(not missing and not unexpected, f"Wrong APK native ABIs: missing {missing}, unexpected {unexpected}")
    for abi, entries in libraries.items():
        required = f'lib/{abi}/{NATIVE_LIBRARY}'
        require(sum(entry.filename == required for entry in entries) == 1,
                f"Expected exactly one required native library: {required}")
        for entry in entries:
            require(entry.compress_type == zipfile.ZIP_STORED,
                    f"Native library must be stored for ZIP page alignment: {entry.filename}")
            verify_elf_load_alignment(archive, entry, abi)
    return sorted(libraries)


def verify_runtime_error_allocations(apk: Path, tools: Path, root: Path) -> None:
    """Require both concrete error identities in actual DEX allocation instructions."""
    # Dexdump includes modified UTF-8 DEX strings. Stream its bytes without decoding the dump.
    with subprocess.Popen([str(tools / 'dexdump'), '-d', str(apk)], cwd=root,
                          stdout=subprocess.PIPE) as dump:
        allocated = error_allocation_types(dump.stdout)
        status = dump.wait()
    require(status == 0, f"dexdump failed while checking runtime error allocations ({status})")
    missing = sorted(name.decode('ascii') for name in REQUIRED_ERROR_ALLOCATIONS - allocated)
    require(not missing, f"Optimized APK lost runtime error allocations: {', '.join(missing)}")


def error_allocation_types(lines: Iterable[bytes]) -> set[bytes]:
    return {match.group(1) for line in lines if (match := ERROR_ALLOCATION.match(line))}


def verify_source_version(source: str, name: str, code: str) -> None:
    """Keep the version literal and unambiguous for F-Droid's static Gradle parser."""
    codes = re.findall(r'^\s*versionCode\s+(\d+)\s*$', source, re.MULTILINE)
    names = re.findall(r'^\s*versionName\s+"([^"\n]+)"\s*$', source, re.MULTILINE)
    require(codes == [code], "APK version code differs from literal F-Droid-readable source")
    require(names == [name], "APK version name differs from literal F-Droid-readable source")


def file_sha256(path: Path) -> str:
    """Hash every byte without allocating the whole release APK."""
    digest = hashlib.sha256()
    with path.open('rb') as source:
        while chunk := source.read(1024 * 1024):
            digest.update(chunk)
    return digest.hexdigest()


def verify(tag: str, apk: Path, tools: Path) -> dict[str, object]:
    root = Path(__file__).resolve().parent.parent
    require(re.fullmatch(r'v\d+\.\d+\.\d+(?:-[0-9A-Za-z.-]+)?', tag) is not None,
            "Expected a version tag such as v0.1.2-rc1")
    commit = run(['git', 'rev-parse', f'{tag}^{{commit}}'], root).strip()
    require(run(['git', 'rev-parse', 'HEAD'], root).strip() == commit, "HEAD does not match tag")
    require(not run(['git', 'status', '--porcelain', '--untracked-files=all'], root).strip(),
            "Release source must be clean")
    initial_hash = file_sha256(apk)
    with zipfile.ZipFile(apk) as z:
        names = z.namelist()
        require(len(names) == len(set(names)), "Duplicate ZIP entries")
        require(VCS_ENTRY in names, "APK VCS metadata is missing")
        verify_revision(z.read(VCS_ENTRY).decode('utf-8'), commit)
        require(z.testzip() is None, "APK ZIP integrity check failed")
        native_abis = verify_native_libraries(z)
    ids = signing_block_ids(apk)
    verify_block_policy(ids)
    verify_apk_signatures(apk, tools, root, ids)
    run([str(tools / 'zipalign'), '-c', '-P', '16', '4', str(apk)], root)
    badging = run([str(tools / 'aapt2'), 'dump', 'badging', str(apk)], root)
    info = re.search(r"^package: name='([^']+)' versionCode='([^']+)' versionName='([^']+)'",
                     badging, re.MULTILINE)
    require(info is not None, "Missing APK package/version metadata")
    package, code, name = info.groups()
    require(package == PACKAGE, f"Unexpected package: {package}")
    require(name == tag[1:], f"APK version {name} does not match {tag}")
    source = (root / 'app/build.gradle').read_text()
    verify_source_version(source, name, code)
    require('application-debuggable' not in badging, "Release APK is debuggable")
    require('android.permission.INTERNET' not in badging, "Unexpected network permission")
    verify_runtime_error_allocations(apk, tools, root)
    require(run(['git', 'rev-parse', 'HEAD'], root).strip() == commit, "HEAD changed during verification")
    require(not run(['git', 'status', '--porcelain', '--untracked-files=all'], root).strip(),
            "Source changed during verification")
    require(file_sha256(apk) == initial_hash, "APK changed during verification")
    return {'tag': tag, 'commit': commit, 'package': package, 'versionName': name,
            'versionCode': int(code), 'sha256': initial_hash, 'signerSha256': SIGNER,
            'signingBlocks': [f'0x{x:08x}' for x in sorted(ids)], 'zipalign16KiB': True,
            'nativeAbis': native_abis, 'elfLoadAlign16KiB': True,
            'vcsRevisionMatchesTag': True, 'debuggable': False, 'networkPermission': False,
            'runtimeErrorAllocations': True}


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
