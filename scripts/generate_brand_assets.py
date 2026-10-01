#!/usr/bin/env python3
"""Generate Reverb's native and store marks from icon.svg; requires rsvg-convert."""
from pathlib import Path
import argparse
import re
import subprocess
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
SVG = "{http://www.w3.org/2000/svg}"
ANDROID = "http://schemas.android.com/apk/res/android"
ET.register_namespace("android", ANDROID)


def android(**values):
    return {f"{{{ANDROID}}}{key}": value for key, value in values.items()}


def encode(element):
    ET.indent(element, space="    ")
    return ('<?xml version="1.0" encoding="utf-8"?>\n'
            '<!-- Generated from app/src/main/icon.svg; run scripts/generate_brand_assets.py. -->\n'
            + ET.tostring(element, encoding="unicode") + "\n").encode()


def assets():
    source = ROOT / "app/src/main/icon.svg"
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
    if any(layer.get("href") != "#r" for layer in mark) or main.get("x") != "0":
        raise ValueError("All layers must reuse the R, with main at the shared anchor")
    output = {}
    for variant, filename in (("color", "ic_launcher_foreground"),
                              ("mono", "ic_launcher_monochrome"),
                              ("splash", "reverb_splash_vector")):
        size = "432dp" if variant == "splash" else "108dp"
        vector = ET.Element("vector", android(width=size, height=size, viewportWidth="512", viewportHeight="512"))
        group = ET.SubElement(vector, "group", android(name="mark_root", translateX=translation[1], translateY=translation[2]))
        for layer in mark:
            name = layer.attrib["id"]
            x = near.attrib["x"] if variant == "splash" and name == "far" else layer.attrib["x"]
            child = ET.SubElement(group, "group", android(name="main_r" if name == "main" else name + "_echo", translateX=x))
            for path in glyph:
                color = "#FFFFFFFF" if variant == "mono" else "@color/launcher_" + ("main" if name == "main" else "accent")
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
    for name, value in (("background", document.find(SVG + "circle").attrib["fill"]),
                        ("accent", near.attrib["stroke"]), ("main", main.attrib["stroke"])):
        ET.SubElement(colors, "color", {"name": "launcher_" + name}).text = value
    output["app/src/main/res/values/colors.xml"] = encode(colors)
    output["fastlane/metadata/android/en-US/images/icon.png"] = subprocess.check_output(
        ["rsvg-convert", "--width", "512", "--height", "512", str(source)], stderr=subprocess.PIPE)
    return output


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--check", action="store_true", help="Report drift without writing")
    args = parser.parse_args()
    output = assets()
    changed = [name for name, data in output.items() if not (ROOT / name).exists() or (ROOT / name).read_bytes() != data]
    if args.check:
        if changed:
            raise SystemExit("Regenerate brand assets: " + ", ".join(changed))
        print(f"All {len(output)} generated brand assets match the canonical SVG")
    else:
        for name in changed:
            (ROOT / name).write_bytes(output[name])
        print(f"Updated {len(changed)} brand assets from the canonical SVG")


if __name__ == "__main__":
    main()
