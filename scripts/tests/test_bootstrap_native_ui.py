"""Offline integration tests for the committed native-ui bundle and bootstrap."""
import json
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest


ROOT = Path(__file__).resolve().parents[2]


class NativeUiBootstrapTest(unittest.TestCase):
    def setUp(self):
        scratch = ROOT / '.tmp'
        scratch.mkdir(exist_ok=True)
        self.temporary = tempfile.TemporaryDirectory(prefix='native-ui-bootstrap-test-', dir=scratch)
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.pin = json.loads((ROOT / 'third_party/native-ui.json').read_text())

    def git(self, root, *args):
        result = subprocess.run(['git', *args], cwd=root, text=True, capture_output=True)
        self.assertEqual(0, result.returncode, result.stderr)
        return result.stdout.strip()

    def project(self, name='project', repository=False):
        root = self.root / name
        (root / 'scripts').mkdir(parents=True)
        (root / 'third_party').mkdir()
        for name in ['scripts/bootstrap-native-ui.py', 'third_party/native-ui.json',
                     'third_party/native-ui.bundle', '.gitmodules']:
            shutil.copyfile(ROOT / name, root / name)
        if repository:
            self.git(root, 'init', '--quiet')
            self.git(root, 'add', '.')
            self.git(root, 'update-index', '--add', '--cacheinfo',
                     f'160000,{self.pin["commit"]},native-ui')
            self.git(root, '-c', 'user.name=Bootstrap test', '-c',
                     'user.email=bootstrap-test@example.invalid', 'commit', '--quiet', '-m', 'fixture')
        return root

    def bootstrap(self, root, *args, success=True):
        result = subprocess.run([sys.executable, 'scripts/bootstrap-native-ui.py', *args],
                                cwd=root, text=True, capture_output=True)
        if success:
            self.assertEqual(0, result.returncode, result.stderr)
            receipt = json.loads(result.stdout)
            self.assertEqual(self.pin['commit'], receipt['commit'])
            return receipt
        self.assertNotEqual(0, result.returncode, result.stdout)
        self.assertNotIn('Traceback', result.stderr)
        return result.stderr

    def test_plain_clone_initializes_exact_detached_gitlink(self):
        source = self.project('source', repository=True)
        clone = self.root / 'clone'
        self.git(self.root, 'clone', '--quiet', str(source), str(clone))
        receipt = self.bootstrap(clone)
        self.assertTrue(receipt['initialized'])
        self.assertTrue(receipt['parent_gitlink_verified'])
        self.assertEqual('HEAD', self.git(clone / 'native-ui', 'rev-parse', '--abbrev-ref', 'HEAD'))
        self.assertEqual('', self.git(clone, 'status', '--porcelain'))
        self.assertFalse(self.bootstrap(clone, '--check')['initialized'])

    def test_source_archive_initializes_without_parent_git(self):
        root = self.project()
        self.assertFalse(self.bootstrap(root)['parent_gitlink_verified'])
        self.assertFalse(self.bootstrap(root)['initialized'])

    def test_ignored_build_cache_does_not_block(self):
        root = self.project()
        self.bootstrap(root)
        cache = root / 'native-ui/build/preserved.txt'
        cache.parent.mkdir()
        cache.write_text('owned build cache')
        self.bootstrap(root, '--check')
        self.assertEqual('owned build cache', cache.read_text())

    def test_dirty_checkout_is_preserved(self):
        for filename in ['README.md', 'untracked-work.txt']:
            with self.subTest(filename=filename):
                root = self.project(filename.replace('.', '-'))
                self.bootstrap(root)
                changed = root / 'native-ui' / filename
                changed.write_text('local library work')
                self.assertIn('local changes', self.bootstrap(root, success=False))
                self.assertEqual('local library work', changed.read_text())

    def test_conflicting_directory_is_preserved(self):
        root = self.project(repository=True)
        child = root / 'native-ui'
        child.mkdir()
        sentinel = child / 'local.txt'
        sentinel.write_text('do not replace')
        self.bootstrap(root, success=False)
        self.assertEqual('do not replace', sentinel.read_text())
        self.assertFalse((child / '.git').exists())

    def test_wrong_commit_is_preserved(self):
        root = self.project()
        self.bootstrap(root)
        child = root / 'native-ui'
        previous = self.git(child, 'rev-parse', 'HEAD^')
        self.git(child, 'checkout', '--quiet', '--detach', previous)
        self.assertIn('another commit', self.bootstrap(root, success=False))
        self.assertEqual(previous, self.git(child, 'rev-parse', 'HEAD'))

    def test_bundle_digest_mismatch_cannot_initialize(self):
        root = self.project()
        bundle = root / 'third_party/native-ui.bundle'
        with bundle.open('ab') as stream:
            stream.write(b'changed')
        self.assertIn('digest mismatch', self.bootstrap(root, success=False))
        self.assertFalse((root / 'native-ui').exists())

    def test_parent_gitlink_mismatch_cannot_initialize(self):
        root = self.project(repository=True)
        self.git(root, 'update-index', '--cacheinfo', f'160000,{"1" * 40},native-ui')
        self.assertIn('index entry', self.bootstrap(root, success=False))
        self.assertFalse((root / 'native-ui').exists())

    def test_malformed_pin_cannot_initialize(self):
        root = self.project()
        for value in [[], {**self.pin, 'commit': 1}]:
            (root / 'third_party/native-ui.json').write_text(json.dumps(value))
            self.bootstrap(root, success=False)
            self.assertFalse((root / 'native-ui').exists())

    def test_symlink_is_not_followed(self):
        root = self.project()
        target = self.root / 'existing'
        target.mkdir()
        (root / 'native-ui').symlink_to(target, target_is_directory=True)
        self.assertIn('symlink', self.bootstrap(root, success=False))
        self.assertEqual([], list(target.iterdir()))

    def test_check_does_not_initialize(self):
        root = self.project()
        self.assertIn('missing', self.bootstrap(root, '--check', success=False))
        self.assertFalse((root / 'native-ui').exists())


if __name__ == '__main__':
    unittest.main()
