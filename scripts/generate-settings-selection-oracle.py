#!/usr/bin/env python3
"""Generate a separate QA oracle for the accepted f9610538 selection palette change.

Frozen renderer files remain untouched. Reverse normalization must reconstruct their exact bytes.
"""
import argparse
import hashlib
import json
from pathlib import Path
import re

COMMIT = 'f9610538eb27495e40f8e1a9bc5c5bb8be77a1d6'
INPUTS = {
    'SettingsScreenRenderer.kt': 'ba93936a5ebaa3eddc4271025bb20d1373dcdf96b969974b2fa865d86cf069fb',
    'ReleasedMainScreenParityOracle.kt': 'd9fea75a0477ae83a31e57417f3bdb8f3af45a35b567954d03dc3bc7992d8dac',
}
HEADER = '// Generated supplemental oracle: accepted selected fill/ink roles only; frozen original remains separate.\n'
RENAMES = {
    'SettingsScreenRenderer.kt': {
        'SettingsScreen': 'ReadableSelectionSettingsScreen',
        'SettingsInitialConfiguration': 'ReadableSelectionInitialConfiguration',
        'RETENTION_MODE_OPTIONS': 'READABLE_SELECTION_RETENTION_MODE_OPTIONS',
        'SettingsAudioGroup': 'ReadableSelectionAudioGroup',
        'settingsAudioDivider': 'readableSelectionAudioDivider',
    },
    'ReleasedMainScreenParityOracle.kt': {
        'ReleasedMainScreenParityOracle': 'ReadableSelectionMainScreenOracle',
        'MainParityOracleDragTarget': 'ReadableSelectionOracleDragTarget',
        'MAIN_PARITY_ORACLE_SETTLE_DURATION_MS': 'READABLE_SELECTION_ORACLE_SETTLE_DURATION_MS',
        'SettingsScreen': 'ReadableSelectionSettingsScreen',
    },
}
OUTPUTS = {
    'SettingsScreenRenderer.kt': 'ReadableSelectionSettingsScreenRenderer.kt',
    'ReleasedMainScreenParityOracle.kt': 'ReadableSelectionMainScreenOracle.kt',
}
ROLES = {
    'if (isSelected) chrome.raised else Color.Transparent':
        'if (isSelected) MaterialTheme.colorScheme.primary else Color.Transparent',
    'if (isSelected) chrome.ink else chrome.muted':
        'if (isSelected) MaterialTheme.colorScheme.onPrimary else chrome.muted',
}

def sha(data):
    return hashlib.sha256(data).hexdigest()

def generate(root, output):
    source = root / 'app/src/reliability/java/app/smallthingz/reverb'
    package = output / 'app/smallthingz/reverb'
    package.mkdir(parents=True, exist_ok=True)
    hashes = {}
    for name, expected in INPUTS.items():
        original = (source / name).read_bytes()
        if sha(original) != expected:
            raise ValueError('Frozen oracle input changed: ' + name)
        text = original.decode('utf-8')
        if name == 'SettingsScreenRenderer.kt':
            for old, new in ROLES.items():
                if text.count(old) != 1:
                    raise ValueError('Selection role substitution must occur exactly once: ' + old)
                text = text.replace(old, new)
        for old, new in RENAMES[name].items():
            text, count = re.subn(r'\b' + re.escape(old) + r'\b', new, text)
            if count == 0:
                raise ValueError('Missing unique supplemental symbol: ' + old)
        restored = text
        for old, new in reversed(list(RENAMES[name].items())):
            restored = re.sub(r'\b' + re.escape(new) + r'\b', old, restored)
        if name == 'SettingsScreenRenderer.kt':
            for old, new in ROLES.items():
                if restored.count(new) != 1:
                    raise ValueError('Supplemental role no longer uniquely reversible')
                restored = restored.replace(new, old)
        if restored.encode('utf-8') != original:
            raise ValueError('Supplemental oracle changed undeclared source: ' + name)
        generated = (HEADER + text).encode('utf-8')
        (package / OUTPUTS[name]).write_bytes(generated)
        hashes[OUTPUTS[name]] = sha(generated)
    provenance = '''package app.smallthingz.reverb

internal object ReadableSelectionOracleProvenance {
    const val acceptedCommit = "%s"
    const val settingsInputSha256 = "%s"
    const val mainInputSha256 = "%s"
    const val settingsOutputSha256 = "%s"
    const val mainOutputSha256 = "%s"
    const val policy = "Selected Settings Theme and Time/Size fill primary, ink onPrimary; all other reference source unchanged apart from unique names. Settled frames only."
}
''' % (COMMIT, INPUTS['SettingsScreenRenderer.kt'], INPUTS['ReleasedMainScreenParityOracle.kt'],
       hashes['ReadableSelectionSettingsScreenRenderer.kt'], hashes['ReadableSelectionMainScreenOracle.kt'])
    (package / 'ReadableSelectionOracleProvenance.kt').write_text(provenance)
    manifest = {'accepted_commit': COMMIT, 'inputs_sha256': INPUTS, 'outputs_sha256': hashes,
                'exact_role_substitutions': ROLES, 'symbol_renames': RENAMES,
                'reverse_normalization_matches_original': True}
    (output / 'selection-oracle-manifest.json').write_text(json.dumps(manifest, indent=2) + '\n')
    return manifest

if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--root', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    print(json.dumps(generate(args.root, args.output), indent=2))
