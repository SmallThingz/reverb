# AndroidX palette source

Reverb's native palette preserves the released AndroidX Compose Material3 **1.4.0** roles.

Source artifact:
https://dl.google.com/dl/android/maven2/androidx/compose/material3/material3-android/1.4.0/material3-android-1.4.0-sources.jar

SHA-256: `3e3209422a382566a780e2c1410edd866e4474498098fdbcf942c690ade1ddaa`

- `ReverbPalette.kt` ports the resource mappings from `DynamicTonalPalette.android.kt` and the literal fallback roles from `ColorLightTokens.kt`, `ColorDarkTokens.kt` and `PaletteTokens.kt` (token version `v0_210`).
- `Cam.kt`, `CamUtils.kt`, `Frame.kt` and `HCTSolver.kt` retain the exact AndroidX Kotlin algorithms. Only package/import paths and a provenance comment changed.
- The bundled AndroidX Core 1.18.0 `CamColor` is not interchangeable: it uses the older chroma/lightness search instead of Material3's default-frame `HctSolver`.
- `reverbColorWithAlpha` follows the sRGB alpha conversion in Compose UI Graphics **1.11.2** `Color.kt` (`copy` / float `Color` constructor): clamp then `(alpha * 255f + .5f).toInt()`. Existing RGB bytes remain unchanged.

Copyright 2021, 2024 The Android Open Source Project. Licensed under Apache License 2.0; see `LICENSE.txt` and the retained file headers. Reverb-specific mapping/adaptation remains in `ReverbPalette.kt`.

This is a static ARGB palette, not an animation implementation. Compose color animations interpolate in Oklab and require a separately qualified native adapter; Android's ARGB evaluator is not equivalent.
