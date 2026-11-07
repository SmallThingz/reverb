package app.smallthingz.reverb.nativeui

import android.view.View

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
