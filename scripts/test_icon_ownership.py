"""App icon geometry must be library-owned; platform aliases preserve existing IDs."""
import json
from pathlib import Path
import re
import unittest
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]


class AppIconOwnershipTest(unittest.TestCase):
    def test_platform_aliases_resolve_to_library_artwork_without_local_overrides(self):
        manifest = json.loads((ROOT / 'native-ui/docs/resource-provenance/reverb-icons/OWNERSHIP.json').read_text())
        aliases = ET.parse(ROOT / 'app/src/main/res/values/native_icon_aliases.xml').getroot()
        actual = {item.attrib['name']: item.text for item in aliases.findall('item')}
        expected = {name: '@drawable/' + item['resource'] for name, item in manifest['platformResources'].items()}
        self.assertEqual(expected, actual)
        names = set(expected) | {item['resource'] for item in manifest['platformResources'].values()}
        for resource in (ROOT / 'app/src').glob('*/res/drawable*/*.xml'):
            self.assertNotIn(resource.stem, names, str(resource))

    def test_only_generated_launcher_assets_have_app_vector_geometry(self):
        drawable = ROOT / 'app/src/main/res/drawable'
        allowed = {
            drawable / 'ic_launcher_background.xml',
            drawable / 'ic_launcher_foreground.xml',
            drawable / 'ic_launcher_monochrome.xml',
            drawable / 'reverb_splash_foreground.xml',
            drawable / 'reverb_splash_vector.xml',
        }
        for resource in (ROOT / 'app/src').glob('*/res/**/*.xml'):
            if 'android:pathData=' in resource.read_text():
                self.assertIn(resource, allowed, str(resource))

    def test_icon_adapters_do_not_generate_paths(self):
        for source in (ROOT / 'app/src/main').rglob('*Icon*.kt'):
            self.assertIsNone(re.search(r'\b(?:moveTo|lineTo|cubicTo|rCubicTo|pathData|ImageVector)\b',
                                        source.read_text()), str(source))


if __name__ == '__main__':
    unittest.main()
