package app.smallthingz.reverb.nativeui

import android.view.View
import smallthingz.ui.reverb.ReverbInlineRecordingRename
import smallthingz.ui.util.closePreservingPrimaryFailure
import androidx.lifecycle.LifecycleOwner
import app.smallthingz.reverb.NativeRecordingRenameCallbacks
import app.smallthingz.reverb.NativeRecordingRenameOwner
import app.smallthingz.reverb.NativeRecordingRenameState
import app.smallthingz.reverb.R
import app.smallthingz.reverb.RecordingEntity
import kotlinx.coroutines.CoroutineScope

/**
 * Retained same-recording header. Recycling the row never owns the accepted operation.
 * Retire terminal delivery before clearing focus/detaching the editor.
 */
internal class NativeLibraryRenamePresentation(
    anchor:View,
    private var environment:ReverbViewEnvironment,
    recording:RecordingEntity,
    active:Boolean,
    lifecycleOwner:LifecycleOwner,
    parentScope:CoroutineScope,
    callbacks:NativeRecordingRenameCallbacks,
    private val changedNodes: () -> Unit,
    private val revealRequested: () -> Unit,
):AutoCloseable {
    private var ready=false
    private var closed=false
    private var renderedState: NativeRecordingRenameState? = null
    private var renderedEnvironment: ReverbViewEnvironment? = null
    val owner: NativeRecordingRenameOwner=NativeRecordingRenameOwner(anchor.context,recording,active,lifecycleOwner,
        parentScope,callbacks, ::acceptsInput){if(ready&&!closed)render()}
    val view: ReverbInlineRecordingRename = ReverbInlineRecordingRename(anchor.context, environment, parentScope, anchor,
        anchor.context.getString(R.string.rename_recording), anchor.context.getString(R.string.cancel),
        anchor.context.getString(R.string.done), { owner.state.name }, owner::editName,
        owner::requestDismiss, owner::submit)
    init {
        ready = true
        render()
    }
    private fun acceptsInput() = ready && !closed && view.isAttachedToWindow && view.isShown && view.hasWindowFocus()
    fun bindEnvironment(next: ReverbViewEnvironment) { environment = next; render() }
    fun bind(recording:RecordingEntity,active:Boolean,callbacks:NativeRecordingRenameCallbacks) {
        if(closed)return
        owner.bind(recording,active,callbacks)
        if (!active) view.editor.retireInputSession()
        render()
    }
    private fun render() {
        val state=owner.state
        if (state == renderedState && environment === renderedEnvironment) return
        val geometryChanged = state.error != renderedState?.error || environment !== renderedEnvironment
        renderedState = state
        renderedEnvironment = environment
        view.bind(environment, state.name, state.error, state.isRenaming)
        if (view.isAttachedToWindow) {
            if (geometryChanged) revealRequested()
            changedNodes()
        }
    }
    override fun close() {
        if(closed)return
        closed=true
        val failure = closePreservingPrimaryFailure(null, owner::close)
        closePreservingPrimaryFailure(failure, view::close)?.let { throw it }
    }
}
