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
translation balances the primary R and both echoes in circular and rounded-rectangle
launcher masks. Keep that translation shared by the adaptive-icon wrappers, in-app
marks and store artwork.

The generator produces the launcher foreground, themed monochrome mask, splash
vector and echo-animation endpoints, baseline palette and store PNG. Android's
Material You color overrides remain separate; their geometry is identical.
The top bar and About both render the same generated launcher foreground.

The splash maintains the primary R's final anchor throughout the echo reveal and
follows the native splash lifetime. Asset tests check shared geometry, animation
endpoints, adaptive wrappers, and the full store mark's contrast-weighted center
and clearance within the circular adaptive viewport. Review both masks at launcher size.

The isolated reliability runner's `brand_geometry` case rasterizes the actual
Android color, monochrome and initial splash drawables under both light and dark
configurations. It checks the shared opaque R anchor and the completed mark's
alpha-weighted balance and circular clearance, and saves six `brand-*.png` captures
in the QA package's files directory. See `README-reliability.md` for the
isolated build/install commands; never run fixtures in the populated app.
