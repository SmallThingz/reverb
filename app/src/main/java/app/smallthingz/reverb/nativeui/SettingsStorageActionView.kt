package app.smallthingz.reverb.nativeui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Color as AndroidColor
import android.graphics.Rect
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import app.smallthingz.nativeui.graphics.Color
import app.smallthingz.nativeui.graphics.toArgb
import androidx.core.view.ViewCompat
import app.smallthingz.reverb.NativeClickPhaseEndpoint
import app.smallthingz.reverb.NativeClickPhaseHost
import app.smallthingz.reverb.ReverbClickPhaseEndpoint
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Storage's two exact source surfaces:48dp/14dp icon action or default TextButton/12dp raised fill. */
internal class SettingsStorageActionView(
    context:Context,
    initial:ReverbViewEnvironment,
    private val icon:ReverbIcon?,
    private val description:String,
    inputRoot:()->View?,
    onClick:()->Unit,
):ViewGroup(context),AutoCloseable {
    private var environment=initial
    private var callback:(()->Unit)?=onClick
    private val radius=if(icon==null)12f else 14f
    private val surface=ReverbGroupLayout(context,radius)
    private val content=ActionContent(context)
    private val indication=ReverbRowIndication.install(content,initial.palette.onSurface,ValueAnimator::getDurationScale)
    private val controller=ReverbClickController(content,object:NativeClickEnvironment {
        override val nativeInputRoot:View? get()=inputRoot()
        override val minimumTouchWidthPx get()=48f*environment.textDensity.density
        override val minimumTouchHeightPx get()=minimumTouchWidthPx
        override fun admitDown(event:MotionEvent)=reverbGroupGeometry(content.width.toFloat(),content.height.toFloat(),
            environment.textDensity.density,radius).acceptsPointer(event.x,event.y,
                event.getToolType(0)==MotionEvent.TOOL_TYPE_FINGER,minimumTouchWidthPx)
        override fun interestedParent(event:MotionEvent)=false
        override fun longPressOwnershipChanged(owns:Boolean)=Unit
    },ReverbRowIndicationSink(indication){x,y,out->out.set(x,y)})
    private val endpoint=ReverbClickPhaseEndpoint(controller)
    val visual:View get()=content
    init {
        importantForAccessibility=IMPORTANT_FOR_ACCESSIBILITY_NO
        clipChildren=false;clipToPadding=false
        surface.setPalette(initial.palette.raised,AndroidColor.TRANSPARENT)
        surface.addRow(content,ReverbGroupLayout.LayoutParams(-1,-2))
        addView(surface)
        bind(initial,true)
    }
    fun bind(next:ReverbViewEnvironment,enabled:Boolean) {
        environment=next
        surface.setPalette(next.palette.raised,AndroidColor.TRANSPARENT)
        val textColor=if(enabled)next.palette.primary else Color(next.palette.onSurface).copy(alpha=.38f).toArgb()
        content.label?.let {
            it.setTypography(reverbButtonTextStyle(),next.textDensity,next.sharedFontResolver)
            it.setTextColor(textColor)
        }
        content.glyph?.bind(requireNotNull(icon),next.palette.onSurfaceVariant)
        // Surface Icon overrides only the Icon tint; TextButton changes its inherited content color.
        indication.updateColor(if(icon==null)textColor else next.palette.onSurface)
        controller.bind(NativeClickBinding(this,enabled,{content.performClick()}))
        content.isEnabled=enabled;content.isFocusable=enabled
        requestLayout()
    }
    override fun onMeasure(w:Int,h:Int) {
        surface.measure(w,h)
        val minimum=dp(48)
        setMeasuredDimension(resolveSize(max(minimum,surface.measuredWidth),w),
            resolveSize(max(minimum,surface.measuredHeight),h))
    }
    override fun onLayout(changed:Boolean,l:Int,t:Int,r:Int,b:Int) {
        val x=((width-surface.measuredWidth)/2f).roundToInt()
        val y=((height-surface.measuredHeight)/2f).roundToInt()
        surface.layout(x,y,x+surface.measuredWidth,y+surface.measuredHeight)
    }
    override fun close() {callback=null;controller.cancel()}
    private fun dp(value:Int)=(value*environment.textDensity.density).roundToInt()

    private inner class ActionContent(context:Context):ViewGroup(context),
        NativeClickPhaseHost,NativePointerLayerShape,NativeMergedRowAccessibilityHost {
        val label=if(icon==null)ReverbParagraphView(context,reverbButtonTextStyle(),environment.textDensity,
            environment.sharedFontResolver,environment.palette.primary).also {it.setText(description)}else null
        val glyph=icon?.let {OnboardingIconView(context,it,environment.palette.onSurfaceVariant).apply {
            contentDescription=description
            importantForAccessibility=IMPORTANT_FOR_ACCESSIBILITY_YES
        }}
        private val semanticChild:View get()=label?:requireNotNull(glyph)
        override val nativeClickEndpoint:NativeClickPhaseEndpoint get()=endpoint
        override fun containsPointerLayer(x:Float,y:Float)=reverbGroupGeometry(width.toFloat(),height.toFloat(),
            environment.textDensity.density,radius).contains(x,y)
        override val mergedRowAccessibility=ReverbMergedRowAccessibility(this,NativeMergedRowRole.BUTTON,
            {NativeMergedRowAccessibilityState(isEnabled,null,null,listOf(semanticChild))},{activate()})
        init {
            importantForAccessibility=IMPORTANT_FOR_ACCESSIBILITY_YES
            isClickable=true;isFocusable=true
            descendantFocusability=FOCUS_BLOCK_DESCENDANTS
            ViewCompat.setScreenReaderFocusable(semanticChild,false)
            addView(semanticChild);addView(mergedRowAccessibility.roleView)
            accessibilityDelegate=mergedRowAccessibility
        }
        private fun activate():Boolean {
            val action=callback?.takeIf{isEnabled}?:return false
            action();return true
        }
        override fun performClick():Boolean {
            if(!isEnabled||callback==null)return false
            super.performClick();return activate()
        }
        override fun createAccessibilityNodeInfo():AccessibilityNodeInfo?=
            if(mergedRowAccessibility.isNodePresent())super.createAccessibilityNodeInfo()else null
        override fun addChildrenForAccessibility(outChildren:ArrayList<View>)=
            mergedRowAccessibility.addChildrenForAccessibility(outChildren)
        override fun onMeasure(w:Int,h:Int) {
            val available=if(MeasureSpec.getMode(w)==MeasureSpec.UNSPECIFIED)Int.MAX_VALUE else MeasureSpec.getSize(w)
            val width:Int
            val height:Int
            if(label!=null) {
                label.measure(if(available==Int.MAX_VALUE)unspecified()else atMost((available-2*dp(12)).coerceAtLeast(0)),unspecified())
                width=resolveSize(max(dp(58),label.measuredWidth+2*dp(12)),w)
                height=resolveSize(max(dp(40),label.measuredHeight+2*dp(8)),h)
            }else {
                width=resolveSize(dp(48),w);height=resolveSize(dp(48),h)
                requireNotNull(glyph).measure(exact(min(width,dp(24))),exact(min(height,dp(24))))
            }
            mergedRowAccessibility.roleView.measure(exact(width),exact(height))
            setMeasuredDimension(width,height)
        }
        override fun onLayout(changed:Boolean,l:Int,t:Int,r:Int,b:Int) {
            val child=semanticChild
            val x=((width-child.measuredWidth)/2f).roundToInt()
            val y=((height-child.measuredHeight)/2f).roundToInt()
            child.layout(x,y,x+child.measuredWidth,y+child.measuredHeight)
            mergedRowAccessibility.roleView.layout(0,0,width,height)
            indication.onMeasured(width,height,environment.textDensity.density)
        }
        override fun onTouchEvent(event:MotionEvent)=controller.touch(event)
        override fun onKeyDown(code:Int,event:KeyEvent)=if(activation(code))controller.key(event)else super.onKeyDown(code,event)
        override fun onKeyUp(code:Int,event:KeyEvent)=if(activation(code))controller.key(event)else super.onKeyUp(code,event)
        override fun onHoverEvent(event:MotionEvent):Boolean {val native=super.onHoverEvent(event);return controller.hover(event)||native}
        override fun onFocusChanged(gain:Boolean,direction:Int,old:Rect?) {super.onFocusChanged(gain,direction,old);controller.focusChanged(gain)}
        override fun onAttachedToWindow() {
            super.onAttachedToWindow();controller.attached()
            controller.bind(if(callback==null)null else NativeClickBinding(this@SettingsStorageActionView,isEnabled,{performClick()}))
        }
        override fun onDetachedFromWindow() {controller.detached();super.onDetachedFromWindow()}
        override fun onCancelPendingInputEvents() {super.onCancelPendingInputEvents();controller.cancel()}
        private fun activation(code:Int)=code==KeyEvent.KEYCODE_DPAD_CENTER||code==KeyEvent.KEYCODE_ENTER||
            code==KeyEvent.KEYCODE_NUMPAD_ENTER||code==KeyEvent.KEYCODE_SPACE
    }
}
