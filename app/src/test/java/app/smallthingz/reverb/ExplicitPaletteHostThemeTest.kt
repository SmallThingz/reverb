package app.smallthingz.reverb

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Build
import android.view.ContextThemeWrapper
import android.widget.FrameLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import smallthingz.ui.R as NativeUiR

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28, 29, 35])
class ExplicitPaletteHostThemeTest {
    @Test @Config(qualifiers = "night")
    fun fixedLightWindowUnderSystemNightKeepsPaletteOnThemeChanges() = checkHost(systemLight = false)

    @Test @Config(qualifiers = "notnight")
    fun fixedDarkWindowUnderSystemDayKeepsPaletteOnThemeChanges() = checkHost(systemLight = true)

    private fun checkHost(systemLight: Boolean) {
        val app = RuntimeEnvironment.getApplication()
        assertHost(ContextThemeWrapper(app, R.style.Theme_Reverb), systemLight)
        val controller = Robolectric.buildActivity(Activity::class.java).create()
        val activity = controller.get()
        fun select(light: Boolean) = activity.setTheme(
            if (light) R.style.Theme_Reverb_Light else R.style.Theme_Reverb_Dark
        )
        try {
            select(!systemLight)
            activity.setContentView(FrameLayout(activity))
            controller.start().resume().visible()
            val decor = activity.window.decorView
            for (light in listOf(!systemLight, systemLight, !systemLight)) {
                select(light)
                assertHost(activity.window.context, light)
                assertSame(decor, activity.window.decorView)
            }
        } finally {
            controller.pause().stop().destroy()
        }
    }

    private fun assertHost(context: Context, light: Boolean) {
        val attrs = context.obtainStyledAttributes(intArrayOf(
            android.R.attr.windowBackground, android.R.attr.windowNoTitle))
        try {
            val background = if (light) NativeUiR.color.native_default_light_background
                else NativeUiR.color.native_default_amoled_background
            assertEquals(context.getColor(background), attrs.getColor(0, 0))
            assertTrue(attrs.getBoolean(1, false))
        } finally {
            attrs.recycle()
        }
        if (Build.VERSION.SDK_INT >= 29) {
            // API 28 uses a different, private resource ID for this attribute.
            val theme = context.obtainStyledAttributes(intArrayOf(android.R.attr.isLightTheme))
            try {
                assertEquals(light, theme.getBoolean(0, !light))
            } finally {
                theme.recycle()
            }
            val policy = context.obtainStyledAttributes(intArrayOf(android.R.attr.forceDarkAllowed))
            try {
                assertTrue(policy.hasValue(0))
                assertFalse(policy.getBoolean(0, true))
            } finally {
                policy.recycle()
            }
        }
    }
}
