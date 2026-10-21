package app.smallthingz.reverb.nativeui

import android.content.Context
import android.view.View
import android.view.ViewGroup
import app.smallthingz.reverb.NativeViewPointerHitLayout
import kotlin.math.max
import kotlin.math.roundToInt

/** ReverbActionSheet's caller action Row: logical End, centered vertically, explicit8dp Spacer. */
internal class LibrarySheetActions(context:Context,private val host:NativeActionSheetContentContext,
                                  val buttons:List<LibrarySheetButton>):ViewGroup(context),AutoCloseable {
    private var environment=host.environment
    private var contentWidth=0
    private val measuredGaps=IntArray(buttons.size)
    val hitLayout:NativeViewPointerHitLayout=host.hit(this,emptyList(),children={buttons.asReversed().map{it.hitLayout}})
    init {importantForAccessibility=IMPORTANT_FOR_ACCESSIBILITY_NO;clipChildren=false;clipToPadding=false;buttons.forEach(::addView)}
    fun bindEnvironment(next:ReverbViewEnvironment) {
        if(environment===next)return
        val densityChanged=environment.textDensity!=next.textDensity
        environment=next
        buttons.forEach { it.bindEnvironment(next) }
        if(densityChanged)requestLayout()
    }
    override fun onMeasure(w:Int,h:Int) {
        val width=MeasureSpec.getSize(w);val gap=(8f*environment.textDensity.density).roundToInt()
        var used=0;var tallest=0
        for((index,button) in buttons.withIndex()) {
            measuredGaps[index]=if(index>0)minOf(gap,max(0,width-used))else 0
            used+=measuredGaps[index]
            button.measure(MeasureSpec.makeMeasureSpec(max(0,width-used),MeasureSpec.AT_MOST),h)
            used+=button.measuredWidth;tallest=max(tallest,button.measuredHeight)
        }
        contentWidth=used
        setMeasuredDimension(resolveSize(width,w),resolveSize(tallest,h))
    }
    override fun onLayout(changed:Boolean,l:Int,t:Int,r:Int,b:Int) {
        var logical=width-contentWidth
        for((index,button) in buttons.withIndex()) {
            logical+=measuredGaps[index]
            val x=if(layoutDirection==LAYOUT_DIRECTION_RTL)width-logical-button.measuredWidth else logical
            val y=((height-button.measuredHeight)*.5f).roundToInt()
            button.layout(x,y,x+button.measuredWidth,y+button.measuredHeight);logical+=button.measuredWidth
        }
    }
    override fun close() {
        var failure:Throwable?=null
        // Each button adds a scoped clip mapping. Retire them in reverse installation order.
        buttons.asReversed().forEach {button->try{button.close()}catch(error:Throwable){
            if(failure==null)failure=error else failure!!.addSuppressed(error)
        }}
        failure?.let{throw it}
    }
}
