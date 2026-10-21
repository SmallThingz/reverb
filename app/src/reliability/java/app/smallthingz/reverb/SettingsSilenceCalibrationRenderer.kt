package app.smallthingz.reverb

import android.content.Context
import android.view.View
import android.view.ViewTreeObserver
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

@Composable
internal fun rememberSettingsSilenceCalibration(
    readOwner: (SilenceGateConfig, SilenceCalibrationInput) -> Pair<Long, Int>?,
    onCalibrated: (SilenceGateConfig, SilenceCalibrationHistogram) -> Unit,
): SettingsSilenceCalibration {
    val context = LocalContext.current
    val view = LocalView.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val scope = rememberCoroutineScope()
    val latestOwner = rememberUpdatedState(readOwner)
    val latestResult = rememberUpdatedState(onCalibrated)
    val controller = remember(context, view, lifecycle, scope) {
        SettingsSilenceCalibration(context, scope, view, lifecycle,
            readOwner = { config, input -> latestOwner.value(config, input) },
            onCalibrated = { config, histogram -> latestResult.value(config, histogram) })
    }
    DisposableEffect(controller, view, lifecycle) {
        var tree: ViewTreeObserver? = null
        val focus = ViewTreeObserver.OnWindowFocusChangeListener {
            if (!it) controller.cancel()
        }
        fun removeFocusListener() {
            tree?.takeIf { it.isAlive }?.removeOnWindowFocusChangeListener(focus)
            tree = null
        }
        fun observeAttachment() {
            removeFocusListener()
            if (view.isAttachedToWindow) view.viewTreeObserver.takeIf { it.isAlive }?.let {
                tree = it
                it.addOnWindowFocusChangeListener(focus)
            }
        }
        val attachment = object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) = observeAttachment()
            override fun onViewDetachedFromWindow(v: View) {
                controller.cancel()
                removeFocusListener()
            }
        }
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE || event == Lifecycle.Event.ON_STOP ||
                event == Lifecycle.Event.ON_DESTROY) controller.cancel()
        }
        view.addOnAttachStateChangeListener(attachment)
        lifecycle.addObserver(observer)
        observeAttachment()
        onDispose {
            controller.close()
            view.removeOnAttachStateChangeListener(attachment)
            lifecycle.removeObserver(observer)
            removeFocusListener()
        }
    }
    return controller
}
