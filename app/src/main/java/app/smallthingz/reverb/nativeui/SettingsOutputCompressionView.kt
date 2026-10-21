package app.smallthingz.reverb.nativeui

import android.content.Context
import android.view.View
import android.view.ViewGroup
import app.smallthingz.reverb.NativeDragPointerChange
import app.smallthingz.reverb.R
import kotlinx.coroutines.CoroutineScope
import kotlin.math.max
import kotlin.math.roundToInt

internal data class SettingsOutputCompressionState(val enabled:Boolean,val level:Int,val interactionEnabled:Boolean)

/** OutputCompressionSettings31–112, including its Top expansion, source slider and two short labels. */
internal class SettingsOutputCompressionView(
    context:Context,
    initial:SettingsOutputCompressionState,
    private var environment:ReverbViewEnvironment,
    inputRoot:()->View?,
    parentScope:CoroutineScope,
    private val parentInterested:(NativeDragPointerChange)->Boolean,
    onEnabledChange:(Boolean)->Unit,
    private var onLevelChange:((Int)->Unit)?,
):ViewGroup(context),AutoCloseable {
    private var state=initial
    private val row=SettingsToggleRowView(context,context.getString(R.string.output_compression),
        initial.enabled,initial.interactionEnabled,environment,inputRoot,true,onEnabledChange)
    private val expansion=SettingsExpansionView(context,initial.enabled,parentScope,alignBottom=false) {
        CompressionBody(context,state,environment,parentInterested) {onLevelChange?.invoke(it)}
    }
    val currentSlider:CompressionSliderView? get()=(expansion.content as? CompressionBody)?.slider
    init {importantForAccessibility=IMPORTANT_FOR_ACCESSIBILITY_NO;addView(row);addView(expansion)}
    fun bind(next:SettingsOutputCompressionState,colors:ReverbViewEnvironment) {
        state=next;environment=colors
        row.bind(next.enabled,next.interactionEnabled,colors)
        (expansion.content as? CompressionBody)?.bind(next,colors)
        expansion.visible(next.enabled)
        requestLayout()
    }
    override fun onMeasure(w:Int,h:Int) {
        val width=MeasureSpec.getSize(w)
        row.measure(exact(width),unspecified())
        expansion.measure(atMost(width),unspecified())
        setMeasuredDimension(resolveSize(width,w),resolveSize(row.measuredHeight+expansion.measuredHeight,h))
    }
    override fun onLayout(changed:Boolean,l:Int,t:Int,r:Int,b:Int) {
        row.layout(0,0,width,row.measuredHeight)
        val x=if(layoutDirection==LAYOUT_DIRECTION_RTL)width-expansion.measuredWidth else 0
        expansion.layout(x,row.measuredHeight,x+expansion.measuredWidth,row.measuredHeight+expansion.measuredHeight)
    }
    override fun close() {onLevelChange=null;row.close();expansion.close()}
}

private class CompressionBody(
    context:Context,
    initial:SettingsOutputCompressionState,
    private var environment:ReverbViewEnvironment,
    parentInterested:(NativeDragPointerChange)->Boolean,
    onLevelChange:(Int)->Unit,
):ViewGroup(context),SettingsExpansionContent {
    override val view:View get()=this
    val slider=CompressionSliderView(context,initial.level,initial.enabled&&initial.interactionEnabled,
        environment,onLevelChange,parentInterested)
    private val faster=environment.paragraph(context,context.getString(R.string.compression_faster),
        ReverbTextRole.BODY_SMALL,environment.palette.muted)
    private val smaller=environment.paragraph(context,context.getString(R.string.compression_smaller),
        ReverbTextRole.BODY_SMALL,environment.palette.muted)
    private var labelHeight=0
    init {importantForAccessibility=IMPORTANT_FOR_ACCESSIBILITY_NO;addView(slider);addView(faster);addView(smaller)}
    fun bind(state:SettingsOutputCompressionState,next:ReverbViewEnvironment) {
        environment=next
        slider.bind(state.level,state.enabled&&state.interactionEnabled,next)
        next.bindParagraphStyle(faster,ReverbTextRole.BODY_SMALL,next.palette.muted)
        next.bindParagraphStyle(smaller,ReverbTextRole.BODY_SMALL,next.palette.muted)
        requestLayout()
    }
    override fun onMeasure(w:Int,h:Int) {
        val width=MeasureSpec.getSize(w)
        val inner=(width-2*dp(16)).coerceAtLeast(0)
        slider.measure(exact(inner),unspecified())
        faster.measure(atMost(inner),unspecified())
        smaller.measure(atMost((inner-faster.measuredWidth).coerceAtLeast(0)),unspecified())
        labelHeight=max(faster.measuredHeight,smaller.measuredHeight)
        setMeasuredDimension(resolveSize(width,w),resolveSize(2*dp(12)+slider.measuredHeight+labelHeight,h))
    }
    override fun onLayout(changed:Boolean,l:Int,t:Int,r:Int,b:Int) {
        slider.layout(dp(16),dp(12),width-dp(16),dp(12)+slider.measuredHeight)
        val y=dp(12)+slider.measuredHeight
        val left=if(layoutDirection==LAYOUT_DIRECTION_RTL)smaller else faster
        val right=if(layoutDirection==LAYOUT_DIRECTION_RTL)faster else smaller
        left.layout(dp(16),y,dp(16)+left.measuredWidth,y+left.measuredHeight)
        right.layout(width-dp(16)-right.measuredWidth,y,width-dp(16),y+right.measuredHeight)
    }
    override fun close()=slider.close()
    private fun dp(v:Int)=(v*environment.textDensity.density).roundToInt()
}
