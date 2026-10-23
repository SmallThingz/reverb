package app.smallthingz.reverb.nativeui

import android.content.Context
import android.view.View
import android.view.ViewGroup
import smallthingz.ui.unit.IntSize
import kotlinx.coroutines.CoroutineScope
import kotlin.math.roundToInt

internal interface SettingsExpansionContent:AutoCloseable {val view:View}

/** One source AnimatedVisibility lifetime; the child is released after both size and alpha finish. */
internal class SettingsExpansionView(
    context:Context,
    initiallyVisible:Boolean,
    parentScope:CoroutineScope,
    private val alignBottom:Boolean=false,
    private val factory:()->SettingsExpansionContent,
):ViewGroup(context),AutoCloseable {
    private var motion=SettingsExpansionMotion(initiallyVisible)
    private val frames=SettingsExpansionFrames(parentScope,{motion.running},::sampleFrame)
    private var closed=false
    var content:SettingsExpansionContent?=null
        private set
    init {
        importantForAccessibility=IMPORTANT_FOR_ACCESSIBILITY_NO
        clipChildren=true;clipToPadding=true
        if(initiallyVisible)ensureContent()
    }
    private fun ensureContent() {
        if(content!=null)return
        val created=factory()
        content=created
        addView(created.view)
    }
    fun visible(value:Boolean) {
        if(closed)return
        if(value)ensureContent()
        motion.visible(value)
        publish()
        requestLayout()
        schedule()
    }
    override fun onMeasure(w:Int,h:Int) {
        val current=content
        if(current==null) {setMeasuredDimension(0,0);return}
        current.view.measure(exact(MeasureSpec.getSize(w)),unspecified())
        motion.measure(IntSize(current.view.measuredWidth,current.view.measuredHeight))
        val size=motion.measuredSize
        setMeasuredDimension(resolveSize(size.width,w),resolveSize(size.height,h))
        publish();schedule()
    }
    override fun onLayout(changed:Boolean,l:Int,t:Int,r:Int,b:Int) {
        content?.view?.let {child->
            // expandVertically Top/Bottom map to TopCenter/BottomCenter in pinned source.
            val x=((width-child.measuredWidth)/2f).roundToInt()
            val y=if(alignBottom)height-child.measuredHeight else 0
            child.layout(x,y,x+child.measuredWidth,y+child.measuredHeight)
        }
    }
    private fun publish() {
        content?.view?.alpha=motion.alpha
        if(!motion.present&&!motion.visibleTarget)retireContent()
    }
    private fun schedule() {
        if(!closed)frames.schedule()
    }
    private fun sampleFrame(frameTimeNanos:Long,durationScale:Float) {
        if(closed)return
        val before=motion.measuredSize
        motion.frame(frameTimeNanos,durationScale)
        publish()
        if(before!=motion.measuredSize)requestLayout()
    }
    override fun onAttachedToWindow() {super.onAttachedToWindow();frames.attached()}
    override fun onDetachedFromWindow() {frames.detached();super.onDetachedFromWindow()}
    private fun retireContent() {
        val old=content?:return
        content=null
        // AnimatedVisibility disposes its EnterExit child transition with this content.
        motion=SettingsExpansionMotion(false)
        try {old.close()} finally {removeView(old.view)}
        requestLayout()
    }
    override fun close() {
        if(closed)return
        closed=true;frames.close()
        retireContent()
    }
}
