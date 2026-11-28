package app.smallthingz.reverb

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.view.ViewGroup
import android.app.Activity
import org.robolectric.Robolectric
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import smallthingz.ui.components.*
import smallthingz.ui.graphics.NoiseDrawable
import smallthingz.ui.graphics.NoiseTexture
import smallthingz.ui.reverb.*

/** Production Reverb recipes, including initial Light creation and retained theme round trips. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class ReverbLightSurfaceGrainTest {
    @Test fun aboutAndSheetUseSharedModalRecipesWithLightGrain() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().visible()
        val context = activity.get()
        val light = ReverbDesign.theme(context, false)
        val dark = ReverbDesign.theme(context, true)
        val details = ReverbAboutDetails("Reverb", "1.0", "GitHub", "Repository", "Unavailable")
        val anchor = View(context).also { context.setContentView(it) }
        val dialog = ReverbAboutDialog(anchor, light, details, openRepository = { false }, onDismiss = {})
        val about = ReverbAboutDialog::class.java.getDeclaredField("content").let {
            it.isAccessible = true; it.get(dialog) as View
        }
        try {
            awaitTexture()
            val panel = descendants(about).filterIsInstance<NativeFloatingPanelSurface>().single()
            val clip = panel.clipHost
            val shader = NoiseTexture.shader(NoiseDrawable.SEED_BACKGROUND)
            var firstLight: IntArray? = null
            for ((index, theme) in listOf(light, dark, light).withIndex()) {
                if (index > 0) dialog.bind(theme, details, {})
                assertSame(panel, descendants(about).filterIsInstance<NativeFloatingPanelSurface>().single())
                assertSame(clip, panel.clipHost)
                val sheet = reverbActionSheetStyle(theme)
                assertEquals(NativeUiAppearance.Default.modals.actionSheetStyle(
                    theme.density, theme.fontResolver, theme.palette), sheet)
                assertNotNull("Light and Dark sheets retain their grain seed", sheet.recipe.grainSeed)
                // Both modals use backdrop grain: subdued in Light and full in AMOLED.
                val expectedBackdropGrain = if (index == 1) 1f else .22f
                assertEquals(expectedBackdropGrain, sheet.recipe.grainIntensity, 0f)
                assertEquals(2f, sheet.recipe.handleHeightDp, 0f)
                assertEquals(16f, sheet.recipe.handleVerticalPaddingDp, 0f)
                assertEquals(theme.palette.background, sheet.palette.surfaceContainerLow)
                assertEquals(ReverbDesign.modalSurface(theme.palette), sheet.palette.surfaceContainerLow)
                val floating = NativeFloatingPanelRecipe()
                val fill = floating.surfaceColor(theme.palette)
                val expected = NoiseDrawable(fill, floating.grainSeed).apply {
                    setNoiseIntensity(expectedBackdropGrain)
                }.paintedTextSurface
                assertEquals("Panel samples the grain for foreground contrast", expected, panel.paintedTextSurface)
                about.measure(exact(360), View.MeasureSpec.makeMeasureSpec(640, View.MeasureSpec.AT_MOST))
                about.layout(0, 0, about.measuredWidth, about.measuredHeight)
                val bitmap = Bitmap.createBitmap(panel.width, panel.height, Bitmap.Config.ARGB_8888)
                val patch = try {
                    panel.draw(Canvas(bitmap))
                    // Blank top strip excludes content, handle and rounded outer corners.
                    IntArray((panel.width - 64) * 6) { offset -> bitmap.getPixel(32 + offset % (panel.width - 64), 2 + offset / (panel.width - 64)) }
                } finally { bitmap.recycle() }
                assertTrue("Actual panel grain is visible in theme step $index", patch.toSet().size > 1)
                if (index == 0) firstLight = patch
                if (index == 2) assertArrayEquals(firstLight!!, patch)
                assertSame(shader, NoiseTexture.shader(NoiseDrawable.SEED_BACKGROUND))
            }
        } finally { try { dialog.dismiss() } finally { activity.pause().stop().destroy() } }
    }

    private fun awaitTexture() {
        NoiseTexture.prepare()
        val deadline = System.nanoTime() + 5_000_000_000L
        while (NoiseTexture.shader(NoiseDrawable.SEED_BACKGROUND) == null && System.nanoTime() < deadline) Thread.sleep(5)
        assertNotNull(NoiseTexture.shader(NoiseDrawable.SEED_BACKGROUND))
    }
    private fun exact(size: Int) = View.MeasureSpec.makeMeasureSpec(size, View.MeasureSpec.EXACTLY)
    private fun descendants(view: View): List<View> = buildList {
        add(view)
        if (view is ViewGroup) for (index in 0 until view.childCount) addAll(descendants(view.getChildAt(index)))
    }
}
