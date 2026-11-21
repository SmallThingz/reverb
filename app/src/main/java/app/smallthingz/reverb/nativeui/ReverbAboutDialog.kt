package app.smallthingz.reverb.nativeui

import android.view.View
import smallthingz.ui.components.NativeFloatingDialog
import smallthingz.ui.components.NativeFloatingDialogContent

/** Reverb content, links and scroll restoration bound to the shared floating window owner. */
internal class ReverbAboutDialog(
    anchor: View,
    appearance: AboutContentAppearance,
    restoredScrollPosition: Int,
    onDismiss: () -> Unit,
) {
    constructor(anchor: View, appearance: AboutContentAppearance, onDismiss: () -> Unit) :
        this(anchor, appearance, 0, onDismiss)

    private lateinit var content: ReverbAboutContentView
    private val dialog = NativeFloatingDialog(anchor, appearance.density.density, onDismiss,
        statusBarProtection = createReverbStatusBarProtection(anchor.context, appearance.uiAppearance),
        shadowDp = appearance.panel.shadowDp) { context, runtime, dismiss ->
        val body = createReverbAboutContent(context, appearance, dismiss, runtime, restoredScrollPosition)
        content = body
        NativeFloatingDialogContent(body, body.predictiveMotionTarget, body::bindWindowAccessibility, body::dispose)
    }

    fun scrollPosition(): Int = content.scrollPosition()
    val window get() = dialog.window
    fun updateAppearance(appearance: AboutContentAppearance, onDismiss: () -> Unit) {
        dialog.bind(onDismiss, appearance.panel.shadowDp)
        content.setAppearance(appearance, dialog::requestDismiss)
    }
    fun show() = dialog.show()
    fun dismiss() = dialog.dismiss()
    fun requestDismiss() = dialog.requestDismiss()
}
