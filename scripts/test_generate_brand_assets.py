"""Bounded brand-generation regressions; all mutations stay in temporary fixtures."""
import shutil
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path
import xml.etree.ElementTree as ET

import generate_brand_assets as brand


class BrandGenerationTest(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory(prefix="reverb-brand-test-")
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        sources = ["scripts/generate_brand_assets.py", brand.SOURCE]
        sources += [path.relative_to(brand.ROOT).as_posix() for path in
                    (brand.ROOT / "native-ui/src/main/res").glob("values*/reverb_brand_colors.xml")]
        for name in sources:
            target = self.root / name
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(brand.ROOT / name, target)
        for name, data in brand.assets(android_only=True).items():
            target = self.root / name
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes(data)

    def run_generator(self, check=True):
        command = [sys.executable, "-B", str(self.root / "scripts/generate_brand_assets.py"), "--android-only"]
        if check:
            command.append("--check")
        return subprocess.run(command, capture_output=True, text=True, timeout=10)

    def replace(self, name, before, after):
        path = self.root / name
        contents = path.read_text()
        self.assertIn(before, contents)
        path.write_text(contents.replace(before, after))

    def test_clean_runtime_assets_need_no_store_renderer(self):
        result = self.run_generator()
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertFalse((self.root / "fastlane").exists())

    def test_old_in_app_anchor_is_rejected(self):
        self.replace("native-ui/src/main/kotlin/smallthingz/ui/reverb/ReverbBrandMarkView.kt",
                     "originX = 28.5f", "originX = 44.5f")
        result = self.run_generator()
        self.assertNotEqual(0, result.returncode)
        self.assertIn("ReverbBrandMarkView.kt", result.stderr)

    def test_independent_night_launcher_color_is_rejected(self):
        self.replace("app/src/main/res/values-night-v31/colors.xml", "@color/reverb_brand_main", "#FFFFFF")
        result = self.run_generator()
        self.assertNotEqual(0, result.returncode)
        self.assertIn("values-night-v31/colors.xml", result.stderr)

    def test_new_variant_color_override_is_rejected(self):
        override = self.root / "app/src/debug/res/values-v35/colors.xml"
        override.parent.mkdir(parents=True)
        override.write_text('<resources><color name="launcher_main">#FFFFFF</color></resources>')
        result = self.run_generator()
        self.assertNotEqual(0, result.returncode)
        self.assertIn("Brand color override outside canonical/generated palette", result.stderr)

    def assert_geometry_override_rejected(self, relative, content):
        override = self.root / relative
        override.parent.mkdir(parents=True, exist_ok=True)
        override.write_bytes(content)
        try:
            result = self.run_generator()
            self.assertNotEqual(0, result.returncode)
            self.assertIn("Brand geometry override outside generated resources", result.stderr)
            self.assertIn(relative, result.stderr)
        finally:
            override.unlink()

    def test_variant_generated_geometry_overrides_are_rejected(self):
        for kind, name in (("drawable", "ic_launcher_foreground"),
                           ("animator", "reverb_splash_far_translate")):
            with self.subTest(kind=kind):
                source = self.root / f"app/src/main/res/{kind}/{name}.xml"
                self.assert_geometry_override_rejected(f"app/src/debug/res/{kind}/{name}.xml", source.read_bytes())

    def test_qualified_generated_geometry_overrides_are_rejected(self):
        for kind, name in (("drawable", "ic_launcher_monochrome"),
                           ("animator", "reverb_splash_near_alpha")):
            with self.subTest(kind=kind):
                source = self.root / f"app/src/main/res/{kind}/{name}.xml"
                self.assert_geometry_override_rejected(f"app/src/main/res/{kind}-night-v31/{name}.xml", source.read_bytes())
        # Resource-name matching also covers bitmap/nine-patch replacements, not only XML.
        self.assert_geometry_override_rejected("app/src/release/res/drawable-hdpi/ic_launcher_foreground.9.png", b"bitmap")

    def test_generated_geometry_values_alias_overrides_are_rejected(self):
        for kind, name in (("drawable", "reverb_splash_vector"),
                           ("animator", "reverb_splash_far_alpha")):
            with self.subTest(kind=kind):
                content = f'<resources><item type="{kind}" name="{name}">@{kind}/other</item></resources>'
                self.assert_geometry_override_rejected("app/src/debug/res/values-night/brand.xml", content.encode())

    def test_unsupported_svg_transform_is_rejected(self):
        self.replace(brand.SOURCE, 'id="body" d=', 'id="body" transform="translate(1 0)" d=')
        result = self.run_generator()
        self.assertNotEqual(0, result.returncode)
        self.assertIn("Unsupported canonical SVG attributes", result.stderr)

    def test_canonical_anchor_change_regenerates_both_renderers(self):
        self.replace(brand.SOURCE, "translate(28.5 0)", "translate(29.5 0)")
        self.assertNotEqual(0, self.run_generator().returncode)
        result = self.run_generator(check=False)
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertEqual(0, self.run_generator().returncode)
        renderer = self.root / "native-ui/src/main/kotlin/smallthingz/ui/reverb/ReverbBrandMarkView.kt"
        self.assertIn("originX = 29.5f", renderer.read_text())
        vector = ET.parse(self.root / "app/src/main/res/drawable/ic_launcher_foreground.xml").getroot()
        self.assertEqual("29.5", vector.find("group").get("{" + brand.ANDROID + "}translateX"))


if __name__ == "__main__":
    unittest.main()
