package app.smallthingz.reverb.nativeui

import android.content.Context
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import app.smallthingz.reverb.AppThemeMode
import app.smallthingz.reverb.R
import app.smallthingz.reverb.RetentionMode
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Released LazyColumn item identities; retained state belongs to the item, never its current index. */
internal enum class SettingsSectionKey(val sourceKey:String) {
    BACKGROUND("background-reliability"), THEME("theme"), RETENTION("retention"),
    RECORDING("recording"), QUICK_TILES("quick-save-tiles"), STORAGE("storage"), WAKE_LOCK("wake-lock");
    companion object {
        fun visible(restricted:Boolean):List<SettingsSectionKey> =
            if(restricted) entries else entries.drop(1)
        fun retentionIndex(restricted:Boolean)=if(restricted)2 else 1
    }
}

internal class SettingsThemeSection(
    context:Context,
    initialTheme:AppThemeMode,
    private var environment:ReverbViewEnvironment,
    inputRoot:()->View?,
    onTheme:(AppThemeMode)->Unit,
):ViewGroup(context),AutoCloseable {
    private val title=settingsSectionTitle(context,R.string.theme_title,environment)
    private val segments=SettingsSegmentedControlView(context,AppThemeMode.entries.map {
        SettingsSegment(it,context.getString(it.labelRes),when(it) {
            AppThemeMode.SYSTEM->ReverbIcon.ThemeSystem
            AppThemeMode.LIGHT->ReverbIcon.ThemeLight
            AppThemeMode.DARK->ReverbIcon.ThemeDark
        })
    },initialTheme,environment,inputRoot,onTheme)
    private var rowHeight=0
    private var gap=0
    init {importantForAccessibility=IMPORTANT_FOR_ACCESSIBILITY_NO;addView(title);addView(segments)}
    fun bind(theme:AppThemeMode,next:ReverbViewEnvironment) {
        environment=next;bindSectionTitle(title,next);segments.bind(theme,next);requestLayout()
    }
    override fun onMeasure(w:Int,h:Int) {
        val width=View.MeasureSpec.getSize(w)
        val available=(width-2*dp(16)).coerceAtLeast(0)
        title.measure(atMost(available),unspecified())
        gap=min(dp(16),(available-title.measuredWidth).coerceAtLeast(0))
        segments.measure(exact((available-title.measuredWidth-gap).coerceAtLeast(0)),unspecified())
        rowHeight=max(title.measuredHeight,segments.measuredHeight)
        setMeasuredDimension(resolveSize(width,w),resolveSize(2*dp(12)+rowHeight+dp(12),h))
    }
    override fun onLayout(changed:Boolean,l:Int,t:Int,r:Int,b:Int) {
        placeLogical(title,dp(16),dp(12)+((rowHeight-title.measuredHeight)/2f).roundToInt())
        placeLogical(segments,dp(16)+title.measuredWidth+gap,dp(12)+((rowHeight-segments.measuredHeight)/2f).roundToInt())
    }
    override fun close()=segments.close()
    private fun dp(v:Int)=(v*environment.textDensity.density).roundToInt()
}

/**
 * Released RetentionSection structural layout. The two passed values are the real native retention
 * value Views (label/editor/estimate), not strings or replacement platform text fields.
 */
internal class SettingsRetentionSection(
    context:Context,
    mode:RetentionMode,
    private var environment:ReverbViewEnvironment,
    inputRoot:()->View?,
    onMode:(RetentionMode)->Unit,
    oneShotValue:View,
    loopingValue:View,
):ViewGroup(context),AutoCloseable {
    private val title=settingsSectionTitle(context,R.string.retention_mode_title,environment)
    private val modes=SettingsSegmentedControlView(context,listOf(
        SettingsSegment(RetentionMode.TIME,context.getString(R.string.retention_time_label)),
        SettingsSegment(RetentionMode.SIZE,context.getString(R.string.retention_size_mode_label)),
    ),mode,environment,inputRoot,onMode)
    private val surface=ReverbGroupLayout(context,23f)
    private val body=RetentionBody(context,oneShotValue,loopingValue)
    private val error=environment.paragraph(context,"",ReverbTextRole.BODY_SMALL,environment.palette.error)
    private var headerHeight=0
    init {
        importantForAccessibility=IMPORTANT_FOR_ACCESSIBILITY_NO
        error.visibility=GONE
        body.addView(oneShotValue);body.addView(loopingValue);body.addView(error)
        surface.addRow(body,ReverbGroupLayout.LayoutParams(-1,-2))
        surface.setPalette(environment.palette.field,environment.palette.border)
        addView(title);addView(modes);addView(surface)
    }
    fun bind(mode:RetentionMode,message:String?,next:ReverbViewEnvironment) {
        environment=next;bindSectionTitle(title,next);modes.bind(mode,next)
        error.setText(message.orEmpty());error.visibility=if(message==null)GONE else VISIBLE
        next.bindParagraphStyle(error,ReverbTextRole.BODY_SMALL,next.palette.error)
        surface.setPalette(next.palette.field,next.palette.border);requestLayout()
    }
    override fun onMeasure(w:Int,h:Int) {
        val width=MeasureSpec.getSize(w)
        val inner=(width-2*dp(16)).coerceAtLeast(0)
        title.measure(atMost(inner),unspecified())
        modes.measure(exact(min(dp(126),(inner-title.measuredWidth).coerceAtLeast(0))),unspecified())
        headerHeight=max(title.measuredHeight,modes.measuredHeight)
        surface.measure(exact(inner),unspecified())
        // The trailing12dp Spacer belongs to the same released Lazy item.
        setMeasuredDimension(resolveSize(width,w),resolveSize(headerHeight+dp(14)+surface.measuredHeight+dp(12),h))
    }
    override fun onLayout(changed:Boolean,l:Int,t:Int,r:Int,b:Int) {
        placeLogical(title,dp(16),((headerHeight-title.measuredHeight)/2f).roundToInt())
        placeLogical(modes,width-dp(16)-modes.measuredWidth,((headerHeight-modes.measuredHeight)/2f).roundToInt())
        surface.layout(dp(16),headerHeight+dp(14),width-dp(16),headerHeight+dp(14)+surface.measuredHeight)
    }
    override fun close()=modes.close()
    private fun dp(v:Int)=(v*environment.textDensity.density).roundToInt()
    private inner class RetentionBody(context:Context,val first:View,val second:View):ViewGroup(context) {
        private var rowHeight=0
        private var firstWidth=0
        private var secondWidth=0
        private var gap=0
        init {importantForAccessibility=IMPORTANT_FOR_ACCESSIBILITY_NO}
        override fun onMeasure(w:Int,h:Int) {
            val width=MeasureSpec.getSize(w)
            val inner=(width-2*dp(20)).coerceAtLeast(0)
            gap=min(dp(24),inner)
            val remaining=inner-gap
            val unit=(remaining/2f).roundToInt()
            firstWidth=unit+(remaining-unit*2).coerceIn(-1,1)
            secondWidth=remaining-firstWidth
            first.measure(exact(firstWidth),unspecified())
            second.measure(exact(secondWidth),unspecified())
            rowHeight=max(first.measuredHeight,second.measuredHeight)
            if(error.visibility!=GONE)error.measure(atMost(inner),unspecified())
            setMeasuredDimension(width,2*dp(20)+rowHeight+
                if(error.visibility!=GONE)dp(12)+error.measuredHeight else 0)
        }
        override fun onLayout(changed:Boolean,l:Int,t:Int,r:Int,b:Int) {
            placeLogical(first,dp(20),dp(20))
            placeLogical(second,dp(20)+firstWidth+gap,dp(20))
            if(error.visibility!=GONE)placeLogical(error,dp(20),dp(20)+rowHeight+dp(12))
        }
    }
}

/** The source audio group is shared by all five choices and all three expanding feature rows. */
internal class SettingsRecordingSection(
    context:Context,
    private val choices:SettingsAudioChoicesSection,
    outputCompression:View,
    flacWarning:ReverbParagraphView,
    skipSilence:View,
    notificationSave:View,
    private var environment:ReverbViewEnvironment,
):ViewGroup(context) {
    private val title=settingsSectionTitle(context,R.string.recording_settings_title,environment)
    private val warning=SettingsInsetView(context,flacWarning,14,8,14,8,environment,fillContentWidth=false)
    init {
        importantForAccessibility=IMPORTANT_FOR_ACCESSIBILITY_NO
        addView(title);addView(choices.view)
        choices.view.addRow(outputCompression,ReverbGroupLayout.LayoutParams(-1,-2).apply{dividerTop=true})
        choices.view.addRow(warning,ReverbGroupLayout.LayoutParams(-1,-2))
        choices.view.addRow(skipSilence,ReverbGroupLayout.LayoutParams(-1,-2).apply{dividerTop=true})
        choices.view.addRow(notificationSave,ReverbGroupLayout.LayoutParams(-1,-2).apply{dividerTop=true})
    }
    fun bind(showFlacPcmWarning:Boolean,next:ReverbViewEnvironment) {
        environment=next;bindSectionTitle(title,next)
        warning.visibility=if(showFlacPcmWarning)VISIBLE else GONE
        warning.setEnvironment(next);requestLayout()
    }
    override fun onMeasure(w:Int,h:Int) {
        val width=MeasureSpec.getSize(w);val inner=(width-2*dp(16)).coerceAtLeast(0)
        title.measure(atMost(inner),unspecified());choices.view.measure(exact(inner),unspecified())
        setMeasuredDimension(resolveSize(width,w),resolveSize(2*dp(12)+title.measuredHeight+choices.view.measuredHeight,h))
    }
    override fun onLayout(changed:Boolean,l:Int,t:Int,r:Int,b:Int) {
        placeLogical(title,dp(16),dp(12))
        val y=2*dp(12)+title.measuredHeight
        choices.view.layout(dp(16),y,width-dp(16),y+choices.view.measuredHeight)
    }
    private fun dp(v:Int)=(v*environment.textDensity.density).roundToInt()
}

/** A concrete padding modifier for a single section leaf; edges round independently as upstream. */
internal class SettingsInsetView(
    context:Context,
    val content:View,
    private val startDp:Int,
    private val topDp:Int,
    private val endDp:Int,
    private val bottomDp:Int,
    private var environment:ReverbViewEnvironment,
    private val fillContentWidth:Boolean=true,
):ViewGroup(context) {
    init {importantForAccessibility=IMPORTANT_FOR_ACCESSIBILITY_NO;addView(content)}
    fun setEnvironment(next:ReverbViewEnvironment) {environment=next;requestLayout()}
    override fun onMeasure(w:Int,h:Int) {
        val horizontal=dp(startDp)+dp(endDp);val vertical=dp(topDp)+dp(bottomDp)
        val childW=getChildMeasureSpec(w,horizontal,if(fillContentWidth)LayoutParams.MATCH_PARENT else LayoutParams.WRAP_CONTENT)
        val childH=getChildMeasureSpec(h,vertical,LayoutParams.WRAP_CONTENT)
        content.measure(childW,childH)
        setMeasuredDimension(resolveSize(content.measuredWidth+horizontal,w),resolveSize(content.measuredHeight+vertical,h))
    }
    override fun onLayout(changed:Boolean,l:Int,t:Int,r:Int,b:Int) {placeLogical(content,dp(startDp),dp(topDp))}
    private fun dp(v:Int)=(v*environment.textDensity.density).roundToInt()
}

private fun settingsSectionTitle(context:Context,stringRes:Int,environment:ReverbViewEnvironment):ReverbParagraphView =
    environment.paragraph(context,context.getString(stringRes),ReverbTextRole.BODY_LARGE,environment.palette.onSurface)
        .also { bindSectionTitle(it,environment) }
private fun bindSectionTitle(view:ReverbParagraphView,environment:ReverbViewEnvironment) {
    view.setTypography(ReverbParagraphStyles[ReverbTextRole.BODY_LARGE].copy(
        fontSize=19.sp,lineHeight=26.sp,fontWeight=FontWeight.Medium),
        environment.textDensity,environment.sharedFontResolver)
    view.setTextColor(environment.palette.onSurface)
}
private fun ViewGroup.placeLogical(child:View,start:Int,top:Int) {
    val x=if(layoutDirection==View.LAYOUT_DIRECTION_RTL)width-start-child.measuredWidth else start
    child.layout(x,top,x+child.measuredWidth,top+child.measuredHeight)
}
