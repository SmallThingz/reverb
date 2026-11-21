package app.smallthingz.reverb.nativeui

import android.content.Context
import android.view.View
import smallthingz.ui.components.NativeDiscreteSliderLabels
import smallthingz.ui.components.NativeDiscreteSliderView
import smallthingz.ui.components.NativeExpandableSettingsSectionLayout
import smallthingz.ui.input.phase.NativeDragPointerChange
import app.smallthingz.reverb.R
import kotlinx.coroutines.CoroutineScope

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
):NativeExpandableSettingsSectionLayout(context),AutoCloseable {
    private var state=initial
    private val row=SettingsToggleRowView(context,R.string.output_compression,
        initial.enabled,initial.interactionEnabled,environment,inputRoot,true,onEnabledChange)
    private val expansion=SettingsExpansionView(context,initial.enabled,parentScope,alignBottom=false) {
        CompressionBody(context,state,environment,parentInterested) {onLevelChange?.invoke(it)}
    }
    val currentSlider:NativeDiscreteSliderView? get()=(expansion.content as? CompressionBody)?.slider
    init {installExpansion(row,expansion)}
    fun bind(next:SettingsOutputCompressionState,colors:ReverbViewEnvironment) {
        state=next;environment=colors
        row.bind(next.enabled,next.interactionEnabled,colors)
        (expansion.content as? CompressionBody)?.bind(next,colors)
        expansion.visible(next.enabled)
        requestLayout()
    }
    override fun close() {onLevelChange=null;row.close();expansion.close()}
}

private class CompressionBody(
    context:Context,
    initial:SettingsOutputCompressionState,
    environment:ReverbViewEnvironment,
    parentInterested:(NativeDragPointerChange)->Boolean,
    onLevelChange:(Int)->Unit,
):SettingsExpansionContent {
    val slider=NativeDiscreteSliderView(context,initial.level,maximum=8,
        initialEnabled=initial.enabled&&initial.interactionEnabled,
        density=environment.textDensity.density,style=environment.appearance.discreteSlider,
        palette=environment.palette,label=context.getString(R.string.compression_level),
        parentInterested=parentInterested,onValueChange=onLevelChange)
    private val faster=environment.paragraph(context,context.getString(R.string.compression_faster),
        environment.appearance.discreteSlider.labelRole,environment.palette.muted)
    private val smaller=environment.paragraph(context,context.getString(R.string.compression_smaller),
        environment.appearance.discreteSlider.labelRole,environment.palette.muted)
    override val view = NativeDiscreteSliderLabels(context, slider, faster, smaller,
        environment.textDensity.density, environment.appearance.discreteSlider)
    fun bind(state:SettingsOutputCompressionState,next:ReverbViewEnvironment) {
        val style=next.appearance.discreteSlider
        slider.bind(state.level,state.enabled&&state.interactionEnabled,next.textDensity.density,style,next.palette)
        next.bindParagraphStyle(faster,style.labelRole,next.palette.muted)
        next.bindParagraphStyle(smaller,style.labelRole,next.palette.muted)
        view.setAppearance(next.textDensity.density,style)
    }
    override fun close()=slider.close()
}
