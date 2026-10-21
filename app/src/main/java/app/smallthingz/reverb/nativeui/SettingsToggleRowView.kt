package app.smallthingz.reverb.nativeui

import android.content.Context
import android.graphics.Rect
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.core.view.ViewCompat
import app.smallthingz.reverb.NativeClickPhaseEndpoint
import app.smallthingz.reverb.NativeClickPhaseHost
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Shared only by the three released Audio toggle headers with identical64dp/14dp/8dp geometry. */
internal class SettingsToggleRowView(
    context:Context,
    text:String,
    initialChecked:Boolean,
    initialEnabled:Boolean,
    private var environment:ReverbViewEnvironment,
    inputRoot:()->View?,
    private val dimDisabledText:Boolean,
    private var changed:((Boolean)->Unit)?,
):ViewGroup(context),NativeClickPhaseHost,NativeMergedRowAccessibilityHost,AutoCloseable {
    private var checked=initialChecked
    private var enabled=initialEnabled
    private val label=environment.paragraph(context,text,ReverbTextRole.BODY_LARGE)
    private val switch=ReverbMaterialSwitchView(context,environment.palette,initialChecked,initialEnabled)
    private val interactions=OnboardingNativeInteractionsFactory(inputRoot).row(this)
    override val nativeClickEndpoint:NativeClickPhaseEndpoint get()=interactions.pointerEndpoint
    override val mergedRowAccessibility=ReverbMergedRowAccessibility(this,NativeMergedRowRole.SWITCH,
        {NativeMergedRowAccessibilityState(enabled,checked,null,listOf(label))},{activate()})
    private var gap=0
    private var labelWidth=0
    init {
        isClickable=true;isFocusable=enabled;isEnabled=enabled
        importantForAccessibility=IMPORTANT_FOR_ACCESSIBILITY_YES
        descendantFocusability=FOCUS_BLOCK_DESCENDANTS
        ViewCompat.setScreenReaderFocusable(label,false)
        switch.importantForAccessibility=IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        addView(label);addView(switch);addView(mergedRowAccessibility.roleView)
        accessibilityDelegate=mergedRowAccessibility
        bind(initialChecked,initialEnabled,environment)
    }
    fun bind(checked:Boolean,enabled:Boolean,next:ReverbViewEnvironment) {
        this.checked=checked;this.enabled=enabled;environment=next
        interactions.bind(enabled,next.palette.ink)
        isEnabled=enabled;isFocusable=enabled
        next.bindParagraphStyle(label,ReverbTextRole.BODY_LARGE,
            if(!enabled&&dimDisabledText)next.palette.muted else next.palette.ink)
        switch.setPalette(next.palette);switch.setState(checked,enabled)
        requestLayout()
    }
    private fun activate():Boolean {
        val action=changed?.takeIf { enabled }?:return false
        action(!checked);return true
    }
    override fun performClick():Boolean {
        if(!enabled||changed==null)return false
        super.performClick();return activate()
    }
    override fun onMeasure(w:Int,h:Int) {
        val width=MeasureSpec.getSize(w);val inner=(width-2*dp(14)).coerceAtLeast(0)
        switch.measure(atMost(inner),unspecified())
        gap=min(dp(12),(inner-switch.measuredWidth).coerceAtLeast(0))
        labelWidth=(inner-switch.measuredWidth-gap).coerceAtLeast(0)
        // Text itself has weight(1f), so unlike Text inside a weighted Column it fills its allocation.
        label.measure(exact(labelWidth),unspecified())
        val height=max(dp(64),2*dp(8)+max(label.measuredHeight,switch.measuredHeight))
        mergedRowAccessibility.roleView.measure(exact(width),exact(height))
        setMeasuredDimension(resolveSize(width,w),resolveSize(height,h))
    }
    override fun onLayout(changed:Boolean,l:Int,t:Int,r:Int,b:Int) {
        fun place(view:View,start:Int) {
            val x=if(layoutDirection==LAYOUT_DIRECTION_RTL)width-start-view.measuredWidth else start
            val y=dp(8)+((height-2*dp(8)-view.measuredHeight)/2f).roundToInt()
            view.layout(x,y,x+view.measuredWidth,y+view.measuredHeight)
        }
        place(label,dp(14));place(switch,dp(14)+labelWidth+gap)
        mergedRowAccessibility.roleView.layout(0,0,width,height)
        interactions.measured(width,height)
    }
    override fun onTouchEvent(event:MotionEvent)=interactions.touch(event)
    override fun addChildrenForAccessibility(outChildren:ArrayList<View>) =
        mergedRowAccessibility.addChildrenForAccessibility(outChildren)
    override fun onKeyDown(code:Int,event:KeyEvent)=if(activationKey(code))interactions.key(event)else super.onKeyDown(code,event)
    override fun onKeyUp(code:Int,event:KeyEvent)=if(activationKey(code))interactions.key(event)else super.onKeyUp(code,event)
    override fun onHoverEvent(event:MotionEvent):Boolean {val native=super.onHoverEvent(event);return interactions.hover(event)||native}
    override fun onFocusChanged(gain:Boolean,direction:Int,previous:Rect?) {super.onFocusChanged(gain,direction,previous);interactions.focusChanged(gain)}
    override fun onAttachedToWindow() {super.onAttachedToWindow();interactions.attached()}
    override fun onDetachedFromWindow() {interactions.detached();super.onDetachedFromWindow()}
    override fun onCancelPendingInputEvents() {super.onCancelPendingInputEvents();interactions.cancel()}
    override fun close() {changed=null;interactions.cancel()}
    private fun dp(v:Int)=(v*environment.textDensity.density).roundToInt()
    private fun activationKey(code:Int)=code==KeyEvent.KEYCODE_DPAD_CENTER||code==KeyEvent.KEYCODE_ENTER||
        code==KeyEvent.KEYCODE_NUMPAD_ENTER||code==KeyEvent.KEYCODE_SPACE
}
