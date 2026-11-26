# Reverb brand assets

The UI library owns the brand. Edit `native-ui/src/main/brand/reverb.svg` for
geometry and store artwork colors. Generated Android resources at
`native-ui/src/main/res/values*/reverb_brand_colors.xml` alias background, main
and accent to `reverb_brand_surface`, `reverb_brand_surface_main` and
`reverb_brand_surface_accent`, respectively, in every qualifier.
That library resource exports the actual recording-info foreground recipe:
`NativeGroupSurfaceRecipes.Default.recordingInfoRow` (`HIGHEST`, or
`surfaceContainerHighest`), including the central AMOLED preset at night.
Stroke colors follow central `NativeColorScheme.ink` and `primary`; the light
fallback no longer paints the SVG's white stroke on a light plate. There is no
app-local plate palette and no API31–33 lStar approximation.

From the app repository root:

```sh
python3 native-ui/scripts/generate_reverb_brand_plate.py
python3 scripts/generate_brand_assets.py --android-only
python3 scripts/generate_brand_assets.py --check --android-only
python3 -B -m unittest discover -s scripts -p test_generate_brand_assets.py
./gradlew :app:testDebugUnitTest --tests '*BrandAssetsTest'
```

The Android-only commands need standard Python 3. The app generator first invokes
the library plate exporter's `--check`; only that library script derives the
surface resources and texture. Regenerate its outputs first if that check fails.
Every app variant's `preBuild`
depends on `verifyBrandAssets`, which checks committed runtime outputs without
writing them. A stale launcher vector, in-app renderer, SVG mirror, palette alias,
or splash animator fails the build with a regeneration instruction. The launcher
background is generated too: it references library `@drawable/reverb_brand_plate`,
which combines `reverb_brand_surface` with `reverb_brand_grain`. The check
also rejects extra brand-color definitions outside the canonical/generated
palette files and overrides of generated or shared plate/grain resource names in
either module's source variants or resource qualifiers, including app shadows of
library colors, bitmap replacements and values aliases. Update the library checkout and app outputs together before
updating the app's library pin.

The SVG defines the two R paths once, with one optical translation and three echo
layers. The generator produces the shared UI's `ReverbBrandMarkView.kt` Path data,
the app's `icon.svg` mirror, launcher foreground and themed monochrome vectors,
splash vector and animation endpoints, and library resource aliases. The app's
launcher colors reference `@color/reverb_brand_*` in every library qualifier, so
resource selection follows the same dynamic day/night strokes as the in-app mark.
Do not edit generated files or add app-local artwork offsets or palette values.

Both launcher and Home use the library-owned `reverb_brand_grain` tiled drawable
and its nodpi PNG. The icon tile is a deterministic 64x64 phase-zero crop of the
central noise, using the original 1024-pixel row stride and unchanged RAW sample
alpha. No foreground intensity is baked into the PNG. Launcher XML applies the
central default foreground alpha once; the native binder applies its current
palette foreground alpha once (128 at the default .5), never twice.
Its icon-specific repeat period is 64 pixels; ordinary UI grain retains its
1024-pixel period. This is shared icon texture, not a claim that the entire UI
repeats the cropped tile. The Home bind site supplies the current window palette
through `mark.bindReverbBrandSurface(value.palette)`; launcher resources use the
corresponding system configuration. Per-window app theme overrides cannot change
the independently configured launcher process.

The native mark continues to use `NativeLayeredMarkView`: retained Paths and its
existing bitmap cache, 108dp intrinsic size, FillBounds geometry, circular clip,
and resource-color refresh. The generated mark adds only the default surface-bind
initializer; the library's surface binder owns its plate texture. There is no
runtime SVG parsing. The current SVG's
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
