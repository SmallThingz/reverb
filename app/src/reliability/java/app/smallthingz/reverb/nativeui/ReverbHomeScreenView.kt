package app.smallthingz.reverb.nativeui

import android.content.Context
import android.graphics.RectF
import android.view.View
import app.smallthingz.reverb.ReverbService.BufferSlot
import kotlinx.coroutines.CoroutineScope

/**
 * Existing normal-Home API. Its root now shares the exact same direct-child Scaffold geometry with
 * Main's permission/range branch host; there is no additional View, renderer or hidden Capture owner.
 */
internal class ReverbHomeScreenView(
    context: Context,
    initialEnvironment: ReverbViewEnvironment,
    initialState: ReverbHomeBodyState,
    initialSelection: BufferSlot,
    initialActions: ReverbHomeActions,
    initialNavigation: ReverbAppTopBarActions,
    initialHasIncidents: Boolean,
    initialInsets: ReverbHomeInsets,
    nativeInputRoot: View,
    animationScope: CoroutineScope,
    onBoundsChanged: (body: View, bodyLocalBounds: RectF, blob: View) -> Unit,
) : ReverbHomeScaffoldView(context, initialEnvironment, initialNavigation, initialHasIncidents,
    initialInsets, nativeInputRoot), AutoCloseable {
    private val bodyOwner = ReverbHomeBodyOwner(context, initialEnvironment, initialState,
        initialSelection, initialActions, animationScope, { nativeInputRoot }, onBoundsChanged)

    init { setBody(bodyOwner.view) }

    fun bind(
        state: ReverbHomeBodyState,
        selection: BufferSlot,
        actions: ReverbHomeActions,
        navigation: ReverbAppTopBarActions,
        hasIncidents: Boolean,
        nextInsets: ReverbHomeInsets,
    ) {
        bindShell(navigation, hasIncidents, nextInsets)
        bodyOwner.bind(state, selection, actions)
    }

    override fun setEnvironment(value: ReverbViewEnvironment) {
        super.setEnvironment(value)
        bodyOwner.setEnvironment(value)
    }

    override fun close() = bodyOwner.close()
}
