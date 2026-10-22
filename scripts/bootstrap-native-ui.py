#!/usr/bin/env python3
"""Restore Reverb's pinned native-ui checkout from its committed offline bundle."""
import argparse
import hashlib
import json
import re
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path


ROOT = Path(__file__).resolve().parent.parent
MODULE = ROOT / 'native-ui'
BUNDLE = ROOT / 'third_party/native-ui.bundle'
PIN = ROOT / 'third_party/native-ui.json'


def require(condition, message):
    if not condition:
        raise RuntimeError(message)


def git(*arguments, cwd=ROOT):
    result = subprocess.run(['git', *arguments], cwd=cwd, text=True,
                            stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    if result.returncode:
        raise RuntimeError(result.stderr.strip() or 'Git command failed')
    return result.stdout.strip()


def digest(path):
    value = hashlib.sha256()
    with path.open('rb') as source:
        for block in iter(lambda: source.read(1024 * 1024), b''):
            value.update(block)
    return value.hexdigest()


def check_root_pin(commit):
    metadata = ROOT / '.git'
    if not (metadata.exists() or metadata.is_symlink()):
        return False  # A source archive uses the same committed pin and bundle.
    require(Path(git('rev-parse', '--show-toplevel')).resolve() == ROOT,
            'Expected Reverb to be the Git checkout root')
    entry = git('ls-files', '--stage', '--', 'native-ui')
    require(entry == f'160000 {commit} 0\tnative-ui',
            'native-ui Git index entry does not match the committed pin; '
            'stage the intended gitlink before bootstrapping')
    return True


def check_child(path, commit):
    require(not path.is_symlink(), 'native-ui must be its own checkout, not a symlink')
    require(Path(git('rev-parse', '--show-toplevel', cwd=path)).resolve() == path.resolve(),
            'native-ui is not an independent Git checkout')
    require(git('rev-parse', 'HEAD', cwd=path) == commit,
            'native-ui points at another commit; existing work was preserved')
    require(not git('status', '--porcelain=v1', '--untracked-files=all', cwd=path),
            'native-ui contains local changes; existing work was preserved')


def register_child():
    # Absolute paths live only in this checkout's untracked Git configuration.
    git('config', '--local', 'submodule.native-ui.url', str(BUNDLE))
    git('config', '--local', 'submodule.native-ui.active', 'true')
    git('submodule', 'absorbgitdirs', '--', 'native-ui')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--check', action='store_true', help='Verify without initializing a checkout')
    args = parser.parse_args()
    pin = json.loads(PIN.read_text(encoding='utf-8'))
    require(isinstance(pin, dict) and pin.get('schema') == 1 and pin.get('path') == 'native-ui'
            and pin.get('bundle') == 'third_party/native-ui.bundle', 'Unsupported native-ui pin')
    commit, checksum = pin.get('commit', ''), pin.get('bundle_sha256', '')
    require(isinstance(commit, str) and isinstance(checksum, str)
            and re.fullmatch('[0-9a-f]{40}', commit) and re.fullmatch('[0-9a-f]{64}', checksum),
            'Malformed native-ui commit or bundle digest')
    require(BUNDLE.is_file() and digest(BUNDLE) == checksum, 'native-ui bundle digest mismatch')
    heads = git('bundle', 'list-heads', str(BUNDLE)).splitlines()
    require(any(line.split(' ', 1)[0] == commit for line in heads),
            'Pinned native-ui commit is not advertised by the bundle')
    parent_is_git = check_root_pin(commit)
    require(not MODULE.is_symlink(), 'native-ui must not be a symlink')
    initialized = False
    if MODULE.exists() and MODULE.is_dir() and any(MODULE.iterdir()):
        check_child(MODULE, commit)
    else:
        require(not args.check, 'native-ui is missing; run this script without --check')
        require(not MODULE.exists() or MODULE.is_dir(), 'native-ui path conflicts with an existing file')
        staging = Path(tempfile.mkdtemp(prefix='.native-ui-bootstrap-', dir=ROOT))
        try:
            # Direct cloning from this verified local bundle needs no network,
            # global Git configuration or protocol permission override.
            git('clone', '--quiet', '--no-checkout', str(BUNDLE), str(staging))
            git('checkout', '--quiet', '--detach', commit, cwd=staging)
            check_child(staging, commit)
            if MODULE.exists():
                require(MODULE.is_dir() and not MODULE.is_symlink() and not any(MODULE.iterdir()),
                        'native-ui changed during bootstrap; existing work was preserved')
                MODULE.rmdir()
            staging.rename(MODULE)
            initialized = True
        finally:
            if staging.exists():
                shutil.rmtree(staging)
    if parent_is_git and not args.check:
        register_child()
    check_child(MODULE, commit)
    print(json.dumps({'status': 'ready', 'commit': commit, 'bundle_sha256': checksum,
                      'initialized': initialized, 'parent_gitlink_verified': parent_is_git}))


if __name__ == '__main__':
    try:
        main()
    except (OSError, ValueError, RuntimeError) as error:
        print(f'native-ui: {error}', file=sys.stderr)
        sys.exit(1)
