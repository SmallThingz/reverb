"""Exercise the private pre-sign gate using small ZIP fixtures and a mocked tool."""
from pathlib import Path
import struct
import tempfile
import unittest
from unittest.mock import patch
import zipfile

from prepare_release_apk import prepare, verify_repack
from verify_release import VCS_ENTRY, file_sha256

REVISION = "ab" * 20
NATIVE = "lib/arm64-v8a/libreverb_file_move.so"


def fixture(path, *, level=0, content=b"unchanged", timestamp=2, signed=False):
    with zipfile.ZipFile(path, "w") as output:
        def entry(name, data, method=zipfile.ZIP_DEFLATED):
            info = zipfile.ZipInfo(name, (2026, 1, timestamp, 0, 0, 0))
            info.compress_type = method
            info.external_attr = 0o644 << 16
            info.comment = b"entry metadata fixture"
            output.writestr(info, data, compresslevel=level)

        entry(VCS_ENTRY, f'local_root_path: "$PROJECT_DIR"\nrevision: "{REVISION}"\n'.encode())
        entry("classes.dex", b"dex\n039\0" + content * 1000)
        entry(NATIVE, b"fixture ELF bytes", zipfile.ZIP_STORED)
        entry("resources.arsc", b"resource table", zipfile.ZIP_STORED)
        entry("assets/dexopt/baseline.prof", b"ART profile")
        if signed:
            entry("META-INF/CERT.RSA", b"existing signature fixture")


def add_flag(path, name, flag):
    with zipfile.ZipFile(path) as archive:
        cursor = archive.start_dir
        data = bytearray(path.read_bytes())
        for entry in archive.infolist():
            if entry.filename == name:
                struct.pack_into("<H", data, entry.header_offset + 6, flag)
                struct.pack_into("<H", data, cursor + 8, flag)
                break
            lengths = struct.unpack_from("<HHH", data, cursor + 28)
            cursor += 46 + sum(lengths)
    path.write_bytes(data)


class PrepareReleaseApkTest(unittest.TestCase):
    def setUp(self):
        root = self.enterContext(tempfile.TemporaryDirectory())
        self.source = Path(root) / "original.apk"
        self.native_output = Path(root) / "tool_fixture.apk"
        self.destination = Path(root) / "candidate.apk"
        self.tool = Path(root) / "zipalign"
        self.tool.write_bytes(b"pinned tool fixture")
        fixture(self.source)
        fixture(self.native_output, level=9)
        add_flag(self.native_output, "classes.dex", 2)

    def test_same_decoded_payload_metadata_methods_and_deflate_hints(self):
        report = verify_repack(self.source, self.native_output)
        self.assertEqual(report["entries"], 5)
        self.assertEqual(report["deflate_hint_changes"], 1)
        self.assertLess(report["candidate_dex_packed_bytes"], report["original_dex_packed_bytes"])
        with zipfile.ZipFile(self.native_output) as output:
            for name in (NATIVE, "resources.arsc"):
                self.assertEqual(output.getinfo(name).compress_type, zipfile.ZIP_STORED)

    def test_gate_rejects_meaningful_metadata_or_payload_differences(self):
        fixture(self.native_output, content=b"different")
        with self.assertRaisesRegex(ValueError, "classes.dex: ZIP (CRC|file_size) changed"):
            verify_repack(self.source, self.native_output)
        fixture(self.native_output, timestamp=3)
        with self.assertRaisesRegex(ValueError, "ZIP date_time changed"):
            verify_repack(self.source, self.native_output)
        fixture(self.native_output)
        add_flag(self.native_output, NATIVE, 2)
        with self.assertRaisesRegex(ValueError, "non-DEFLATE-hint ZIP flags changed"):
            verify_repack(self.source, self.native_output)

    def test_rejects_signed_input(self):
        fixture(self.source, signed=True)
        with self.assertRaisesRegex(ValueError, "JAR signature"):
            self.run_prepare()
        fixture(self.source)
        data = self.source.read_bytes()
        with zipfile.ZipFile(self.source) as original:
            offset = original.start_dir
        block = struct.pack("<QQ", 24, 24) + b"APK Sig Block 42"
        signed = bytearray(data[:offset] + block + data[offset:])
        struct.pack_into("<I", signed, len(signed) - 6, offset + len(block))
        self.source.write_bytes(signed)
        with self.assertRaisesRegex(ValueError, "APK Signing Block"):
            self.run_prepare()

    def mock_tool(self, command, cwd):
        self.assertEqual(cwd, self.destination.parent)
        self.assertEqual(command[0], str(self.tool))
        if command[1:5] == ["-z", "-P", "16", "4"]:
            self.assertEqual(command[5], str(self.source))
            Path(command[6]).write_bytes(self.native_output.read_bytes())
        else:
            self.assertEqual(command[1:5], ["-c", "-P", "16", "4"])
            self.assertTrue(Path(command[5]).is_file())
            self.assertFalse(self.destination.exists())
        return ""

    def run_prepare(self, source_revision=REVISION, input_sha=None, tool_sha=None, expected=None):
        return prepare(self.source, self.destination, self.tool, source_revision,
                       input_sha or file_sha256(self.source), tool_sha or file_sha256(self.tool), expected)

    def test_exact_tool_options_aligned_check_and_no_overwrite(self):
        before = self.source.read_bytes()
        with patch("prepare_release_apk.run", side_effect=self.mock_tool) as runner:
            receipt = self.run_prepare(expected=file_sha256(self.native_output))
            self.assertEqual(runner.call_count, 2)
            self.assertEqual(self.source.read_bytes(), before)
            self.assertEqual(self.destination.read_bytes(), self.native_output.read_bytes())
            self.assertGreater(receipt["saved_bytes"], 0)
            with self.assertRaisesRegex(ValueError, "output already exists"):
                self.run_prepare()
            self.destination = self.source
            with self.assertRaisesRegex(ValueError, "output already exists"):
                self.run_prepare()
            self.assertEqual(runner.call_count, 2)

    def test_binding_checks_and_failed_alignment_leave_no_output(self):
        with patch("prepare_release_apk.run") as runner:
            for args, message in (({"input_sha": "0" * 64}, "Input APK SHA-256"),
                                  ({"tool_sha": "0" * 64}, "zipalign SHA-256"),
                                  ({"source_revision": "ef" * 20}, "VCS revision")):
                with self.subTest(args=args), self.assertRaisesRegex(ValueError, message):
                    self.run_prepare(**args)
            runner.assert_not_called()

        def bad_alignment(command, cwd):
            if command[1] == "-c":
                raise ValueError("ZIP alignment check failed")
            return self.mock_tool(command, cwd)

        with patch("prepare_release_apk.run", side_effect=bad_alignment):
            with self.assertRaisesRegex(ValueError, "alignment check failed"):
                self.run_prepare()
        self.assertFalse(self.destination.exists())
        with patch("prepare_release_apk.run", side_effect=self.mock_tool):
            with self.assertRaisesRegex(ValueError, "Candidate SHA-256"):
                self.run_prepare(expected="0" * 64)
        self.assertFalse(self.destination.exists())
        self.assertFalse(list(self.destination.parent.glob(".reverb-unsigned-*")))


if __name__ == "__main__":
    unittest.main()
