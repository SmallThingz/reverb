package app.smallthingz.reverb.nativeui

import android.view.View
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
    private val dialog=ReverbActionSheetDialog(anchor,environment,
        anchor.context.getString(R.string.rename_recording),owner::requestDismiss)
    private val field=ReverbRenameFieldView(anchor.context,dialog.contentContext,
        owner.state.name,owner.state.error,{owner.state.name},owner::editName,owner::submit)
    private val cancel=LibrarySheetButton(anchor.context,dialog.contentContext,false,
        anchor.context.getString(R.string.cancel),owner::requestDismiss)
    private val confirm=LibrarySheetButton(anchor.context,dialog.contentContext,true,
        anchor.context.getString(R.string.rename_recording),owner::submit)
    private val actions=LibrarySheetActions(anchor.context,dialog.contentContext,listOf(cancel,confirm))
    init {
        dialog.setContent(field,actions,listOf(actions.hitLayout,field.hitLayout))
        ready=true
        render()
    }
    fun show()=dialog.show()
    fun bindEnvironment(next:ReverbViewEnvironment) {
        if(closed)return
        dialog.bindEnvironment(next)
        field.bindEnvironment(next)
        actions.bindEnvironment(next)
    }
    fun bind(recording:RecordingEntity,active:Boolean,callbacks:NativeRecordingRenameCallbacks) {
        if(closed)return
        owner.bind(recording,active,callbacks)
        render()
    }
    private fun render() {
        val state=owner.state
        field.bind(state.name,state.error,{owner.state.name},owner::editName,owner::submit)
        cancel.bind(dialog.context.getString(R.string.cancel),!state.isRenaming,owner::requestDismiss)
        confirm.bind(dialog.context.getString(R.string.rename_recording),
            !state.isRenaming&&state.name.isNotBlank(),owner::submit)
    }
    override fun close() {
        if(closed)return
        closed=true
        var failure:Throwable?=null
        fun release(block:()->Unit){try{block()}catch(error:Throwable){if(failure==null)failure=error else failure!!.addSuppressed(error)}}
        release(dialog::dismiss)
        release(actions::close)
        release(field::close)
        release(owner::close)
        failure?.let{throw it}
    }
}
