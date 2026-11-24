package app.smallthingz.reverb.nativeui

import android.content.Context
import smallthingz.ui.reverb.readReverbBrandPalette as libraryBrandPalette
import smallthingz.ui.reverb.reverbBrandFillBounds as libraryBrandFillBounds

internal typealias ReverbBrandMarkView = smallthingz.ui.reverb.ReverbBrandMarkView

internal fun readReverbBrandPalette(context: Context) = libraryBrandPalette(context)

internal fun reverbBrandFillBounds(density: Float, width: Int, height: Int) =
    libraryBrandFillBounds(density, width, height)
