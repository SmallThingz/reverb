package app.smallthingz.reverb.nativeui

import android.graphics.Rect
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo

/** Optional whole-tree capture for one synchronous parent/children accessibility request. */
internal interface NativeSourceAccessibilityNodeProvider : (View) -> NativeSourceAccessibilityNode {
    fun snapshotNodes(): Map<View, NativeSourceAccessibilityNode>
}

/** On-demand facts for an existing View; absence, explicit hiding and clipping are independent. */
internal data class NativeSourceAccessibilityNode(
    val present: Boolean,
    val hidden: Boolean,
    val visibleToUser: Boolean,
    val boundsInScreen: Rect,
) {
    fun populate(info: AccessibilityNodeInfo) {
        info.isImportantForAccessibility = !hidden
        info.isVisibleToUser = present && visibleToUser
        if (hidden) info.isScreenReaderFocusable = false
        info.setBoundsInScreen(boundsInScreen)
    }
    companion object {
        fun absent() = NativeSourceAccessibilityNode(false, false, false, Rect())
    }
}

/** Main's retained surfaces can remain drawn after their input ownership ends. */
internal interface NativeSurfaceInputAdmission {
    fun admitsSurfaceInput(descendant: View): Boolean
}

internal fun nativeSurfaceInputAllowed(view: View): Boolean {
    var ancestor = view.parent as? View
    while (ancestor != null) {
        if (ancestor is NativeSurfaceInputAdmission && !ancestor.admitsSurfaceInput(view)) return false
        ancestor = ancestor.parent as? View
    }
    return true
}
