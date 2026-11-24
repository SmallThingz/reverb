package app.smallthingz.reverb.nativeui

import android.content.Context
import android.view.View
import androidx.lifecycle.LifecycleOwner
import app.smallthingz.reverb.FeedbackTone
import app.smallthingz.reverb.NativeAppFeedbackOwner
import kotlinx.coroutines.CoroutineScope
import smallthingz.ui.components.NativeFeedbackTone
import smallthingz.ui.platform.AccessibilityManager
import smallthingz.ui.reverb.ReverbAppFeedbackHostView
import smallthingz.ui.reverb.ReverbFeedbackMessage
import smallthingz.ui.text.view.NativeSourceAccessibilityNode
import smallthingz.ui.util.closePreservingPrimaryFailure

/** Connects process feedback delivery to the current window's presentation. */
internal class NativeAppFeedbackHostView(
    context: Context,
    environment: ReverbViewEnvironment,
    root: View,
    lifecycleOwner: LifecycleOwner,
    parentScope: CoroutineScope,
    accessibilityManager: AccessibilityManager?,
    windowText: NativeWindowTextAccessibilityOwner,
    sourceNode: (View) -> NativeSourceAccessibilityNode,
) : ReverbAppFeedbackHostView(context, environment, root, parentScope, { content, clip ->
    windowText.bindContent(content, clip, sourceNode) { sourceNode(it).present }
}) {
    private val owner = NativeAppFeedbackOwner(root, lifecycleOwner, parentScope, accessibilityManager,
        display = { event ->
            display(event?.let {
                val tone = when (it.tone) {
                    FeedbackTone.INFO -> NativeFeedbackTone.INFO
                    FeedbackTone.SUCCESS -> NativeFeedbackTone.SUCCESS
                    FeedbackTone.ERROR -> NativeFeedbackTone.ERROR
                }
                ReverbFeedbackMessage(it.message, tone)
            })
        },
        visibilityAllowed = ::setVisibilityAllowed,
    )

    fun bind(deliveryAllowed: Boolean, environment: ReverbViewEnvironment) {
        bindEnvironment(environment)
        owner.bind(deliveryAllowed)
    }

    override fun close() {
        val failure = closePreservingPrimaryFailure(null, owner::close)
        closePreservingPrimaryFailure(failure) { super.close() }?.let { throw it }
    }
}
