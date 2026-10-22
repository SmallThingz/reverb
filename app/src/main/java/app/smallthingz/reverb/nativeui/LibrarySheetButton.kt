package app.smallthingz.reverb.nativeui

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
import android.view.accessibility.AccessibilityNodeInfo
import app.smallthingz.reverb.NativeClickPhaseHost
import app.smallthingz.reverb.NativeClickPhaseNode
import app.smallthingz.reverb.NativeViewPointerHitLayout
import app.smallthingz.reverb.NativeViewPhasePointerNode
import app.smallthingz.reverb.NativeDragPointerChange
import app.smallthingz.nativeui.geometry.Offset
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Direct native-window bindings needed by this Button leaf; no renderer, registry or state tree. */
internal class NativeMaterialButtonHost(
    val environment:ReverbViewEnvironment,
    val inputRoot:View,
    val interestedParent:(NativeDragPointerChange)->Boolean,
    val textWindow:PlainTextAccessibilityWindowScope,
    val textGeometry:NativePlainTextViewGeometry,
    private val sourceFacts:(View,Float)->NativeSourceAccessibilityNode,
    val registerTextClip:(View)->AutoCloseable,
    private val createHit:(View,List<NativeViewPhasePointerNode>,()->List<NativeViewPointerHitLayout>,
                           (Offset)->Boolean)->NativeViewPointerHitLayout,
) {
    fun sourceNode(view:View,minimumTouch:Float=0f)=sourceFacts(view,minimumTouch)
    fun hit(view:View,nodes:List<NativeViewPhasePointerNode>,
            children:()->List<NativeViewPointerHitLayout> = {emptyList()},clip:(Offset)->Boolean={true})=
        createHit(view,nodes,children,clip)

    companion object {
        fun forSheet(sheet:NativeActionSheetContentContext)=NativeMaterialButtonHost(
            sheet.environment,sheet.inputRoot,sheet.interestedParent,sheet.accessibility.cursor,
            sheet.accessibility.geometry,sheet.accessibility::sourceNode,
            registerTextClip={content->
                val a=sheet.accessibility
                val earlier=a.extraClip
                val own:(View)->NativeTextSemanticsClip={view->if(view===content)
                    NativeTextSemanticsClip.Rectangle(app.smallthingz.nativeui.geometry.Rect(0f,0f,
                        view.width.toFloat(),view.height.toFloat()))else earlier(view)}
                a.extraClip=own
                AutoCloseable {if(a.extraClip===own)a.extraClip=earlier}
            },
            createHit={view,nodes,children,clip->sheet.hit(view,nodes,children,clip=clip)},
        )
    }
}

/** Released natural-width default Button/TextButton, not the footer's fixed-width control. */
internal class LibrarySheetButton(
    context: Context,
    private val host: NativeMaterialButtonHost,
    private val filled: Boolean,
    text: String,
    onClick: () -> Unit,
    leadingIcon: ReverbIcon? = null,
    errorColors: Boolean = false,
) : ViewGroup(context), AutoCloseable {
    constructor(context:Context,host:NativeActionSheetContentContext,filled:Boolean,text:String,onClick:()->Unit,
                leadingIcon:ReverbIcon?=null,errorColors:Boolean=false):
        this(context,NativeMaterialButtonHost.forSheet(host),filled,text,onClick,leadingIcon,errorColors)
    private var environment=host.environment
    private val surface = ButtonSurface(context,host,filled,text,onClick,leadingIcon,errorColors)
    private val click = NativeClickPhaseNode(surface,surface.nativeClickEndpoint,host.interestedParent)
    private var closed=false
    val hitLayout: NativeViewPointerHitLayout = host.hit(this,emptyList(),children = {
        listOf(host.hit(surface,listOf(click),clip = { surface.containsPointerLayer(it.x,it.y) }))
    })
    init {
        importantForAccessibility=IMPORTANT_FOR_ACCESSIBILITY_NO;clipChildren=false;clipToPadding=false
        addView(surface)
    }
    fun bind(text:String,enabled:Boolean,onClick:()->Unit)=surface.bind(text,enabled,onClick)
    fun bindEnvironment(next:ReverbViewEnvironment) {
        if(closed || environment===next)return
        val densityChanged=environment.textDensity!=next.textDensity
        environment=next;surface.bindEnvironment(next)
        if(densityChanged)requestLayout()
    }
    override fun onMeasure(w:Int,h:Int) {
        surface.measure(w,h)
        val minimum=(48f*environment.textDensity.density).roundToInt()
        setMeasuredDimension(resolveSize(max(minimum,surface.measuredWidth),w),
            resolveSize(max(minimum,surface.measuredHeight),h))
    }
    override fun onLayout(changed:Boolean,l:Int,t:Int,r:Int,b:Int) {
        val x=((width-surface.measuredWidth)*.5f).roundToInt()
        val y=((height-surface.measuredHeight)*.5f).roundToInt()
        surface.layout(x,y,x+surface.measuredWidth,y+surface.measuredHeight)
    }
    override fun close() {
        if(closed)return
        closed=true
        var failure:Throwable?=null
        try{click.cancel()}catch(error:Throwable){failure=error}
        try{surface.close()}catch(error:Throwable){if(failure==null)failure=error else failure!!.addSuppressed(error)}
        failure?.let{throw it}
    }
}

private class ButtonSurface(context:Context,private val host:NativeMaterialButtonHost,
                            private val filled:Boolean,text:String,onClick:()->Unit,private val leadingIcon:ReverbIcon?,
                            private val errorColors:Boolean):
    ViewGroup(context),NativeClickPhaseHost,NativePointerLayerShape,NativeMergedRowAccessibilityHost,AutoCloseable {
    private var environment=host.environment
    private var labelText=text
    private var action=onClick
    private var closed=false
    private val fill=Paint(Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val label=ReverbParagraphView(context,reverbButtonTextStyle(),environment.textDensity,
        environment.sharedFontResolver,environment.palette.primary)
    private var icon=leadingIcon?.let { ReverbIconDrawable(it,environment.textDensity.density,environment.palette.onPrimary) }
    private val content=object:ViewGroup(context) {
        private var iconWidth=0
        private var iconHeight=0
        private var iconGap=0
        private val softwareClip=Path()
        init {importantForAccessibility=IMPORTANT_FOR_ACCESSIBILITY_NO;addView(label);clipToOutline=true;setWillNotDraw(false)}
        override fun onMeasure(w:Int,h:Int) {
            val horizontal=dp(if(filled)24f else 12f);val vertical=dp(8f)
            val width=MeasureSpec.getSize(w)
            val bounded=MeasureSpec.getMode(h)!=MeasureSpec.UNSPECIFIED
            val innerWidth=max(0,width-horizontal-horizontal)
            val innerHeight=if(bounded)max(0,MeasureSpec.getSize(h)-vertical-vertical)else Int.MAX_VALUE
            iconWidth=if(icon==null)0 else min(dp(19f),innerWidth)
            iconHeight=if(icon==null)0 else min(dp(19f),innerHeight)
            iconGap=if(icon==null)0 else min(dp(7f),max(0,innerWidth-iconWidth))
            label.measure(sheetButtonAtMost(max(0,innerWidth-iconWidth-iconGap)),
                if(bounded)sheetButtonAtMost(innerHeight) else h)
            setMeasuredDimension(resolveSize(max(dp(58f),horizontal+horizontal+iconWidth+iconGap+label.measuredWidth),w),
                resolveSize(max(dp(40f),vertical+vertical+max(iconHeight,label.measuredHeight)),h))
        }
        override fun onLayout(changed:Boolean,l:Int,t:Int,r:Int,b:Int) {
            val total=iconWidth+iconGap+label.measuredWidth
            val start=(width-total)*.5f
            // Arrangement.Center accumulates Float positions in physical order, then rounds each.
            val rtl=layoutDirection==LAYOUT_DIRECTION_RTL
            val x=(if(rtl)start else start+iconWidth+iconGap).roundToInt()
            val y=((height-label.measuredHeight)*.5f).roundToInt()
            label.layout(x,y,x+label.measuredWidth,y+label.measuredHeight)
            val ix=(if(rtl)start+label.measuredWidth+iconGap else start).roundToInt()
            val iy=((height-iconHeight)*.5f).roundToInt()
            icon?.setBounds(ix,iy,ix+iconWidth,iy+iconHeight)
        }
        override fun onDraw(canvas:Canvas) {icon?.let {it.layoutDirection=layoutDirection;it.draw(canvas)}}
        override fun onSizeChanged(w:Int,h:Int,oldw:Int,oldh:Int) {
            softwareClip.rewind();val radius=min(w,h)*.5f
            softwareClip.addRoundRect(0f,0f,w.toFloat(),h.toFloat(),radius,radius,Path.Direction.CW)
        }
        override fun draw(canvas:Canvas) {
            if(canvas.isHardwareAccelerated)super.draw(canvas) else {
                val save=canvas.save();try{canvas.clipPath(softwareClip);super.draw(canvas)}finally{canvas.restoreToCount(save)}
            }
        }
    }
    private val interactions=OnboardingNativeInteractionsFactory {host.inputRoot}.create(this,content,filled) {elevation=it}
    override val nativeClickEndpoint get()=interactions.pointerEndpoint
    override val mergedRowAccessibility=ReverbMergedRowAccessibility(this,NativeMergedRowRole.BUTTON,
        {NativeMergedRowAccessibilityState(isEnabled,null,null,listOf(label))},
        {if(isEnabled&&isAttachedToWindow){action();true}else false})
    private val leases=ArrayList<AutoCloseable>()
    private var restoreClip:(()->Unit)?=null
    private val outline=object:ViewOutlineProvider(){override fun getOutline(view:View,outline:Outline){
        outline.setRoundRect(0,0,view.width,view.height,min(view.width,view.height)*.5f)
    }}
    init {
        setWillNotDraw(false);importantForAccessibility=IMPORTANT_FOR_ACCESSIBILITY_YES
        isClickable=true;isFocusable=true;descendantFocusability=FOCUS_BLOCK_DESCENDANTS
        outlineProvider=outline;content.outlineProvider=outline
        label.isScreenReaderFocusable=false
        addView(content);addView(mergedRowAccessibility.roleView)
        accessibilityDelegate=mergedRowAccessibility
        setOnClickListener {if(isEnabled&&isAttachedToWindow)action()}
        if(android.os.Build.VERSION.SDK_INT>=29)isForceDarkAllowed=false
        leases+=mergedRowAccessibility.bindGeometry(host.textGeometry,{host.sourceNode(this,48f*environment.textDensity.density)},
            {host.sourceNode(it).present}){host.sourceNode(this,48f*environment.textDensity.density).present}
        leases+=label.bindPlainTextAccessibility(host.textWindow,host.textGeometry,{host.sourceNode(label)}){host.sourceNode(label).present}
        val clipLease=host.registerTextClip(content)
        restoreClip=clipLease::close
        bind(text,true,onClick)
    }
    fun bind(text:String,enabled:Boolean,onClick:()->Unit) {
        action=onClick;labelText=text
        val palette=environment.palette
        val ink=if(enabled){if(errorColors)palette.onErrorContainer else if(filled)palette.onPrimary else palette.primary}else reverbColorWithAlpha(palette.onSurface,.38f)
        interactions.bind(enabled,ink)
        isEnabled=enabled;isFocusable=enabled
        label.setText(text);label.setTextColor(ink);icon?.setTint(ink);content.invalidate()
        fill.color=if(!filled)android.graphics.Color.TRANSPARENT else if(enabled){if(errorColors)palette.errorContainer else palette.primary}else reverbColorWithAlpha(palette.onSurface,.12f)
        invalidate()
    }
    fun bindEnvironment(next:ReverbViewEnvironment) {
        if(closed || environment===next)return
        val densityChanged=environment.textDensity!=next.textDensity
        environment=next
        label.setTypography(reverbButtonTextStyle(),next.textDensity,next.sharedFontResolver)
        if(densityChanged)icon=leadingIcon?.let {ReverbIconDrawable(it,next.textDensity.density,next.palette.onPrimary)}
        bind(labelText,isEnabled,action)
        if(densityChanged)requestLayout()
    }
    private fun dp(value:Float)=(value*environment.textDensity.density).roundToInt()
    override fun containsPointerLayer(x:Float,y:Float)=reverbGroupGeometry(width.toFloat(),height.toFloat(),
        environment.textDensity.density,min(width,height)/(2f*environment.textDensity.density)).contains(x,y)
    override fun onMeasure(w:Int,h:Int) {
        content.measure(w,h);setMeasuredDimension(content.measuredWidth,content.measuredHeight)
        mergedRowAccessibility.roleView.measure(sheetButtonExact(measuredWidth),sheetButtonExact(measuredHeight))
    }
    override fun onLayout(changed:Boolean,l:Int,t:Int,r:Int,b:Int) {
        content.layout(0,0,width,height);mergedRowAccessibility.roleView.layout(0,0,width,height)
        invalidateOutline();content.invalidateOutline();interactions.measured(width,height)
    }
    override fun onDraw(canvas:Canvas){val radius=min(width,height)*.5f;canvas.drawRoundRect(0f,0f,width.toFloat(),height.toFloat(),radius,radius,fill)}
    override fun createAccessibilityNodeInfo():AccessibilityNodeInfo?=if(mergedRowAccessibility.isNodePresent())super.createAccessibilityNodeInfo()else null
    override fun addChildrenForAccessibility(out:ArrayList<View>)=mergedRowAccessibility.addChildrenForAccessibility(out)
    override fun onTouchEvent(event:MotionEvent)=interactions.touch(event)
    override fun onKeyDown(code:Int,event:KeyEvent)=if(activation(code))interactions.key(event)else super.onKeyDown(code,event)
    override fun onKeyUp(code:Int,event:KeyEvent)=if(activation(code))interactions.key(event)else super.onKeyUp(code,event)
    override fun onHoverEvent(event:MotionEvent):Boolean{val handled=super.onHoverEvent(event);return interactions.hover(event)||handled}
    override fun onFocusChanged(focused:Boolean,direction:Int,rect:Rect?){super.onFocusChanged(focused,direction,rect);interactions.focusChanged(focused)}
    override fun onAttachedToWindow(){super.onAttachedToWindow();interactions.attached();interactions.focusChanged(isFocused)}
    override fun onDetachedFromWindow(){interactions.detached();super.onDetachedFromWindow()}
    override fun getAccessibilityClassName():CharSequence="android.view.View"
    override fun close() {
        if(closed)return
        closed=true
        var failure:Throwable?=null
        fun release(block:()->Unit){try{block()}catch(error:Throwable){if(failure==null)failure=error else failure!!.addSuppressed(error)}}
        release(interactions::cancel)
        val clip=restoreClip;restoreClip=null
        release{clip?.invoke()}
        val owned=leases.toList();leases.clear()
        owned.asReversed().forEach{release(it::close)}
        action={}
        failure?.let{throw it}
    }
}
private fun activation(code:Int)=code==KeyEvent.KEYCODE_DPAD_CENTER||code==KeyEvent.KEYCODE_ENTER||code==KeyEvent.KEYCODE_NUMPAD_ENTER||code==KeyEvent.KEYCODE_SPACE
private fun sheetButtonExact(value:Int)=View.MeasureSpec.makeMeasureSpec(value.coerceAtLeast(0),View.MeasureSpec.EXACTLY)
private fun sheetButtonAtMost(value:Int)=View.MeasureSpec.makeMeasureSpec(value.coerceAtLeast(0),View.MeasureSpec.AT_MOST)
