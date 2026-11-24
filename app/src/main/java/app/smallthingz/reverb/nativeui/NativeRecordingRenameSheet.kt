package app.smallthingz.reverb.nativeui

import android.view.View
import smallthingz.ui.reverb.ReverbRecordingRenameSheet
import smallthingz.ui.util.closePreservingPrimaryFailure
import androidx.lifecycle.LifecycleOwner
import app.smallthingz.reverb.NativeRecordingRenameCallbacks
import app.smallthingz.reverb.NativeRecordingRenameOwner
import app.smallthingz.reverb.R
import app.smallthingz.reverb.RecordingEntity
import kotlinx.coroutines.CoroutineScope

/**
 * Retained same-recording Rename composition. Accepted operations live in the source owner rather
 * than in the Dialog/Editor; replacing metadata does not replace the editor selection or Window.
 */
internal class NativeRecordingRenameSheet(
    anchor:View,
    environment:ReverbViewEnvironment,
    recording:RecordingEntity,
    active:Boolean,
    lifecycleOwner:LifecycleOwner,
    parentScope:CoroutineScope,
    callbacks:NativeRecordingRenameCallbacks,
):AutoCloseable {
    private var ready=false
    private var closed=false
    private val owner=NativeRecordingRenameOwner(anchor.context,recording,active,lifecycleOwner,
        parentScope,callbacks){if(ready&&!closed)render()}
    private val sheet = ReverbRecordingRenameSheet(anchor, environment,
        anchor.context.getString(R.string.rename_recording), anchor.context.getString(R.string.cancel),
        owner.state.name, owner.state.error, { owner.state.name }, { !owner.state.isRenaming },
        owner::editName, owner::requestDismiss, owner::submit)
    init {
        ready = true
        render()
    }
    fun show() = sheet.show()
    fun bindEnvironment(next: ReverbViewEnvironment) = sheet.bindEnvironment(next)
    fun bind(recording:RecordingEntity,active:Boolean,callbacks:NativeRecordingRenameCallbacks) {
        if(closed)return
        owner.bind(recording,active,callbacks)
        render()
    }
    private fun render() {
        val state=owner.state
        sheet.bind(state.name, state.error, !state.isRenaming && state.name.isNotBlank())
    }
    override fun close() {
        if(closed)return
        closed=true
        val failure = closePreservingPrimaryFailure(null, sheet::close)
        closePreservingPrimaryFailure(failure, owner::close)?.let { throw it }
    }
}
