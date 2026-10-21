package app.smallthingz.reverb.nativeui

import android.view.View
import app.smallthingz.reverb.R

/**
 * MainActivity309-325's existing ReverbMessageSheet. The Activity still owns actual permission facts
 * and ACTION_APPLICATION_DETAILS_SETTINGS; this presentation never requests or changes permissions.
 */
internal class ReverbPermissionDeniedDialog(
    anchor:View,
    environment:ReverbViewEnvironment,
    microphoneAllowed:Boolean,
    legacyStorageAllowed:Boolean,
    onDismiss:()->Unit,
    onOpenApplicationSettings:()->Unit,
):AutoCloseable {
    val dialog=ReverbActionSheetDialog(anchor,environment,anchor.context.getString(R.string.permission_required),onDismiss)
    private val context=dialog.context
    private val host=dialog.contentContext
    private val message=environment.paragraph(context,"",ReverbTextRole.BODY_MEDIUM,environment.palette.onSurfaceVariant)
    private val close=LibrarySheetButton(context,host,false,context.getString(R.string.close),onDismiss)
    private val allow=LibrarySheetButton(context,host,true,context.getString(R.string.allow),onOpenApplicationSettings)
    private val actions=LibrarySheetActions(context,host,listOf(close,allow))
    private val messageLease=message.bindPlainTextAccessibility(host.accessibility.cursor,host.accessibility.geometry,
        sourceNode={host.accessibility.sourceNode(message)}) {host.accessibility.sourceNode(message).present}
    private var disposed=false
    init {
        message.isScreenReaderFocusable=true
        dialog.setContent(message,actions,listOf(actions.hitLayout))
        bind(microphoneAllowed,legacyStorageAllowed,onDismiss,onOpenApplicationSettings)
    }
    fun bind(microphoneAllowed:Boolean,legacyStorageAllowed:Boolean,onDismiss:()->Unit,
             onOpenApplicationSettings:()->Unit) {
        dialog.bind(context.getString(R.string.permission_required),onDismiss)
        val resource=if(!microphoneAllowed)R.string.permission_required_message
            else if(!legacyStorageAllowed)R.string.storage_permission_required_message
            else R.string.permission_required_message
        message.setText(context.getString(resource))
        close.bind(context.getString(R.string.close),true,onDismiss)
        // Released confirm launches Settings without dismissing this sheet.
        allow.bind(context.getString(R.string.allow),true,onOpenApplicationSettings)
    }
    fun bindEnvironment(next:ReverbViewEnvironment) {
        if(disposed||dialog.environment===next)return
        dialog.bindEnvironment(next)
        next.bindParagraphStyle(message,ReverbTextRole.BODY_MEDIUM,next.palette.onSurfaceVariant)
        actions.bindEnvironment(next)
    }
    fun show()=dialog.show()
    override fun close() {
        if(disposed)return
        disposed=true
        var failure:Throwable?=null
        fun release(action:()->Unit) {try{action()}catch(error:Throwable) {
            val first=failure
            if(first==null)failure=error else if(first!==error)first.addSuppressed(error)
        }}
        release {dialog.dismiss()}
        release {actions.close()}
        release {messageLease.close()}
        failure?.let{throw it}
    }
}
