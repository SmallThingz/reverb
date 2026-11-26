"""Negative regressions for the release failures documented in F-Droid MR !50231."""
import sys
sys.dont_write_bytecode = True
from pathlib import Path
import hashlib
import io
import struct
import subprocess
import tempfile
import unittest
from unittest.mock import MagicMock, patch
import zipfile
import verify_release as release
from verify_release import (SIGNER, verify_revision, verify_block_policy, verify_signer_output,
                            verify_source_version, verify_runtime_error_allocations)


class ReleaseVerificationTests(unittest.TestCase):
    revision = '1' * 40

    def test_exact_tagged_revision(self):
        verify_revision(f'repositories {{\n local_root_path: "$PROJECT_DIR"\n revision: "{self.revision}"\n}}', self.revision)

    def test_earlier_commit_is_rejected(self):
        with self.assertRaisesRegex(ValueError, 'does not match tag'):
            verify_revision(f'local_root_path: "$PROJECT_DIR"\nrevision: "{"2" * 40}"', self.revision)

    def test_missing_revision_is_rejected(self):
        with self.assertRaises(ValueError):
            verify_revision('', self.revision)

    def test_duplicate_revisions_are_rejected(self):
        with self.assertRaises(ValueError):
            verify_revision(f'revision: "{self.revision}"\nrevision: "{self.revision}"', self.revision)

    def test_valid_signing_blocks(self):
        verify_block_policy({0x7109871A, 0x42726577})

    def test_dependency_metadata_is_rejected(self):
        with self.assertRaisesRegex(ValueError, 'dependency metadata'):
            verify_block_policy({0x7109871A, 0x504B4453})

    def test_unsigned_or_unknown_blocks_are_rejected(self):
        for ids in (set(), {0x7109871A, 0x12345678}):
            with self.subTest(ids=ids), self.assertRaises(ValueError):
                verify_block_policy(ids)


class SignerOutputTests(unittest.TestCase):
    def output(self, label, digest=SIGNER, count=1, scheme='v2'):
        return (f'Number of signers: {count}\n'
                f'Verified using {scheme} scheme (APK Signature Scheme {scheme}): true\n'
                f'{label} certificate SHA-256 digest: {digest}\n')

    def test_build_tools_37(self):
        verify_signer_output(self.output('V2 Signer:'))

    def test_legacy_output(self):
        verify_signer_output(self.output('Signer #1'))

    def test_v3_output_requires_v3_and_the_pinned_certificate(self):
        text = self.output('V3.0 Signer:', scheme='v3')
        verify_signer_output(text, scheme='v3')
        for bad in (text.replace('): true', '): false'),
                    text.replace(SIGNER, '0' * 64),
                    text + 'Verified using v3 scheme (APK Signature Scheme v3): false\n'):
            with self.subTest(output=bad), self.assertRaises(ValueError):
                verify_signer_output(bad, scheme='v3')

    def test_v2_status_must_be_unique_and_exact(self):
        text = self.output('V2 Signer:')
        for bad in (text.replace('): true', '): true-extra'),
                    text + 'Verified using v2 scheme (APK Signature Scheme v2): false\n'):
            with self.subTest(output=bad), self.assertRaises(ValueError):
                verify_signer_output(bad)

    def test_missing_certificate(self):
        with self.assertRaises(ValueError):
            verify_signer_output('Number of signers: 1\n')

    def test_wrong_or_multiple_signers(self):
        for text in (self.output('V2 Signer:', '0' * 64), self.output('V2 Signer:', count=2)):
            with self.subTest(text=text), self.assertRaises(ValueError):
                verify_signer_output(text)



class SourceVersionTests(unittest.TestCase):
    source = '    versionCode 3\n    versionName "0.1.2-rc1"\n'

    def test_literal_prerelease_is_readable(self):
        verify_source_version(self.source, '0.1.2-rc1', '3')

    def test_symbolic_versions_are_rejected(self):
        for source in (
            self.source.replace('versionCode 3', 'versionCode appVersionCode'),
            self.source.replace('"0.1.2-rc1"', 'appVersionName'),
        ):
            with self.subTest(source=source), self.assertRaises(ValueError):
                verify_source_version(source, '0.1.2-rc1', '3')

    def test_duplicate_or_mismatched_versions_are_rejected(self):
        for source in (self.source + self.source, self.source.replace('3', '4'),
                       self.source.replace('0.1.2-rc1', '0.1.2')):
            with self.subTest(source=source), self.assertRaises(ValueError):
                verify_source_version(source, '0.1.2-rc1', '3')


class RuntimeErrorAllocationTests(unittest.TestCase):
    catalog = b'CatalogFirstPaintUnavailableException'
    directory = b'ConfiguredRecordingDirectoryUnavailableException'
    unrelated = b'AudioStoreAdmissionCancelledException'

    @staticmethod
    def instruction(name, opcode=b'new-instance'):
        return b'14daac: 220a 2d03 |00c6: ' + opcode + b' v10, Lapp/smallthingz/reverb/' + name + b'; // type@032d\n'

    def verify_dump(self, lines, status=0):
        dump = MagicMock()
        dump.__enter__.return_value = dump
        dump.stdout = iter(lines)
        dump.wait.return_value = status
        with patch('verify_release.subprocess.Popen', return_value=dump):
            verify_runtime_error_allocations(Path('release.apk'), Path('build-tools'), Path('.'))

    def test_requires_both_concrete_allocations(self):
        self.verify_dump([b'Other DEX strings can contain modified UTF-8: \xc0\x80\n',
                          self.instruction(self.catalog), self.instruction(self.directory)])

    def test_descriptor_only_and_unrelated_allocations_do_not_count(self):
        with self.assertRaisesRegex(ValueError, 'CatalogFirstPaintUnavailableException'):
            self.verify_dump([b'Class descriptor  : \'Lapp/smallthingz/reverb/' + self.catalog + b';\'\n',
                              self.instruction(self.catalog, b'check-cast'),
                              self.instruction(self.unrelated), self.instruction(self.directory)])

    def test_tool_failure_is_rejected_even_after_both_allocations(self):
        with self.assertRaisesRegex(ValueError, r'dexdump failed.*\(2\)'):
            self.verify_dump([self.instruction(self.catalog), self.instruction(self.directory)], status=2)


V2 = 0x7109871A
REVISION = '1' * 40
ABI_ELF = {'arm64-v8a': (2, 183), 'armeabi-v7a': (1, 40), 'riscv64': (2, 243),
           'x86': (1, 3), 'x86_64': (2, 62)}
ARM64_LIBRARY = 'lib/arm64-v8a/libreverb_file_move.so'


def tiny_elf(abi, segments=((1, 0, 0, 0x4000), (1, 0x100, 0x4100, 0x4000)), *, table_padding=0):
    """ELF program headers with (type, file offset, virtual address, alignment) inputs."""
    elf_class, machine = ABI_ELF[abi]
    ident = b'\x7fELF' + bytes((elf_class, 1, 1)) + bytes(9)
    ehsize, phsize = (52, 32) if elf_class == 1 else (64, 56)
    header = struct.pack('<HHIIIIIHHHHHH' if elf_class == 1 else '<HHIQQQIHHHHHH',
                         3, machine, 1, 0, ehsize + table_padding, 0, 0,
                         ehsize, phsize, len(segments), 0, 0, 0)
    table = bytearray()
    for kind, offset, address, alignment in segments:
        if elf_class == 1:
            table.extend(struct.pack('<8I', kind, offset, address, address, 8, 16, 5, alignment))
        else:
            table.extend(struct.pack('<IIQQQQQQ', kind, 5, offset, address, address, 8, 16, alignment))
    return (ident + header + bytes(table_padding) + table).ljust(0x200, b'\0')


def native_fixtures():
    return {f'lib/{abi}/libreverb_file_move.so': (tiny_elf(abi), zipfile.ZIP_STORED) for abi in ABI_ELF}


def signing_entry(block_id, value=b'x'):
    return struct.pack('<QI', 4 + len(value), block_id) + value


def tiny_apk(entries=None, comment=b'', *, libraries=None, align_native=False):
    """Real ZIP records plus a synthetic signing envelope; no cryptographic claim."""
    buffer = io.BytesIO()
    with zipfile.ZipFile(buffer, 'w') as archive:
        info = zipfile.ZipInfo(release.VCS_ENTRY, (2020, 1, 1, 0, 0, 0))
        archive.writestr(info, f'local_root_path: "$PROJECT_DIR"\nrevision: "{REVISION}"\n')
        for name, (payload, compression) in (native_fixtures() if libraries is None else libraries).items():
            info = zipfile.ZipInfo(name)
            if align_native:
                padding = (-(buffer.tell() + 30 + len(name) + 4)) % 0x4000
                info.extra = struct.pack('<HH', 0xcafe, padding) + bytes(padding)
            archive.writestr(info, payload, compress_type=compression)
    data = buffer.getvalue()
    # The fixture writer produced an uncommented, non-ZIP64 archive.
    eocd = bytearray(data[-22:])
    cd_offset = struct.unpack_from('<I', eocd, 16)[0]
    if entries is None:
        entries = signing_entry(V2)
    size = 24 + len(entries)
    block = struct.pack('<Q', size) + entries + struct.pack('<Q', size) + b'APK Sig Block 42'
    struct.pack_into('<I', eocd, 16, cd_offset + len(block))
    struct.pack_into('<H', eocd, 20, len(comment))
    return data[:cd_offset] + block + data[cd_offset:-22] + eocd + comment


class NativeLibrariesTests(unittest.TestCase):
    def verify(self, libraries):
        with zipfile.ZipFile(io.BytesIO(tiny_apk(libraries=libraries))) as archive:
            return release.verify_native_libraries(archive)

    def assert_bad_arm64(self, payload, message):
        libraries = native_fixtures()
        libraries[ARM64_LIBRARY] = (payload, zipfile.ZIP_STORED)
        with self.assertRaisesRegex(ValueError, message):
            self.verify(libraries)

    def test_all_five_abis_with_elf32_elf64_and_larger_power_of_two_alignment(self):
        libraries = native_fixtures()
        # Non-LOAD headers need no page alignment. The LOAD need not start on
        # a page if its file offset and virtual address are congruent.
        additional = tiny_elf('riscv64', ((4, 0, 0, 4), (1, 0x100, 0x8100, 0x8000)))
        libraries['lib/riscv64/libextra.so'] = (additional, zipfile.ZIP_STORED)
        self.assertEqual(self.verify(libraries), sorted(ABI_ELF))

    def test_empty_missing_and_extra_abis(self):
        missing = native_fixtures()
        del missing['lib/x86/libreverb_file_move.so']
        extra = native_fixtures()
        extra['lib/mips/libextra.so'] = (b'', zipfile.ZIP_STORED)
        for libraries, message in (({}, r'missing .*arm64-v8a'),
                                   (missing, r"missing \['x86'\]"),
                                   (extra, r"unexpected \['mips'\]")):
            with self.subTest(message=message), self.assertRaisesRegex(ValueError, message):
                self.verify(libraries)

    def test_required_library_name_and_stored_packaging(self):
        libraries = native_fixtures()
        libraries['lib/arm64-v8a/libsubstitute.so'] = libraries.pop(ARM64_LIBRARY)
        with self.assertRaisesRegex(ValueError, 'required native library.*libreverb_file_move.so'):
            self.verify(libraries)
        libraries = native_fixtures()
        libraries[ARM64_LIBRARY] = (tiny_elf('arm64-v8a'), zipfile.ZIP_DEFLATED)
        with self.assertRaisesRegex(ValueError, 'must be stored'):
            self.verify(libraries)

    def test_no_load_incongruent_load_and_invalid_alignment(self):
        for segments, message in (
            (((4, 0, 0, 4),), 'no PT_LOAD'),
            (((1, 0x100, 0x4200, 0x4000),), 'offset and virtual address are incongruent'),
            (((1, 0, 0, 0x6000),), 'not a power of two'),
            (((1, 0x1ff, 0x41ff, 0x4000),), 'invalid file or memory bounds'),
        ):
            with self.subTest(segments=segments):
                self.assert_bad_arm64(tiny_elf('arm64-v8a', segments), message)

    def test_wrong_abi_and_malformed_elf_headers(self):
        correct = tiny_elf('arm64-v8a')
        for payload, message in (
            (b'not elf', 'Invalid ELF identification'),
            (tiny_elf('x86'), 'Wrong ELF class'),
            (tiny_elf('x86_64'), 'Wrong ELF shared object type, machine or version'),
            (correct[:30], 'Truncated ELF header'),
            (correct[:100], 'Invalid ELF program header table'),
        ):
            with self.subTest(message=message):
                self.assert_bad_arm64(payload, message)

    def test_program_header_table_must_be_naturally_aligned_and_at_most_64k(self):
        for abi, padding in (('arm64-v8a', 4), ('armeabi-v7a', 2)):
            with self.subTest(abi=abi):
                libraries = native_fixtures()
                libraries[f'lib/{abi}/libreverb_file_move.so'] = (
                    tiny_elf(abi, table_padding=padding), zipfile.ZIP_STORED)
                with self.assertRaisesRegex(ValueError, 'Invalid ELF program header table'):
                    self.verify(libraries)
        headers = ((1, 0, 0, 0x4000),) + ((0, 0, 0, 0),) * 1170
        self.assert_bad_arm64(tiny_elf('arm64-v8a', headers), 'Invalid ELF program header table')

    def test_zero_file_size_load_may_start_at_eof_but_not_past_it(self):
        elf = bytearray(tiny_elf('arm64-v8a'))
        # ELF64 second program header, beginning with its p_offset field.
        load_offset_field = 64 + 56 + 8
        struct.pack_into('<QQQQ', elf, load_offset_field, len(elf), len(elf), len(elf), 0)
        libraries = native_fixtures()
        libraries[ARM64_LIBRARY] = (bytes(elf), zipfile.ZIP_STORED)
        self.assertEqual(self.verify(libraries), sorted(ABI_ELF))
        struct.pack_into('<QQQQ', elf, load_offset_field, 0x40000000, 0x40000000, 0x40000000, 0)
        self.assert_bad_arm64(bytes(elf), 'invalid file or memory bounds')

    def test_every_library_in_an_allowed_abi_is_checked(self):
        libraries = native_fixtures()
        libraries['lib/arm64-v8a/libextra.so'] = (tiny_elf('arm64-v8a', ((1, 0, 0, 0x1000),)),
                                                zipfile.ZIP_STORED)
        with self.assertRaisesRegex(ValueError, r'libextra\.so PT_LOAD header 0 alignment 0x1000'):
            self.verify(libraries)


class BinarySigningBlockTests(unittest.TestCase):
    def decode(self, data):
        # Exercise the production decoder against binary bytes, not decoded IDs.
        apk = MagicMock(spec=Path)
        apk.open.return_value = io.BytesIO(data)
        return release.signing_block_ids(apk)

    def test_real_zip_and_allowed_binary_blocks(self):
        data = tiny_apk(b''.join(signing_entry(i, bytes(range(8)))
                                 for i in sorted(release.ALLOWED_BLOCKS)))
        with zipfile.ZipFile(io.BytesIO(data)) as archive:
            self.assertIsNone(archive.testzip())
            self.assertIn(REVISION.encode(), archive.read(release.VCS_ENTRY))
        ids = self.decode(data)
        self.assertEqual(ids, release.ALLOWED_BLOCKS)
        release.verify_block_policy(ids)

    def test_binary_policy_rejects_missing_v2_dependency_and_unknown(self):
        for entries, message in (
            (b'', 'v2 is missing'),
            (signing_entry(0x42726577), 'v2 is missing'),
            (signing_entry(V2) + signing_entry(0x504B4453), 'dependency metadata'),
            (signing_entry(V2) + signing_entry(0x12345678), 'Unapproved'),
        ):
            with self.subTest(message=message, entries=entries):
                with self.assertRaisesRegex(ValueError, message):
                    release.verify_block_policy(self.decode(tiny_apk(entries)))

    def test_comments_with_embedded_eocd_magic(self):
        # These are valid comments, not alternate EOCD records. ZipFile has its
        # own last-magic limitation; this regression is decoder-only.
        for comment in (b'PK\x05\x06', b'prefixPK\x05\x06' + b'x' * 30,
                        b'PK\x05\x06' + bytes(18),
                        b'x' * (65535 - 4) + b'PK\x05\x06'):
            with self.subTest(length=len(comment)):
                self.assertEqual(self.decode(tiny_apk(comment=comment)), {V2})

    def test_empty_ordinary_and_maximum_comments(self):
        for comment in (b'', b'release', b'x' * 65535):
            with self.subTest(length=len(comment)):
                self.assertEqual(self.decode(tiny_apk(comment=comment)), {V2})

    def test_invalid_eocd_and_directory_geometry(self):
        original = tiny_apk()
        variants = [b'', original[:21], original[:-1], original + b'x']
        for field, value in ((12, 0), (16, 0), (16, 0xffffffff)):
            data = bytearray(original)
            struct.pack_into('<I', data, len(data) - 22 + field, value)
            variants.append(data)
        data = bytearray(original)
        data[-22:-18] = b'NOPE'
        variants.append(data)
        for index, data in enumerate(variants):
            with self.subTest(case=index), self.assertRaises(ValueError):
                self.decode(data)

    def test_duplicate_blocks_rejected_even_with_different_values(self):
        with self.assertRaisesRegex(ValueError, 'Duplicate'):
            self.decode(tiny_apk(signing_entry(V2) + signing_entry(V2, b'other')))

    def test_footer_header_and_block_sizes(self):
        original = tiny_apk()
        cd_offset = struct.unpack_from('<I', original, len(original) - 6)[0]
        block_size = struct.unpack_from('<Q', original, cd_offset - 24)[0]
        cases = (
            (cd_offset - 16, b'X', 'footer'),
            (cd_offset - block_size - 8, struct.pack('<Q', block_size + 1), 'sizes differ'),
            (cd_offset - 24, struct.pack('<Q', 23), 'Block size'),
            (cd_offset - 24, struct.pack('<Q', cd_offset), 'Block size'),
        )
        for offset, value, message in cases:
            data = bytearray(original)
            data[offset:offset + len(value)] = value
            with self.subTest(message=message), self.assertRaisesRegex(ValueError, message):
                self.decode(data)

    def test_entry_lengths_and_truncation(self):
        for entries, message in (
            (b'x' * 7, 'Truncated'),
            (struct.pack('<Q', 4) + b'\x1a\x87\x09', 'Truncated'),
            (struct.pack('<QI', 3, V2), 'entry size'),
            (struct.pack('<QI', 5, V2), 'entry size'),
            (struct.pack('<QI', 2**64 - 1, V2), 'entry size'),
            (signing_entry(V2) + b'x', 'Truncated'),
        ):
            with self.subTest(entries=entries), self.assertRaisesRegex(ValueError, message):
                self.decode(tiny_apk(entries))

    def test_zero_length_value_is_structurally_valid(self):
        self.assertEqual(self.decode(tiny_apk(signing_entry(V2, b''))), {V2})


class FileHashTests(unittest.TestCase):
    def test_bounded_reads_hash_empty_and_multiple_chunks_through_eof(self):
        for data in (b'', b'abcd' * (1024 * 1024) + b'tail'):
            reads = []

            class BoundedReader(io.BytesIO):
                def read(self, size=-1):
                    if not 0 < size <= 1024 * 1024:
                        raise AssertionError(f'Unbounded or oversized digest read: {size}')
                    # Short reads must not be mistaken for EOF.
                    chunk = super().read(min(size, 65537))
                    reads.append(len(chunk))
                    return chunk

            with self.subTest(length=len(data)):
                apk = MagicMock(spec=Path)
                source = BoundedReader(data)
                apk.open.return_value = source
                self.assertEqual(release.file_sha256(apk), hashlib.sha256(data).hexdigest())
                self.assertEqual(sum(reads), len(data))
                self.assertEqual(reads[-1], 0)
                self.assertTrue(source.closed)

    def test_read_failure_propagates_without_a_partial_digest(self):
        class FailingReader(io.BytesIO):
            def read(self, size=-1):
                if self.tell():
                    raise OSError('digest read failed')
                return super().read(1)

        source = FailingReader(b'ab')
        apk = MagicMock(spec=Path)
        apk.open.return_value = source
        with self.assertRaisesRegex(OSError, 'digest read failed'):
            release.file_sha256(apk)
        self.assertTrue(source.closed)


class VerifyOrchestrationTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix='reverb-release-test-')
        self.addCleanup(self.temp.cleanup)
        self.apk = Path(self.temp.name) / 'tiny.apk'
        self.data = tiny_apk()
        self.apk.write_bytes(self.data)
        self.tools = Path(self.temp.name) / 'tools'
        self.root = Path(release.__file__).resolve().parent.parent
        self.heads = iter([REVISION, REVISION])
        self.statuses = iter(['', ''])
        self.calls = []
        self.failure = None
        self.on_dump = lambda: None
        self.signature = SignerOutputTests().output('V2 Signer:')
        self.v2_signature = self.signature
        self.badging = "package: name='app.smallthingz.reverb' versionCode='3' versionName='0.1.2-rc1'\n"
        self.dump_status = 0
        self.dump_lines = [RuntimeErrorAllocationTests.instruction(name)
                           for name in sorted(release.REQUIRED_ERROR_ALLOCATIONS)]
        self.source = SourceVersionTests.source
        self.enterContext(patch.object(release.subprocess, 'run', side_effect=self.run_tool))
        self.enterContext(patch.object(release.subprocess, 'Popen', side_effect=self.open_dump))
        self.enterContext(patch.object(Path, 'read_text', autospec=True, side_effect=self.read_source))

    def read_source(self, path, *args, **kwargs):
        self.assertEqual(path, self.root / 'app/build.gradle')
        return self.source

    def run_tool(self, args, **kwargs):
        self.assertEqual(kwargs['cwd'], self.root)
        self.calls.append(tuple(args))
        name = Path(args[0]).name
        if self.failure == tuple(args) or self.failure == name:
            return subprocess.CompletedProcess(args, 2, stdout='synthetic tool failure')
        if args[:2] == ['git', 'rev-parse']:
            output = REVISION if args[2] == 'v0.1.2-rc1^{commit}' else next(self.heads)
        elif args == ['git', 'status', '--porcelain', '--untracked-files=all']:
            output = next(self.statuses)
        elif name == 'apksigner':
            if '--min-sdk-version' in args:
                self.assertEqual(args[1:], ['verify', '--verbose', '--print-certs',
                                           '--min-sdk-version', '24', '--max-sdk-version', '27', str(self.apk)])
                output = self.v2_signature
            else:
                self.assertEqual(args[1:], ['verify', '--verbose', '--print-certs', str(self.apk)])
                output = self.signature
        elif name == 'zipalign':
            self.assertEqual(args[1:], ['-c', '-P', '16', '4', str(self.apk)])
            output = ''
        elif name == 'aapt2':
            self.assertEqual(args[1:], ['dump', 'badging', str(self.apk)])
            output = self.badging
        else:
            self.fail(f'Unexpected command: {args}')
        return subprocess.CompletedProcess(args, 0, stdout=output)

    def open_dump(self, args, **kwargs):
        self.assertEqual(args, [str(self.tools / 'dexdump'), '-d', str(self.apk)])
        self.assertEqual(kwargs['cwd'], self.root)
        self.calls.append(tuple(args))
        self.on_dump()
        dump = MagicMock()
        dump.__enter__.return_value = dump
        dump.stdout = iter(self.dump_lines)
        dump.wait.return_value = self.dump_status
        return dump

    def verify(self):
        return release.verify('v0.1.2-rc1', self.apk, self.tools)

    def test_success_runs_real_zip_decoder_policy_hash_and_final_git_checks(self):
        result = self.verify()
        self.assertEqual(result['sha256'], hashlib.sha256(self.data).hexdigest())
        self.assertEqual(result['commit'], REVISION)
        self.assertEqual(result['signingBlocks'], ['0x7109871a'])
        self.assertEqual(result['versionCode'], 3)
        self.assertTrue(result['runtimeErrorAllocations'])
        self.assertEqual(result['nativeAbis'], sorted(ABI_ELF))
        self.assertTrue(result['elfLoadAlign16KiB'])
        self.assertTrue(result['zipalign16KiB'])
        self.assertEqual([Path(args[0]).name for args in self.calls],
                         ['git', 'git', 'git', 'apksigner', 'apksigner', 'zipalign', 'aapt2', 'dexdump', 'git', 'git'])
        self.assertEqual(self.calls[-2:], [('git', 'rev-parse', 'HEAD'),
                                         ('git', 'status', '--porcelain', '--untracked-files=all')])

    def test_zip_aligned_native_entry_with_second_load_at_4k_is_rejected(self):
        libraries = native_fixtures()
        libraries[ARM64_LIBRARY] = (
            tiny_elf('arm64-v8a', ((1, 0, 0, 0x4000), (1, 0x100, 0x4100, 0x1000))),
            zipfile.ZIP_STORED,
        )
        data = tiny_apk(libraries=libraries, align_native=True)
        with zipfile.ZipFile(io.BytesIO(data)) as archive:
            entry = archive.getinfo(ARM64_LIBRARY)
            name_size, extra_size = struct.unpack_from('<HH', data, entry.header_offset + 26)
            self.assertEqual((entry.header_offset + 30 + name_size + extra_size) % 0x4000, 0)
        self.apk.write_bytes(data)
        with self.assertRaisesRegex(ValueError, r'PT_LOAD header 1 alignment 0x1000'):
            self.verify()
        self.assertEqual([args[0] for args in self.calls], ['git'] * 3)

    def test_apk_reads_are_bounded_through_both_hashes(self):
        # Instrument actual file reads solely to enforce the memory bound;
        # ZIP decoding, hashing and gate decisions still execute normally.
        real_open = Path.open
        reads = []

        class BoundedFile:
            def __init__(self, raw):
                self.raw = raw

            def __enter__(self):
                return self

            def __exit__(self, *args):
                return self.raw.__exit__(*args)

            def __getattr__(self, name):
                return getattr(self.raw, name)

            def read(self, size=-1):
                if not 0 < size <= 1024 * 1024:
                    raise AssertionError(f'Unbounded or oversized APK read: {size}')
                reads.append(size)
                return self.raw.read(size)

        def bounded_open(path, *args, **kwargs):
            raw = real_open(path, *args, **kwargs)
            return BoundedFile(raw) if path == self.apk else raw

        with patch.object(Path, 'open', autospec=True, side_effect=bounded_open):
            self.assertEqual(self.verify()['sha256'], hashlib.sha256(self.data).hexdigest())
        self.assertTrue(reads)

    def test_final_head_change_rejected_after_tool_checks(self):
        self.heads = iter([REVISION, '2' * 40])
        with self.assertRaisesRegex(ValueError, 'HEAD changed during verification'):
            self.verify()
        self.assertEqual(Path(self.calls[-2][0]).name, 'dexdump')

    def test_final_dirty_tracked_or_untracked_tree_rejected(self):
        for status in (' M app/build.gradle\n', '?? unexpected.txt\n'):
            with self.subTest(status=status):
                self.heads = iter([REVISION, REVISION])
                self.statuses = iter(['', status])
                with self.assertRaisesRegex(ValueError, 'Source changed during verification'):
                    self.verify()
                self.assertEqual(Path(self.calls[-3][0]).name, 'dexdump')

    def test_final_digest_change_rejected_even_when_size_is_unchanged(self):
        def mutate():
            data = bytearray(self.data)
            data[0] ^= 1
            self.apk.write_bytes(data)
        self.on_dump = mutate
        with self.assertRaisesRegex(ValueError, 'APK changed during verification'):
            self.verify()
        self.assertEqual(self.apk.stat().st_size, len(self.data))
        self.assertEqual(self.calls[-1][1], 'status')

    def test_final_apk_unavailable_fails_closed(self):
        self.on_dump = lambda: self.apk.rename(self.apk.with_suffix('.preserved'))
        with self.assertRaises(FileNotFoundError):
            self.verify()
        self.assertEqual(self.calls[-1][1], 'status')

    def test_external_tool_nonzero_status_rejected(self):
        for name in ('apksigner', 'zipalign', 'aapt2'):
            with self.subTest(tool=name):
                self.heads = iter([REVISION, REVISION])
                self.statuses = iter(['', ''])
                self.failure = name
                with self.assertRaisesRegex(ValueError, name + r' failed \(2\)'):
                    self.verify()
                self.assertEqual(Path(self.calls[-1][0]).name, name)

    def test_missing_external_tool_fails_closed(self):
        with patch.object(release.subprocess, 'Popen', side_effect=FileNotFoundError('dexdump')):
            with self.assertRaisesRegex(FileNotFoundError, 'dexdump'):
                self.verify()
        self.assertEqual(Path(self.calls[-1][0]).name, 'aapt2')

    def test_failed_signature_output_rejected_before_alignment(self):
        self.signature = self.signature.replace('): true', '): false')
        with self.assertRaisesRegex(ValueError, 'v2 signature verification failed'):
            self.verify()
        self.assertEqual(Path(self.calls[-1][0]).name, 'apksigner')

    def use_v3_apk(self):
        self.apk.write_bytes(tiny_apk(signing_entry(V2) + signing_entry(0xF05368C0)))
        self.signature = (SignerOutputTests().output('V3.0 Signer:', scheme='v3') +
                          'Verified using v2 scheme (APK Signature Scheme v2): false\n')

    def test_build_tools_37_default_v3_and_separate_v2_both_verify(self):
        self.use_v3_apk()
        result = self.verify()
        self.assertEqual(result['signingBlocks'], ['0x7109871a', '0xf05368c0'])
        commands = [args for args in self.calls if Path(args[0]).name == 'apksigner']
        self.assertEqual(commands, [
            (str(self.tools / 'apksigner'), 'verify', '--verbose', '--print-certs', str(self.apk)),
            (str(self.tools / 'apksigner'), 'verify', '--verbose', '--print-certs',
             '--min-sdk-version', '24', '--max-sdk-version', '27', str(self.apk)),
        ])

    def test_v3_block_cannot_fall_back_to_successful_v2_output(self):
        self.use_v3_apk()
        self.signature = self.v2_signature
        with self.assertRaisesRegex(ValueError, 'v3 signature verification failed'):
            self.verify()
        self.assertEqual(sum(Path(args[0]).name == 'apksigner' for args in self.calls), 1)

    def test_separate_v2_failure_or_different_signer_stops_before_alignment(self):
        for bad in (self.v2_signature.replace('): true', '): false'),
                    self.v2_signature.replace(SIGNER, '0' * 64),
                    self.v2_signature.replace('Number of signers: 1', 'Number of signers: 2')):
            with self.subTest(output=bad):
                self.heads = iter([REVISION, REVISION])
                self.statuses = iter(['', ''])
                self.calls.clear()
                self.use_v3_apk()
                self.v2_signature = bad
                with self.assertRaises(ValueError):
                    self.verify()
                self.assertEqual([Path(args[0]).name for args in self.calls],
                                 ['git', 'git', 'git', 'apksigner', 'apksigner'])

    def test_separate_v2_tool_failure_is_not_ignored(self):
        self.use_v3_apk()
        self.failure = (str(self.tools / 'apksigner'), 'verify', '--verbose', '--print-certs',
                        '--min-sdk-version', '24', '--max-sdk-version', '27', str(self.apk))
        with self.assertRaisesRegex(ValueError, r'apksigner failed \(2\)'):
            self.verify()
        self.assertEqual(self.calls[-1], self.failure)

    def test_dexdump_nonzero_status_rejected_despite_expected_allocations(self):
        self.dump_status = 2
        with self.assertRaisesRegex(ValueError, 'dexdump failed'):
            self.verify()
        self.assertEqual(Path(self.calls[-1][0]).name, 'dexdump')

    def test_final_git_failures_are_not_clean_results(self):
        for command in (('git', 'rev-parse', 'HEAD'),
                        ('git', 'status', '--porcelain', '--untracked-files=all')):
            with self.subTest(command=command):
                self.heads = iter([REVISION, REVISION])
                self.statuses = iter(['', ''])
                self.failure = None
                self.on_dump = lambda: setattr(self, 'failure', command)
                with self.assertRaisesRegex(ValueError, r'git failed \(2\)'):
                    self.verify()
                self.assertEqual(self.calls[-1], command)

    def test_binary_dependency_block_stops_before_external_tools(self):
        self.apk.write_bytes(tiny_apk(signing_entry(V2) + signing_entry(0x504B4453)))
        with self.assertRaisesRegex(ValueError, 'dependency metadata'):
            self.verify()
        self.assertEqual([args[0] for args in self.calls], ['git'] * 3)


if __name__ == '__main__':
    unittest.main()
