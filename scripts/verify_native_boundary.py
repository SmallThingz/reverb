#!/usr/bin/env python3
"""Verify resolved production classpaths and actual APK DEX type tables.

Run the companion native-boundary.gradle task in the contained build lane first.
String constants containing old saved-state class names are deliberately not types.
"""
import argparse
import hashlib
import json
from pathlib import Path
import re
import struct
import sys
import zipfile
import zlib

CONFIGURATIONS = {
    "debugCompileClasspath", "debugRuntimeClasspath",
    "releaseCompileClasspath", "releaseRuntimeClasspath",
}
LEGACY_READER_FILES = (
    "app/src/nativeCompatibility/java/androidx/compose/runtime/ParcelableSnapshotMutableState.java",
    "app/src/nativeCompatibility/java/androidx/compose/runtime/ParcelableSnapshotMutableIntState.java",
)
LEGACY_TYPES = frozenset(
    ("Landroidx/compose/runtime/" + name + suffix + ";").encode("ascii")
    for name in ("ParcelableSnapshotMutableState", "ParcelableSnapshotMutableIntState")
    for suffix in ("", "$LegacyCreator")
)
TYPE_PREFIXES = (
    b"Landroidx/compose/",
    b"Landroidx/activity/compose/",
    b"Landroidx/lifecycle/compose/",
    b"Landroidx/navigation/compose/",
    b"Lcom/google/android/material/",
)


def forbidden_module(group, name):
    return (
        group == "androidx.compose" or group.startswith("androidx.compose.")
        or group == "com.google.android.material"
        or (group.startswith("androidx.") and
            (name.endswith("-compose") or "-compose-" in name))
    )


def sha256(data):
    return hashlib.sha256(data).hexdigest()


def inspect_dependencies(path):
    raw = Path(path).read_bytes()
    report = json.loads(raw)
    if report.get("schema") != 1:
        raise ValueError("Unsupported dependency report schema")
    configurations = report.get("configurations")
    if not isinstance(configurations, dict) or set(configurations) != CONFIGURATIONS:
        raise ValueError("Dependency report must contain exactly the four production classpaths")
    findings = []
    counts = {}
    for name, graph in configurations.items():
        modules = graph.get("modules")
        if not isinstance(modules, list) or not modules:
            raise ValueError(f"{name}: missing resolved modules")
        if graph.get("unresolved"):
            raise ValueError(f"{name}: unresolved dependencies")
        seen = set()
        for module in modules:
            group, artifact, version = (module.get(key) for key in ("group", "name", "version"))
            if not all(isinstance(value, str) and value for value in (group, artifact, version)):
                raise ValueError(f"{name}: malformed component")
            identity = (group, artifact, version)
            if identity in seen:
                raise ValueError(f"{name}: duplicate component")
            seen.add(identity)
            if forbidden_module(group, artifact):
                findings.append({"configuration": name, **module})
        counts[name] = len(modules)
    return {"path": str(Path(path).resolve()), "sha256": sha256(raw),
            "module_counts": counts, "forbidden_modules": findings}


def inspect_legacy_sources(manifest_path):
    manifest_path = Path(manifest_path)
    raw = manifest_path.read_bytes()
    manifest = json.loads(raw)
    sources = manifest.get("sources")
    if manifest.get("schema") != 1 or not isinstance(sources, dict) or set(sources) != set(LEGACY_READER_FILES):
        raise ValueError("Legacy exception must describe exactly the two reviewed readers")
    root = Path(__file__).resolve().parents[1]
    actual = {}
    for relative, expected in sources.items():
        content = (root / relative).read_bytes()
        if len(content) > 4096 or sha256(content) != expected:
            raise ValueError("Legacy reader source changed: " + relative)
        actual[relative] = {"sha256": expected, "bytes": len(content)}
    return {"path": str(manifest_path.resolve()), "sha256": sha256(raw), "sources": actual,
            "allowed_types": sorted(value.decode("ascii") for value in LEGACY_TYPES)}


def inspect_dex(data, allowed_legacy=frozenset()):
    if len(data) < 112 or not re.fullmatch(rb"dex\n0(?:35|37|38|39|40)\x00", data[:8]):
        raise ValueError("Unsupported or truncated standard DEX header")
    u32 = lambda offset: struct.unpack_from("<I", data, offset)[0]
    if u32(32) != len(data) or u32(36) != 112 or u32(40) != 0x12345678:
        raise ValueError("Invalid DEX file size, header size or endian tag")
    if hashlib.sha1(data[32:]).digest() != data[12:32]:
        raise ValueError("DEX signature mismatch")
    if zlib.adler32(data[12:]) & 0xffffffff != u32(8):
        raise ValueError("DEX checksum mismatch")

    def table(header, stride):
        count, offset = u32(header), u32(header + 4)
        if count == 0:
            if offset != 0:
                raise ValueError("Empty DEX table has nonzero offset")
        elif offset < 112 or offset % 4 or count > (len(data) - offset) // stride:
            raise ValueError("DEX table outside file")
        return count, offset

    string_count, strings_offset = table(56, 4)
    type_count, types_offset = table(64, 4)
    class_count, classes_offset = table(96, 32)
    data_size, data_offset = u32(104), u32(108)
    if data_offset < 112 or data_offset > len(data) or data_size != len(data) - data_offset:
        raise ValueError("Invalid DEX data section")

    def string(index):
        if index >= string_count:
            raise ValueError("DEX type references nonexistent string")
        offset = u32(strings_offset + index * 4)
        if not data_offset <= offset < len(data):
            raise ValueError("DEX string outside data section")
        # Skip the bounded ULEB128 UTF-16 length; descriptors use ASCII prefixes.
        for shift in range(0, 35, 7):
            if offset >= len(data):
                raise ValueError("Truncated DEX string length")
            byte = data[offset]
            offset += 1
            if shift == 28 and byte > 15:
                raise ValueError("Invalid DEX string length")
            if byte < 128:
                break
        else:
            raise ValueError("Unterminated DEX string length")
        end = data.find(b"\x00", offset)
        if end < 0:
            raise ValueError("Unterminated DEX string")
        return data[offset:end]

    types = [string(u32(types_offset + index * 4)) for index in range(type_count)]
    definitions = set()
    for index in range(class_count):
        class_index = u32(classes_offset + index * 32)
        if class_index >= type_count or class_index in definitions:
            raise ValueError("Invalid or duplicate DEX class definition")
        definitions.add(class_index)
    forbidden = []
    legacy = []
    for index, descriptor in enumerate(types):
        if descriptor.lstrip(b"[").startswith(TYPE_PREFIXES):
            entry = {"descriptor": descriptor.decode("ascii", errors="backslashreplace"),
                     "defined": index in definitions}
            if descriptor.lstrip(b"[") in allowed_legacy:
                legacy.append(entry)
            else:
                forbidden.append(entry)
    return {"sha256": sha256(data), "bytes": len(data), "types": type_count,
            "definitions": class_count, "forbidden_types": forbidden, "legacy_reader_types": legacy}


def inspect_apk(path, allowed_legacy=frozenset()):
    path = Path(path)
    results = {}
    with zipfile.ZipFile(path) as archive:
        infos = [info for info in archive.infolist() if re.fullmatch(r"classes(?:[2-9]|[1-9][0-9]+)?\.dex", info.filename)]
        if not infos or not any(info.filename == "classes.dex" for info in infos):
            raise ValueError("APK has no primary DEX")
        if len({info.filename for info in infos}) != len(infos):
            raise ValueError("APK contains duplicate DEX entries")
        for info in infos:
            if info.file_size > 256 * 1024 * 1024:
                raise ValueError("DEX exceeds this bounded gate's supported size")
            results[info.filename] = inspect_dex(archive.read(info), allowed_legacy)
    return {"path": str(path.resolve()), "sha256": sha256(path.read_bytes()),
            "dex": results}


def verify_required_legacy_definitions(apk_report):
    defined = {entry["descriptor"] for dex in apk_report["dex"].values()
               for entry in dex["legacy_reader_types"] if entry["defined"]}
    required = {descriptor.decode("ascii") for descriptor in LEGACY_TYPES if b"$" not in descriptor}
    if not required.issubset(defined):
        raise ValueError("Required released Parcelable reader is absent from APK: " +
                         ", ".join(sorted(required - defined)))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--dependencies", required=True)
    parser.add_argument("--apk", required=True)
    parser.add_argument("--output", required=True)
    parser.add_argument("--legacy-compatibility-manifest")
    args = parser.parse_args()
    result = {"schema": 1, "passed": False}
    try:
        result["dependencies"] = inspect_dependencies(args.dependencies)
        allowed_legacy = frozenset()
        if args.legacy_compatibility_manifest:
            result["legacy_exception"] = inspect_legacy_sources(args.legacy_compatibility_manifest)
            allowed_legacy = LEGACY_TYPES
        result["apk"] = inspect_apk(args.apk, allowed_legacy)
        if allowed_legacy:
            verify_required_legacy_definitions(result["apk"])
        result["passed"] = not result["dependencies"]["forbidden_modules"] and not any(
            value["forbidden_types"] for value in result["apk"]["dex"].values())
    except (OSError, ValueError, KeyError, TypeError, struct.error, zipfile.BadZipFile) as error:
        result["error"] = f"{type(error).__name__}: {error}"
    Path(args.output).write_text(json.dumps(result, indent=2, sort_keys=True) + "\n")
    print("Production native boundary: " + ("PASS" if result["passed"] else "FAIL"))
    return 0 if result["passed"] else 1


if __name__ == "__main__":
    sys.exit(main())
