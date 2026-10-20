package app.smallthingz.reverb

import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlin.math.roundToInt

/** The containing Audio group owns the background, outer border, and separators. */
@Composable
internal fun NotificationSaveSettings(
    enabled: Boolean,
    seconds: Int,
    maxSeconds: Int,
    onEnabledChange: (Boolean) -> Unit,
    onSecondsChange: (Int) -> Unit,
    interactionReady: Boolean,
    editRevision: Long,
    readEditRevision: () -> Long?,
    interactionState: NotificationDurationInteractionState,
    modifier: Modifier = Modifier,
) {
    val chrome = appChrome()
    Column(modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .toggleable(
                    value = enabled,
                    enabled = interactionReady,
                    role = Role.Switch,
                    onValueChange = onEnabledChange,
                )
                .heightIn(min = 64.dp)
                .padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = stringResource(R.string.feature_notification_save),
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyLarge,
                color = chrome.ink,
            )
            Switch(
                checked = enabled,
                onCheckedChange = null,
                enabled = interactionReady,
            )
        }
        if (enabled) {
            NotificationDurationWheel(
                seconds = seconds,
                maxSeconds = maxSeconds,
                onSecondsChange = { accepted ->
                    onSecondsChange(accepted)
                    readEditRevision()?.let { NotificationDurationCommit(accepted, it) }
                },
                enabled = interactionReady,
                editRevision = editRevision,
                readEditRevision = readEditRevision,
                interactionState = interactionState,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
            )
        }
    }
}

/** Uses the existing range editor wheel, including its native cylinder and step profile. */
@Composable
internal fun NotificationDurationWheel(
    seconds: Int,
    maxSeconds: Int,
    onSecondsChange: (Int) -> NotificationDurationCommit?,
    enabled: Boolean,
    editRevision: Long,
    readEditRevision: () -> Long?,
    interactionState: NotificationDurationInteractionState,
    modifier: Modifier = Modifier,
    stepDuration: (Int, Int, Boolean) -> Int = ::notificationDurationStepSeconds,
) {
    val colors = MaterialTheme.colorScheme
    val label = stringResource(R.string.range_export_duration)
    val binding = remember(interactionState) { NotificationDurationBinding(interactionState) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(binding) { onDispose { binding.release() } }
    DisposableEffect(binding, lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE || event == Lifecycle.Event.ON_STOP) {
                // The native View can still be settling. Revoke its commit, but keep Save
                // locked until its terminal callback resets the pixels or the View is released.
                binding.invalidateInteraction()
            }
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            binding.invalidateInteraction()
        }
    }
    val currentChange by rememberUpdatedState(onSecondsChange)
    val currentOwner by rememberUpdatedState(readEditRevision)
    val maximum = maxSeconds.coerceIn(1, 86_400)
    AndroidView(
        factory = { context -> RangeDurationWheelView(context) },
        update = { view ->
            // Draft identity survives a temporary focus loss; admission is checked live below.
            binding.update(seconds, maximum, enabled, editRevision)
            fun commitInput(value: Int): Boolean {
                val accepted = binding.commitInput(value, currentOwner(), currentChange) ?: return false
                view.setDurationSeconds(accepted.toDouble())
                return true
            }
            fun stepInput(forward: Boolean): Boolean =
                commitInput(stepDuration(binding.seconds, maximum, forward))
            view.isEnabled = enabled
            view.setAccessibilityLabel(label)
            view.setPalette(
                ink = colors.onSurface.toArgb(),
                muted = colors.onSurfaceVariant.toArgb(),
                border = colors.outlineVariant.toArgb(),
                error = colors.error.toArgb(),
            )
            view.onInteractionStart = { binding.beginInteraction(currentOwner()) }
            view.onDurationChanged = binding::select
            view.onInteractionEnd = {
                binding.finishInteraction(currentOwner(), currentChange) { canonicalSeconds ->
                    view.setDurationSeconds(canonicalSeconds.toDouble())
                }
            }
            view.setMaximumDurationSeconds(maximum.toDouble())
            // Retain a saved over-limit request until a deliberate edit. The native wheel
            // marks it as invalid; Settings owns the format-limit warning when saving.
            view.setDurationSeconds(binding.seconds.toDouble())
            view.setOnKeyListener { _, keyCode, event ->
                if (event.action != KeyEvent.ACTION_DOWN) false else when (keyCode) {
                    KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_RIGHT ->
                        stepInput(true)
                    KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_LEFT ->
                        stepInput(false)
                    else -> false
                }
            }
            view.accessibilityDelegate = object : View.AccessibilityDelegate() {
                override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfo) {
                    super.onInitializeAccessibilityNodeInfo(host, info)
                    info.className = android.widget.SeekBar::class.java.name
                    info.rangeInfo = AccessibilityNodeInfo.RangeInfo.obtain(
                        AccessibilityNodeInfo.RangeInfo.RANGE_TYPE_INT,
                        1f,
                        maximum.toFloat(),
                        binding.seconds.coerceIn(1, maximum).toFloat(),
                    )
                    if (enabled) {
                        info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS)
                        info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD)
                        info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_BACKWARD)
                    }
                }

                override fun performAccessibilityAction(host: View, action: Int, arguments: Bundle?): Boolean =
                    when (action) {
                        AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.id -> {
                            val value = arguments?.getFloat(
                                AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE,
                                Float.NaN,
                            ) ?: Float.NaN
                            value.isFinite() && commitInput(value.roundToInt())
                        }
                        AccessibilityNodeInfo.ACTION_SCROLL_FORWARD -> stepInput(true)
                        AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD -> stepInput(false)
                        else -> super.performAccessibilityAction(host, action, arguments)
                    }
            }
        },
        onRelease = { view ->
            binding.release()
            view.onInteractionStart = null
            view.onDurationChanged = null
            view.onInteractionEnd = null
            view.setOnKeyListener(null)
            view.accessibilityDelegate = null
        },
        modifier = modifier.fillMaxWidth().height(160.dp),
    )
}

/** The parent acknowledges only this synchronous edit, including any canonicalized duration. */
internal data class NotificationDurationCommit(val seconds: Int, val owner: Long)

internal fun notificationDurationStepSeconds(seconds: Int, maximum: Int, forward: Boolean): Int =
    (seconds.coerceIn(1, 86_400) + if (forward) 1 else -1)
        .coerceIn(1, maximum.coerceIn(1, 86_400))

/** Save admission shared by the wheel and its containing Settings/tile Save action. */
internal class NotificationDurationInteractionState {
    private val owners = mutableSetOf<Any>()
    var pending by mutableStateOf(false)
        private set

    fun begin(): Any = Any().also { owner ->
        owners += owner
        pending = true
    }

    fun end(owner: Any) {
        if (owners.remove(owner)) pending = owners.isNotEmpty()
    }
}

/** A pending native settle cannot overwrite a newer Settings edit, rollback, or save. */
internal class NotificationDurationBinding(
    private val interactionState: NotificationDurationInteractionState = NotificationDurationInteractionState(),
) {
    var seconds = 1
        private set
    private var maximum = 1
    private var enabled = false
    private var renderedOwner: Long? = null
    private var revision = 0L
    private var interactionRevision: Long? = null
    private var ownerRevision: Long? = null
    private var pendingSeconds: Int? = null
    private var interactionToken: Any? = null

    fun update(seconds: Int, maximum: Int, enabled: Boolean, owner: Long) {
        val value = seconds.coerceIn(1, 86_400)
        val limit = maximum.coerceIn(1, 86_400)
        if (this.seconds != value || this.maximum != limit || this.enabled != enabled || renderedOwner != owner) {
            revision++
        }
        this.seconds = value
        this.maximum = limit
        this.enabled = enabled
        renderedOwner = owner
    }

    fun beginInteraction(owner: Long?) {
        // The native View will move even when its eventual draft commit is inadmissible.
        // Replace the visual token without a transient Save-admission gap.
        val nextToken = interactionState.begin()
        cancelInteraction()
        interactionToken = nextToken
        if (enabled && owner != null && owner == renderedOwner) {
            interactionRevision = revision
            ownerRevision = owner
        }
    }

    fun select(seconds: Int) {
        if (interactionRevision != null) pendingSeconds = seconds
    }

    fun finishInteraction(owner: Long?, apply: (Int) -> NotificationDurationCommit?): Int? =
        finishInteraction(owner, apply, resetDisplay = {})

    fun finishInteraction(
        owner: Long?,
        apply: (Int) -> NotificationDurationCommit?,
        resetDisplay: (Int) -> Unit,
    ): Int? {
        val accepted = pendingSeconds?.takeIf {
            enabled && owner != null && owner == renderedOwner &&
                owner == ownerRevision && interactionRevision == revision
        }
        val completedToken = interactionToken
        interactionToken = null
        invalidateInteraction()
        var result: Int? = null
        var failure: Throwable? = null
        try {
            result = accepted?.let { commit(it, requireNotNull(owner), apply) }
        } catch (error: Throwable) {
            failure = error
        }
        try {
            resetDisplay(seconds)
        } catch (error: Throwable) {
            if (failure == null) failure = error else if (failure !== error) failure.addSuppressed(error)
        } finally {
            // A reentrant/newer interaction owns a different token and remains pending.
            completedToken?.let(interactionState::end)
        }
        failure?.let { throw it }
        return result
    }

    /** Invalidation alone does not end the native gesture or its visible snap animation. */
    fun invalidateInteraction() {
        interactionRevision = null
        ownerRevision = null
        pendingSeconds = null
    }

    fun commitInput(seconds: Int, owner: Long?, apply: (Int) -> NotificationDurationCommit?): Int? =
        if (enabled && owner != null && owner == renderedOwner && interactionToken == null) {
            commit(seconds, owner, apply)
        } else null

    fun release() {
        enabled = false
        renderedOwner = null
        cancelInteraction()
    }

    private fun commit(value: Int, owner: Long, apply: (Int) -> NotificationDurationCommit?): Int? {
        val acceptedRevision = revision
        val accepted = apply(value.coerceIn(1, maximum)) ?: return null
        // Only an accepted callback can advance our rendered owner without a new Compose frame.
        // A reentrant outside update still owns the binding and must not be overwritten here.
        if (!enabled || revision != acceptedRevision || renderedOwner != owner ||
            accepted.seconds !in 1..maximum
        ) return null
        seconds = accepted.seconds
        renderedOwner = accepted.owner
        revision++
        return seconds
    }

    private fun cancelInteraction() {
        val cancelledToken = interactionToken
        interactionToken = null
        invalidateInteraction()
        cancelledToken?.let(interactionState::end)
    }
}
