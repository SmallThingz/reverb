"""Negative regressions for the release failures documented in F-Droid MR !50231."""
import sys
sys.dont_write_bytecode = True
import unittest
from verify_release import verify_revision, verify_block_policy


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


if __name__ == '__main__':
    unittest.main()
