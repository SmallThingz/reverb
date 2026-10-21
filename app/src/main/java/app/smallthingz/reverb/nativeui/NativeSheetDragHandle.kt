package app.smallthingz.reverb.nativeui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.os.Bundle
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import app.smallthingz.reverb.NativeClickPhaseHost
import app.smallthingz.reverb.ReverbClickPhaseEndpoint
import kotlin.math.max
import kotlin.math.roundToInt

/** M3's32dp handle box with22dp vertical padding;48dp is touch expansion, not layout width. */
internal class NativeSheetDragHandle(
    context:Context,
    private var environment:ReverbViewEnvironment,
    inputRoot:()->View?,
    runtime:NativeAboutWindowRuntime,
    private val action:()->Unit,
    private val dismissSheet:()->Unit,
):ViewGroup(context),NativeClickPhaseHost,AutoCloseable {
    private val identity=Any()
    val mark=HandleMark(context,environment)
    private val indication=ReverbRowIndication.install(this,environment.palette.onSurface,
        ValueAnimator::getDurationScale)
    private val controller=ReverbClickController(this,object:NativeClickEnvironment {
        override val nativeInputRoot get()=inputRoot()
        override val minimumTouchWidthPx get()=48f*environment.textDensity.density
        override val minimumTouchHeightPx get()=minimumTouchWidthPx
        override fun admitDown(event:MotionEvent):Boolean {
            val expand=if(event.getToolType(0)==MotionEvent.TOOL_TYPE_FINGER)
                max(0f,minimumTouchWidthPx-width)*.5f else 0f
            return event.x>=-expand&&event.x<=width+expand&&event.y>=0&&event.y<=height
        }
        override fun interestedParent(event:MotionEvent)=false
        override fun longPressOwnershipChanged(owns:Boolean)=Unit
    },ReverbRowIndicationSink(indication) {x,y,out->out.set(x,y)})
    override val nativeClickEndpoint=ReverbClickPhaseEndpoint(controller)
    val tooltip=NativeSheetTooltip(this,environment,runtime,mark.contentDescription.toString())
    var sourceNode:(()->NativeSourceAccessibilityNode)?=null
    init {
        clipChildren=false;clipToPadding=false
        importantForAccessibility=IMPORTANT_FOR_ACCESSIBILITY_YES
        isClickable=true;isFocusable=true;isScreenReaderFocusable=true
        setOnClickListener {action()}
        addView(mark)
        controller.bind(NativeClickBinding(identity,true,{performClick()}))
    }
    fun bindEnvironment(next:ReverbViewEnvironment) {
        if(environment===next)return
        val densityChanged=environment.textDensity!=next.textDensity
        environment=next
        mark.bindColor(next.palette.onSurfaceVariant)
        indication.updateColor(next.palette.onSurface)
        tooltip.bindEnvironment(next)
        if(densityChanged)requestLayout()
    }
    fun refreshState()=invalidate()
    override fun onMeasure(widthMeasureSpec:Int,heightMeasureSpec:Int) {
        val d=environment.textDensity.density
        val w=resolveSize((32f*d).roundToInt(),widthMeasureSpec)
        val pad=(22f*d).roundToInt()
        val h=resolveSize(pad*2+(4f*d).roundToInt(),heightMeasureSpec)
        mark.measure(sheetHandleExact(w),sheetHandleExact(max(0,h-pad*2)))
        setMeasuredDimension(w,h)
    }
    override fun onLayout(changed:Boolean,l:Int,t:Int,r:Int,b:Int) {
        val y=(22f*environment.textDensity.density).roundToInt().coerceAtMost(height)
        mark.layout(0,y,width,y+mark.measuredHeight)
        indication.onMeasured(width,height,environment.textDensity.density)
    }
    override fun addChildrenForAccessibility(outChildren:ArrayList<View>) {
        if(mark.visibility==VISIBLE&&mark.sourceNode?.invoke()?.present!=false)outChildren.add(mark)
    }
    override fun onInitializeAccessibilityNodeInfo(info:AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        info.className="android.view.View";info.text=null;info.contentDescription=null
        info.isClickable=true;info.isLongClickable=true;info.isEnabled=true
        info.isFocusable=true;info.isFocused=isFocused;info.isScreenReaderFocusable=true
        info.isSelected=false;info.isCheckable=false;info.isChecked=false
        info.actionList.toList().forEach(info::removeAction)
        info.addAction(if(isAccessibilityFocused)AccessibilityNodeInfo.AccessibilityAction.ACTION_CLEAR_ACCESSIBILITY_FOCUS
            else AccessibilityNodeInfo.AccessibilityAction.ACTION_ACCESSIBILITY_FOCUS)
        info.addAction(if(isFocused)AccessibilityNodeInfo.AccessibilityAction.ACTION_CLEAR_FOCUS
            else AccessibilityNodeInfo.AccessibilityAction.ACTION_FOCUS)
        info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_CLICK)
        info.addAction(AccessibilityNodeInfo.AccessibilityAction(AccessibilityNodeInfo.ACTION_LONG_CLICK,
            context.getString(androidx.compose.foundation.R.string.tooltip_label)))
        info.addAction(AccessibilityNodeInfo.AccessibilityAction(AccessibilityNodeInfo.ACTION_DISMISS,
            context.getString(androidx.compose.material3.R.string.m3c_bottom_sheet_dismiss_description)))
        sourceNode?.invoke()?.populate(info)
    }
    override fun createAccessibilityNodeInfo():AccessibilityNodeInfo? =
        if(sourceNode?.invoke()?.present!=false)super.createAccessibilityNodeInfo()else null
    override fun performAccessibilityAction(action:Int,args:Bundle?):Boolean=when(action) {
        AccessibilityNodeInfo.ACTION_CLICK->{this.action();true}
        AccessibilityNodeInfo.ACTION_LONG_CLICK->{tooltip.showFromAccessibility();true}
        AccessibilityNodeInfo.ACTION_DISMISS->{dismissSheet();true}
        AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS,AccessibilityNodeInfo.ACTION_CLEAR_ACCESSIBILITY_FOCUS,
        AccessibilityNodeInfo.ACTION_FOCUS,AccessibilityNodeInfo.ACTION_CLEAR_FOCUS->
            super.performAccessibilityAction(action,args)
        else->false
    }
    override fun onTouchEvent(event:MotionEvent)=controller.touch(event)
    override fun dispatchKeyEvent(event:KeyEvent):Boolean=when(event.keyCode) {
        KeyEvent.KEYCODE_DPAD_CENTER,KeyEvent.KEYCODE_ENTER,KeyEvent.KEYCODE_NUMPAD_ENTER,KeyEvent.KEYCODE_SPACE->
            controller.key(event)
        else->super.dispatchKeyEvent(event)
    }
    override fun onHoverEvent(event:MotionEvent):Boolean {
        val platform=super.onHoverEvent(event)
        tooltip.hover(event)
        return controller.hover(event)||platform
    }
    override fun onFocusChanged(focused:Boolean,direction:Int,old:Rect?) {
        super.onFocusChanged(focused,direction,old)
        controller.focusChanged(focused);tooltip.focusChanged(focused)
    }
    override fun onAttachedToWindow() {
        super.onAttachedToWindow();controller.attached()
        controller.bind(NativeClickBinding(identity,true,{performClick()}));controller.focusChanged(isFocused)
    }
    override fun onDetachedFromWindow() {controller.detached();tooltip.retire();super.onDetachedFromWindow()}
    override fun close() {controller.detached();tooltip.close()}
    override fun getAccessibilityClassName():CharSequence="android.view.View"
    internal class HandleMark(context:Context,environment:ReverbViewEnvironment):View(context) {
        var sourceNode:(()->NativeSourceAccessibilityNode)?=null
        private val paint=Paint(Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
            color=reverbColorWithAlpha(environment.palette.onSurfaceVariant,.45f)
        }
        init {contentDescription=context.getString(androidx.compose.material3.R.string.m3c_bottom_sheet_drag_handle_description)
            importantForAccessibility=IMPORTANT_FOR_ACCESSIBILITY_YES;isScreenReaderFocusable=false}
        fun bindColor(color:Int) {paint.color=reverbColorWithAlpha(color,.45f);invalidate()}
        override fun onDraw(canvas:Canvas) {
            val r=minOf(width,height)*.5f
            canvas.drawRoundRect(0f,0f,width.toFloat(),height.toFloat(),r,r,paint)
        }
        override fun getAccessibilityClassName():CharSequence="android.view.View"
        override fun createAccessibilityNodeInfo():AccessibilityNodeInfo?=
            if(sourceNode?.invoke()?.present!=false)super.createAccessibilityNodeInfo()else null
    }
}
private fun sheetHandleExact(value:Int)=View.MeasureSpec.makeMeasureSpec(value,View.MeasureSpec.EXACTLY)
