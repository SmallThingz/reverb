package app.smallthingz.reverb.nativeui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.os.Build
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import androidx.compose.ui.geometry.Offset
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

internal data class NativeSheetPredictiveTransform(
    val scaleX: Float, val scaleY: Float, val pivotY: Float,
    val childScaleY: Float, val overflowScaleY: Float, val overflowChildScaleY: Float,
)
internal fun nativeSheetPredictiveTransform(
    width: Float, height: Float, offset: Float, minimumAnchor: Float, density: Float, progress: Float,
): NativeSheetPredictiveTransform {
    val x = if (width.isNaN() || width == 0f) 1f else 1f-min(48f*density,width)*progress/width
    val y = if (height.isNaN() || height == 0f) 1f else 1f-min(24f*density,height)*progress/height
    val overflow = if (offset < minimumAnchor) minimumAnchor-offset else 0f
    val up = if (height != 0f && overflow > 0f) (height+overflow)/height else 1f
    return NativeSheetPredictiveTransform(x,y,offset+height,if(y!=0f)x/y else 1f,up,1f/up)
}

/** Distinct real graphics-layer owners preserve source transform and clip order. */
internal open class NativeSheetFrame(context: Context) : ViewGroup(context) {
    init {
        clipChildren=false;clipToPadding=false
        importantForAccessibility=IMPORTANT_FOR_ACCESSIBILITY_NO
    }
    override fun onMeasure(widthMeasureSpec: Int,heightMeasureSpec: Int) {
        val child=if(childCount>0)getChildAt(0)else null
        child?.measure(widthMeasureSpec,heightMeasureSpec)
        setMeasuredDimension(resolveSize(child?.measuredWidth?:0,widthMeasureSpec),
            resolveSize(child?.measuredHeight?:0,heightMeasureSpec))
    }
    override fun onLayout(changed:Boolean,l:Int,t:Int,r:Int,b:Int) {
        if(childCount>0)getChildAt(0).let {it.layout(0,0,it.measuredWidth,it.measuredHeight)}
    }
}

/** Surface fill is outside the rounded child clip, matching background(shape).clip(shape). */
internal class NativeSheetSurface(
    context:Context,
    private var environment:ReverbViewEnvironment,
    handle:View,
    val appContent:NativeSheetAppContent,
) : NativeSheetFrame(context) {
    var sourceChildPresent:((View)->Boolean)?=null
    val predictiveLayer=NativeSheetFrame(context)
    val overflowLayer=NativeSheetFrame(context)
    val painted=PaintedSurface(context,environment)
    val insets=SheetInsets(context)
    val predictiveContent=NativeSheetFrame(context)
    val overflowContent=NativeSheetFrame(context)
    val column=SheetColumn(context,handle,appContent)
    init {
        addView(predictiveLayer);predictiveLayer.addView(overflowLayer);overflowLayer.addView(painted)
        painted.clip.addView(insets);insets.addView(predictiveContent)
        predictiveContent.addView(overflowContent);overflowContent.addView(column)
        importantForAccessibility=IMPORTANT_FOR_ACCESSIBILITY_YES
        accessibilityPaneTitle=context.getString(androidx.compose.material3.R.string.m3c_bottom_sheet_pane_title)
        isScreenReaderFocusable=false
    }
    fun bindEnvironment(next:ReverbViewEnvironment) {
        environment=next;painted.bindEnvironment(next)
    }
    fun applyMotion(offset:Float,minimumAnchor:Float,progress:Float) {
        val value=nativeSheetPredictiveTransform(width.toFloat(),height.toFloat(),offset,
            minimumAnchor,environment.textDensity.density,progress)
        predictiveLayer.pivotX=width*.5f;predictiveLayer.pivotY=value.pivotY
        predictiveLayer.scaleX=value.scaleX;predictiveLayer.scaleY=value.scaleY
        overflowLayer.pivotX=width*.5f;overflowLayer.pivotY=0f;overflowLayer.scaleY=value.overflowScaleY
        predictiveContent.pivotX=predictiveContent.width*.5f;predictiveContent.pivotY=0f
        // Source uses this inner layer's size after content window-insets padding.
        val inner=nativeSheetPredictiveTransform(predictiveContent.width.toFloat(),
            predictiveContent.height.toFloat(),offset,minimumAnchor,environment.textDensity.density,progress)
        predictiveContent.scaleY=inner.childScaleY
        overflowContent.pivotX=overflowContent.width*.5f;overflowContent.pivotY=0f
        overflowContent.scaleY=inner.overflowChildScaleY
    }
    fun containsShape(p:Offset)=painted.containsShape(p)

    override fun addChildrenForAccessibility(outChildren:ArrayList<View>) {
        val start=outChildren.size
        super.addChildrenForAccessibility(outChildren)
        val present=sourceChildPresent?:return
        for(index in outChildren.lastIndex downTo start)if(!present(outChildren[index]))outChildren.removeAt(index)
    }

    internal class PaintedSurface(context:Context,private var env:ReverbViewEnvironment):NativeSheetFrame(context) {
        private val fill=Paint(Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
            color=env.palette.surfaceContainerLow
        }
        private val shape=Path()
        val clip=SheetClip(context)
        private var radius=0f
        init {setWillNotDraw(false);addView(clip)}
        fun bindEnvironment(next:ReverbViewEnvironment) {
            val densityChanged=env.textDensity!=next.textDensity
            env=next;fill.color=next.palette.surfaceContainerLow
            if(densityChanged)onSizeChanged(width,height,width,height)
            invalidate()
        }
        override fun onSizeChanged(w:Int,h:Int,oldw:Int,oldh:Int) {
            val raw=28f*env.textDensity.density
            // CornerBasedShape first normalizes each start/end pair against minDimension;
            // the path backend then normalizes the top pair if it exceeds the actual width.
            val minimum=min(w,h).toFloat()
            val resolved=if(raw>minimum)raw*(minimum/raw)else raw
            radius=min(resolved,w*.5f)
            shape.rewind()
            shape.addRoundRect(RectF(0f,0f,w.toFloat(),h.toFloat()),
                floatArrayOf(resolved,resolved,resolved,resolved,0f,0f,0f,0f),Path.Direction.CW)
            clip.setShape(shape)
        }
        override fun onDraw(canvas:Canvas) {canvas.drawPath(shape,fill)}
        fun containsShape(p:Offset):Boolean {
            if(p.x<0f||p.y<0f||p.x>=width||p.y>=height)return false
            if(p.y>=radius || p.x>=radius&&p.x<width-radius)return true
            val cx=if(p.x<radius)radius else width-radius
            val dx=p.x-cx;val dy=p.y-radius
            return dx*dx+dy*dy<=radius*radius
        }
    }
    internal class SheetClip(context:Context):NativeSheetFrame(context) {
        private val shape=Path()
        private var nativeClip=false
        init {
            outlineProvider=object:ViewOutlineProvider() {
                override fun getOutline(view:View,outline:Outline)=fillOutline(outline)
            }
        }
        fun setShape(value:Path) {
            shape.set(value)
            val outline=Outline();fillOutline(outline)
            nativeClip=outline.canClip();clipToOutline=nativeClip
            invalidateOutline();invalidate()
        }
        @Suppress("DEPRECATION")
        private fun fillOutline(outline:Outline) {
            if(Build.VERSION.SDK_INT>=30)outline.setPath(shape)else outline.setConvexPath(shape)
        }
        override fun dispatchDraw(canvas:Canvas) {
            if(canvas.isHardwareAccelerated&&nativeClip)super.dispatchDraw(canvas)
            else {val save=canvas.save();try {canvas.clipPath(shape);super.dispatchDraw(canvas)}
                finally {canvas.restoreToCount(save)}}
        }
    }
    internal class SheetInsets(context:Context):NativeSheetFrame(context) {
        var contentTop=0;private set
        var contentBottom=0;private set
        fun update(top:Int,bottom:Int):Boolean {
            if(contentTop==top&&contentBottom==bottom)return false
            contentTop=top;contentBottom=bottom;requestLayout();return true
        }
        override fun onMeasure(widthMeasureSpec:Int,heightMeasureSpec:Int) {
            val maximum=MeasureSpec.getSize(heightMeasureSpec)
            val constrained=max(0,maximum-contentTop-contentBottom)
            val child=getChildAt(0)
            child.measure(widthMeasureSpec,MeasureSpec.makeMeasureSpec(constrained,MeasureSpec.AT_MOST))
            setMeasuredDimension(child.measuredWidth,resolveSize(child.measuredHeight+contentTop+contentBottom,heightMeasureSpec))
        }
        override fun onLayout(changed:Boolean,l:Int,t:Int,r:Int,b:Int) {
            getChildAt(0).let {it.layout(0,contentTop,it.measuredWidth,contentTop+it.measuredHeight)}
        }
    }
    internal class SheetColumn(context:Context,val handle:View,val content:View):ViewGroup(context) {
        init {clipChildren=false;clipToPadding=false;importantForAccessibility=IMPORTANT_FOR_ACCESSIBILITY_NO
            addView(handle);addView(content)}
        override fun onMeasure(widthMeasureSpec:Int,heightMeasureSpec:Int) {
            val width=MeasureSpec.getSize(widthMeasureSpec)
            val remaining=MeasureSpec.getSize(heightMeasureSpec)
            handle.measure(sheetSurfaceAtMost(width),sheetSurfaceAtMost(remaining))
            content.measure(sheetSurfaceExact(width),sheetSurfaceAtMost(max(0,remaining-handle.measuredHeight)))
            setMeasuredDimension(width,resolveSize(handle.measuredHeight+content.measuredHeight,heightMeasureSpec))
        }
        override fun onLayout(changed:Boolean,l:Int,t:Int,r:Int,b:Int) {
            val x=((width-handle.measuredWidth)*.5f).roundToInt()
            handle.layout(x,0,x+handle.measuredWidth,handle.measuredHeight)
            content.layout(0,handle.measuredHeight,width,handle.measuredHeight+content.measuredHeight)
        }
    }
}

/** Released app Column: no inferred weighting, scrolling, safety padding or layout normalization. */
internal class NativeSheetAppContent(
    context:Context,
    private var environment:ReverbViewEnvironment,
    title:String,
    inputRoot:()->View?,
    dismiss:()->Unit,
):ViewGroup(context) {
    private var currentDismiss=dismiss
    val titleView=environment.paragraph(context,title,ReverbTextRole.TITLE_LARGE)
    val close=ReverbStandardIconButtonView(context,environment,inputRoot)
    private val header=Header(context,titleView,close)
    private var body:View?=null
    private var actions:View?=null
    private var topBody=0
    private var topActions=0
    private val density get()=environment.textDensity.density
    val minimumTouchTargetPx:Float get()=48f*density
    init {
        clipChildren=false;clipToPadding=false;importantForAccessibility=IMPORTANT_FOR_ACCESSIBILITY_NO
        background=ReverbNoiseDrawable(resources)
        addView(header)
        close.bind(environment,ReverbIcon.Close,context.getString(app.smallthingz.reverb.R.string.close),true,
            environment.palette.onSurfaceVariant,environment.palette.onSurface,dismiss)
    }
    fun setContent(body:View,actions:View) {
        this.body?.let(::removeView);this.actions?.let(::removeView)
        this.body=body;this.actions=actions;addView(body);addView(actions);requestLayout()
    }
    fun bind(title:String,dismiss:()->Unit) {
        currentDismiss=dismiss
        titleView.setText(title)
        close.bind(environment,ReverbIcon.Close,context.getString(app.smallthingz.reverb.R.string.close),true,
            environment.palette.onSurfaceVariant,environment.palette.onSurface,dismiss)
    }
    fun bindEnvironment(next:ReverbViewEnvironment) {
        if(environment===next)return
        val densityChanged=environment.textDensity!=next.textDensity
        environment=next
        next.bindParagraphStyle(titleView,ReverbTextRole.TITLE_LARGE)
        close.bind(next,ReverbIcon.Close,context.getString(app.smallthingz.reverb.R.string.close),true,
            next.palette.onSurfaceVariant,next.palette.onSurface,currentDismiss)
        if(densityChanged)requestLayout()
    }
    override fun onMeasure(widthMeasureSpec:Int,heightMeasureSpec:Int) {
        val width=MeasureSpec.getSize(widthMeasureSpec)
        val pad=(24f*density).roundToInt()
        val inner=max(0,width-pad*2)
        val maximum=MeasureSpec.getSize(heightMeasureSpec)
        val bottom=min(pad,maximum)
        var used=0
        var remaining=max(0,maximum-bottom)
        header.measure(sheetSurfaceExact(inner),sheetSurfaceAtMost(remaining));used+=header.measuredHeight;remaining-=header.measuredHeight
        val gap12=min((12f*density).roundToInt(),remaining);used+=gap12;remaining-=gap12
        topBody=used
        body?.let {it.measure(sheetSurfaceExact(inner),sheetSurfaceAtMost(remaining));used+=it.measuredHeight;remaining-=it.measuredHeight}
        val gap20=min((20f*density).roundToInt(),remaining);used+=gap20;remaining-=gap20
        topActions=used
        actions?.let {it.measure(sheetSurfaceExact(inner),sheetSurfaceAtMost(remaining));used+=it.measuredHeight}
        setMeasuredDimension(width,resolveSize(used+bottom,heightMeasureSpec))
    }
    override fun onLayout(changed:Boolean,l:Int,t:Int,r:Int,b:Int) {
        val pad=(24f*density).roundToInt()
        header.layout(pad,0,pad+header.measuredWidth,header.measuredHeight)
        body?.let {it.layout(pad,topBody,pad+it.measuredWidth,topBody+it.measuredHeight)}
        actions?.let {it.layout(pad,topActions,pad+it.measuredWidth,topActions+it.measuredHeight)}
    }
    private class Header(context:Context,val title:View,val close:View):ViewGroup(context) {
        init {importantForAccessibility=IMPORTANT_FOR_ACCESSIBILITY_NO;clipChildren=false;addView(title);addView(close)}
        override fun onMeasure(widthMeasureSpec:Int,heightMeasureSpec:Int) {
            val width=MeasureSpec.getSize(widthMeasureSpec)
            close.measure(sheetSurfaceAtMost(width),heightMeasureSpec)
            title.measure(sheetSurfaceExact(max(0,width-close.measuredWidth)),heightMeasureSpec)
            setMeasuredDimension(width,resolveSize(max(title.measuredHeight,close.measuredHeight),heightMeasureSpec))
        }
        override fun onLayout(changed:Boolean,l:Int,t:Int,r:Int,b:Int) {
            val rtl=layoutDirection==LAYOUT_DIRECTION_RTL
            val titleX=if(rtl)close.measuredWidth else 0
            val closeX=if(rtl)0 else title.measuredWidth
            fun place(view:View,x:Int) {val y=((height-view.measuredHeight)*.5f).roundToInt()
                view.layout(x,y,x+view.measuredWidth,y+view.measuredHeight)}
            place(title,titleX);place(close,closeX)
        }
    }
}
private fun sheetSurfaceExact(value:Int)=View.MeasureSpec.makeMeasureSpec(value,View.MeasureSpec.EXACTLY)
private fun sheetSurfaceAtMost(value:Int)=View.MeasureSpec.makeMeasureSpec(value,View.MeasureSpec.AT_MOST)
