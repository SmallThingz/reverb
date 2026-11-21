package app.smallthingz.reverb

import android.graphics.Bitmap
import app.smallthingz.reverb.nativeui.reverbAppearance
import smallthingz.ui.components.NativeQuickTilesRecipe
import smallthingz.ui.components.NativeTileIconGlyph
import smallthingz.ui.components.renderNativeTileIcon

/** The same alpha-only art feeds the preview, Android add prompt and installed tile. */
internal fun quickSaveTileBitmap(buffer: ReverbService.BufferSlot, amount: Int, unit: QuickSaveUnit,
                                 pixels: Int = 96, recipe: NativeQuickTilesRecipe = reverbAppearance.quickTiles): Bitmap =
    renderNativeTileIcon(if (buffer == ReverbService.BufferSlot.LOOPING) NativeTileIconGlyph.LOOP
        else NativeTileIconGlyph.FORWARD, amount, unit.symbol, pixels, recipe)
