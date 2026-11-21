package app.smallthingz.reverb.nativeui

import android.content.Context
import android.graphics.Color as AndroidColor
import android.view.View
import android.view.ViewGroup
import smallthingz.ui.components.NativeGroupLayout
import smallthingz.ui.components.NativeButtonInputPolicy
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
    private val recipe = initial.appearance.buttons.let { if (icon == null) it.raisedText else it.raisedIcon }
    private val surface = NativeGroupLayout(context, requireNotNull(recipe.surface.radiusDp),
        onPaintedSurfaceChanged = ::invalidateSettingsTextContrast)
    private val content = NativeMaterialButtonView(context, recipe,
        initial.textDensity, initial.sharedFontResolver,
        NativeButtonInputPolicy(inputRoot), onClick)
    private val glyph = icon?.let { OnboardingIconView(context, it, recipe.contentColor(initial.palette.buttonPalette, true)).apply {
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
        val palette = next.palette.buttonPalette
        surface.setPalette(recipe.surroundingColor(palette), AndroidColor.TRANSPARENT)
        if (glyph != null) {
            glyph.bind(requireNotNull(icon), recipe.contentColor(palette, enabled))
        }
        content.bindTypography(next.textDensity, next.sharedFontResolver)
        content.bind(description, enabled, palette)
        requestLayout()
    }
    override fun onMeasure(w:Int,h:Int) {
        surface.measure(w,h)
        val minimum=dp(content.minimumTouchTargetDp)
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
