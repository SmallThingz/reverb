package smallthingz.ui.reverb

import org.junit.Assert.*
import org.junit.Test
import smallthingz.ui.components.*
import smallthingz.ui.reverb.ReverbDesign
import smallthingz.ui.graphics.NoiseDrawable
import smallthingz.ui.controls.textSurfaceRange
import smallthingz.ui.unit.Density
import org.robolectric.RuntimeEnvironment
import android.app.Application
import android.view.View

@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner::class)
@org.robolectric.annotation.Config(sdk = [35], application = android.app.Application::class)
class ReverbGrainPolicyTest {
    private fun colors(dark: Boolean) = ReverbDesign.appearance.colorScheme.resolve(dark, 30) { RuntimeEnvironment.getApplication().getColor(it) }

    @Test fun lightNeutralSurfacesPreserveMaterialInkOutlinesAndPresetPropagation() {
        val base = NativeColorScheme.fallback(false)
        val light = colors(false)
        val page = 0xfff6f6f6.toInt()
        val field = 0xfffcfcfc.toInt()
        val popup = 0xffffffff.toInt()
        assertEquals(base.copy(background = page, surface = page, surfaceDim = page,
            surfaceBright = popup, surfaceVariant = field, surfaceContainerLowest = popup,
            surfaceContainerLow = field, surfaceContainer = field, surfaceContainerHigh = field,
            surfaceContainerHighest = field, popupFillOverride = popup), light.copy(surfacePreset = null))
        assertEquals(base.border, light.border)
        assertEquals(base.buttonPalette.copy(quietSurface = field, raisedSurface = field),
            light.buttonPalette.copy(surfacePreset = null))
        assertEquals(base.settingsControls.copy(surface = field, raised = field),
            light.settingsControls.copy(surfacePreset = null))
        val preset = requireNotNull(preset(light))
        assertTrue(preset is NativeGrainPreset)
        assertNull(preset.borderColor)
        assertNull(preset.borderWidthDp)
        assertEquals(.20f, preset.foregroundGrain, 0f)
        assertEquals(.22f, preset.backgroundGrain, 0f)
        assertSame(preset, preset(light.buttonPalette))
        assertSame(preset, preset(light.settingsControls))
        assertSame(preset, preset(light.pageControls))
    }

    @Test fun darkPolicyAndGenericLightOptOutStayUnchanged() {
        val dark = colors(true)
        assertEquals(NativeDarkSurfacePreset.Amoled.colorScheme().resolve(true, 30) { error("Unused") }, dark)
        assertSame(NativeDarkSurfacePreset.Amoled, preset(dark))
        val generic = NativeColorScheme.fallback(false)
        assertNull(preset(generic))
        assertNull(preset(generic.settingsControls))
        assertNull(preset(generic.buttonPalette))
    }

    @Test fun retainedMenusCarryLightElevationAndThemeGrainWithGenericOptOut() {
        val context = RuntimeEnvironment.getApplication()
        val recipe = NativeAnchoredMenuRecipe.Default
        val panel = recipe.createPanel(View(context), Density(1f), colors(false))
        val surface = panel.getChildAt(0) as NativeModalPaintedSurface
        for (dark in listOf(false, true, false)) {
            val palette = colors(dark)
            panel.bindPalette(palette)
            val fill = recipe.surfaceColor(palette)
            val expected = NoiseDrawable(fill).apply { setNoiseIntensity(if (dark) .5f else .20f) }.paintedTextSurface
            assertEquals(expected, surface.paintedTextSurface)
            if (!dark) {
                assertEquals(0xffffffff.toInt(), fill)
                val equal = palette.copy(surfacePreset = NativeGrainPreset(.22f, .20f))
                assertEquals(palette, equal)
                panel.bindPalette(equal)
                assertEquals(expected, surface.paintedTextSurface)
                assertNotEquals(recipe.surfaceColor(palette.copy(surfacePreset = null, popupFillOverride = null)), fill)
            }
            assertSame(surface, panel.getChildAt(0))
        }
        val generic = NativeColorScheme.fallback(false)
        panel.bindPalette(generic)
        assertEquals("Generic recipes retain their flat default", textSurfaceRange(recipe.surfaceColor(generic)),
            surface.paintedTextSurface)
    }

    // Inspect policy propagation without exposing library internals as public runtime API.
    private fun preset(palette: Any): NativeSurfacePreset? = palette.javaClass.getDeclaredField("surfacePreset").let {
        it.isAccessible = true
        it.get(palette) as NativeSurfacePreset?
    }
}
