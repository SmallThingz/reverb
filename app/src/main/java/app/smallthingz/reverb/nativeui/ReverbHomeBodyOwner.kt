package app.smallthingz.reverb.nativeui

import android.content.Context
import android.graphics.RectF
import android.view.View
import app.smallthingz.reverb.RetentionMode
import app.smallthingz.reverb.ReverbService.BufferSlot
import app.smallthingz.reverb.captureBufferUiState
import kotlinx.coroutines.CoroutineScope

internal typealias ReverbHomeBodyOwner = smallthingz.ui.reverb.home.ReverbHomeBodyOwner<BufferSlot, RetentionMode>

internal fun ReverbHomeBodyOwner(
    context: Context,
    environment: ReverbViewEnvironment,
    state: ReverbHomeBodyState,
    selection: BufferSlot,
    actions: ReverbHomeActions,
    scope: CoroutineScope,
    inputRoot: () -> View?,
    onBoundsChanged: (View, RectF, View) -> Unit,
): ReverbHomeBodyOwner = ReverbHomeBodyOwner(
    context, environment, state, selection, actions, scope, inputRoot,
    BufferSlot.ONE_SHOT, BufferSlot.LOOPING, HomeReadoutCache(), ::captureBufferUiState, onBoundsChanged,
)
