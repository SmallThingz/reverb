package app.smallthingz.reverb

import android.app.Application
import android.content.res.Configuration
import android.util.TypedValue
import android.view.ContextThemeWrapper
import androidx.core.graphics.ColorUtils
import androidx.core.splashscreen.R as SplashR
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.xmlpull.v1.XmlPullParser
import smallthingz.ui.R as NativeUiR

private const val ANDROID_NS = "http://schemas.android.com/apk/res/android"

/** Resolve the packaged starting theme before any Activity or splash installation changes it. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class StartupSplashThemeResourcesTest {
    @Test @Config(sdk = [28, 30])
    fun compatibilityDayBarsContrastAgainstSharedBackdrops() = assertCompatibilityTheme(dark = false)

    @Test @Config(sdk = [28, 30])
    fun compatibilityNightBarsContrastAgainstSharedBackdrops() = assertCompatibilityTheme(dark = true)

    @Test @Config(sdk = [33, 35])
    fun platformDaySplashKeepsSharedBackgroundAndAnimatedBrand() = assertPlatformTheme(dark = false)

    @Test @Config(sdk = [33, 35])
    fun platformNightSplashKeepsSharedBackgroundAndAnimatedBrand() = assertPlatformTheme(dark = true)

    private fun assertCompatibilityTheme(dark: Boolean) {
        val context = splashContext(dark)
        val backdrop = context.getColor(if (dark) NativeUiR.color.native_default_amoled_background
            else NativeUiR.color.native_default_light_background)
        assertEquals(context.getColor(NativeUiR.color.native_default_amoled_background),
            context.colorAttribute(android.R.attr.statusBarColor))
        assertEquals(backdrop, context.colorAttribute(android.R.attr.navigationBarColor))
        assertEquals(backdrop, context.colorAttribute(SplashR.attr.windowSplashScreenBackground))
        context.assertBooleanAttribute(android.R.attr.windowLightStatusBar, false)
        context.assertBooleanAttribute(android.R.attr.windowLightNavigationBar, !dark)
        assertEquals(R.drawable.reverb_splash_foreground,
            context.attribute(SplashR.attr.windowSplashScreenAnimatedIcon).resourceId)
        context.assertSplashMainStrokeContrast(R.drawable.reverb_splash_foreground, backdrop)
        assertEquals(R.style.Theme_Reverb, context.attribute(SplashR.attr.postSplashScreenTheme).resourceId)
    }

    private fun assertPlatformTheme(dark: Boolean) {
        val context = splashContext(dark)
        val backdrop = context.getColor(if (dark) NativeUiR.color.native_default_amoled_background
            else NativeUiR.color.native_default_light_background)
        assertEquals(backdrop, context.colorAttribute(android.R.attr.windowSplashScreenBackground))
        assertEquals(backdrop, context.colorAttribute(SplashR.attr.windowSplashScreenBackground))
        val animatedIcon = context.attribute(android.R.attr.windowSplashScreenAnimatedIcon).resourceId
        assertEquals(R.drawable.reverb_splash_animated_icon, animatedIcon)
        assertEquals(R.drawable.reverb_splash_animated_icon,
            context.attribute(SplashR.attr.windowSplashScreenAnimatedIcon).resourceId)
        assertEquals(960, context.attribute(android.R.attr.windowSplashScreenAnimationDuration).data)
        assertEquals(960, context.attribute(SplashR.attr.windowSplashScreenAnimationDuration).data)
        assertEquals(R.style.Theme_Reverb, context.attribute(SplashR.attr.postSplashScreenTheme).resourceId)
        val vector = context.resources.getXml(animatedIcon).use { xml ->
            var event = xml.next()
            while (event != XmlPullParser.START_TAG && event != XmlPullParser.END_DOCUMENT) event = xml.next()
            assertEquals("animated-vector", xml.name)
            xml.getAttributeResourceValue(ANDROID_NS, "drawable", 0)
        }
        assertEquals(R.drawable.reverb_splash_vector, vector)
        context.assertSplashMainStrokeContrast(vector, backdrop)
    }

    private fun ContextThemeWrapper.assertSplashMainStrokeContrast(vector: Int, backdrop: Int) {
        val mainPaths = setOf("main_body", "main_leg")
        val checked = mutableSetOf<String>()
        resources.getXml(vector).use { xml ->
            while (xml.next() != XmlPullParser.END_DOCUMENT) {
                if (xml.eventType != XmlPullParser.START_TAG || xml.name != "path") continue
                val name = xml.getAttributeValue(ANDROID_NS, "name") ?: continue
                if (name !in mainPaths) continue
                val ink = xml.getAttributeResourceValue(ANDROID_NS, "strokeColor", 0)
                assertEquals(R.color.reverb_splash_compat_main, ink)
                assertEquals(1f, xml.getAttributeFloatValue(ANDROID_NS, "strokeAlpha", 0f), 0f)
                val contrast = ColorUtils.calculateContrast(getColor(ink), backdrop)
                assertTrue("Splash $name contrast was $contrast", contrast >= 3.0)
                checked += name
            }
        }
        assertEquals(mainPaths, checked)
    }

    private fun splashContext(dark: Boolean): ContextThemeWrapper {
        val application = RuntimeEnvironment.getApplication()
        val configuration = Configuration(application.resources.configuration).apply {
            uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
                (if (dark) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO)
        }
        return ContextThemeWrapper(application.createConfigurationContext(configuration), R.style.Theme_Reverb_Splash)
    }

    private fun ContextThemeWrapper.attribute(id: Int): TypedValue = TypedValue().also {
        assertTrue("Starting theme must resolve ${resources.getResourceName(id)}", theme.resolveAttribute(id, it, true))
    }

    private fun ContextThemeWrapper.colorAttribute(id: Int): Int {
        val value = attribute(id)
        assertTrue("${resources.getResourceName(id)} must resolve to a color",
            value.type in TypedValue.TYPE_FIRST_COLOR_INT..TypedValue.TYPE_LAST_COLOR_INT)
        return value.data
    }

    private fun ContextThemeWrapper.assertBooleanAttribute(id: Int, expected: Boolean) {
        val value = attribute(id)
        assertEquals("${resources.getResourceName(id)} must resolve to a boolean",
            TypedValue.TYPE_INT_BOOLEAN, value.type)
        assertEquals(resources.getResourceName(id), expected, value.data != 0)
    }
}
