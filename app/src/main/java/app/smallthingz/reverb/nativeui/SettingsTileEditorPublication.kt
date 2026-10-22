package app.smallthingz.reverb.nativeui

import app.smallthingz.reverb.QuickSettingsTileDeleteTarget
import app.smallthingz.reverb.QuickSettingsTileEditorTarget
import app.smallthingz.reverb.QuickSettingsTilesEditorState

/** Child editor changes can be published without changing the parent Settings draft. */
internal class SettingsTileEditorPublication {
    private var observed = false
    private var target: QuickSettingsTileEditorTarget? = null
    private var deleting: QuickSettingsTileDeleteTarget? = null
    private var editorOpen = false

    /** Record before binding: child reconciliation may synchronously publish again. */
    fun changed(state: QuickSettingsTilesEditorState): Boolean {
        val nextTarget = state.target
        val nextDeleting = state.deleting
        val nextOpen = state.editorOpen
        if (observed && target === nextTarget && deleting === nextDeleting && editorOpen == nextOpen) return false
        observed = true
        target = nextTarget
        deleting = nextDeleting
        editorOpen = nextOpen
        return true
    }

    fun reset() {
        observed = false
        target = null
        deleting = null
        editorOpen = false
    }
}
