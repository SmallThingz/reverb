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
    private fun colors(dark: Boolean) = ReverbDesign.appearance.colorScheme.resolve(dark, 30) { error("No dynamic resources") }

    @Test fun lightGrainDoesNotReplaceMaterialFillsInkOrOutlines() {
        val base = NativeColorScheme.fallback(false)
        val light = colors(false)
        // The pre-existing Reverb page darkening is the only color difference.
        assertEquals(base, light.copy(background = base.background, surface = base.surface, surfacePreset = null))
        assertEquals(base.border, light.border)
        assertEquals(base.buttonPalette, light.buttonPalette.copy(surfacePreset = null))
        assertEquals(base.settingsControls, light.settingsControls.copy(surfacePreset = null))
        val preset = requireNotNull(preset(light))
        assertTrue(preset is NativeGrainPreset)
        assertNull(preset.borderColor)
        assertNull(preset.borderWidthDp)
        assertEquals(.5f, preset.foregroundGrain, 0f)
        assertEquals(1f, preset.backgroundGrain, 0f)
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

    @Test fun menusCarryAllThemeGrainWithoutAmoledLiftInLight() {
        val context = RuntimeEnvironment.getApplication()
        val recipe = NativeAnchoredMenuRecipe.Default
        val panel = recipe.createPanel(View(context), Density(1f), colors(false))
        val surface = panel.getChildAt(0) as NativeModalPaintedSurface
        for (dark in listOf(false, true, false)) {
            val palette = colors(dark)
            panel.bindPalette(palette)
            val fill = recipe.surfaceColor(palette)
            val expected = NoiseDrawable(fill).apply { setNoiseIntensity(.5f) }.paintedTextSurface
            assertEquals(expected, surface.paintedTextSurface)
            if (!dark) assertEquals(recipe.surfaceColor(palette.copy(surfacePreset = null)), fill)
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
