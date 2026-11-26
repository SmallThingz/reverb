package app.smallthingz.reverb.nativeui

import smallthingz.ui.input.phase.NativePointerLayerShape
import smallthingz.ui.input.phase.NativeClickPhaseHost
import smallthingz.ui.input.phase.NativeClickPhaseNode
import smallthingz.ui.input.phase.NativeViewPointerHitLayout
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import androidx.lifecycle.findViewTreeLifecycleOwner
import app.smallthingz.reverb.*
import smallthingz.ui.components.NativeActionSheet

/** One real ReverbActionSheet route per current Settings tile-delete target. */
internal class NativeSettingsTileDeleteSheet(
    private val anchor:View,
    private val environment:()->ReverbViewEnvironment,
):AutoCloseable {
    private var owner:NativeQuickSettingsTilesOwner?=null
    private var target:QuickSettingsTileDeleteTarget?=null
    private var dialog:NativeActionSheet?=null
    private var content:QuickTileDeleteContent?=null
    private var focusLease:AutoCloseable?=null
    private var textLease:AutoCloseable?=null
    private var actionsLease:AutoCloseable?=null
    private val clicks=ArrayList<NativeClickPhaseNode>()
    private var closed=false

    fun bind(currentOwner:NativeQuickSettingsTilesOwner,next:QuickSettingsTileDeleteTarget?) {
        if(closed)return
        if(next==null) {
            if(owner===currentOwner)retire()
            return
        }
        if(owner===currentOwner&&target===next) {
            val value = environment()
            dialog?.bindEnvironment(value)
            content?.bind(value)
            return
        }
        retire()
        owner=currentOwner;target=next
        val value=environment()
        val sheet=createReverbActionSheet(anchor,value,anchor.context.getString(R.string.tiles_ui_delete)) {
            currentOwner.dismissDelete()
        }
        dialog=sheet
        try {
            val context=sheet.contentContext
            val lifecycle=requireNotNull(anchor.findViewTreeLifecycleOwner()).lifecycle
            val body=QuickTileDeleteContent(sheet.context,currentOwner,next,value,{context.inputRoot},
                lifecycle,{context.inputRoot},{sheet.hasCurrentWindowFocus})
            content=body
            val accessibility=context.accessibility
            textLease=body.body.bindPlainTextAccessibility(accessibility.cursor,accessibility.geometry,
                {accessibility.sourceNode(body.body)}) {accessibility.sourceNode(body.body).present}
            actionsLease=NativePageTextAccessibilityOwner(body.actions,accessibility.cursor,accessibility.geometry,
                {accessibility.sourceNode(it).present},{accessibility.sourceNode(it)}).also {it.refresh()}
            fun hit(view:View):NativeViewPointerHitLayout {
                val node=(view as?NativeClickPhaseHost)?.let {
                    NativeClickPhaseNode(view,it.nativeClickEndpoint,context.interestedParent).also(clicks::add)
                }
                val children=if(view is ViewGroup)(view.childCount-1 downTo 0).map {hit(view.getChildAt(it))} else emptyList()
                return context.hit(view,node?.let(::listOf)?:emptyList(),{children},
                    clip={point->(view as?NativePointerLayerShape)?.containsPointerLayer(point.x,point.y)?:true})
            }
            sheet.setContent(body.body,body.actions,listOf(hit(body.body),hit(body.actions)),scrollable=true)
            focusLease=observeSheetFocus(sheet,body)
            sheet.show()
        } catch(failure:Throwable) {
            try {retire()}catch(cleanup:Throwable) {if(cleanup!==failure)failure.addSuppressed(cleanup)}
            throw failure
        }
    }

    private fun observeSheetFocus(sheet: NativeActionSheet, body: QuickTileDeleteContent): AutoCloseable {
        val decor = checkNotNull(sheet.window).decorView
        val root = sheet.contentContext.inputRoot
        var tree: ViewTreeObserver? = null
        fun refresh() {
            if (dialog === sheet && content === body) body.bind(environment())
        }
        val focus = ViewTreeObserver.OnWindowFocusChangeListener { refresh() }
        fun removeFocusListener() {
            tree?.takeIf { it.isAlive }?.removeOnWindowFocusChangeListener(focus)
            tree = null
        }
        val attachment = object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(view: View) {
                tree = decor.viewTreeObserver.also { it.addOnWindowFocusChangeListener(focus) }
                refresh()
            }

            override fun onViewDetachedFromWindow(view: View) = removeFocusListener()
        }
        root.addOnAttachStateChangeListener(attachment)
        return AutoCloseable {
            root.removeOnAttachStateChangeListener(attachment)
            removeFocusListener()
        }
    }

    private fun retire() {
        val oldDialog=dialog;dialog=null
        val oldContent=content;content=null
        val oldFocus=focusLease;focusLease=null
        val oldText=textLease;textLease=null
        val oldActions=actionsLease;actionsLease=null
        owner=null;target=null
        var failure:Throwable?=null
        fun release(action:()->Unit) {try{action()}catch(error:Throwable){
            if(failure==null)failure=error else if(failure!==error)failure!!.addSuppressed(error)
        }}
        release {oldFocus?.close()}
        // Window removal cancels its actual retained pointer paths before the controls are disposed.
        release {oldDialog?.dismiss()}
        clicks.forEach {release(it::cancel)};clicks.clear()
        release {oldActions?.close()};release {oldText?.close()};release {oldContent?.close()}
        failure?.let {throw it}
    }
    override fun close() {if(!closed){closed=true;retire()}}
}
