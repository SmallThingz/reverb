package app.smallthingz.reverb.nativeui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.accessibility.AccessibilityNodeInfo
import smallthingz.ui.graphics.Color
import smallthingz.ui.graphics.toArgb
import androidx.core.view.ViewCompat
import app.smallthingz.reverb.NativeClickPhaseEndpoint
import app.smallthingz.reverb.NativeClickPhaseHost
import smallthingz.ui.animation.view.ViewColorTransition
import smallthingz.ui.animation.view.ViewSpringDriver
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Shared action with opt-in filled calibration progress; Initial gesture owns the stable outer box. */
internal class SettingsCalibrationActionView(
    context:Context,
    environment:ReverbViewEnvironment,
    inputRoot:()->View?,
    leadingIcon:ReverbIcon?=null,
    private val stableLabels:List<String> = emptyList(),
    private val progressSurface:Boolean=false,
    action:()->Unit,
):ViewGroup(context),AutoCloseable {
    private var environment=environment
    private val visual=CalibrationActionSurface(context,environment,inputRoot,leadingIcon,progressSurface,action)
    private val sizingLabel=if(stableLabels.isEmpty())null else ReverbParagraphView(context,reverbButtonTextStyle(),
        environment.textDensity,environment.sharedFontResolver,environment.palette.primary)
    val textView get()=visual.label
    init {
        importantForAccessibility=IMPORTANT_FOR_ACCESSIBILITY_NO
        clipChildren=false;clipToPadding=false;addView(visual)
    }
    fun bind(text:String,enabled:Boolean,next:ReverbViewEnvironment) {
        environment=next;visual.bind(text,enabled,next)
    }
    fun bindProgress(progress:Float?,running:Boolean) = visual.bindProgress(progress,running)
    override fun onMeasure(w:Int,h:Int) {
        visual.measure(w,h)
        val minimum=(48f*environment.textDensity.density).roundToInt()
        var stableHeight=minimum
        sizingLabel?.let {label->
            label.setTypography(reverbButtonTextStyle(),environment.textDensity,environment.sharedFontResolver)
            val padding=2*(12f*environment.textDensity.density).roundToInt()
            val textWidth=if(MeasureSpec.getMode(w)==MeasureSpec.UNSPECIFIED)unspecified()
                else atMost((MeasureSpec.getSize(w)-padding).coerceAtLeast(0))
            stableLabels.forEach {text->
                label.setText(text);label.measure(textWidth,unspecified())
                stableHeight=max(stableHeight,label.measuredHeight+2*(8f*environment.textDensity.density).roundToInt())
            }
        }
        val measuredW=resolveSize(max(minimum,visual.measuredWidth),w)
        val measuredH=resolveSize(max(stableHeight,visual.measuredHeight),h)
        if(progressSurface)visual.measure(exact(measuredW),exact(measuredH))
        setMeasuredDimension(measuredW,measuredH)
    }
    override fun onLayout(changed:Boolean,l:Int,t:Int,r:Int,b:Int) {
        val x=((width-visual.measuredWidth)/2f).roundToInt()
        val y=((height-visual.measuredHeight)/2f).roundToInt()
        visual.layout(x,y,x+visual.measuredWidth,y+visual.measuredHeight)
    }
    override fun close()=visual.close()
}

private class CalibrationActionSurface(
    context:Context,
    private var environment:ReverbViewEnvironment,
    inputRoot:()->View?,
    private val leadingIcon:ReverbIcon?,
    private val progressSurface:Boolean,
    private var action:(()->Unit)?,
):ViewGroup(context),NativeClickPhaseHost,NativePointerLayerShape,NativeMergedRowAccessibilityHost,AutoCloseable {
    val label=ReverbParagraphView(context,reverbButtonTextStyle(),environment.textDensity,
        environment.sharedFontResolver,environment.palette.primary)
    private val icon=leadingIcon?.let {OnboardingIconView(context,it,environment.palette.primary).apply {
        importantForAccessibility=IMPORTANT_FOR_ACCESSIBILITY_NO
    }}
    private val interactions=OnboardingNativeInteractionsFactory(inputRoot).create(this,this,false) {}
    private var enabled=true
    private val path=Path()
    private val paint=Paint(Paint.ANTI_ALIAS_FLAG)
    private var progress:Float?=null
    private var running=false
    private var closed=false
    private val fill=calibrationProgressSpring()
    private var fillDriver:ViewSpringDriver<Float,smallthingz.ui.animation.core.AnimationVector1D>?=null
    private var heldPulse:ValueAnimator?=null
    private var heldOpacity=.7f
    private fun stopMotion() {
        fillDriver?.close();fillDriver=null
        heldPulse?.cancel();heldPulse=null
    }
    private fun updateMotion() {
        if(closed||!isAttachedToWindow||!isShown||windowVisibility!=VISIBLE||!running) {
            stopMotion();fill.snapTo(progress?:0f);return
        }
        if(progress==null) {
            fillDriver?.close();fillDriver=null
            if(heldPulse==null&&ValueAnimator.areAnimatorsEnabled()) {
                heldPulse=ValueAnimator.ofFloat(.45f,1f).apply {
                    duration=900L;repeatMode=ValueAnimator.REVERSE;repeatCount=ValueAnimator.INFINITE
                    interpolator=AccelerateDecelerateInterpolator()
                    addUpdateListener {heldOpacity=it.animatedValue as Float;invalidate()}
                    start()
                }
            }
        } else {
            heldPulse?.cancel();heldPulse=null
            val driver=fillDriver?:ViewSpringDriver(this,fill,{invalidate()}).also {fillDriver=it}
            driver.retarget(requireNotNull(progress))
        }
    }
    private val colors=ViewColorTransition(this,intArrayOf(
        if(progressSurface)environment.palette.ink else environment.palette.primary,
        Color(environment.palette.primary).copy(alpha=.12f).toArgb(),
        Color(environment.palette.primary).copy(alpha=.22f).toArgb()),{publishColors()})
    private fun publishColors() {
        val ink=colors.color(0)
        label.setTextColor(ink)
        if(leadingIcon!=null)icon?.bindImmediately(leadingIcon,ink)
        interactions.bind(enabled,ink)
        invalidate()
    }
    private fun retargetColors() {
        val ink=if(enabled||progressSurface&&running) {
            if(progressSurface)environment.palette.ink else environment.palette.primary
        } else Color(environment.palette.onSurfaceVariant).copy(alpha=.38f).toArgb()
        colors.retarget(ink,
            Color(environment.palette.primary).copy(alpha=if(enabled||running).12f else .06f).toArgb(),
            Color(environment.palette.primary).copy(alpha=.22f).toArgb())
        publishColors()
    }
    fun bindProgress(value:Float?,active:Boolean) {
        val starting=active&&!running
        progress=value?.takeIf {it.isFinite()}?.coerceIn(0f,1f);running=active
        if(starting) {stopMotion();fill.snapTo(0f);heldOpacity=.7f}
        updateMotion()
        retargetColors()
        ViewCompat.setStateDescription(this,if(active) progress?.let {"${(it*100).roundToInt()}%"} ?: context.getString(app.smallthingz.reverb.R.string.silence_sampling) else null)
        invalidate()
    }
    override val nativeClickEndpoint:NativeClickPhaseEndpoint get()=interactions.pointerEndpoint
    override fun containsPointerLayer(x:Float,y:Float)=reverbGroupGeometry(width.toFloat(),height.toFloat(),
        environment.textDensity.density,min(width,height)/(2f*environment.textDensity.density)).contains(x,y)
    override val mergedRowAccessibility=run {
        val children=listOf(label)
        ReverbMergedRowAccessibility(this,NativeMergedRowRole.BUTTON,
            {NativeMergedRowAccessibilityState(enabled,null,null,children)},{activate()})
    }
    init {
        isClickable=true;isFocusable=true
        importantForAccessibility=IMPORTANT_FOR_ACCESSIBILITY_YES
        descendantFocusability=FOCUS_BLOCK_DESCENDANTS
        ViewCompat.setScreenReaderFocusable(label,false)
        icon?.let(::addView)
        addView(label);addView(mergedRowAccessibility.roleView)
        accessibilityDelegate=mergedRowAccessibility
        clipToOutline=true
        outlineProvider=object:ViewOutlineProvider() {
            override fun getOutline(view:View,outline:Outline) {
                outline.setRoundRect(0,0,width,height,min(width,height)/2f)
            }
        }
        if(android.os.Build.VERSION.SDK_INT>=29)isForceDarkAllowed=false
    }
    fun bind(text:String,enabled:Boolean,next:ReverbViewEnvironment) {
        val densityChanged=environment.textDensity.density!=next.textDensity.density
        this.enabled=enabled;environment=next
        isEnabled=enabled;isFocusable=enabled
        label.setText(text)
        label.setTypography(reverbButtonTextStyle(),next.textDensity,next.sharedFontResolver)
        retargetColors()
        invalidate()
        if(densityChanged)requestLayout()
    }
    private fun activate():Boolean {
        val callback=action?.takeIf {enabled}?:return false
        callback();return true
    }
    override fun performClick():Boolean {
        if(!enabled||action==null)return false
        super.performClick();return activate()
    }
    override fun addChildrenForAccessibility(outChildren:ArrayList<View>)=
        mergedRowAccessibility.addChildrenForAccessibility(outChildren)
    override fun createAccessibilityNodeInfo():AccessibilityNodeInfo?=
        if(mergedRowAccessibility.isNodePresent())super.createAccessibilityNodeInfo()?.also {node->
            if(progressSurface&&running) {
                androidx.core.view.accessibility.AccessibilityNodeInfoCompat.wrap(node).stateDescription=
                    progress?.let {"${(it*100).roundToInt()}%"} ?: context.getString(app.smallthingz.reverb.R.string.silence_sampling)
                progress?.let {node.rangeInfo=AccessibilityNodeInfo.RangeInfo.obtain(
                    AccessibilityNodeInfo.RangeInfo.RANGE_TYPE_FLOAT,0f,1f,it)}
            }
        }else null
    override fun onMeasure(w:Int,h:Int) {
        val horizontal=dp(12);val vertical=dp(8)
        val innerWidth=if(MeasureSpec.getMode(w)==MeasureSpec.UNSPECIFIED)Int.MAX_VALUE
            else (MeasureSpec.getSize(w)-2*horizontal).coerceAtLeast(0)
        val innerHeight=if(MeasureSpec.getMode(h)==MeasureSpec.UNSPECIFIED)Int.MAX_VALUE
            else (MeasureSpec.getSize(h)-2*vertical).coerceAtLeast(0)
        icon?.measure(exact(min(dp(20),innerWidth)),exact(min(dp(20),innerHeight)))
        val leading=icon?.let {it.measuredWidth+min(dp(8),(innerWidth-it.measuredWidth).coerceAtLeast(0))}?:0
        val availableWidth=if(MeasureSpec.getMode(w)==MeasureSpec.UNSPECIFIED)unspecified()
            else atMost((innerWidth-leading).coerceAtLeast(0))
        val availableHeight=if(MeasureSpec.getMode(h)==MeasureSpec.UNSPECIFIED)unspecified()
            else atMost((MeasureSpec.getSize(h)-2*vertical).coerceAtLeast(0))
        label.measure(availableWidth,availableHeight)
        val width=resolveSize(max(dp(58),leading+label.measuredWidth+2*horizontal),w)
        val height=resolveSize(max(dp(40),max(label.measuredHeight,icon?.measuredHeight?:0)+2*vertical),h)
        mergedRowAccessibility.roleView.measure(exact(width),exact(height))
        setMeasuredDimension(width,height)
    }
    override fun onLayout(changed:Boolean,l:Int,t:Int,r:Int,b:Int) {
        val innerWidth=(width-2*dp(12)).coerceAtLeast(0)
        val leading=icon?.let {it.measuredWidth+min(dp(8),(innerWidth-it.measuredWidth).coerceAtLeast(0))}?:0
        val start=((width-label.measuredWidth-leading)/2f).roundToInt()
        icon?.let {
            val ix=if(layoutDirection==LAYOUT_DIRECTION_RTL)width-start-it.measuredWidth else start
            val iy=((height-it.measuredHeight)/2f).roundToInt()
            it.layout(ix,iy,ix+it.measuredWidth,iy+it.measuredHeight)
        }
        val x=if(icon==null)start else if(layoutDirection==LAYOUT_DIRECTION_RTL)
            width-start-leading-label.measuredWidth else start+leading
        val y=((height-label.measuredHeight)/2f).roundToInt()
        label.layout(x,y,x+label.measuredWidth,y+label.measuredHeight)
        mergedRowAccessibility.roleView.layout(0,0,width,height)
        interactions.measured(width,height)
    }
    override fun onSizeChanged(w:Int,h:Int,oldw:Int,oldh:Int) {
        path.rewind();path.addRoundRect(0f,0f,w.toFloat(),h.toFloat(),min(w,h)/2f,min(w,h)/2f,Path.Direction.CW)
        invalidateOutline()
    }
    override fun dispatchDraw(canvas:Canvas) {
        if(progressSurface) {
            paint.color=colors.color(1)
            canvas.drawRoundRect(0f,0f,width.toFloat(),height.toFloat(),height/2f,height/2f,paint)
            if(running) {
                val checkpoint=canvas.save()
                canvas.clipPath(path)
                paint.color=colors.color(2)
                if(progress==null)paint.alpha=(paint.alpha*heldOpacity).roundToInt()
                val fraction=if(progress==null)1f else fill.value.coerceIn(0f,1f)
                if(layoutDirection==LAYOUT_DIRECTION_RTL)canvas.drawRect(width*(1f-fraction),0f,width.toFloat(),height.toFloat(),paint)
                else canvas.drawRect(0f,0f,width*fraction,height.toFloat(),paint)
                canvas.restoreToCount(checkpoint)
            }
        }
        super.dispatchDraw(canvas)
    }
    override fun draw(canvas:Canvas) {
        if(canvas.isHardwareAccelerated)super.draw(canvas)
        else {
            val checkpoint=canvas.save()
            try {canvas.clipPath(path);super.draw(canvas)}finally {canvas.restoreToCount(checkpoint)}
        }
    }
    override fun onTouchEvent(event:MotionEvent)=interactions.touch(event)
    override fun onKeyDown(code:Int,event:KeyEvent)=if(isActivation(code))interactions.key(event)else super.onKeyDown(code,event)
    override fun onKeyUp(code:Int,event:KeyEvent)=if(isActivation(code))interactions.key(event)else super.onKeyUp(code,event)
    override fun onHoverEvent(event:MotionEvent):Boolean {val native=super.onHoverEvent(event);return interactions.hover(event)||native}
    override fun onFocusChanged(focused:Boolean,direction:Int,old:Rect?) {super.onFocusChanged(focused,direction,old);interactions.focusChanged(focused)}
    override fun onAttachedToWindow() {super.onAttachedToWindow();interactions.attached();updateMotion()}
    override fun onDetachedFromWindow() {stopMotion();interactions.detached();super.onDetachedFromWindow()}
    override fun onVisibilityChanged(changedView:View,visibility:Int) {
        super.onVisibilityChanged(changedView,visibility)
        if(isAttachedToWindow)updateMotion()
    }
    override fun onWindowVisibilityChanged(visibility:Int) {
        super.onWindowVisibilityChanged(visibility)
        if(isAttachedToWindow)updateMotion()
    }
    override fun onCancelPendingInputEvents() {super.onCancelPendingInputEvents();interactions.cancel()}
    override fun close() {closed=true;stopMotion();action=null;interactions.cancel();colors.close()}
    private fun dp(value:Int)=(value*environment.textDensity.density).roundToInt()
    private fun isActivation(code:Int)=code==KeyEvent.KEYCODE_DPAD_CENTER||code==KeyEvent.KEYCODE_ENTER||
        code==KeyEvent.KEYCODE_NUMPAD_ENTER||code==KeyEvent.KEYCODE_SPACE
}
