package app.smallthingz.reverb.nativeui

import smallthingz.ui.components.NativeDividerLayout
import android.content.Context

/** A paired settings row; no extra wrapper, surface, padding, or independent corner shape. */
internal class ReverbGroupRow(context: Context) : NativeDividerLayout(context) {
    init {
        orientation = HORIZONTAL
    }
}
