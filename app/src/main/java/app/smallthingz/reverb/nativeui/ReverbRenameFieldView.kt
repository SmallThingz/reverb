/*
 * No-label OutlinedTextField layout/border adapted from AndroidX Material3 1.4.0 and
 * foundation 1.11.2. Copyright The Android Open Source Project. Apache-2.0.
 */
package app.smallthingz.reverb.nativeui

import android.content.Context
import android.content.ClipboardManager
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityEvent
import android.view.inputmethod.InputMethodManager
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import smallthingz.ui.animation.Animatable
import smallthingz.ui.animation.core.Animatable as ValueAnimatable
import smallthingz.ui.animation.core.VectorConverter
import smallthingz.ui.animation.core.spring
import smallthingz.ui.graphics.Color
import smallthingz.ui.graphics.toArgb
import smallthingz.ui.text.PlatformTextStyle
import smallthingz.ui.text.TextStyle
import smallthingz.ui.text.style.LineHeightStyle
import smallthingz.ui.unit.dp
import app.smallthingz.reverb.NativeAndroidInteropHost
import app.smallthingz.reverb.NativeAndroidInteropNode
import app.smallthingz.reverb.NativeAndroidInteropRequests
import app.smallthingz.reverb.NativeViewPointerHitLayout
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

private val renameInheritedBody = TextStyle.Default.copy(
    platformStyle=PlatformTextStyle(includeFontPadding=false),
    lineHeightStyle=LineHeightStyle(LineHeightStyle.Alignment.Center,LineHeightStyle.Trim.None),
).merge(ReverbParagraphStyles[ReverbTextRole.BODY_LARGE])

private val renameSupportingStyle=renameInheritedBody.merge(ReverbParagraphStyles[ReverbTextRole.BODY_SMALL])

/**
 * Concrete released Rename's no-label/single-line outlined field. This is a source candidate:
 * native Editor text/cursor/selection, drag arbitration and editable semantic topology still require
 * actual A/B qualification. The border, supporting text and IME contract are not platform defaults.
 */
internal class ReverbRenameFieldView(
    context:Context,
    private val host:NativeActionSheetContentContext,
    initialName:String,
    initialError:String?,
    readName:()->String,
    onNameChanged:(String)->Unit,
    onDone:()->Unit,
):ViewGroup(context),NativeAndroidInteropHost,AutoCloseable {
    private var environment=host.environment
    private var error=initialError
    private var readName=readName
    private var onNameChanged=onNameChanged
    private var onDone=onDone
    private var closed=false
    private var fieldHeight=0
    private var textBoxHeight=0
    private var supportBoxHeight=0
    private var textTop=0
    private var supportTop=0
    private val borderPaint=Paint(Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val borderBounds=RectF()
    private val color=Animatable(Color(if(initialError!=null)environment.palette.error else environment.palette.outline))
    private val thickness=ValueAnimatable(1.dp,smallthingz.ui.unit.Dp.VectorConverter)
    private val colorTargets=Channel<Color>(Channel.CONFLATED)
    private val thicknessTargets=Channel<smallthingz.ui.unit.Dp>(Channel.CONFLATED)
    private val ownerJob=Job(host.runtime.scope.coroutineContext[Job])
    private val scope=CoroutineScope(host.runtime.scope.coroutineContext+ownerJob)
    override val nativeInteropRequests=NativeAndroidInteropRequests()
    val editor=ReverbSingleLineInputView(context,environment,configuration())
    private val support=ReverbParagraphView(context,renameSupportingStyle,environment.textDensity,
        environment.sharedFontResolver,environment.palette.error)
    private val interop=NativeAndroidInteropNode(this,nativeInteropRequests)
    val hitLayout:NativeViewPointerHitLayout=host.hit(this,listOf(interop))
    private val supportLease:AutoCloseable

    init {
        setWillNotDraw(false)
        clipChildren=false;clipToPadding=false
        importantForAccessibility=IMPORTANT_FOR_ACCESSIBILITY_YES
        isScreenReaderFocusable=true
        editor.importantForAccessibility=IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        if(android.os.Build.VERSION.SDK_INT>=29)isForceDarkAllowed=false
        addView(editor);addView(support)
        support.isScreenReaderFocusable=false // Descendant of the source merging editable node.
        supportLease=support.bindPlainTextAccessibility(host.accessibility.cursor,host.accessibility.geometry,
            {host.accessibility.sourceNode(support)}){host.accessibility.sourceNode(support).present}
        // CoreTextField's merging editable node is the OUTER decoration, with supporting Text
        // as its real semantic child. The inner native Editor must never become a sibling node.
        // No TextView.setError: that would add a platform icon/popup absent from the source.
        editor.onNativeFocusChanged={
            retarget()
            bindEditor()
        }
        // animate*AsState's conflated target delivery plus child animation jobs. The shared actual
        // Window dispatcher/clock and duration scale remain authoritative; no private Choreographer.
        scope.launch {
            for(target in colorTargets) {
                val next=colorTargets.tryReceive().getOrNull()?:target
                launch {if(next!=color.targetValue)color.animateTo(next,spring(dampingRatio=1f,stiffness=3800f)){invalidate()}}
            }
        }
        scope.launch {
            for(target in thicknessTargets) {
                val next=thicknessTargets.tryReceive().getOrNull()?:target
                launch {if(next!=thickness.targetValue)thickness.animateTo(next,spring(dampingRatio=.9f,stiffness=1400f)){invalidate()}}
            }
        }
        bind(initialName,initialError,readName,onNameChanged,onDone)
    }

    fun bind(name:String,error:String?,readName:()->String,onNameChanged:(String)->Unit,onDone:()->Unit) {
        if(closed)return
        val changedError=this.error!=error
        this.error=error;this.readName=readName;this.onNameChanged=onNameChanged;this.onDone=onDone
        support.visibility=if(error==null)GONE else VISIBLE
        support.setText(error.orEmpty())
        editor.bind(name,configuration(),environment,readName,onNameChanged,onDone)
        retarget()
        if(changedError)requestLayout()
    }
    fun bindEnvironment(next:ReverbViewEnvironment) {
        if(closed || environment===next)return
        val densityChanged=environment.textDensity!=next.textDensity
        environment=next
        support.setTypography(renameSupportingStyle,next.textDensity,next.sharedFontResolver)
        support.setTextColor(next.palette.error)
        bindEditor()
        retarget()
        if(densityChanged)requestLayout()
    }
    private fun bindEditor()=editor.bind(readName(),configuration(),environment,readName,onNameChanged,onDone)
    private fun configuration()=NativeSingleLineInputConfiguration(
        style=renameInheritedBody,textColor=environment.palette.onSurface,
        cursorColor=if(error!=null)environment.palette.error else environment.palette.primary,
        selectionBackgroundColor=reverbColorWithAlpha(environment.palette.primary,.4f),
        selectionHandleColor=environment.palette.primary,ime=nativeRenameImeContract())
    private fun retarget() {
        colorTargets.trySend(Color(when {
            error!=null->environment.palette.error
            editor.isFocused->environment.palette.primary
            else->environment.palette.outline
        }))
        thicknessTargets.trySend(if(editor.isFocused)2.dp else 1.dp)
    }

    override fun onMeasure(w:Int,h:Int) {
        val width=MeasureSpec.getSize(w) // Caller is the source fillMaxWidth.
        val bounded=MeasureSpec.getMode(h)!=MeasureSpec.UNSPECIFIED
        val maximum=if(bounded)MeasureSpec.getSize(h)else Int.MAX_VALUE
        val edge=dp(16f);val textWidth=max(0,width-edge-edge)
        val supportPadding=dp(4f)
        val intrinsicSupport=if(error==null)0 else max(dp(16f),
            support.intrinsicTextHeight(textWidth)+supportPadding)
        val textMaximum=if(bounded)max(0,maximum-edge-edge-intrinsicSupport)else Int.MAX_VALUE
        editor.measure(renameExact(textWidth),if(bounded)renameAtMost(textMaximum)else renameUnspecified())
        textBoxHeight=max(dp(24f),editor.measuredHeight).coerceAtMost(textMaximum)
        val occupied=edge+textBoxHeight+edge
        if(error!=null) {
            val supportMaximum=if(bounded)max(0,maximum-occupied)else Int.MAX_VALUE
            support.measure(renameAtMost(textWidth),
                if(bounded)renameAtMost(max(0,supportMaximum-supportPadding))else renameUnspecified())
            supportBoxHeight=max(dp(16f),support.measuredHeight+supportPadding).coerceAtMost(supportMaximum)
        } else {
            support.measure(renameExact(0),renameExact(0));supportBoxHeight=0
        }
        val minimum=if(MeasureSpec.getMode(h)==MeasureSpec.EXACTLY)maximum else min(dp(56f),maximum)
        val measured=nativeRenameFieldHeight(textBoxHeight,supportBoxHeight,environment.textDensity.density,minimum,maximum)
        setMeasuredDimension(resolveSize(width,w),resolveSize(measured,h))
        fieldHeight=measuredHeight-supportBoxHeight
        val boxTop=((fieldHeight-textBoxHeight)*.5f).roundToInt().coerceAtLeast(0)
        textTop=boxTop+((textBoxHeight-editor.measuredHeight)*.5f).roundToInt()
        supportTop=fieldHeight+((supportBoxHeight-(support.measuredHeight+supportPadding))*.5f).roundToInt()+supportPadding
    }
    override fun onLayout(changed:Boolean,l:Int,t:Int,r:Int,b:Int) {
        val edge=dp(16f)
        editor.layout(edge,textTop,edge+editor.measuredWidth,textTop+editor.measuredHeight)
        val x=if(layoutDirection==LAYOUT_DIRECTION_RTL)width-edge-support.measuredWidth else edge
        support.layout(x,supportTop,x+support.measuredWidth,supportTop+support.measuredHeight)
    }
    override fun dispatchDraw(canvas:Canvas) {
        super.dispatchDraw(canvas)
        val minimum=min(width.toFloat(),fieldHeight.toFloat())
        if(minimum<=0f)return
        val stroke=min(ceil(thickness.value.value*environment.textDensity.density),ceil(minimum*.5f))
        val radius=min(4f*environment.textDensity.density,minimum*.5f)
        borderPaint.color=color.value.toArgb()
        borderBounds.set(0f,0f,width.toFloat(),fieldHeight.toFloat())
        if(stroke*2f>minimum) {
            borderPaint.style=Paint.Style.FILL
            canvas.drawRoundRect(borderBounds,radius,radius,borderPaint)
        } else if(radius<stroke*.5f) {
            val save=canvas.save()
            try {
                canvas.clipOutRect(stroke,stroke,width-stroke,fieldHeight-stroke)
                borderPaint.style=Paint.Style.FILL
                canvas.drawRoundRect(borderBounds,radius,radius,borderPaint)
            } finally {canvas.restoreToCount(save)}
        } else {
            borderBounds.inset(stroke*.5f,stroke*.5f)
            borderPaint.style=Paint.Style.STROKE;borderPaint.strokeWidth=stroke
            canvas.drawRoundRect(borderBounds,radius-stroke*.5f,radius-stroke*.5f,borderPaint)
        }
    }
    // BasicTextField admits the decorated field, not only glyph bounds. Map that retained local
    // stream to the actual editor; its precise gesture behavior remains part of native qualification.
    override fun dispatchTouchEvent(event:MotionEvent):Boolean {
        val local=MotionEvent.obtain(event)
        local.offsetLocation(-editor.left.toFloat(),-editor.top.toFloat())
        return try{editor.dispatchTouchEvent(local)}finally{local.recycle()}
    }
    override fun requestDisallowInterceptTouchEvent(disallowIntercept:Boolean) {
        nativeInteropRequests.request(disallowIntercept)
        super.requestDisallowInterceptTouchEvent(disallowIntercept)
    }
    override fun addChildrenForAccessibility(out:ArrayList<View>) {
        if(host.accessibility.sourceNode(this).present && error!=null &&
            host.accessibility.sourceNode(support).present) out.add(support)
    }

    override fun onInitializeAccessibilityNodeInfo(info:AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        info.className="android.widget.EditText"
        info.text=editor.text.toString().let { value ->
            // AndroidComposeView's ParcelSafeTextLength; never split a surrogate pair.
            if(value.length<=100000)value else value.substring(0,
                if(Character.isHighSurrogate(value[99999]) && Character.isLowSurrogate(value[100000]))99999 else 100000)
        }
        info.isEditable=true;info.isPassword=false;info.maxTextLength=-1
        info.isEnabled=true;info.isFocusable=true;info.isFocused=editor.isFocused
        info.isClickable=true;info.isLongClickable=true;info.isScreenReaderFocusable=true
        info.isContentInvalid=error!=null
        info.error=if(error!=null)context.getString(smallthingz.ui.R.string.default_error_message)else null
        AccessibilityNodeInfoCompat.wrap(info).stateDescription=
            if(editor.length()==0 && error.isNullOrEmpty())context.getString(smallthingz.ui.R.string.state_empty)else null
        info.addAction(if(editor.isFocused)AccessibilityNodeInfo.ACTION_CLEAR_FOCUS else AccessibilityNodeInfo.ACTION_FOCUS)
        info.addAction(AccessibilityNodeInfo.ACTION_CLICK)
        info.addAction(AccessibilityNodeInfo.ACTION_LONG_CLICK)
        info.addAction(AccessibilityNodeInfo.ACTION_SET_TEXT)
        info.addAction(AccessibilityNodeInfo.AccessibilityAction(android.R.id.accessibilityActionImeEnter,null))
        val selected=editor.selectionStart!=editor.selectionEnd
        if(selected) {
            info.addAction(AccessibilityNodeInfo.ACTION_COPY)
            info.addAction(AccessibilityNodeInfo.ACTION_CUT)
        }
        if(editor.isFocused && (context.getSystemService(ClipboardManager::class.java).primaryClipDescription?.hasMimeType("text/*")==true))
            info.addAction(AccessibilityNodeInfo.ACTION_PASTE)
        if(editor.length()>0) {
            info.setTextSelection(editor.selectionStart,editor.selectionEnd)
            info.addAction(AccessibilityNodeInfo.ACTION_SET_SELECTION)
            info.addAction(AccessibilityNodeInfo.ACTION_NEXT_AT_MOVEMENT_GRANULARITY)
            info.addAction(AccessibilityNodeInfo.ACTION_PREVIOUS_AT_MOVEMENT_GRANULARITY)
            info.movementGranularities=AccessibilityNodeInfo.MOVEMENT_GRANULARITY_CHARACTER or
                AccessibilityNodeInfo.MOVEMENT_GRANULARITY_WORD or AccessibilityNodeInfo.MOVEMENT_GRANULARITY_PARAGRAPH
            if(editor.isFocused) info.movementGranularities=info.movementGranularities or
                AccessibilityNodeInfo.MOVEMENT_GRANULARITY_LINE or AccessibilityNodeInfo.MOVEMENT_GRANULARITY_PAGE
            info.availableExtraData=listOf(AccessibilityNodeInfo.EXTRA_DATA_TEXT_CHARACTER_LOCATION_KEY)
        }
        host.accessibility.sourceNode(this).populate(info)
    }

    override fun performAccessibilityAction(action:Int,args:Bundle?):Boolean {
        if(closed || !host.accessibility.sourceNode(this).present)return false
        return when(action) {
            AccessibilityNodeInfo.ACTION_FOCUS -> editor.requestFocus()
            AccessibilityNodeInfo.ACTION_CLEAR_FOCUS -> if(editor.isFocused){editor.clearFocus();true}else false
            AccessibilityNodeInfo.ACTION_CLICK -> {
                editor.requestFocus()
                context.getSystemService(InputMethodManager::class.java).showSoftInput(editor,0)
                sendAccessibilityEvent(AccessibilityEvent.TYPE_VIEW_CLICKED)
                true
            }
            AccessibilityNodeInfo.ACTION_LONG_CLICK -> {editor.performLongClick();true}
            AccessibilityNodeInfo.ACTION_SET_TEXT -> editor.setTextFromSemantics(
                args?.getCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE)?.toString().orEmpty())
            AccessibilityNodeInfo.ACTION_SET_SELECTION -> editor.setSelectionFromSemantics(
                args?.getInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT,-1)?:-1,
                args?.getInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT,-1)?:-1)
            android.R.id.accessibilityActionImeEnter -> {onDone();true}
            AccessibilityNodeInfo.ACTION_COPY -> if(editor.selectionStart!=editor.selectionEnd){
                editor.onTextContextMenuItem(android.R.id.copy);true
            }else false
            AccessibilityNodeInfo.ACTION_CUT -> if(editor.selectionStart!=editor.selectionEnd){
                editor.onTextContextMenuItem(android.R.id.cut);true
            }else false
            AccessibilityNodeInfo.ACTION_PASTE -> {editor.onTextContextMenuItem(android.R.id.paste);true}
            AccessibilityNodeInfo.ACTION_NEXT_AT_MOVEMENT_GRANULARITY,
            AccessibilityNodeInfo.ACTION_PREVIOUS_AT_MOVEMENT_GRANULARITY -> editor.performAccessibilityAction(action,args)
            // Native Editor must not silently add platform-only editing actions to the outer node.
            else -> super.performAccessibilityAction(action,args)
        }
    }

    override fun addExtraDataToAccessibilityNodeInfo(info:AccessibilityNodeInfo,key:String,args:Bundle?) {
        if(key==AccessibilityNodeInfo.EXTRA_DATA_TEXT_CHARACTER_LOCATION_KEY &&
            host.accessibility.sourceNode(this).present)
            editor.addExtraDataToAccessibilityNodeInfo(info,key,args)
        else super.addExtraDataToAccessibilityNodeInfo(info,key,args)
    }

    override fun onInitializeAccessibilityEvent(event:AccessibilityEvent) {
        // createEvent supplies no synthesized text/content for click/focus/subtree events.
        event.className="android.view.View"
        event.packageName=context.packageName
        event.isEnabled=true
        event.isPassword=false
        event.setSource(this)
    }
    override fun dispatchPopulateAccessibilityEvent(event:AccessibilityEvent):Boolean=true

    override fun requestSendAccessibilityEvent(child:View,event:AccessibilityEvent):Boolean {
        if(child===editor) {
            // The input leaf is hidden, so both text/selection events and their source IDs belong
            // to the exposed outer editable owner, not a nonexistent native Editor node.
            event.setSource(this)
            event.className=if(event.eventType==AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED)
                "android.widget.EditText" else "android.view.View"
            event.isEnabled=true
            event.isPassword=false
        }
        return super.requestSendAccessibilityEvent(child,event)
    }
    override fun close() {
        if(closed)return
        closed=true
        var failure:Throwable?=null
        fun release(action:()->Unit){try{action()}catch(error:Throwable){if(failure==null)failure=error else if(failure!==error)failure!!.addSuppressed(error)}}
        release(interop::close);release(supportLease::close)
        release(editor::releaseCallbacks)
        release{colorTargets.close()};release{thicknessTargets.close()};release{ownerJob.cancel()}
        failure?.let{throw it}
    }
    private fun dp(value:Float)=(value*environment.textDensity.density).roundToInt()
}

private fun renameExact(value:Int)=View.MeasureSpec.makeMeasureSpec(value.coerceAtLeast(0),View.MeasureSpec.EXACTLY)
private fun renameAtMost(value:Int)=View.MeasureSpec.makeMeasureSpec(value.coerceAtLeast(0),View.MeasureSpec.AT_MOST)
private fun renameUnspecified()=View.MeasureSpec.makeMeasureSpec(0,View.MeasureSpec.UNSPECIFIED)
