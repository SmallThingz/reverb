package app.smallthingz.reverb.nativeui

import android.view.View
import app.smallthingz.reverb.R
import app.smallthingz.reverb.RecordingEntity

/** One source RecordingInfoDialogContent lifetime; no recording read/write, player or operation owner. */
internal class NativeRecordingInfoSheet(
    anchor:View,
    environment:ReverbViewEnvironment,
    recording:RecordingEntity,
    onDismiss:()->Unit,
):AutoCloseable {
    private val dialog=ReverbActionSheetDialog(anchor,environment,
        anchor.context.getString(R.string.recording_info),onDismiss)
    private val body=ReverbRecordingInfoBody(anchor.context,dialog.contentContext,recording)
    private val actions=LibrarySheetActions(anchor.context,dialog.contentContext,listOf(
        LibrarySheetButton(anchor.context,dialog.contentContext,true,anchor.context.getString(R.string.close),onDismiss)))
    private var closed=false
    init {dialog.setContent(body,actions,listOf(actions.hitLayout,body.hitLayout))}
    fun show()=dialog.show()
    fun bindEnvironment(next:ReverbViewEnvironment) {
        if(closed)return
        dialog.bindEnvironment(next)
        body.bindEnvironment(next)
        actions.bindEnvironment(next)
    }
    fun bind(recording:RecordingEntity,onDismiss:()->Unit) {
        body.bind(recording)
        dialog.bind(dialog.context.getString(R.string.recording_info),onDismiss)
        actions.buttons.single().bind(dialog.context.getString(R.string.close),true,onDismiss)
    }
    override fun close() {
        if(closed)return
        closed=true
        var failure:Throwable?=null
        fun cleanup(block:()->Unit){try{block()}catch(error:Throwable){if(failure==null)failure=error else failure!!.addSuppressed(error)}}
        cleanup(dialog::dismiss);cleanup(actions::close);cleanup(body::close)
        failure?.let{throw it}
    }
}
