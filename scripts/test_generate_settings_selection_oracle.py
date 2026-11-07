"""Source-bound checks for frozen selection oracles and QA-only Theme resource aliases."""
import importlib.util
from pathlib import Path
import tempfile
import unittest
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
SPEC = importlib.util.spec_from_file_location(
    "selection_oracle", Path(__file__).with_name("generate-settings-selection-oracle.py"))
generator = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(generator)


class SelectionOracleSourceTest(unittest.TestCase):
    def test_current_oracles_match_original_pins_and_reverse_exactly(self):
        with tempfile.TemporaryDirectory() as directory:
            manifest = generator.generate(ROOT, Path(directory))
        self.assertEqual("ba93936a5ebaa3eddc4271025bb20d1373dcdf96b969974b2fa865d86cf069fb",
                         manifest["inputs_sha256"]["SettingsScreenRenderer.kt"])
        self.assertTrue(manifest["reverse_normalization_matches_original"])

    def test_undeclared_renderer_change_is_still_rejected(self):
        relative = Path("app/src/reliability/java/app/smallthingz/reverb")
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / relative).mkdir(parents=True)
            for name in generator.INPUTS:
                (root / relative / name).write_bytes((ROOT / relative / name).read_bytes())
            renderer = root / relative / "SettingsScreenRenderer.kt"
            renderer.write_bytes(renderer.read_bytes() + b"\n// undeclared change\n")
            with self.assertRaisesRegex(ValueError, "Frozen oracle input changed: SettingsScreenRenderer.kt"):
                generator.generate(root, root / "generated")

    def test_qa_aliases_resolve_only_to_library_owned_intrinsic_strings(self):
        aliases = ET.parse(ROOT / "app/src/reliability/res/values/theme_oracle_aliases.xml").getroot()
        expected = {"theme_" + suffix: "@string/native_theme_" + suffix
                    for suffix in ("title", "system", "light", "dark")}
        self.assertEqual(expected, {entry.attrib["name"]: entry.text for entry in aliases})
        self.assertTrue(all(entry.tag == "item" and entry.attrib["type"] == "string" for entry in aliases))
        resources = list((ROOT / "native-ui/src/main/res").glob("values*/native_theme_strings.xml"))
        self.assertEqual(15, len(resources))
        for resource in resources:
            with self.subTest(resource=resource.parent.name):
                self.assertEqual({target.removeprefix("@string/") for target in expected.values()},
                                 {entry.attrib["name"] for entry in ET.parse(resource).getroot()})


if __name__ == "__main__":
    unittest.main()
