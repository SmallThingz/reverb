package app.smallthingz.reverb.nativeui

import smallthingz.ui.components.NativeGroupLayout
import android.view.View
import android.view.ViewGroup
import smallthingz.ui.text.view.ParagraphView

/** Current known paint only; no target palette approximation or retained ancestor identity. */
internal fun settingsTextSurface(view: View): Long {
    // Dirty-Back hints and other own overlays are not the ancestor's bare paint. Preserve that
    // renderer until the existing drawable invalidation returns the overlay to transparent.
    val ownBackground = view.background
    if (ownBackground != null && ownBackground.alpha != 0) return 0L
    var parent = view.parent as? View
    var settings = false
    var surface = 0L
    var found = false
    while (parent != null) {
        if (parent is NativeSettingsScreenContent) settings = true
        if (!found) {
            if (parent is NativeGroupLayout) {
                surface = parent.paintedTextSurface
                found = true
            } else {
                val background = parent.background
                if (background is ReverbNoiseDrawable) {
                    surface = background.paintedTextSurface
                    found = true
                } else if (background != null) return 0L
            }
        }
        if (settings && found) return surface
        parent = parent.parent as? View
    }
    return 0L
}

/** Existing paint owners call this only when their actual fill changes. No registry/frame loop. */
internal fun invalidateSettingsTextContrast(owner: View) {
    if (!owner.isAttachedToWindow) return
    var parent: View? = owner
    var inside = false
    while (parent != null) {
        if (parent is NativeSettingsScreenContent) { inside = true; break }
        parent = parent.parent as? View
    }
    if (inside) invalidateSettingsTextContrastTree(owner)
}
private fun invalidateSettingsTextContrastTree(view: View) {
    if (!view.isAttachedToWindow) return
    when (view) {
        is ParagraphView -> if (view.paintedTextSurface != null) view.invalidate()
        is SettingsRetentionInputView -> view.invalidate()
        is OnboardingIconView -> if(view.useTextSurfaceContrast)view.invalidate()
        is ReverbTopBarIconAction -> view.invalidateTextSurfaceInk()
    }
    if (view is ViewGroup) for (index in 0 until view.childCount)
        invalidateSettingsTextContrastTree(view.getChildAt(index))
}
