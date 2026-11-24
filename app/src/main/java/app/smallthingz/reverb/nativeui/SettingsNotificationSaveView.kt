package app.smallthingz.reverb.nativeui

import android.content.Context
import android.view.View
import androidx.lifecycle.Lifecycle
import app.smallthingz.reverb.NotificationDurationCommit
import app.smallthingz.reverb.NotificationDurationInteractionState
import smallthingz.ui.components.NativeViewAnimatorScale
import smallthingz.ui.reverb.settings.ReverbNotificationSaveSettings
import smallthingz.ui.reverb.settings.ReverbNotificationSaveState

internal typealias SettingsAnimatorDurationScale = NativeViewAnimatorScale
internal typealias SettingsNotificationSaveState = ReverbNotificationSaveState

internal class SettingsNotificationSaveView private constructor(
    context: Context,
    initial: SettingsNotificationSaveState,
    environment: ReverbViewEnvironment,
    inputRoot: () -> View?,
    onEnabledChange: (Boolean) -> Unit,
    private val incoming: NotificationWheelCallbacks,
) : ReverbNotificationSaveSettings(context, initial, environment, inputRoot, onEnabledChange, incoming::createWheel) {
    constructor(
        context: Context,
        initial: SettingsNotificationSaveState,
        environment: ReverbViewEnvironment,
        inputRoot: () -> View?,
        lifecycle: Lifecycle,
        interactionState: NotificationDurationInteractionState,
        onEnabledChange: (Boolean) -> Unit,
        onSecondsChange: (Int) -> Unit,
        readEditRevision: () -> Long?,
    ) : this(context, initial, environment, inputRoot, onEnabledChange,
        NotificationWheelCallbacks(context, lifecycle, interactionState, onSecondsChange, readEditRevision))

    fun callbacks(onSeconds: (Int) -> Unit, owner: () -> Long?, interactionChanged: () -> Unit = {}) {
        incoming.onSeconds = onSeconds
        incoming.readRevision = owner
        incoming.interactionChanged = interactionChanged
    }

    private class NotificationWheelCallbacks(
        private val context: Context,
        private val lifecycle: Lifecycle,
        private val interactionState: NotificationDurationInteractionState,
        var onSeconds: (Int) -> Unit,
        var readRevision: () -> Long?,
    ) {
        var interactionChanged: () -> Unit = {}

        fun createWheel() = NativeNotificationDurationWheel(context, lifecycle, interactionState,
            { readRevision() }, { seconds ->
                onSeconds(seconds)
                readRevision()?.let { NotificationDurationCommit(seconds, it) }
            }, interactionChanged = { interactionChanged() })
    }
}
