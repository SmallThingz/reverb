package app.smallthingz.reverb.nativeui

import android.content.Context
import android.graphics.Color
import android.view.View
import android.view.ViewGroup
import smallthingz.ui.geometry.Offset
import smallthingz.ui.geometry.Rect
import app.smallthingz.reverb.NativeVerticalPhaseBinding
import app.smallthingz.reverb.NativeVerticalScrollAccessibilityBinding
import app.smallthingz.reverb.NativeViewPhasePointerNode
import app.smallthingz.reverb.NativeViewPointerHitLayout
import app.smallthingz.reverb.R
import app.smallthingz.reverb.RecordingEntity
import app.smallthingz.reverb.describeRecordingLocation
import app.smallthingz.reverb.formatSavedRecordingDuration
import app.smallthingz.reverb.formatShortFileSize
import java.util.Date
import kotlin.math.max
import kotlin.math.roundToInt

/** The released Info content only; the shared action sheet owns title/close, window and motion. */
internal class ReverbRecordingInfoBody(
    context: Context,
    private val host: NativeActionSheetContentContext,
    recording: RecordingEntity,
) : ViewGroup(context), AutoCloseable {
    private var environment = host.environment
    private val name = environment.paragraph(context, recording.displayName, ReverbTextRole.TITLE_MEDIUM,
        environment.palette.onSurface)
    private val rows = InfoRows(context, environment)
    val viewport = NativeVerticalViewport(context, NativeVerticalScrollState(), environment.textDensity,
        host.runtime.frames, host.runtime.scope, host.nestedScroll)
    private val scroll = NativeVerticalPhaseBinding(viewport,
        { android.view.ViewConfiguration.get(context).scaledTouchSlop.toFloat() },
        { android.view.ViewConfiguration.get(context).scaledMaximumFlingVelocity.toFloat() },
        host.interestedParent)
    private val leases = ArrayList<AutoCloseable>()
    private var viewportTop = 0
    private var closed = false
    private var restoredClip: (() -> Unit)? = null
    private val surfaceLayouts = ArrayList<NativeViewPointerHitLayout>()
    val hitLayout: NativeViewPointerHitLayout

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        clipChildren = false
        clipToPadding = false
        addView(name)
        viewport.setContent(rows)
        addView(viewport)
        bind(recording)
        for (row in rows.items) {
            val surface = row.surface
            val shield = object : NativeViewPhasePointerNode {
                override val view: View get() = surface
                override fun dispatch(event: app.smallthingz.reverb.NativeDragPointerEvent,
                                      pass: app.smallthingz.reverb.NativePointerPass) = Unit
                override fun cancel() = Unit
            }
            surfaceLayouts += host.hit(surface, listOf(shield), clip = { surface.containsVisualPoint(it.x, it.y) })
        }
        val listHit = host.hit(rows, emptyList(), children = { surfaceLayouts.asReversed() })
        val scrollHit = host.hit(viewport, listOf(scroll.observerNode, scroll.dragNode), children = { listOf(listHit) },
            clip = {
                val extra = dp(30f)
                it.x >= -extra && it.x < viewport.width + extra && it.y >= 0f && it.y < viewport.height
            })
        hitLayout = host.hit(this, emptyList(), children = { listOf(scrollHit) })
        val accessibility = host.accessibility
        viewport.sourceAccessibilityNode = { accessibility.sourceNode(viewport) }
        viewport.sourceAccessibilityChildren = {
            rows.items.flatMap { listOf(it.label, it.value) }
                .filter { accessibility.sourceNode(it).present }
        }
        leases += NativeVerticalScrollAccessibilityBinding(viewport, host.scrollEvents) {
            accessibility.sourceNode(viewport).present
        }
        // The sheet composes caller extraClip with its own Surface/viewport clips.
        val previousClips = accessibility.extraClip
        val ownClips: (View) -> NativeTextSemanticsClip = { view ->
            if (rows.items.any { it.surface.pointerContent === view }) NativeTextSemanticsClip.Rectangle(
                Rect(0f,0f,view.width.toFloat(),view.height.toFloat())) else previousClips(view)
        }
        accessibility.extraClip = ownClips
        restoredClip = { if (accessibility.extraClip === ownClips) accessibility.extraClip = previousClips }
        for (text in listOf(name) + rows.items.flatMap { listOf(it.label, it.value) }) {
            leases += text.bindPlainTextAccessibility(accessibility.cursor, accessibility.geometry,
                sourceNode = { accessibility.sourceNode(text) }) { accessibility.sourceNode(text).present }
        }
    }
    /** Metadata enrichment updates text only; the source rememberScrollState/window stays live. */
    fun bind(recording: RecordingEntity) {
        name.setText(recording.displayName)
        val started = Date(recording.startedAtMillis)
        val date = android.text.format.DateFormat.getMediumDateFormat(context)
        val time = android.text.format.DateFormat.getTimeFormat(context)
        rows.setRows(listOf(
            context.getString(R.string.recording_details_started) to "${date.format(started)} ${time.format(started)}",
            context.getString(R.string.recording_details_duration) to formatSavedRecordingDuration(context, recording.durationMillis),
            context.getString(R.string.recording_details_size) to formatShortFileSize(recording.sizeBytes),
            context.getString(R.string.recording_details_codec) to recording.codecSummary,
            context.getString(R.string.recording_details_mime) to recording.mimeType,
            context.getString(R.string.recording_details_storage) to recording.storageType.name,
            context.getString(R.string.recording_details_location) to describeRecordingLocation(context, recording),
        ))
    }
    fun bindEnvironment(next:ReverbViewEnvironment) {
        if(closed || environment===next)return
        val densityChanged=environment.textDensity!=next.textDensity
        environment=next
        next.bindParagraphStyle(name,ReverbTextRole.TITLE_MEDIUM,next.palette.onSurface)
        rows.bindEnvironment(next)
        if(densityChanged)requestLayout()
    }
    override fun onMeasure(w: Int, h: Int) {
        val width = MeasureSpec.getSize(w)
        val maxHeight = MeasureSpec.getSize(h)
        // Sequential unweighted Column: filename,14dp Spacer, then fillMaxWidth verticalScroll.
        name.measure(infoAtMost(width), infoAtMost(maxHeight))
        val gap = minOf(dp(14f), (maxHeight - name.measuredHeight).coerceAtLeast(0))
        viewportTop = name.measuredHeight + gap
        viewport.measure(infoExact(width), infoAtMost((maxHeight - viewportTop).coerceAtLeast(0)))
        setMeasuredDimension(resolveSize(width,w), resolveSize(viewportTop + viewport.measuredHeight,h))
    }
    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val x = if (layoutDirection == LAYOUT_DIRECTION_RTL) width - name.measuredWidth else 0
        name.layout(x,0,x+name.measuredWidth,name.measuredHeight)
        viewport.layout(0,viewportTop,width,viewportTop+viewport.measuredHeight)
    }
    private fun dp(value: Float) = (value * environment.textDensity.density).roundToInt()
    override fun close() {
        if (closed) return
        closed = true
        var failure: Throwable? = null
        fun release(block: () -> Unit) { try { block() } catch(error: Throwable) {
            if (failure == null) failure = error else failure!!.addSuppressed(error)
        } }
        release(scroll::close)
        release { restoredClip?.invoke() }; restoredClip = null
        leases.asReversed().forEach { release(it::close) }
        leases.clear()
        viewport.sourceAccessibilityNode = null
        viewport.sourceAccessibilityChildren = null
        failure?.let { throw it }
    }

}

private class InfoRows(context: Context, private var environment: ReverbViewEnvironment) : ViewGroup(context) {
    val items = ArrayList<InfoRow>()
    init { importantForAccessibility=IMPORTANT_FOR_ACCESSIBILITY_NO;clipChildren=false;clipToPadding=false }
    fun setRows(rows: List<Pair<String,String>>) {
        if (items.isEmpty()) {
            rows.forEach { (label,value) -> InfoRow(context,environment,label,value).also { items+=it;addView(it) } }
        } else {
            check(items.size == rows.size) // This source has seven fixed metadata rows.
            rows.forEachIndexed { index, (label,value) ->
                items[index].label.setText(label.removeSuffix(":"))
                items[index].value.setText(value)
            }
        }
    }
    fun bindEnvironment(next:ReverbViewEnvironment) {
        val densityChanged=environment.textDensity!=next.textDensity
        environment=next
        items.forEach { it.bindEnvironment(next) }
        if(densityChanged)requestLayout()
    }
    override fun onMeasure(w: Int,h: Int) {
        val width=MeasureSpec.getSize(w)
        val gap=(10f*environment.textDensity.density).roundToInt()
        val bounded=MeasureSpec.getMode(h)!=MeasureSpec.UNSPECIFIED
        var used=0
        for ((index,row) in items.withIndex()) {
            if(index>0)used+=gap
            row.measure(infoExact(width),if(bounded)infoAtMost(max(0,MeasureSpec.getSize(h)-used)) else h)
            used+=row.measuredHeight
        }
        setMeasuredDimension(resolveSize(width,w),resolveSize(used,h))
    }
    override fun onLayout(changed:Boolean,l:Int,t:Int,r:Int,b:Int) {
        var y=0;val gap=(10f*environment.textDensity.density).roundToInt()
        for((index,row) in items.withIndex()) {
            if(index>0)y+=gap
            row.layout(0,y,width,y+row.measuredHeight);y+=row.measuredHeight
        }
    }
}
private class InfoRow(context:Context,private var environment:ReverbViewEnvironment,label:String,value:String):ViewGroup(context) {
    val surface=ReverbGroupLayout(context,14f)
    val label=environment.paragraph(context,label.removeSuffix(":"),ReverbTextRole.LABEL_MEDIUM,environment.palette.onSurfaceVariant)
    val value=environment.paragraph(context,value,ReverbTextRole.BODY_MEDIUM,environment.palette.onSurface)
    private val body=object:ViewGroup(context) {
        init {importantForAccessibility=IMPORTANT_FOR_ACCESSIBILITY_NO;addView(this@InfoRow.label);addView(this@InfoRow.value)}
        override fun onMeasure(w:Int,h:Int) {
            val width=MeasureSpec.getSize(w);val x=dp(14f);val y=dp(11f)
            val bounded=MeasureSpec.getMode(h)!=MeasureSpec.UNSPECIFIED
            var remaining=if(bounded)max(0,MeasureSpec.getSize(h)-y-y) else Int.MAX_VALUE
            val textWidth=max(0,width-x-x)
            this@InfoRow.label.measure(infoAtMost(textWidth),if(bounded)infoAtMost(remaining) else h)
            remaining-=this@InfoRow.label.measuredHeight
            val gap=if(bounded)minOf(dp(2f),remaining.coerceAtLeast(0)) else dp(2f)
            remaining-=gap
            this@InfoRow.value.measure(infoAtMost(textWidth),if(bounded)infoAtMost(remaining.coerceAtLeast(0)) else h)
            setMeasuredDimension(resolveSize(width,w),resolveSize(y+y+this@InfoRow.label.measuredHeight+gap+this@InfoRow.value.measuredHeight,h))
        }
        override fun onLayout(changed:Boolean,l:Int,t:Int,r:Int,b:Int) {
            val x=dp(14f);val y=dp(11f)
            for((text,top) in listOf(this@InfoRow.label to y,this@InfoRow.value to(y+this@InfoRow.label.measuredHeight+dp(2f)))) {
                val left=if(layoutDirection==LAYOUT_DIRECTION_RTL)width-x-text.measuredWidth else x
                text.layout(left,top,left+text.measuredWidth,top+text.measuredHeight)
            }
        }
    }
    init {
        importantForAccessibility=IMPORTANT_FOR_ACCESSIBILITY_NO
        surface.setPalette(environment.palette.surfaceContainerHighest,Color.TRANSPARENT)
        surface.addRow(body,ReverbGroupLayout.LayoutParams(LayoutParams.MATCH_PARENT,LayoutParams.WRAP_CONTENT));addView(surface)
    }
    fun bindEnvironment(next:ReverbViewEnvironment) {
        val densityChanged=environment.textDensity!=next.textDensity
        environment=next
        next.bindParagraphStyle(label,ReverbTextRole.LABEL_MEDIUM,next.palette.onSurfaceVariant)
        next.bindParagraphStyle(value,ReverbTextRole.BODY_MEDIUM,next.palette.onSurface)
        surface.setPalette(next.palette.surfaceContainerHighest,Color.TRANSPARENT)
        if(densityChanged)requestLayout()
    }
    private fun dp(v:Float)=(v*environment.textDensity.density).roundToInt()
    override fun onMeasure(w:Int,h:Int){surface.measure(w,h);setMeasuredDimension(surface.measuredWidth,surface.measuredHeight)}
    override fun onLayout(changed:Boolean,l:Int,t:Int,r:Int,b:Int){surface.layout(0,0,width,height)}
}
private fun infoExact(size:Int)=View.MeasureSpec.makeMeasureSpec(size.coerceAtLeast(0),View.MeasureSpec.EXACTLY)
private fun infoAtMost(size:Int)=View.MeasureSpec.makeMeasureSpec(size.coerceAtLeast(0),View.MeasureSpec.AT_MOST)
