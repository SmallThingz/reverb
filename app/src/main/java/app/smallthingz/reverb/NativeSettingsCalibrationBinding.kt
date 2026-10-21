package app.smallthingz.reverb

import android.content.Context
import android.view.View
import android.view.ViewTreeObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import kotlinx.coroutines.CoroutineScope

/** The attachment/focus/lifecycle effect from rememberSettingsSilenceCalibration, without Composition. */
internal class NativeSettingsCalibrationBinding(
    context: Context,
    scope: CoroutineScope,
    private val view: View,
    private val lifecycle: Lifecycle,
    readOwner: (SilenceGateConfig, SilenceCalibrationInput) -> Pair<Long, Int>?,
    onCalibrated: (SilenceGateConfig, SilenceCalibrationHistogram) -> Unit,
    onChanged: () -> Unit,
) : AutoCloseable {
    val controller = SettingsSilenceCalibration(
        context, scope, view, lifecycle, readOwner, onCalibrated, onChanged,
    )
    private var tree: ViewTreeObserver? = null
    private var closed = false
    private val focus = ViewTreeObserver.OnWindowFocusChangeListener {
        if (!it) controller.cancel()
    }
    private val attachment = object : View.OnAttachStateChangeListener {
        override fun onViewAttachedToWindow(v: View) = observeAttachment()
        override fun onViewDetachedFromWindow(v: View) {
            controller.cancel()
            removeFocusListener()
        }
    }
    private val observer = LifecycleEventObserver { _, event ->
        if (event == Lifecycle.Event.ON_PAUSE || event == Lifecycle.Event.ON_STOP ||
            event == Lifecycle.Event.ON_DESTROY) controller.cancel()
    }

    init {
        view.addOnAttachStateChangeListener(attachment)
        lifecycle.addObserver(observer)
        observeAttachment()
    }

    private fun removeFocusListener() {
        tree?.takeIf { it.isAlive }?.removeOnWindowFocusChangeListener(focus)
        tree = null
    }

    private fun observeAttachment() {
        removeFocusListener()
        if (view.isAttachedToWindow) view.viewTreeObserver.takeIf { it.isAlive }?.let {
            tree = it
            it.addOnWindowFocusChangeListener(focus)
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        controller.close()
        view.removeOnAttachStateChangeListener(attachment)
        lifecycle.removeObserver(observer)
        removeFocusListener()
    }
}
