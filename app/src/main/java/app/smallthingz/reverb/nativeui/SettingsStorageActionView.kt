package app.smallthingz.reverb.nativeui

import android.content.Context
import android.graphics.Color as AndroidColor
import android.view.View
import android.view.ViewGroup
import smallthingz.ui.components.NativeGroupLayout
import smallthingz.ui.components.NativeMaterialButtonStyle
import smallthingz.ui.components.NativeMaterialButtonView
import kotlin.math.max
import kotlin.math.roundToInt

/** Storage's two exact source surfaces:48dp/14dp icon action or default TextButton/12dp raised fill. */
internal class SettingsStorageActionView(
    context:Context,
    initial:ReverbViewEnvironment,
    private val icon:ReverbIcon?,
    private val description:String,
    inputRoot:()->View?,
    onClick:()->Unit,
):ViewGroup(context),AutoCloseable {
    private var environment = initial
    private val radius = if (icon == null) 12f else 14f
    private val surface = NativeGroupLayout(context, radius, onPaintedSurfaceChanged = ::invalidateSettingsTextContrast)
    private val content = NativeMaterialButtonView(context, false, reverbButtonTextStyle(),
        initial.textDensity, initial.sharedFontResolver,
        if (icon == null) NativeMaterialButtonStyle(12, radiusDp = radius)
        else NativeMaterialButtonStyle(0, verticalPaddingDp = 0, minimumWidthDp = 48,
            minimumHeightDp = 48, iconSizeDp = 24, radiusDp = radius, showLabel = false),
        OnboardingNativeInteractionsFactory(inputRoot).roundedButton(radius), onClick)
    private val glyph = icon?.let { OnboardingIconView(context, it, initial.palette.onSurfaceVariant).apply {
        contentDescription = description
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
    } }
    val visual: View get() = content
    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        clipChildren = false
        clipToPadding = false
        content.setLeadingView(glyph, exposeToAccessibility = true)
        surface.addRow(content, NativeGroupLayout.LayoutParams(-1, -2))
        addView(surface)
        bind(initial, true)
    }
    fun bind(next: ReverbViewEnvironment, enabled: Boolean) {
        environment = next
        val palette = next.palette
        surface.setPalette(palette.raised, AndroidColor.TRANSPARENT)
        val textColor = if (enabled) palette.primary else reverbColorWithAlpha(palette.onSurface, .38f)
        if (glyph != null) {
            val iconColor = if (enabled) palette.onSurfaceVariant else reverbColorWithAlpha(palette.onSurfaceVariant, .38f)
            glyph.bind(requireNotNull(icon), iconColor)
        }
        content.bindTypography(reverbButtonTextStyle(), next.textDensity, next.sharedFontResolver)
        content.bind(description, enabled, AndroidColor.TRANSPARENT, textColor,
            indicationColor = if (icon == null) textColor else palette.onSurface)
        requestLayout()
    }
    override fun onMeasure(w:Int,h:Int) {
        surface.measure(w,h)
        val minimum=dp(48)
        setMeasuredDimension(resolveSize(max(minimum,surface.measuredWidth),w),
            resolveSize(max(minimum,surface.measuredHeight),h))
    }
    override fun onLayout(changed:Boolean,l:Int,t:Int,r:Int,b:Int) {
        val x=((width-surface.measuredWidth)/2f).roundToInt()
        val y=((height-surface.measuredHeight)/2f).roundToInt()
        surface.layout(x,y,x+surface.measuredWidth,y+surface.measuredHeight)
    }
    override fun close() = content.close()
    private fun dp(value: Int) = (value * environment.textDensity.density).roundToInt()
}
