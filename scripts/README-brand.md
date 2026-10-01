# Reverb brand assets

Edit `app/src/main/icon.svg`, then run from the repository root:

```sh
python scripts/generate_brand_assets.py
python scripts/generate_brand_assets.py --check
./gradlew :app:testDebugUnitTest --tests '*BrandAssetsTest'
```

Python 3 and librsvg's `rsvg-convert` are needed only to regenerate or compare the
assets. Normal Android builds use the committed vectors and PNG.

The SVG defines the R body and leg once. Both echoes reuse those paths. The shared
translation centers the bright primary R, not the bounding box of its faint echoes.
Do not add offsets in Compose, adaptive-icon wrappers or store artwork.

The generator produces the launcher foreground, themed monochrome mask, splash
vector and echo-animation endpoints, baseline palette and store PNG. Android's
Material You color overrides remain separate; their geometry is identical.
The top bar and About both render the same generated launcher foreground.

The splash reveals the echoes without moving the primary R away from its final
center. It does not extend the native splash lifetime. Asset tests check shared
paths, transforms, animation endpoints, adaptive wrappers and the store PNG's
primary-glyph center. Review the mark at small sizes as well as enlarged.

The isolated reliability runner's `brand_geometry` case rasterizes the actual
Android color, monochrome and initial splash drawables under both light and dark
configurations. It checks their opaque R alignment and saves six `brand-*.png`
captures in the QA package's files directory. See `README-reliability.md` for the
isolated build/install commands; never run fixtures in the populated app.
