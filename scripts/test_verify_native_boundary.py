import hashlib
import importlib.util
import json
from pathlib import Path
import struct
import tempfile
import unittest
from unittest.mock import patch
import warnings
import zipfile
import zlib

SPEC = importlib.util.spec_from_file_location(
    "native_boundary", Path(__file__).with_name("verify_native_boundary.py"))
gate = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(gate)


def dex(types, defined=(), literals=()):
    """Minimal bounded DEX table fixture, not an executable program."""
    strings = list(types) + list(literals)
    string_offset = 112 if strings else 0
    type_offset = 112 + 4 * len(strings) if types else 0
    class_offset = 112 + 4 * len(strings) + 4 * len(types) if defined else 0
    data_offset = 112 + 4 * len(strings) + 4 * len(types) + 32 * len(defined)
    result = bytearray(data_offset)
    result[:8] = b"dex\n039\x00"
    struct.pack_into("<II", result, 36, 112, 0x12345678)
    struct.pack_into("<II", result, 56, len(strings), string_offset)
    struct.pack_into("<II", result, 64, len(types), type_offset)
    struct.pack_into("<II", result, 96, len(defined), class_offset)
    for index, text in enumerate(strings):
        encoded = text.encode("ascii")
        assert len(encoded) < 128
        struct.pack_into("<I", result, string_offset + index * 4, len(result))
        result += bytes([len(encoded)]) + encoded + b"\x00"
    for index in range(len(types)):
        struct.pack_into("<I", result, type_offset + index * 4, index)
    for index, type_index in enumerate(defined):
        struct.pack_into("<I", result, class_offset + index * 32, type_index)
    struct.pack_into("<I", result, 32, len(result))
    struct.pack_into("<II", result, 104, len(result) - data_offset, data_offset)
    return reseal(result)


def reseal(data):
    result = bytearray(data)
    result[12:32] = hashlib.sha1(result[32:]).digest()
    struct.pack_into("<I", result, 8, zlib.adler32(result[12:]) & 0xffffffff)
    return bytes(result)


class NativeBoundaryTests(unittest.TestCase):
    def setUp(self):
        temporary_root = Path(__file__).resolve().parents[1] / ".tmp"
        temporary_root.mkdir(exist_ok=True)
        self.directory = tempfile.TemporaryDirectory(prefix="native-boundary-test-", dir=temporary_root)
        self.addCleanup(self.directory.cleanup)
        self.root = Path(self.directory.name)

    def test_referenced_and_defined_types_are_both_rejected(self):
        result = gate.inspect_dex(dex([
            "Lsmallthingz/ui/View;",
            "Landroidx/compose/runtime/State;",
            "Landroidx/compose/material3/Button;",
        ], defined=(0, 2)))
        self.assertEqual([False, True], [entry["defined"] for entry in result["forbidden_types"]])

    def test_array_types_rejected_but_literal_class_names_are_not_types(self):
        result = gate.inspect_dex(dex(["[[Landroidx/compose/ui/Node;"],
                                     literals=["androidx.compose.runtime.ParcelableSnapshotMutableState"]))
        self.assertEqual(1, len(result["forbidden_types"]))
        result = gate.inspect_dex(dex(["Landroid/view/View;"],
                                     literals=["Landroidx/compose/runtime/State;"]))
        self.assertEqual([], result["forbidden_types"])

    def test_exact_legacy_allowance_does_not_allow_runtime_or_prefix(self):
        descriptors = [value.decode("ascii") for value in sorted(gate.LEGACY_TYPES)]
        descriptors += ["Landroidx/compose/runtime/State;",
                        "Landroidx/compose/runtime/ParcelableSnapshotMutableState$Observer;"]
        result = gate.inspect_dex(dex(descriptors), gate.LEGACY_TYPES)
        self.assertEqual(4, len(result["legacy_reader_types"]))
        self.assertEqual(2, len(result["forbidden_types"]))
        self.assertEqual(6, len(gate.inspect_dex(dex(descriptors))["forbidden_types"]))

    def test_bad_checksums_and_unsupported_container_fail_closed(self):
        value = bytearray(dex(["Landroid/view/View;"]))
        value[-2] ^= 1
        with self.assertRaises(ValueError):
            gate.inspect_dex(bytes(value))
        value = bytearray(dex(["Landroid/view/View;"]))
        value[:8] = b"dex\n041\x00"
        with self.assertRaises(ValueError):
            gate.inspect_dex(reseal(value))

    def test_out_of_bounds_tables_and_type_indexes_fail_closed(self):
        value = bytearray(dex(["Landroid/view/View;"]))
        struct.pack_into("<I", value, 68, len(value) + 4)
        with self.assertRaises(ValueError):
            gate.inspect_dex(reseal(value))
        value = bytearray(dex(["Landroid/view/View;"]))
        struct.pack_into("<I", value, 116, 3)
        with self.assertRaises(ValueError):
            gate.inspect_dex(reseal(value))

    def test_every_multidex_entry_including_classes10_is_inspected(self):
        path = self.root / "fixture.apk"
        with zipfile.ZipFile(path, "w") as archive:
            archive.writestr("classes.dex", dex(["Landroid/view/View;"]))
            archive.writestr("classes10.dex", dex(["Landroidx/compose/runtime/State;"]))
        result = gate.inspect_apk(path)
        self.assertEqual({"classes.dex", "classes10.dex"}, set(result["dex"]))
        self.assertEqual(1, len(result["dex"]["classes10.dex"]["forbidden_types"]))

    def test_missing_primary_and_duplicate_dex_are_rejected_before_payload_reads(self):
        for names, message in ((["classes2.dex"], "no primary DEX"),
                               (["classes.dex", "classes.dex"], "duplicate DEX")):
            with self.subTest(names=names):
                path = self.root / "invalid.apk"
                with warnings.catch_warnings():
                    warnings.simplefilter("ignore", UserWarning)
                    with zipfile.ZipFile(path, "w") as archive:
                        for name in names:
                            archive.writestr(name, dex(["Landroid/view/View;"]))
                with patch.object(zipfile.ZipFile, "read", side_effect=AssertionError("Payload read")):
                    with self.assertRaisesRegex(ValueError, message):
                        gate.inspect_apk(path)

    def test_oversized_dex_is_rejected_before_reading_or_parsing_payload(self):
        info = zipfile.ZipInfo("classes.dex")
        info.file_size = 256 * 1024 * 1024 + 1
        with patch.object(gate.zipfile, "ZipFile") as archive_type:
            archive = archive_type.return_value.__enter__.return_value
            archive.infolist.return_value = [info]
            archive.read.side_effect = AssertionError("Oversized payload was read")
            with patch.object(gate, "inspect_dex", side_effect=AssertionError("Payload parsed")):
                with self.assertRaisesRegex(ValueError, "DEX exceeds"):
                    gate.inspect_apk(self.root / "advertised-oversize.apk")
            archive.read.assert_not_called()

    def test_exact_dex_size_limit_reaches_the_real_parser(self):
        # Only the ZIP size admission is simulated; no 256 MiB allocation is needed.
        info = zipfile.ZipInfo("classes.dex")
        info.file_size = 256 * 1024 * 1024
        with patch.object(gate.zipfile, "ZipFile") as archive_type:
            archive = archive_type.return_value.__enter__.return_value
            archive.infolist.return_value = [info]
            archive.read.return_value = b"malformed DEX"
            with self.assertRaisesRegex(ValueError, "Unsupported or truncated standard DEX"):
                gate.inspect_apk(self.root / "advertised-limit.apk")
            archive.read.assert_called_once_with(info)

    def test_whole_apk_digest_uses_bounded_reads_and_includes_non_dex_bytes(self):
        path = self.root / "hash.apk"
        with zipfile.ZipFile(path, "w") as archive:
            archive.writestr("classes.dex", dex(["Landroid/view/View;"]))
            archive.writestr("assets/payload", b"x" * (2 * 1024 * 1024 + 17))
        expected = hashlib.sha256(path.read_bytes()).hexdigest()
        original_open = Path.open
        reads = []
        sizes = []
        case = self

        class BoundedReader:
            def __init__(self, source):
                self.source = source

            def __enter__(self):
                return self

            def __exit__(self, *args):
                self.source.close()

            def read(self, size=-1):
                case.assertGreater(size, 0, "Whole APK digest requested an unbounded read")
                case.assertLessEqual(size, 1024 * 1024)
                data = self.source.read(size)
                reads.append(size)
                sizes.append(len(data))
                return data

        def open_bounded(file, *args, **kwargs):
            source = original_open(file, *args, **kwargs)
            return BoundedReader(source) if file == path else source

        with patch.object(Path, "open", open_bounded):
            result = gate.inspect_apk(path)
        self.assertEqual(expected, result["sha256"])
        self.assertEqual(path.stat().st_size, sum(sizes))
        self.assertGreaterEqual(len(reads), 4)  # Three data reads and EOF.

    def test_four_real_graphs_required_and_module_groups_rejected(self):
        path = self.root / "graph.json"
        graphs = {name: {"modules": [{"group": "androidx.core", "name": "core", "version": "1.18.0"}],
                         "unresolved": []} for name in gate.CONFIGURATIONS}
        path.write_text(json.dumps({"schema": 1, "configurations": graphs}))
        self.assertEqual([], gate.inspect_dependencies(path)["forbidden_modules"])
        graphs["releaseRuntimeClasspath"]["modules"].append(
            {"group": "androidx.compose.runtime", "name": "runtime", "version": "1.11.2"})
        path.write_text(json.dumps({"schema": 1, "configurations": graphs}))
        self.assertEqual(1, len(gate.inspect_dependencies(path)["forbidden_modules"]))
        graphs.pop("debugRuntimeClasspath")
        path.write_text(json.dumps({"schema": 1, "configurations": graphs}))
        with self.assertRaises(ValueError):
            gate.inspect_dependencies(path)

    def test_unresolved_dependencies_cannot_produce_a_pass(self):
        path = self.root / "graph.json"
        graph = {"modules": [{"group": "androidx.core", "name": "core", "version": "1.18.0"}],
                 "unresolved": ["missing"]}
        path.write_text(json.dumps({"schema": 1, "configurations": {name: graph for name in gate.CONFIGURATIONS}}))
        with self.assertRaises(ValueError):
            gate.inspect_dependencies(path)

    def test_missing_legacy_reader_definition_cannot_pass_on_references_alone(self):
        descriptors = [value.decode("ascii") for value in sorted(gate.LEGACY_TYPES) if b"$" not in value]
        reference_only = {"dex": {"classes.dex": gate.inspect_dex(dex(descriptors), gate.LEGACY_TYPES)}}
        with self.assertRaises(ValueError):
            gate.verify_required_legacy_definitions(reference_only)
        actual_definitions = {"dex": {"classes.dex": gate.inspect_dex(
            dex(descriptors, defined=(0, 1)), gate.LEGACY_TYPES)}}
        gate.verify_required_legacy_definitions(actual_definitions)

    def test_reviewed_legacy_manifest_matches_exact_sources(self):
        result = gate.inspect_legacy_sources(Path(__file__).with_name("native-legacy-compatibility.json"))
        self.assertEqual(2, len(result["sources"]))
        self.assertEqual(4, len(result["allowed_types"]))


if __name__ == "__main__":
    unittest.main()
