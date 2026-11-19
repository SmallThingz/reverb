package app.smallthingz.reverb.nativeui

import android.app.Activity
import android.app.Application
import android.content.res.Configuration
import android.graphics.Color
import android.os.Looper
import android.view.Choreographer
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import app.smallthingz.reverb.R
import app.smallthingz.reverb.AudioSourceMode
import app.smallthingz.reverb.ChannelMode
import app.smallthingz.reverb.InputRouteMode
import app.smallthingz.reverb.PcmSampleFormat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowChoreographer
import smallthingz.ui.components.NativeDropdownContent
import smallthingz.ui.platform.AndroidUiFrameClock
import smallthingz.ui.scroll.NativeVerticalScrollState
import smallthingz.ui.text.TextStyle
import smallthingz.ui.text.font.createFontFamilyResolver
import smallthingz.ui.text.view.ParagraphView
import smallthingz.ui.unit.Density
import smallthingz.ui.unit.sp
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
        val state = SettingsAudioChoicesState(ChannelMode.MONO, listOf(ChannelMode.MONO, ChannelMode.STEREO),
            PcmSampleFormat.PCM_16, listOf(PcmSampleFormat.PCM_16, PcmSampleFormat.PCM_FLOAT),
            48_000, listOf(44_100, 48_000), AudioSourceMode.MIC, listOf(AudioSourceMode.MIC, AudioSourceMode.CAMCORDER),
            InputRouteMode.AUTO, listOf(InputRouteMode.AUTO, InputRouteMode.BUILTIN_MIC), emptySet())
        val popup = object : SettingsAudioPopupHost {
            override fun <T> update(kind: SettingsAudioChoice, anchor: View,
                content: SettingsDropdownContent<T>, onSelected: (T) -> Unit) = Unit
            override fun <T> open(kind: SettingsAudioChoice, anchor: View,
                content: SettingsDropdownContent<T>, onSelected: (T) -> Unit) { actions++ }
        }
        val choices = SettingsAudioChoicesView(activity, environment(1f), state, { "$it Hz" }, popup,
            SettingsAudioSelections({ actions++ }, { actions++ }, { actions++ }, { actions++ }, { actions++ }), { root })
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
            choices.bind(state, environment(2f))
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
            val arrows = nodes.filterIsInstance<OnboardingIconView>()
            assertEquals(5, arrows.size)
            arrows.forEach { assertEquals(activity.getString(R.string.open_options), it.contentDescription) }
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
        val row = SettingsToggleRowView(activity, R.string.auto_start_title, true, true, environment(),
            { root }, true, { calls += it }, summary = activity.getString(R.string.auto_start_summary))
        root.addView(row)
        try {
            root.measure(exact(360), exact(800)); root.layout(0, 0, 360, 800)
            val before = (row.getChildAt(0) as ParagraphView).getText()
            @Suppress("DEPRECATION")
            activity.resources.updateConfiguration(Configuration(original).apply { setLocale(Locale.FRENCH) },
                activity.resources.displayMetrics)
            row.bind(true, true, environment(), activity.getString(R.string.auto_start_summary))
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
            row.close()
            @Suppress("DEPRECATION")
            activity.resources.updateConfiguration(original, activity.resources.displayMetrics)
            owner.pause().stop().destroy()
        }
    }

    @Test fun changedPopupConfigurationKeepsExpansionAndScrollButRejectsRetiredCallbacks() {
        val owner = Robolectric.buildActivity(Activity::class.java).setup().visible()
        val activity = owner.get()
        val root = FrameLayout(activity)
        val anchor = View(activity)
        activity.setContentView(root)
        root.addView(anchor, FrameLayout.LayoutParams(200, 60))
        root.measure(exact(360), exact(800)); root.layout(0, 0, 360, 800)
        val frames = AndroidUiFrameClock(Choreographer.getInstance())
        val scope = CoroutineScope(Dispatchers.Main.immediate + frames)
        val density = Density(1f)
        val resolver = createFontFamilyResolver(activity)
        val palette = ReverbPalette.fallback(false)
        val style = TextStyle(fontSize = 14.sp)
        val expanded = mutableListOf<Boolean>()
        val selected = mutableListOf<Int>()
        val host = SettingsAudioMenuHost(style, density, resolver, frames, scope, palette,
            { _, value -> expanded += value })
        val kind = SettingsAudioChoice.SAMPLE_RATE
        val first = NativeDropdownContent("Sample rate", 1, (1..40).toList()) { value: Int -> "Rate $value" }
        val previousFrameDelay = ShadowChoreographer.getFrameDelay()
        val previousChoreographerPaused = ShadowChoreographer.isPaused()
        try {
            assertTrue(anchor.isAttachedToWindow)
            // Keep Activity's ordinary initial attachment, then manually advance the
            // popup's perpetual frame owner without Choreographer advancing time itself.
            ShadowChoreographer.setPaused(true)
            ShadowChoreographer.setFrameDelay(Duration.ofMillis(16))
            host.open(kind, anchor, first) { selected += it }
            traversePopupWindow()
            val oldWindow = window(host, kind)
            assertTrue(oldWindow.isAttachedToWindow)
            oldWindow.measure(exact(360), View.MeasureSpec.makeMeasureSpec(600, View.MeasureSpec.AT_MOST))
            oldWindow.layout(0, 0, oldWindow.measuredWidth, oldWindow.measuredHeight)
            val oldRow = firstRow(oldWindow)
            oldRow.measure(exact(260), unspecified())
            val oldHeight = (field(oldRow, "text") as ParagraphView).measuredHeight
            @Suppress("UNCHECKED_CAST")
            val oldSelect = field(oldRow, "action") as () -> Unit
            @Suppress("UNCHECKED_CAST")
            val oldDismiss = field(oldWindow, "onDismissRequest") as () -> Unit
            val scroll = (field(host, "scrollStates") as Map<*, *>)[kind] as NativeVerticalScrollState
            assertTrue("positive scroll before replacement", scroll.scrollBy(37f) > 0f)
            val scrollValue = scroll.value
            host.bindConfiguration(style, density, resolver, palette)
            host.update(kind, anchor, first) { selected += it }
            assertSame("unchanged config retains popup", oldWindow, window(host, kind))
            anchor.layoutDirection = View.LAYOUT_DIRECTION_RTL
            host.update(kind, anchor, first) { selected += it }
            val rtlWindow = window(host, kind)
            traversePopupWindow()
            assertNotSame(oldWindow, rtlWindow)
            assertFalse(oldWindow.isAttachedToWindow)
            assertTrue(rtlWindow.isAttachedToWindow)
            assertEquals(View.LAYOUT_DIRECTION_RTL, rtlWindow.layoutDirection)
            assertEquals(listOf(true), expanded)
            host.bindConfiguration(TextStyle(fontSize = 30.sp), Density(1f, 1.5f),
                createFontFamilyResolver(activity), palette)
            assertFalse(rtlWindow.isAttachedToWindow)
            val next = first.copy(selected = 2, optionLabel = { "Frequence $it" })
            host.update(kind, anchor, next) { selected += it }
            traversePopupWindow()
            val replacement = window(host, kind)
            assertNotSame(oldWindow, replacement)
            assertTrue(replacement.isAttachedToWindow)
            assertSame(scroll, (field(host, "scrollStates") as Map<*, *>)[kind])
            assertEquals(scrollValue, scroll.value)
            assertEquals(listOf(true), expanded)
            oldDismiss(); oldSelect()
            assertSame(replacement, window(host, kind))
            assertTrue(selected.isEmpty())
            assertEquals(listOf(true), expanded)
            val newRow = firstRow(replacement)
            newRow.measure(exact(260), unspecified())
            val text = field(newRow, "text") as ParagraphView
            assertEquals("Frequence 1", text.getText())
            assertTrue(text.measuredHeight > oldHeight)
            assertTrue(newRow.performClick())
            assertEquals(listOf(1), selected)
            assertEquals(listOf(true, false), expanded)
            host.open(kind, anchor, next) { selected += it }
            host.bindConfiguration(TextStyle(fontSize = 32.sp), Density(1f), resolver, palette)
            host.dismissAll()
            host.update(kind, anchor, next) { selected += it }
            assertTrue("inactive config boundary must not reopen", (field(host, "sessions") as Map<*, *>).isEmpty())
            assertEquals(listOf(true, false, true, false), expanded)
        } finally {
            try {
                try {
                    host.close()
                } finally {
                    try { scope.cancel() }
                    finally { owner.pause().stop().destroy() }
                }
            } finally {
                ShadowChoreographer.setFrameDelay(previousFrameDelay)
                ShadowChoreographer.setPaused(previousChoreographerPaused)
            }
        }
    }

    private fun window(host: SettingsAudioMenuHost, kind: SettingsAudioChoice): ViewGroup {
        val session = requireNotNull((field(host, "sessions") as Map<*, *>)[kind])
        return field(session, "window") as ViewGroup
    }
    private fun traversePopupWindow() {
        // WindowManager attachment/traversal is queued in PAUSED mode. Do not drain the
        // perpetual popup animation owner; advance only enough for two display frames.
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(34))
    }
    private fun firstRow(window: ViewGroup): View =
        (field(requireNotNull(field(window, "menu")), "column") as ViewGroup).getChildAt(0)
    private fun field(owner: Any, name: String): Any? = owner.javaClass.getDeclaredField(name)
        .apply { isAccessible = true }.get(owner)
    private fun exact(size: Int) = View.MeasureSpec.makeMeasureSpec(size, View.MeasureSpec.EXACTLY)
    private fun unspecified() = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
}
