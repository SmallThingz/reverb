# Reverb brand assets

The UI library owns the brand. Edit `native-ui/src/main/brand/reverb.svg` for
geometry and baseline colors. Its qualified resources at
`native-ui/src/main/res/values*/reverb_brand_colors.xml` own the Android 12+
Material You day/night palette. The baseline resource is generated from the SVG;
the qualified dynamic palette files remain hand-authored.

From the app repository root:

```sh
python3 scripts/generate_brand_assets.py --android-only
python3 scripts/generate_brand_assets.py --check --android-only
python3 -B -m unittest discover -s scripts -p test_generate_brand_assets.py
./gradlew :app:testDebugUnitTest --tests '*BrandAssetsTest'
```

The Android-only commands need standard Python 3. Every app variant's `preBuild`
depends on `verifyBrandAssets`, which checks committed runtime outputs without
writing them. A stale launcher vector, in-app renderer, SVG mirror, palette alias,
or splash animator fails the build with a regeneration instruction. The check
also rejects extra brand-color definitions outside the canonical/generated
palette files and overrides of generated drawable/animator names in any app
source variant or resource qualifier, including bitmap replacements and values
aliases. Update the library checkout and app outputs together before
updating the app's library pin.

The SVG defines the two R paths once, with one optical translation and three echo
layers. The generator produces the shared UI's `ReverbBrandMarkView.kt` Path data,
the app's `icon.svg` mirror, launcher foreground and themed monochrome vectors,
splash vector and animation endpoints, and baseline library palette. The app's
launcher colors reference `@color/reverb_brand_*` in every library qualifier, so
resource selection follows the same dynamic day/night colors as the in-app mark.
Do not edit generated files or add app-local artwork offsets or palette values.

The native mark continues to use `NativeLayeredMarkView`: retained Paths and its
existing bitmap cache, 108dp intrinsic size, FillBounds geometry, circular clip,
and resource-color refresh. There is no runtime SVG parsing. The current SVG's
28.5 horizontal root translation is shared by every output. Adaptive wrappers
retain zero inset; splash retains its 432dp vector size and fixed primary-R
anchor throughout the echo reveal. Parent top-bar/About layout is unchanged.
Unsupported SVG features fail generation rather than silently producing different
native geometry.

To regenerate or compare the store PNG as well, install librsvg's `rsvg-convert`
and omit `--android-only`:

```sh
python3 scripts/generate_brand_assets.py
python3 scripts/generate_brand_assets.py --check
```

The store renderer is optional for normal Android builds. Before changing or
publishing store artwork, run the full check. `BrandAssetsTest` also checks the
store mark's contrast-weighted center and adaptive-mask clearance, launcher and
splash geometry, the production shared-UI renderer, and every palette qualifier.

The isolated reliability runner's `brand_geometry` case rasterizes the actual
Android color, monochrome and initial splash drawables under light and dark
configurations. It checks the shared R anchor, balance and circular clearance,
and saves six `brand-*.png` captures. The `native_brand` probe compares the real
shared native View with the launcher-vector reference at multiple sizes and
resource configurations. See `README-reliability.md` for isolated runner commands;
never run fixtures in the populated app.
