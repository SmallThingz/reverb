package app.smallthingz.reverb.nativeui

import smallthingz.ui.components.NativeGroupLayout
import smallthingz.ui.text.view.ParagraphView
import android.content.Context
import android.content.res.Configuration
import android.os.LocaleList
import android.view.View
import android.view.ViewGroup
import smallthingz.ui.components.NativeChoice
import smallthingz.ui.components.NativeLabeledSegmentedLayout
import app.smallthingz.reverb.R
import app.smallthingz.reverb.RetentionMode
import smallthingz.ui.R as NativeUiR
import kotlin.math.roundToInt

/** Released LazyColumn item identities; retained state belongs to the item, never its current index. */
internal enum class SettingsSectionKey(val sourceKey:String) {
    BACKGROUND("background-reliability"), THEME("theme"), RETENTION("retention"),
    RECORDING("recording"), QUICK_TILES("quick-save-tiles"), STORAGE("storage"),
    AUTO_START("auto-start");
    companion object {
        fun visible(restricted:Boolean):List<SettingsSectionKey> =
            if(restricted) entries else entries.drop(1)
        fun retentionIndex(restricted:Boolean)=if(restricted)2 else 1
    }
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
    private val sectionLayout = environment.appearance.settingsSelectors.retentionSection
    private val title=settingsSectionTitle(context,R.string.retention_mode_title,environment)
    private val modeValues=mapOf("time" to RetentionMode.TIME,"size" to RetentionMode.SIZE)
    private var selectedMode=mode
    private var optionLocales:LocaleList?=null
    private var modeOptions=emptyList<NativeChoice>()
    private val modes=environment.appearance.settingsSelectors.createSegmentedControl(
        context, segmentedPalette(environment), environment.textDensity.density,
        inputAllowed={settingsInputAllowed(inputRoot)}, onPaintedSurfaceChanged=::invalidateSettingsTextContrast,
    ).apply {
        onSelectionRequest={id->modeValues[id]?.let {onMode(it);true}?:false}
    }
    private val fields = environment.appearance.pairedFields
    private val error=environment.paragraph(context,"",ReverbTextRole.BODY_SMALL,environment.palette.error).apply { visibility=GONE }
    private val body=fields.createFields(context,oneShotValue,loopingValue,error,environment.textDensity.density)
    private val surface=fields.createSurface(context,body,::invalidateSettingsTextContrast)
    private val header = NativeLabeledSegmentedLayout(title, modes)
    init {
        importantForAccessibility=IMPORTANT_FOR_ACCESSIBILITY_NO
        fields.bindSurface(surface,environment.palette)
        addView(title);addView(modes);addView(surface)
        bindMode(mode,environment)
    }
    fun bind(mode:RetentionMode,message:String?,next:ReverbViewEnvironment) {
        environment=next;bindSectionTitle(title,next);bindMode(mode,next)
        error.setText(message.orEmpty());error.visibility=if(message==null)GONE else VISIBLE
        next.bindParagraphStyle(error,ReverbTextRole.BODY_SMALL,next.palette.error)
        body.density=next.textDensity.density
        fields.bindSurface(surface,next.palette);requestLayout()
    }
    override fun onMeasure(w:Int,h:Int) {
        val width=MeasureSpec.getSize(w)
        val inner=(width-2*dp(sectionLayout.sideInsetDp)).coerceAtLeast(0)
        header.measure(inner, inlineGap = dp(sectionLayout.inlineGapDp), stackedGap = dp(sectionLayout.stackedGapDp),
            preferredInlineWidth = dp(sectionLayout.preferredInlineWidthDp))
        surface.measure(exact(inner),unspecified())
        // The trailing inset belongs to the same released Lazy item.
        val height = header.measuredHeight + dp(sectionLayout.bodyGapDp) + surface.measuredHeight +
            dp(sectionLayout.trailingInsetDp)
        setMeasuredDimension(resolveSize(width,w),resolveSize(height,h))
    }
    override fun onLayout(changed:Boolean,l:Int,t:Int,r:Int,b:Int) {
        val inset = dp(sectionLayout.sideInsetDp)
        header.layout(width, inset, 0, layoutDirection == LAYOUT_DIRECTION_RTL)
        val top = header.measuredHeight + dp(sectionLayout.bodyGapDp)
        surface.layout(inset,top,width-inset,top+surface.measuredHeight)
    }
    override fun onConfigurationChanged(newConfig:Configuration) {
        super.onConfigurationChanged(newConfig)
        if(optionLocales!=newConfig.locales)bindMode(selectedMode,environment)
    }
    private fun bindMode(mode:RetentionMode,next:ReverbViewEnvironment) {
        selectedMode=mode
        val locales=resources.configuration.locales
        if(optionLocales!=locales) {
            modeOptions=listOf(NativeChoice("time",context.getString(NativeUiR.string.native_settings_time_option)),
                NativeChoice("size",context.getString(NativeUiR.string.native_settings_size_option)))
            title.setText(context.getString(R.string.retention_mode_title))
            optionLocales=locales
        }
        modes.bind(modeOptions,modeValues.entries.single {it.value==mode}.key,segmentedPalette(next),next.textDensity.density)
    }
    override fun close()=modes.close()
    private fun dp(v:Int)=(v*environment.textDensity.density).roundToInt()
}

/** The source audio group is shared by all five choices and all three expanding feature rows. */
internal class SettingsRecordingSection(
    context:Context,
    private val choices:SettingsAudioChoicesSection,
    outputCompression:View,
    flacWarning:ParagraphView,
    skipSilence:View,
    notificationSave:View,
    private var environment:ReverbViewEnvironment,
):ViewGroup(context) {
    private val sectionLayout = environment.appearance.settingsSelectors.recordingSection
    private val title=settingsSectionTitle(context,R.string.recording_settings_title,environment)
    private val warning=SettingsInsetView(context,flacWarning,
        sectionLayout.warningHorizontalInsetDp,sectionLayout.warningVerticalInsetDp,
        sectionLayout.warningHorizontalInsetDp,sectionLayout.warningVerticalInsetDp,
        environment,fillContentWidth=false)
    init {
        importantForAccessibility=IMPORTANT_FOR_ACCESSIBILITY_NO
        addView(title);addView(choices.view)
        choices.view.addRow(outputCompression,NativeGroupLayout.LayoutParams(-1,-2).apply{dividerTop=true})
        choices.view.addRow(warning,NativeGroupLayout.LayoutParams(-1,-2))
        choices.view.addRow(skipSilence,NativeGroupLayout.LayoutParams(-1,-2).apply{dividerTop=true})
        choices.view.addRow(notificationSave,NativeGroupLayout.LayoutParams(-1,-2).apply{dividerTop=true})
    }
    fun bind(showFlacPcmWarning:Boolean,next:ReverbViewEnvironment) {
        environment=next;bindSectionTitle(title,next)
        warning.visibility=if(showFlacPcmWarning)VISIBLE else GONE
        warning.setEnvironment(next);requestLayout()
    }
    override fun onMeasure(w:Int,h:Int) {
        val width=MeasureSpec.getSize(w);val inner=(width-2*dp(sectionLayout.sideInsetDp)).coerceAtLeast(0)
        title.measure(atMost(inner),unspecified());choices.view.measure(exact(inner),unspecified())
        val height = dp(sectionLayout.titleTopInsetDp) + title.measuredHeight + dp(sectionLayout.bodyGapDp) +
            choices.view.measuredHeight
        setMeasuredDimension(resolveSize(width,w),resolveSize(height,h))
    }
    override fun onLayout(changed:Boolean,l:Int,t:Int,r:Int,b:Int) {
        val inset = dp(sectionLayout.sideInsetDp)
        val titleTop = dp(sectionLayout.titleTopInsetDp)
        placeLogical(title,inset,titleTop)
        val y = titleTop + title.measuredHeight + dp(sectionLayout.bodyGapDp)
        choices.view.layout(inset,y,width-inset,y+choices.view.measuredHeight)
    }
    private fun dp(v:Int)=(v*environment.textDensity.density).roundToInt()
}

internal typealias SettingsInsetView = smallthingz.ui.components.NativeSingleChildInset

internal fun settingsSectionTitle(context:Context,stringRes:Int,environment:ReverbViewEnvironment):ParagraphView =
    environment.paragraph(context,context.getString(stringRes),ReverbTextRole.BODY_LARGE,environment.palette.onSurface)
        .also { bindSectionTitle(it,environment) }
internal fun bindSectionTitle(view:ParagraphView,environment:ReverbViewEnvironment) {
    view.setTypography(environment.appearance.settingsSelectors.sectionTitle,
        environment.textDensity,environment.sharedFontResolver)
    view.animateTextColor(environment.palette.onSurface)
}
private fun ViewGroup.placeLogical(child:View,start:Int,top:Int) {
    val x=if(layoutDirection==View.LAYOUT_DIRECTION_RTL)width-start-child.measuredWidth else start
    child.layout(x,top,x+child.measuredWidth,top+child.measuredHeight)
}
