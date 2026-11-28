#!/usr/bin/env python3
"""Generate Reverb brand assets from library artwork and foreground exports; Python only."""
from pathlib import Path
import argparse
import math
import re
import subprocess
import sys
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
SOURCE = "native-ui/src/main/brand/reverb.svg"
PLATE_CHECKER = "native-ui/scripts/generate_reverb_brand_plate.py"
NATIVE_MARK = "native-ui/src/main/adapters/kotlin/smallthingz/ui/reverb/ReverbBrandMarkView.kt"
MARK_SIZE_DP = 108
# Library-owned exports of the fixed-dark foreground and deterministic icon grain.
BRAND_SURFACE_FILES = {
    f"native-ui/src/main/res/{qualifier}/reverb_brand_surface.xml"
    for qualifier in ("values", "values-night", "values-v31", "values-night-v31", "values-v34", "values-night-v34")
}
BRAND_PLATE_FILES = {
    "native-ui/src/main/res/drawable/reverb_brand_plate.xml",
    "native-ui/src/main/res/drawable/reverb_brand_grain.xml",
    "native-ui/src/main/res/drawable-nodpi/reverb_brand_grain_tile.png",
}
SPLASH_PALETTES = {
    "app/src/main/res/values/reverb_splash_colors.xml": (
        "@color/native_default_light_background", "true", "@color/reverb_brand_background",
    ),
    "app/src/main/res/values-night/reverb_splash_colors.xml": (
        "@color/native_default_amoled_background", "false", "@color/reverb_brand_main",
    ),
}
SVG = "{http://www.w3.org/2000/svg}"
ANDROID = "http://schemas.android.com/apk/res/android"
ET.register_namespace("android", ANDROID)


def android(**values):
    return {f"{{{ANDROID}}}{key}": value for key, value in values.items()}


def encode(element, *, generated_comment=True):
    ET.indent(element, space="    ")
    header = '<?xml version="1.0" encoding="utf-8"?>\n'
    if generated_comment:
        header += f'<!-- Generated from {SOURCE}; run scripts/generate_brand_assets.py. -->\n'
    return (header + ET.tostring(element, encoding="unicode") + "\n").encode()


def brand_wrapper_specs():
    res = "app/src/main/res"
    wrappers = {}
    for version in (26, 33):
        adaptive = ET.Element("adaptive-icon")
        ET.SubElement(adaptive, "background", android(drawable="@drawable/ic_launcher_background"))
        ET.SubElement(adaptive, "foreground", android(drawable="@drawable/ic_launcher_foreground_inset"))
        if version == 33:
            ET.SubElement(adaptive, "monochrome", android(drawable="@drawable/ic_launcher_monochrome_inset"))
        for name in ("ic_launcher", "ic_launcher_round"):
            wrappers[f"{res}/mipmap-anydpi-v{version}/{name}.xml"] = adaptive
    for variant in ("foreground", "monochrome"):
        name = f"ic_launcher_{variant}"
        wrappers[f"{res}/drawable/{name}_inset.xml"] = ET.Element("inset", android(
            insetLeft="0dp", insetTop="0dp", insetRight="0dp", insetBottom="0dp", drawable=f"@drawable/{name}"))
    splash = ET.Element("animated-vector", android(drawable="@drawable/reverb_splash_vector"))
    for target, animation in (("far_echo", "far_translate"), ("near_body", "near_alpha"),
                              ("near_leg", "near_alpha"), ("far_body", "far_alpha"), ("far_leg", "far_alpha")):
        ET.SubElement(splash, "target", android(name=target, animation=f"@animator/reverb_splash_{animation}"))
    wrappers[f"{res}/drawable/reverb_splash_animated_icon.xml"] = splash
    return wrappers


def resource_key(path):
    return path.parent.name.split("-", 1)[0], path.name.split(".", 1)[0]


def kotlin_float(value):
    if not math.isfinite(float(value)):
        raise ValueError("Brand coordinates must be finite")
    return value + "f"


def native_path(data):
    token = r"[MLC]|[-+]?(?:\d+(?:\.\d*)?|\.\d+)(?:[eE][-+]?\d+)?"
    if re.sub(token, "", data).strip(" ,\t\r\n"):
        raise ValueError("Native brand paths support only explicit absolute M, L and C commands")
    tokens = re.findall(token, data)
    if not tokens or tokens[0] != "M":
        raise ValueError("Each brand path must start with M")
    commands = {"M": ("moveTo", 2), "L": ("lineTo", 2), "C": ("cubicTo", 6)}
    lines = []
    index = 0
    while index < len(tokens):
        command = tokens[index]
        if command not in commands:
            raise ValueError("Every native brand path segment needs an explicit command")
        method, count = commands[command]
        arguments = tokens[index + 1:index + 1 + count]
        if len(arguments) != count:
            raise ValueError("Incomplete brand path segment")
        lines.append("            " + method + "(" + ", ".join(map(kotlin_float, arguments)) + ")")
        index += 1 + count
    return "\n".join(lines)


def native_mark(glyph, mark, origin):
    paths = ",\n".join("        Path().apply {\n" + native_path(path.attrib["d"]) + "\n        }" for path in glyph)
    layers = ",\n".join("        NativeLayeredMarkStroke(" + kotlin_float(layer.attrib["x"]) + ", "
                         + kotlin_float(layer.attrib["stroke-opacity"]) + ", accent = "
                         + ("false" if layer.attrib["id"] == "main" else "true") + ")" for layer in mark)
    return f'''// Generated from {SOURCE}; run scripts/generate_brand_assets.py. Do not edit.
package smallthingz.ui.reverb

import android.content.Context
import android.graphics.Path
import smallthingz.ui.R
import smallthingz.ui.components.NativeLayeredMarkArt
import smallthingz.ui.components.NativeLayeredMarkPalette
import smallthingz.ui.components.NativeLayeredMarkStroke
import smallthingz.ui.components.NativeLayeredMarkView
import smallthingz.ui.components.nativeLayeredMarkBounds

/** Canonical Reverb artwork in the retained native renderer; palette ownership stays in this library. */
class ReverbBrandMarkView(context: Context, description: CharSequence? = null) :
    NativeLayeredMarkView(context, description, reverbBrandArt, ::readReverbBrandPalette) {{
    init {{ bindDefaultReverbBrandSurface() }}
}}

private const val BRAND_SIZE_DP = {MARK_SIZE_DP}f

private val reverbBrandArt = NativeLayeredMarkArt(
    sizeDp = BRAND_SIZE_DP,
    viewport = 512f,
    strokeWidth = {kotlin_float(glyph.attrib["stroke-width"])},
    originX = {kotlin_float(origin)},
    paths = listOf(
{paths},
    ),
    layers = listOf(
{layers},
    ),
)

fun readReverbBrandPalette(context: Context): NativeLayeredMarkPalette = NativeLayeredMarkPalette(
    context.resources.getColor(R.color.reverb_brand_background, context.theme),
    context.resources.getColor(R.color.reverb_brand_accent, context.theme),
    context.resources.getColor(R.color.reverb_brand_main, context.theme),
)

fun reverbBrandFillBounds(density: Float, width: Int, height: Int) = nativeLayeredMarkBounds(BRAND_SIZE_DP, density, width, height)
'''.encode()


def assets(android_only=False):
    # The library owns the color/texture derivation, including source and output drift checks.
    plate_check = subprocess.run([sys.executable, "-B", str(ROOT / PLATE_CHECKER), "--check"],
                                 capture_output=True, text=True, timeout=30)
    if plate_check.returncode != 0:
        raise ValueError("Shared brand plate export is stale: " + plate_check.stdout + plate_check.stderr)
    source = ROOT / SOURCE
    document = ET.parse(source).getroot()
    glyph = document.find(f"{SVG}defs/{SVG}g[@id='r']")
    mark = document.find(f"{SVG}g[@id='mark']")
    if document.get("viewBox") != "0 0 512 512" or glyph is None or mark is None:
        raise ValueError("Expected the canonical 512-square SVG, r and mark groups")
    translation = re.fullmatch(r"translate\(([-\d.]+) ([-\d.]+)\)", mark.get("transform", ""))
    if translation is None or [p.get("id") for p in glyph] != ["body", "leg"]:
        raise ValueError("Define two R paths and one shared optical translation")
    if [layer.get("id") for layer in mark] != ["far", "near", "main"]:
        raise ValueError("Expected far, near, main paint order")
    far, near, main = list(mark)
    # Fail closed on SVG features the native Path renderer does not implement.
    def attributes(element, allowed):
        if set(element.attrib) != set(allowed.split()):
            raise ValueError(f"Unsupported canonical SVG attributes on {element.tag}: {element.attrib}")
    attributes(document, "width height viewBox role aria-label")
    attributes(glyph, "id fill stroke-linecap stroke-linejoin stroke-width")
    attributes(mark, "id transform")
    if [element.tag for element in document] != [SVG + "circle", SVG + "defs", SVG + "g"]:
        raise ValueError("Expected only the canonical circle, glyph definitions and mark")
    definitions = document.find(SVG + "defs")
    if definitions.attrib or list(definitions) != [glyph]:
        raise ValueError("Expected one untransformed glyph definition")
    if document.get("width") != "512" or document.get("height") != "512":
        raise ValueError("The canonical SVG size must match its viewport")
    for path in glyph:
        if path.tag != SVG + "path":
            raise ValueError("Expected only path geometry in the glyph")
        attributes(path, "id d")
    for layer in mark:
        if layer.tag != SVG + "use":
            raise ValueError("Expected only glyph uses in the mark")
        attributes(layer, "id href x stroke stroke-opacity")
        if not 0 <= float(layer.attrib["stroke-opacity"]) <= 1:
            raise ValueError("Layer opacity must be between zero and one")
    if not 0 < float(glyph.attrib["stroke-width"]) < 512:
        raise ValueError("Stroke width must fit the canonical viewport")
    if any(layer.get("href") != "#r" for layer in mark) or main.get("x") != "0":
        raise ValueError("All layers must reuse the R, with main at the shared anchor")
    if float(translation[2]) != 0:
        raise ValueError("The native layered renderer requires a zero vertical root translation")
    if (glyph.get("fill"), glyph.get("stroke-linecap"), glyph.get("stroke-linejoin")) != ("none", "round", "round"):
        raise ValueError("The native layered renderer requires unfilled paths with round caps and joins")
    circle = document.find(SVG + "circle")
    if circle is None or [circle.get(name) for name in ("cx", "cy", "r")] != ["256", "256", "256"]:
        raise ValueError("The canonical mark requires its existing centered circular background")
    attributes(circle, "cx cy r fill")
    if any(re.fullmatch(r"#[0-9a-fA-F]{6}", color) is None for color in
           (circle.attrib["fill"], far.attrib["stroke"], near.attrib["stroke"], main.attrib["stroke"])):
        raise ValueError("Canonical SVG colors must use opaque six-digit RGB values")
    if far.get("stroke") != near.get("stroke"):
        raise ValueError("Both echoes must use the shared accent role")
    output = {
        "app/src/main/icon.svg": (f"<!-- Generated from {SOURCE}; run scripts/generate_brand_assets.py. -->\n".encode()
                                  + source.read_bytes()),
        NATIVE_MARK: native_mark(glyph, mark, translation[1]),
    }
    # The launcher cannot instantiate an app View: consume the library's resource plate directly.
    background = ET.Element("layer-list")
    ET.SubElement(background, "item", android(drawable="@drawable/reverb_brand_plate"))
    output["app/src/main/res/drawable/ic_launcher_background.xml"] = encode(background)
    for variant, filename in (("color", "ic_launcher_foreground"),
                              ("mono", "ic_launcher_monochrome"),
                              ("compat_splash", "reverb_splash_foreground"),
                              ("splash", "reverb_splash_vector")):
        size = f"{MARK_SIZE_DP * (4 if variant == 'splash' else 1)}dp"
        vector = ET.Element("vector", android(width=size, height=size, viewportWidth="512", viewportHeight="512"))
        group = ET.SubElement(vector, "group", android(name="mark_root", translateX=translation[1], translateY=translation[2]))
        for layer in mark:
            name = layer.attrib["id"]
            x = near.attrib["x"] if variant == "splash" and name == "far" else layer.attrib["x"]
            child = ET.SubElement(group, "group", android(name="main_r" if name == "main" else name + "_echo", translateX=x))
            for path in glyph:
                color = "#FFFFFFFF" if variant == "mono" else "@color/launcher_" + ("main" if name == "main" else "accent")
                # Both splash paths draw directly on a day/night window instead of the brand plate.
                if variant in ("compat_splash", "splash") and name == "main":
                    color = "@color/reverb_splash_compat_main"
                alpha = "0" if variant == "splash" and name != "main" else layer.attrib["stroke-opacity"]
                ET.SubElement(child, "path", android(
                    name=name + "_" + path.attrib["id"], pathData=path.attrib["d"],
                    fillColor="@android:color/transparent", strokeColor=color, strokeAlpha=alpha,
                    strokeWidth=glyph.attrib["stroke-width"], strokeLineCap=glyph.attrib["stroke-linecap"],
                    strokeLineJoin=glyph.attrib["stroke-linejoin"],
                ))
        output[f"app/src/main/res/drawable/{filename}.xml"] = encode(vector)
    for name, prop, start, end in (
        ("far_translate", "translateX", near.attrib["x"], far.attrib["x"]),
        ("far_alpha", "strokeAlpha", "0", far.attrib["stroke-opacity"]),
        ("near_alpha", "strokeAlpha", "0", near.attrib["stroke-opacity"]),
    ):
        output[f"app/src/main/res/animator/reverb_splash_{name}.xml"] = encode(ET.Element("objectAnimator", android(
            duration="960", interpolator="@interpolator/reverb_splash_reveal", propertyName=prop,
            valueFrom=start, valueTo=end, valueType="floatType",
        )))
    colors = ET.Element("resources")
    for name, value in (("background", "@color/reverb_brand_surface"),
                        ("accent", "@color/reverb_brand_surface_accent"),
                        ("main", "@color/reverb_brand_surface_main")):
        ET.SubElement(colors, "color", {"name": "reverb_brand_" + name}).text = value
    output["native-ui/src/main/res/values/reverb_brand_colors.xml"] = encode(colors)
    # Runtime palette ownership stays in native-ui. Android resolves each alias through the
    # library's active qualifier, so launcher and in-app colors cannot acquire separate values.
    qualifiers = {"values"} | {path.parent.name for path in
        (ROOT / "native-ui/src/main/res").glob("values*/reverb_brand_colors.xml")}
    roles = {"reverb_brand_background", "reverb_brand_accent", "reverb_brand_main"}
    for qualifier in sorted(qualifiers):
        if qualifier != "values":
            palette = ET.parse(ROOT / f"native-ui/src/main/res/{qualifier}/reverb_brand_colors.xml").getroot()
            if {entry.get("name") for entry in palette} != roles:
                raise ValueError(f"{qualifier} must define the three canonical brand color roles")
            # All configurations use one shared foreground/content chain, including fallback ink.
            for entry in palette:
                role = entry.get("name")[len("reverb_brand_"):]
                entry.text = "@color/reverb_brand_surface" + ("" if role == "background" else "_" + role)
            output[f"native-ui/src/main/res/{qualifier}/reverb_brand_colors.xml"] = encode(palette)
        aliases = ET.Element("resources")
        for role in ("background", "accent", "main"):
            ET.SubElement(aliases, "color", {"name": "launcher_" + role}).text = "@color/reverb_brand_" + role
        output[f"app/src/main/res/{qualifier}/colors.xml"] = encode(aliases)
    # A new qualifier or a second resource file must not override a generated alias silently.
    for module, prefix, managed in (
        ("app", "launcher_", set(output)),
        ("native-ui", "reverb_brand_", {
            f"native-ui/src/main/res/{qualifier}/reverb_brand_colors.xml" for qualifier in qualifiers} | BRAND_SURFACE_FILES),
    ):
        # The app can shadow a library resource too; ownership crosses the merge boundary.
        for path in (path for owner in ("app", "native-ui")
                     for path in (ROOT / owner).glob("src/*/res/values*/*.xml")):
            for entry in ET.parse(path).getroot():
                is_color = entry.tag == "color" or (entry.tag == "item" and entry.get("type") == "color")
                name = entry.get("name", "")
                if is_color and (name == "native_amoled_foreground_fill"
                                 or (name.startswith(prefix) and path.relative_to(ROOT).as_posix() not in managed)):
                    raise ValueError(f"Brand color override outside canonical/generated palette: {path.relative_to(ROOT)}")
    for name, (background, dark_navigation_icons, main_ink) in SPLASH_PALETTES.items():
        palette = ET.Element("resources")
        ET.SubElement(palette, "color", name="reverb_splash_background").text = background
        ET.SubElement(palette, "bool", name="reverb_splash_dark_navigation_icons").text = dark_navigation_icons
        ET.SubElement(palette, "color", name="reverb_splash_compat_main").text = main_ink
        output[name] = encode(palette, generated_comment=False)
    wrappers = brand_wrapper_specs()
    for name, expected in wrappers.items():
        try:
            actual = ET.parse(ROOT / name).getroot()
        except (OSError, ET.ParseError) as error:
            raise ValueError(f"Cannot read canonical brand wrapper: {name}") from error
        if ET.canonicalize(ET.tostring(actual), strip_text=True) != ET.canonicalize(
                ET.tostring(expected), strip_text=True):
            raise ValueError(f"Brand wrapper does not match the canonical recipe: {name}")

    # Android merges resources by type/name, regardless of source set, qualifier or extension.
    # The exact v26/v33 adaptive pairs are both managed, including each round alias.
    managed_geometry = {name for name in output
                        if name.startswith("app/src/main/res/") and Path(name).parent.name in ("drawable", "animator")}
    managed_geometry |= BRAND_PLATE_FILES | set(wrappers)
    geometry = {resource_key(Path(name)) for name in managed_geometry}
    for path in (path for owner in ("app", "native-ui")
                 for path in (ROOT / owner).glob("src/*/res/*/*")):
        if not path.is_file():
            continue
        key = resource_key(path)
        relative = path.relative_to(ROOT).as_posix()
        if key in geometry and relative not in managed_geometry:
            raise ValueError(f"Brand geometry override outside generated resources: {relative}")
        if key[0] == "values" and path.suffix == ".xml":
            for entry in ET.parse(path).getroot():
                key = (entry.get("type") if entry.tag == "item" else entry.tag, entry.get("name"))
                if key in {("color", "reverb_splash_background"), ("color", "reverb_splash_compat_main"),
                           ("bool", "reverb_splash_dark_navigation_icons")} and relative not in SPLASH_PALETTES:
                    raise ValueError(f"Splash color override outside canonical palette: {relative}")
                if key in geometry:
                    raise ValueError(f"Brand geometry override outside generated resources: {relative}")
    if not android_only:
        output["fastlane/metadata/android/en-US/images/icon.png"] = subprocess.check_output(
            ["rsvg-convert", "--width", "512", "--height", "512", str(source)], stderr=subprocess.PIPE)
    return output


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--check", action="store_true", help="Report drift without writing")
    parser.add_argument("--android-only", action="store_true",
                        help="Check/generate all runtime assets without the optional store PNG renderer")
    args = parser.parse_args()
    output = assets(android_only=args.android_only)
    changed = [name for name, data in output.items() if not (ROOT / name).exists() or (ROOT / name).read_bytes() != data]
    if args.check:
        if changed:
            raise SystemExit("Regenerate brand assets: " + ", ".join(changed))
        print(f"All {len(output)} generated brand assets match the canonical library sources")
    else:
        for name in changed:
            (ROOT / name).parent.mkdir(parents=True, exist_ok=True)
            (ROOT / name).write_bytes(output[name])
        print(f"Updated {len(changed)} brand assets from the canonical library sources")


if __name__ == "__main__":
    main()
