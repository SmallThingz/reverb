package app.smallthingz.reverb.nativeui

import android.content.Context

/** A paired settings row; no extra wrapper, surface, padding, or independent corner shape. */
internal class ReverbGroupRow(context: Context) : ReverbDividerLayout(context) {
    init {
        orientation = HORIZONTAL
    }
}
