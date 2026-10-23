package app.smallthingz.reverb.nativeui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.os.Build
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.accessibility.AccessibilityNodeInfo
import smallthingz.ui.graphics.Color
import smallthingz.ui.graphics.toArgb
import androidx.core.view.ViewCompat
import app.smallthingz.reverb.NativeClickPhaseEndpoint
import app.smallthingz.reverb.NativeClickPhaseHost
import app.smallthingz.reverb.ReverbClickPhaseEndpoint
import kotlin.math.min
import kotlin.math.roundToInt

internal data class SettingsSegment<T>(val value: T, val label: String, val icon: ReverbIcon? = null)

/** SettingsScreen2212–2254:30dp Surface,4dp inset,equal34dp-high source selectables. */
internal class SettingsSegmentedControlView<T>(
    context: Context,
    private val items: List<SettingsSegment<T>>,
    selected: T,
    private var environment: ReverbViewEnvironment,
    inputRoot: () -> View?,
    private val onSelected: (T) -> Unit,
) : ViewGroup(context), AutoCloseable {
    private val surface = ReverbGroupLayout(context,30f)
    private val row = SegmentRow(context)
    private val cells = items.mapIndexed { index,item ->
        SegmentCell(context,item,index,item.value==selected,environment,inputRoot) { onSelected(item.value) }
    }
    init {
        require(items.isNotEmpty())
        importantForAccessibility=IMPORTANT_FOR_ACCESSIBILITY_NO
        surface.addRow(row,ReverbGroupLayout.LayoutParams(-1,-2))
        cells.forEach(row::addView)
        addView(surface)
        surface.setPalette(environment.palette.field,environment.palette.border)
    }
    fun bind(selected: T, next: ReverbViewEnvironment) {
        environment=next
        surface.setPalette(next.palette.field,next.palette.border)
        cells.forEachIndexed { index,cell -> cell.bind(items[index].value==selected,next) }
        requestLayout()
    }
    override fun onMeasure(w: Int,h: Int) {
        surface.measure(w,h)
        setMeasuredDimension(surface.measuredWidth,surface.measuredHeight)
    }
    override fun onLayout(changed: Boolean,l: Int,t: Int,r: Int,b: Int) {
        surface.layout(0,0,width,height)
    }
    override fun close() { cells.forEach { it.close() } }

    private inner class SegmentRow(context: Context): ViewGroup(context), NativeMergedRowAccessibilityHost {
        private val widths=IntArray(items.size)
        override val mergesAccessibilityDescendants=false
        override val accessibilityBoundsInView:smallthingz.ui.geometry.Rect
            get() {
                // padding(4.dp) precedes selectableGroup: only the inner Row is semantic.
                val inset=dp(4).toFloat()
                return smallthingz.ui.geometry.Rect(inset,inset,
                    (width-inset).coerceAtLeast(inset),(height-inset).coerceAtLeast(inset))
            }
        override val mergedRowAccessibility=ReverbMergedRowAccessibility(this,NativeMergedRowRole.BUTTON,
            {NativeMergedRowAccessibilityState(true,null,null,cells,clickable=false,rolePresent=false)},{false})
        init {
            importantForAccessibility=IMPORTANT_FOR_ACCESSIBILITY_YES
            accessibilityDelegate=mergedRowAccessibility
            clipChildren=false
        }
        override fun createAccessibilityNodeInfo():AccessibilityNodeInfo? =
            if(mergedRowAccessibility.isNodePresent())super.createAccessibilityNodeInfo()else null
        override fun addChildrenForAccessibility(children:ArrayList<View>) =
            mergedRowAccessibility.addChildrenForAccessibility(children)
        override fun onInitializeAccessibilityNodeInfo(info:AccessibilityNodeInfo) {
            super.onInitializeAccessibilityNodeInfo(info)
            // Released selectableGroup uses placement order and SELECTION_MODE_NONE.
            info.collectionInfo=AccessibilityNodeInfo.CollectionInfo.obtain(1,items.size,false,
                AccessibilityNodeInfo.CollectionInfo.SELECTION_MODE_NONE)
        }
        override fun onMeasure(w: Int,h: Int) {
            val padding=dp(4)
            val width=MeasureSpec.getSize(w)
            val available=(width-2*padding).coerceAtLeast(0)
            val unit=(available/items.size.toFloat()).roundToInt()
            var remainder=available-unit*items.size
            cells.forEachIndexed { index,cell ->
                val correction=remainder.coerceIn(-1,1)
                remainder-=correction
                widths[index]=(unit+correction).coerceAtLeast(0)
                cell.measure(exact(widths[index]),exact(dp(34)))
            }
            setMeasuredDimension(resolveSize(width,w),resolveSize(dp(34)+2*padding,h))
        }
        override fun onLayout(changed: Boolean,l: Int,t: Int,r: Int,b: Int) {
            var start=dp(4)
            cells.forEachIndexed { index,cell ->
                val x=if(layoutDirection==LAYOUT_DIRECTION_RTL) width-start-widths[index] else start
                cell.layout(x,dp(4),x+widths[index],dp(4)+cell.measuredHeight)
                start+=widths[index]
            }
        }
    }
    private fun dp(v: Int)=(v*environment.textDensity.density).roundToInt()
}

private class SegmentCell<T>(
    context: Context,
    private val item: SettingsSegment<T>,
    private val columnIndex: Int,
    selected: Boolean,
    private var environment: ReverbViewEnvironment,
    inputRoot: () -> View?,
    private var activate: (() -> Unit)?,
): ViewGroup(context), NativeClickPhaseHost, NativePointerLayerShape, NativeMergedRowAccessibilityHost, AutoCloseable {
    override fun containsPointerLayer(x:Float,y:Float):Boolean=
        reverbGroupGeometry(width.toFloat(),height.toFloat(),environment.textDensity.density,22f).contains(x,y)
    private var chosen=selected
    private var closed=false
    private val text=environment.paragraph(context,item.label,ReverbTextRole.BODY_MEDIUM,
        if(selected) environment.palette.settingsOnSelection else environment.palette.muted)
    private val icon=item.icon?.let { OnboardingIconView(context,it,
        if(selected) environment.palette.settingsOnSelection else environment.palette.muted) }
    private val fill=Paint(Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val clip=Path()
    private var contentWidth=0
    private var gap=0
    private val background=ReleasedSpringValue.color(Color(if(selected) environment.palette.settingsSelection else android.graphics.Color.TRANSPARENT))
    private var selectionInk=SettingsSelectionInk(environment.palette)
    private var foregroundColor=if(selected) environment.palette.settingsOnSelection else environment.palette.muted
    private val backgroundDriver=ViewSpringDriver(this,background,{ publishFill(it.toArgb()) })
    private val indication=ReverbRowIndication.install(this,
        if(selected) environment.palette.settingsOnSelection else environment.palette.ink,ValueAnimator::getDurationScale)
    private val sink=ReverbRowIndicationSink(indication) { x,y,out->out.set(x,y) }
    private val input=ReverbClickController(this,object:NativeClickEnvironment {
        override val nativeInputRoot:View? get()=inputRoot()
        override val minimumTouchWidthPx:Float get()=48f*environment.textDensity.density
        override val minimumTouchHeightPx:Float get()=minimumTouchWidthPx
        override fun admitDown(event:MotionEvent)=reverbGroupGeometry(width.toFloat(),height.toFloat(),
            environment.textDensity.density,22f).acceptsPointer(event.x,event.y,
                event.getToolType(0)==MotionEvent.TOOL_TYPE_FINGER,minimumTouchWidthPx)
        override fun interestedParent(event:MotionEvent)=false
        override fun longPressOwnershipChanged(owns:Boolean)=Unit
    },sink)
    override val nativeClickEndpoint:NativeClickPhaseEndpoint=ReverbClickPhaseEndpoint(input)
    override val mergedRowAccessibility=ReverbMergedRowAccessibility(this,NativeMergedRowRole.RADIO_BUTTON,
        {NativeMergedRowAccessibilityState(isEnabled,null,null,listOf(text),selected=chosen)},
        {val action=activate;if(action==null)false else {action();true}})

    init {
        // Released Text only clips actual overflow; glyph ink may exceed its measured advance.
        // The segment outline below remains the source RoundedCornerShape clip.
        clipChildren=false
        clipToPadding=false
        // Draw this owned state layer below glyphs so focus/ripple cannot wash out the label.
        // Preserve Drawable scheduling/invalidation after detaching it from View.foreground.
        check(foreground === indication)
        foreground=null
        indication.callback=this
        setWillNotDraw(false)
        publishFill(background.value.toArgb())
        isClickable=true;isFocusable=true
        importantForAccessibility=IMPORTANT_FOR_ACCESSIBILITY_YES
        ViewCompat.setScreenReaderFocusable(this,true)
        ViewCompat.setScreenReaderFocusable(text,false)
        text.setTextLayout(maxLines=1)
        addView(text)
        icon?.let(::addView)
        // The source merges the label but retains its leaf and a non-drawing role-only child.
        addView(mergedRowAccessibility.roleView)
        accessibilityDelegate=mergedRowAccessibility
        outlineProvider=object:ViewOutlineProvider() {
            override fun getOutline(view:View,outline:Outline) {
                outline.setRoundRect(0,0,view.width,view.height,min(22f*environment.textDensity.density,min(view.width,view.height)/2f))
            }
        }
        clipToOutline=true
        if(Build.VERSION.SDK_INT>=29) isForceDarkAllowed=false
        input.bind(NativeClickBinding(this,true,{performClick()}))
    }
    fun bind(selected:Boolean,next:ReverbViewEnvironment) {
        val selectionChanged=chosen!=selected
        chosen=selected
        if(environment.palette !== next.palette) selectionInk=SettingsSelectionInk(next.palette)
        environment=next
        text.setTypography(ReverbParagraphStyles[ReverbTextRole.BODY_MEDIUM],next.textDensity,next.sharedFontResolver)
        backgroundDriver.retarget(Color(if(selected) next.palette.settingsSelection else android.graphics.Color.TRANSPARENT))
        publishFill(background.value.toArgb())
        updateClip();requestLayout()
        if(selectionChanged)mergedRowAccessibility.notifyRadioSelectionChanged()
    }
    private fun publishFill(argb:Int) {
        fill.color=argb
        val foreground=selectionInk.foreground(argb)
        if(foregroundColor!=foreground) {
            foregroundColor=foreground
            text.setTextColor(foreground)
            item.icon?.let { glyph->icon?.bind(glyph,foreground) }
        }
        indication.updateColor(selectionInk.indicationColor)
    }
    override fun onMeasure(w:Int,h:Int) {
        val width=MeasureSpec.getSize(w);val height=MeasureSpec.getSize(h)
        val iconWidth=if(icon==null)0 else min(dp(16),width)
        icon?.measure(exact(iconWidth),exact(min(dp(16),height)))
        gap=if(icon==null)0 else min(dp(6),(width-iconWidth).coerceAtLeast(0))
        text.measure(atMost((width-iconWidth-gap).coerceAtLeast(0)),atMost(height))
        contentWidth=iconWidth+gap+text.measuredWidth
        mergedRowAccessibility.roleView.measure(exact(width),exact(height))
        setMeasuredDimension(width,height)
    }
    override fun onLayout(changed:Boolean,l:Int,t:Int,r:Int,b:Int) {
        var start=((width-contentWidth)/2f).roundToInt()
        fun place(child:View) {
            val x=if(layoutDirection==LAYOUT_DIRECTION_RTL)width-start-child.measuredWidth else start
            val y=((height-child.measuredHeight)/2f).roundToInt()
            child.layout(x,y,x+child.measuredWidth,y+child.measuredHeight)
        }
        icon?.let { place(it);start+=it.measuredWidth+gap };place(text)
        mergedRowAccessibility.roleView.layout(0,0,width,height)
        indication.onMeasured(width,height,environment.textDensity.density)
    }
    override fun createAccessibilityNodeInfo():AccessibilityNodeInfo? =
        if(mergedRowAccessibility.isNodePresent())super.createAccessibilityNodeInfo()else null
    override fun addChildrenForAccessibility(children:ArrayList<View>) =
        mergedRowAccessibility.addChildrenForAccessibility(children)
    override fun onInitializeAccessibilityNodeInfo(info:AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        info.collectionItemInfo=AccessibilityNodeInfo.CollectionItemInfo.obtain(0,1,columnIndex,1,false,chosen)
    }
    override fun onSizeChanged(w:Int,h:Int,oldw:Int,oldh:Int) { super.onSizeChanged(w,h,oldw,oldh);updateClip() }
    private fun updateClip() {
        val radius=22f*environment.textDensity.density
        clip.rewind();clip.addRoundRect(0f,0f,width.toFloat(),height.toFloat(),radius,radius,Path.Direction.CW)
        invalidateOutline();invalidate()
    }
    override fun verifyDrawable(who:Drawable):Boolean=who===indication||super.verifyDrawable(who)
    override fun onDraw(canvas:Canvas) {
        canvas.drawRect(0f,0f,width.toFloat(),height.toFloat(),fill)
        super.onDraw(canvas)
        indication.draw(canvas)
    }
    override fun draw(canvas:Canvas) {
        if(canvas.isHardwareAccelerated)super.draw(canvas)
        else {val save=canvas.save();try{canvas.clipPath(clip);super.draw(canvas)}finally{canvas.restoreToCount(save)}}
    }
    override fun performClick():Boolean {
        val action=activate?:return false
        super.performClick();action();return true
    }
    override fun onTouchEvent(event:MotionEvent)=input.touch(event)
    override fun onKeyDown(code:Int,event:KeyEvent)=if(activationKey(code))input.key(event)else super.onKeyDown(code,event)
    override fun onKeyUp(code:Int,event:KeyEvent)=if(activationKey(code))input.key(event)else super.onKeyUp(code,event)
    override fun onHoverEvent(event:MotionEvent):Boolean {val native=super.onHoverEvent(event);return input.hover(event)||native}
    override fun onFocusChanged(gain:Boolean,direction:Int,previous:Rect?) {super.onFocusChanged(gain,direction,previous);input.focusChanged(gain)}
    override fun onAttachedToWindow() {super.onAttachedToWindow();input.attached();if(!closed)input.bind(NativeClickBinding(this,true,{performClick()}))}
    override fun onDetachedFromWindow() {input.detached();super.onDetachedFromWindow()}
    override fun onCancelPendingInputEvents() {super.onCancelPendingInputEvents();input.cancel()}
    override fun close() {closed=true;activate=null;input.bind(null);backgroundDriver.close()}
    private fun dp(v:Int)=(v*environment.textDensity.density).roundToInt()
    private fun activationKey(code:Int)=code==KeyEvent.KEYCODE_DPAD_CENTER||code==KeyEvent.KEYCODE_ENTER||
        code==KeyEvent.KEYCODE_NUMPAD_ENTER||code==KeyEvent.KEYCODE_SPACE
}
