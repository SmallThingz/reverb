package app.smallthingz.reverb.nativeui

import android.app.Activity
import android.app.Application
import android.content.res.Configuration
import android.graphics.Color
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.FrameLayout
import app.smallthingz.reverb.R
import app.smallthingz.reverb.AudioSourceMode
import app.smallthingz.reverb.ChannelMode
import app.smallthingz.reverb.InputRouteMode
import app.smallthingz.reverb.PcmSampleFormat
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import smallthingz.ui.common.ControlTheme
import smallthingz.ui.common.DropdownGroup
import smallthingz.ui.common.ToggleState
import smallthingz.ui.components.NativeDecorativeIconView
import smallthingz.ui.components.NativeDropdown
import smallthingz.ui.common.DropdownContent
import smallthingz.ui.reverb.settings.ReverbAudioActions
import smallthingz.ui.reverb.settings.ReverbAudioChoices
import smallthingz.ui.reverb.settings.ReverbAudioOptions
import smallthingz.ui.reverb.settings.ReverbSettingsToggle
import smallthingz.ui.reverb.settings.ReverbSettingsToggleRole
import smallthingz.ui.text.font.createFontFamilyResolver
import smallthingz.ui.text.view.ParagraphView
import smallthingz.ui.unit.Density
import java.util.Locale
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class SettingsControlConfigurationTest {
    @Test fun audioChoiceAdapterRebindsCurrentTypographyLabelsAndArrowDescriptions() {
        val owner = Robolectric.buildActivity(Activity::class.java).setup().visible()
        val activity = owner.get()
        val original = Configuration(activity.resources.configuration)
        @Suppress("DEPRECATION")
        activity.resources.updateConfiguration(Configuration(original).apply { setLocale(Locale.ENGLISH) },
            activity.resources.displayMetrics)
        val root = FrameLayout(activity)
        activity.setContentView(root)
        fun environment(scale: Float) = ReverbViewEnvironment(Density(1f, scale),
            createFontFamilyResolver(activity), ReverbPalette.fallback(false)).forSettings()
        var actions = 0
        fun state() = ReverbAudioOptions(
            DropdownContent(activity.getString(R.string.channel_mode_label),
                ChannelMode.MONO, listOf(ChannelMode.MONO, ChannelMode.STEREO)) { activity.getString(it.labelRes) },
            DropdownContent(activity.getString(R.string.sample_format_label),
                PcmSampleFormat.PCM_16, listOf(PcmSampleFormat.PCM_16, PcmSampleFormat.PCM_FLOAT)) { activity.getString(it.labelRes) },
            DropdownContent(activity.getString(R.string.sample_rate_label), 48_000, listOf(44_100, 48_000)) { "$it Hz" },
            DropdownContent(activity.getString(R.string.audio_source_label),
                AudioSourceMode.MIC, listOf(AudioSourceMode.MIC, AudioSourceMode.CAMCORDER)) { activity.getString(it.labelRes) },
            DropdownContent(activity.getString(R.string.input_route_label),
                InputRouteMode.AUTO, listOf(InputRouteMode.AUTO, InputRouteMode.BUILTIN_MIC)) { activity.getString(it.labelRes) },
        )
        val choices = ReverbAudioChoices(activity, environment(1f).theme, state(),
            ReverbAudioActions({ actions++ }, { actions++ }, { actions++ }, { actions++ }, { actions++ }),
            inputRoot = { root }, inputAllowed = { true })
        root.addView(choices.view, FrameLayout.LayoutParams(-1, -2))
        fun layout() {
            root.measure(exact(480), exact(1200)); root.layout(0, 0, 480, 1200)
        }
        fun descendants(view: View): List<View> = listOf(view) + if (view is ViewGroup)
            (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()
        try {
            layout()
            val nodes = descendants(choices.view)
            assertNotNull(environment(1f).paragraph(activity, "control", ReverbTextRole.BODY_LARGE).paintedTextSurface)
            nodes.filterIsInstance<ParagraphView>().forEach {
                assertNull("dropdown owns contrast against its own expanded fill", it.paintedTextSurface)
            }
            val title = nodes.filterIsInstance<ParagraphView>().single {
                it.getText() == activity.getString(R.string.channel_mode_label)
            }
            val previousHeight = title.measuredHeight
            assertTrue("baseline native text metrics", previousHeight > 0)
            val oldLabel = title.getText()
            @Suppress("DEPRECATION")
            activity.resources.updateConfiguration(Configuration(original).apply { setLocale(Locale.FRENCH) },
                activity.resources.displayMetrics)
            choices.bind(state(), environment(2f).theme)
            layout()
            assertNotEquals(oldLabel, title.getText())
            assertEquals(activity.getString(R.string.channel_mode_label), title.getText())
            assertTrue(title.measuredHeight > previousHeight)
            val currentEnvironment = environment(2f)
            val reference = ParagraphView(activity, ReverbParagraphStyles[ReverbTextRole.BODY_SMALL],
                currentEnvironment.textDensity, currentEnvironment.sharedFontResolver, Color.BLACK)
            reference.setText(title.getText())
            reference.measure(exact(title.measuredWidth), unspecified())
            assertEquals("retained metrics match a fresh current-style paragraph", reference.measuredHeight, title.measuredHeight)
            val arrows = nodes.filterIsInstance<NativeDecorativeIconView>()
            assertEquals(5, arrows.size)
            arrows.forEach { assertEquals(activity.getString(smallthingz.ui.R.string.common_open_options), it.contentDescription) }
            assertEquals(0, actions)
        } finally {
            choices.close()
            @Suppress("DEPRECATION")
            activity.resources.updateConfiguration(original, activity.resources.displayMetrics)
            owner.pause().stop().destroy()
        }
    }

    @Test fun retainedToggleRefreshesDomainTitleAndDescriptionWithoutChangingCheckedAuthority() {
        val owner = Robolectric.buildActivity(Activity::class.java).setup().visible()
        val activity = owner.get()
        val original = Configuration(activity.resources.configuration)
        @Suppress("DEPRECATION")
        activity.resources.updateConfiguration(Configuration(original).apply { setLocale(Locale.ENGLISH) },
            activity.resources.displayMetrics)
        val root = FrameLayout(activity)
        activity.setContentView(root)
        fun environment() = ReverbViewEnvironment(Density(1f), createFontFamilyResolver(activity), ReverbPalette.fallback(false))
        val calls = mutableListOf<Boolean>()
        fun state() = ToggleState(activity.getString(R.string.auto_start_title), true, true,
            activity.getString(R.string.auto_start_summary))
        val control = ReverbSettingsToggle(activity, environment().theme, state(), ReverbSettingsToggleRole.AUTO_START,
            { root }) { calls += it }
        val row = control.view as ViewGroup
        root.addView(row)
        try {
            root.measure(exact(360), exact(800)); root.layout(0, 0, 360, 800)
            val before = (row.getChildAt(0) as ParagraphView).getText()
            @Suppress("DEPRECATION")
            activity.resources.updateConfiguration(Configuration(original).apply { setLocale(Locale.FRENCH) },
                activity.resources.displayMetrics)
            control.bind(state(), environment().theme)
            root.measure(exact(360), exact(800)); root.layout(0, 0, 360, 800)
            val title = row.getChildAt(0) as ParagraphView
            val description = row.getChildAt(1) as ParagraphView
            assertNotEquals(before, title.getText())
            assertEquals(activity.getString(R.string.auto_start_title), title.getText())
            assertEquals(activity.getString(R.string.auto_start_summary), description.getText())
            assertTrue(calls.isEmpty())
            assertTrue(row.performClick())
            assertEquals(listOf(false), calls)
        } finally {
            control.close()
            @Suppress("DEPRECATION")
            activity.resources.updateConfiguration(original, activity.resources.displayMetrics)
            owner.pause().stop().destroy()
        }
    }

    @Test fun audioDropdownAcceptsSelectionEchoAndRetiresOnExternalBindings() {
        val activityOwner = Robolectric.buildActivity(Activity::class.java).setup().visible()
        val activity = activityOwner.get()
        val root = FrameLayout(activity)
        activity.setContentView(root)
        val theme = ControlTheme.resolve(activity, dark = false)
        var admitted = true
        val selected = mutableListOf<Int>()
        val group = DropdownGroup(theme, { root }, { admitted })
        val first = DropdownContent("Sample rate", 2, (1..3).toList()) { value: Int -> "Rate $value" }
        val field = group.field(activity, first) { fail("Retired callback received a selection") }
        val anchor = field.view
        root.addView(anchor, FrameLayout.LayoutParams(240, 96, Gravity.CENTER))
        fun overlay() = root.getChildAt(1) as ViewGroup
        fun wheel() = overlay().getChildAt(1) as ViewGroup
        fun row(index: Int) = wheel().getChildAt(index) as NativeDropdown<*>
        fun drawn() = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(380))
        val select: (Int) -> Unit = { value ->
            selected += value
            field.bind(first.copy(selected = value))
        }
        fun open(content: DropdownContent<Int> = first) {
            field.bind(content, select)
            assertTrue(anchor.performClick())
            drawn()
            assertEquals(2, root.childCount)
        }
        fun assertRetired() = assertEquals(1, root.childCount)
        try {
            drawn()
            assertTrue(anchor.isAttachedToWindow && anchor.isLaidOut)
            // visible() attaches the Robolectric window but does not grant input focus.
            activityOwner.windowFocusChanged(true)
            assertTrue(anchor.hasWindowFocus())
            assertTrue(anchor.performClick())
            drawn()
            val original = overlay()
            val oldRow = row(0)
            val oldTextHeight = (oldRow.getChildAt(1) as ParagraphView).measuredHeight
            assertTrue(oldTextHeight > 0)
            group.bindTheme(theme)
            field.bind(first, select)
            assertSame(original, overlay())
            assertTrue(wheel().performAccessibilityAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD, null))
            drawn()
            assertSame("the Settings draft echoes the in-flight wheel choice", original, overlay())
            assertEquals(listOf(3), selected)
            field.bind(first.copy(selected = 1))
            assertRetired()
            assertFalse(oldRow.performClick())

            open()
            field.bind(first.copy(options = listOf(2, 3)))
            assertRetired()
            open()
            val next = first.copy(optionLabel = { "Frequence $it" })
            field.bind(next)
            assertRetired()
            open()
            anchor.layoutDirection = View.LAYOUT_DIRECTION_RTL
            field.bind(first)
            assertRetired()
            open()
            val larger = activity.createConfigurationContext(Configuration(activity.resources.configuration).apply { fontScale = 1.5f })
            group.bindTheme(ControlTheme.resolve(larger, dark = false))
            assertRetired()
            field.bind(next)
            assertRetired()
            open(next)
            assertEquals(View.LAYOUT_DIRECTION_RTL, overlay().layoutDirection)
            val currentText = row(0).getChildAt(1) as ParagraphView
            assertEquals("Frequence 1", currentText.getText())
            assertTrue(currentText.measuredHeight > oldTextHeight)

            admitted = false
            group.dismiss()
            field.bind(next)
            anchor.performClick()
            assertRetired()
            assertEquals(listOf(3), selected)
        } finally {
            try { field.close() }
            finally {
                try { group.close() }
                finally { activityOwner.pause().stop().destroy() }
            }
        }
    }

    private fun exact(size: Int) = View.MeasureSpec.makeMeasureSpec(size, View.MeasureSpec.EXACTLY)
    private fun unspecified() = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
}
