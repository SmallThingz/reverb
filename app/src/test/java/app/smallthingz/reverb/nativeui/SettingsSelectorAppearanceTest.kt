package app.smallthingz.reverb.nativeui

import android.app.Application
import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.LocaleList
import android.util.DisplayMetrics
import android.view.View
import android.view.ViewGroup
import app.smallthingz.reverb.RetentionMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import smallthingz.ui.components.NativeChoice
import smallthingz.ui.components.NativeChip
import smallthingz.ui.components.NativeColorSchemeResolver
import smallthingz.ui.components.NativeGroupLayout
import smallthingz.ui.components.NativeSegmentedChoice
import smallthingz.ui.components.NativeSegmentedControl
import smallthingz.ui.components.NativeThemeMode
import smallthingz.ui.components.NativeThemePresentation
import smallthingz.ui.components.NativeThemeSelector
import smallthingz.ui.reverb.settings.ReverbThemeSelector
import smallthingz.ui.components.NativeUiAppearance
import smallthingz.ui.controls.ControlPalette
import smallthingz.ui.text.NativeTypography
import smallthingz.ui.text.NativeTypographyRole
import smallthingz.ui.text.font.FontWeight
import smallthingz.ui.text.font.createFontFamilyResolver
import smallthingz.ui.text.view.ParagraphView
import smallthingz.ui.unit.Density
import smallthingz.ui.unit.sp
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SettingsSelectorAppearanceTest {
    @Test
    fun defaultThemeSelectorMatchesThePriorGroupedRenderer() {
        for (locale in listOf(Locale.ENGLISH, Locale.forLanguageTag("ar"))) {
            val context = windowContext(locale)
            for (dark in listOf(false, true)) {
                val environment = environment(context, dark, NativeUiAppearance.Default)
                val expectedTitle = reverbDefaultTextStyle().copy(
                    fontSize = 19.sp, lineHeight = 26.sp, fontWeight = FontWeight.Medium,
                )
                assertEquals(expectedTitle, environment.appearance.settingsSelectors.sectionTitle)
                val palette = segmentedPalette(environment)
                val presentation = object : NativeThemePresentation {
                    override val surface = NativeGroupLayout(context, 30f)
                    override val paintedSurfaceColor: Int get() = surface.paintedFillColor
                    override val title = environment.paragraph(context, "", ReverbTextRole.BODY_LARGE)
                    override fun setContent(content: View) = surface.addRow(content, NativeGroupLayout.LayoutParams(-1, -2))
                    override fun bindSurface(palette: ControlPalette) = surface.setPalette(palette.surface, palette.outline)
                    override fun bindTitle(titleText: String, palette: ControlPalette, titleColor: Int) {
                        title.setText(titleText)
                        title.setTypography(expectedTitle, environment.textDensity, environment.sharedFontResolver)
                        title.animateTextColor(titleColor)
                    }
                }
                val prior = NativeThemeSelector(context, palette, NativeThemeMode.LIGHT, palette.content,
                    presentation, 1f) { error("Binding called the selection callback") }
                val actual = createSettingsThemeSelector(context, environment, NativeThemeMode.LIGHT, { false }) {
                    error("Binding called the selection callback")
                }
                try {
                    prior.layoutDirection = context.resources.configuration.layoutDirection
                    actual.layoutDirection = prior.layoutDirection
                    layout(prior)
                    layout(actual)
                    assertEquals(prior.measuredHeight, actual.measuredHeight)
                    assertEquals(42, actual.getChildAt(1).height)
                    assertEquals(34, choices(actual).height)
                    assertEquals(presentation.title.getText(), (actual.getChildAt(0) as ParagraphView).getText())
                    val expected = bitmap(prior)
                    val observed = bitmap(actual)
                    try {
                        assertTrue("Theme pixels differ for $locale, dark=$dark", expected.sameAs(observed))
                    } finally {
                        expected.recycle()
                        observed.recycle()
                    }
                } finally {
                    actual.close()
                    prior.close()
                }
            }
        }
    }

    @Test
    fun changingOnlyAppearanceRestylesBothReverbCallers() {
        val context = windowContext(Locale.ENGLISH)
        val default = NativeUiAppearance.Default
        val selectors = default.settingsSelectors
        val alternateField = 0xffd4e2ef.toInt()
        val alternate = default.copy(
            colorScheme = NativeColorSchemeResolver { dark, _, _ ->
                ReverbPalette.fallback(dark).copy(surfaceContainerLow = alternateField)
            },
            settingsSelectors = selectors.copy(
                segmented = selectors.segmented.copy(surfaceRadiusDp = 0f, insetDp = 8f,
                    choiceStyle = selectors.segmented.choiceStyle.copy(minHeightDp = 48f)),
                sectionTitle = selectors.sectionTitle.copy(fontSize = 23.sp, lineHeight = 30.sp),
                retentionSection = selectors.retentionSection.copy(sideInsetDp = 24, preferredInlineWidthDp = 144,
                    bodyGapDp = 18, trailingInsetDp = 15),
            ),
        )
        var defaultTitleHeight = 0
        for ((appearance, height) in listOf(default to 42, alternate to 64)) {
            val environment = if (appearance === alternate) ReverbViewEnvironment.withTestAppearance(context, false, appearance).forSettings()
                else environment(context, false, appearance)
            assertSame(appearance, environment.appearance)
            val theme = createSettingsThemeSelector(context, environment, NativeThemeMode.SYSTEM, { false }) {
                error("Inactive Theme should not send a selection request")
            }
            val retention = SettingsRetentionSection(context, RetentionMode.TIME, environment, { null },
                { error("Inactive Retention should not send a selection request") }, View(context), View(context))
            try {
                layout(theme)
                layout(retention)
                val titleHeight = theme.getChildAt(0).height
                if (appearance === default) defaultTitleHeight = titleHeight
                else assertTrue(titleHeight > defaultTitleHeight)
                for (root in listOf(theme, retention)) {
                    val capsule = root.getChildAt(1) as NativeSegmentedControl
                    val surface = capsule.getChildAt(0) as NativeGroupLayout
                    assertEquals(height, capsule.height)
                    assertEquals(appearance === alternate, surface.containsVisualPoint(1f, 1f))
                    val expectedField = if (appearance === alternate) alternateField else ReverbPalette.fallback(false).field
                    assertEquals(expectedField, surface.paintedFillColor)
                    assertFalse(capsule.acceptsNativeInput)
                }
                val changed = appearance === alternate
                val retentionTitle = retention.getChildAt(0)
                val retentionSelector = retention.getChildAt(1)
                val retentionFields = retention.getChildAt(2)
                val inset = if (changed) 24 else 16
                assertEquals(inset, retentionTitle.left)
                assertEquals(retention.width - inset, retentionSelector.right)
                assertEquals(if (changed) 144 else 126, retentionSelector.width)
                assertEquals(inset, retentionFields.left)
                assertEquals(maxOf(retentionTitle.bottom, retentionSelector.bottom) + if (changed) 18 else 14,
                    retentionFields.top)
                assertEquals(if (changed) 15 else 12, retention.height - retentionFields.bottom)
                val themeChoice = choices(theme)
                val dark = themeChoice.choiceView(NativeThemeMode.DARK.name)
                val palette = segmentedPalette(environment)
                theme.bind(NativeThemeMode.DARK, palette, environment.textDensity, environment.sharedFontResolver)
                assertSame(dark, themeChoice.choiceView(NativeThemeMode.DARK.name))
                assertTrue(requireNotNull(dark).isChecked)
                val retentionChoice = choices(retention)
                val size = retentionChoice.getChildAt(1) as NativeChip
                retention.bind(RetentionMode.SIZE, null, environment)
                assertSame(size, retentionChoice.getChildAt(1))
                assertTrue(size.isChecked)
                assertEquals(NativeThemeMode.DARK.name, themeChoice.selectedId)
            } finally {
                retention.close()
                theme.close()
            }
        }
    }

    @Test
    fun retainedParagraphUsesTheChosenAppearanceAcrossPaletteUpdates() {
        val context = windowContext(Locale.ENGLISH)
        val default = NativeUiAppearance.Default
        val role = NativeTypographyRole.BODY_MEDIUM
        val custom = default.copy(
            typography = NativeTypography(mapOf(role to default.typography[role].copy(fontSize = 26.sp, lineHeight = 34.sp))),
        )
        val baseline = environment(context, false, default)
        val environment = environment(context, false, custom)
        val prior = baseline.paragraph(context, "Recording", role)
        val retained = environment.paragraph(context, "Recording", role)
        layout(prior)
        layout(retained)
        assertTrue(retained.measuredHeight > prior.measuredHeight)

        val next = environment.withPalette(ReverbPalette.fallback(true))
        assertSame(custom, next.appearance)
        assertSame(environment.sharedFontResolver, next.sharedFontResolver)
        val height = retained.measuredHeight
        next.bindParagraphStyle(retained, role)
        layout(retained)
        assertEquals(height, retained.measuredHeight)
        assertEquals("Recording", retained.getText())
    }

    @Test
    @Config(qualifiers = "en-mdpi")
    fun retainedRetentionSelectorRebindsTranslatedLabelsWithoutReplacingOptions() {
        val context = RuntimeEnvironment.getApplication()
        val environment = environment(context, false, NativeUiAppearance.Default)
        val retention = SettingsRetentionSection(context, RetentionMode.SIZE, environment, { null },
            { error("Locale changes must not emit selection requests") }, View(context), View(context))
        try {
            val row = choices(retention)
            val time = row.getChildAt(0) as NativeChip
            val size = row.getChildAt(1) as NativeChip
            assertEquals("Time", time.text.toString())
            RuntimeEnvironment.setQualifiers("de-mdpi")
            retention.bind(RetentionMode.SIZE, null, environment)
            assertSame(time, row.getChildAt(0))
            assertSame(size, row.getChildAt(1))
            assertEquals("Zeit", time.text.toString())
            assertEquals("Size", size.text.toString())
            assertEquals("Aufbewahrung", (retention.getChildAt(0) as ParagraphView).getText())
            assertTrue(size.isChecked)
        } finally {
            retention.close()
            RuntimeEnvironment.setQualifiers("en-mdpi")
        }
    }

    @Test
    fun retentionStacksAtLargeLocalizedFontsAndKeepsEditors() {
        val context = windowContext(Locale.GERMAN, fontScale = 2f)
        val environment = environment(context, false, NativeUiAppearance.Default)
        val first = View(context)
        val second = View(context)
        val retention = SettingsRetentionSection(context, RetentionMode.SIZE, environment, { null },
            { error("Measuring must not request a selection") }, first, second)
        try {
            val row = choices(retention)
            val size = row.getChildAt(1) as NativeChip
            val parent = first.parent
            layout(retention, 150)
            assertTrue(retention.getChildAt(1).top >= retention.getChildAt(0).bottom)
            assertTrue(retention.getChildAt(2).top >= retention.getChildAt(1).bottom)
            assertTrue(size.width > 0 && size.height > 0)
            layout(retention, 480)
            assertSame(size, row.getChildAt(1))
            assertSame(parent, first.parent)
            assertSame(parent, second.parent)
            assertTrue(size.isChecked)
        } finally { retention.close() }
    }

    @Test
    fun sameResolvedCapsuleWidthRestoresTheRetainedChipMeasurements() {
        val context = windowContext(Locale.ENGLISH)
        val appearance = NativeUiAppearance.Default
        val palette = segmentedPalette(environment(context, false, appearance))
        val selector = appearance.settingsSelectors.createSegmentedControl(context, palette)
        try {
            selector.bind(listOf(NativeChoice("time", "Time"), NativeChoice("size", "Size")), "size", palette)
            selector.onSelectionRequest = { error("Measuring must not request a selection") }
            fun measure(maxWidth: Int) {
                selector.measure(View.MeasureSpec.makeMeasureSpec(maxWidth, View.MeasureSpec.AT_MOST),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
            }
            measure(360)
            val width = selector.measuredWidth
            selector.layout(0, 0, width, selector.measuredHeight)
            val row = choices(selector)
            val time = requireNotNull(row.choiceView("time"))
            val size = requireNotNull(row.choiceView("size"))
            assertTrue(width in 1 until 360)
            assertEquals(34, size.height)

            fun assertRemeasure(maxWidth: Int) {
                measure(maxWidth)
                assertEquals(width, selector.measuredWidth)
                assertEquals(time.width, time.measuredWidth)
                assertEquals(size.width, size.measuredWidth)
                assertEquals(34, time.measuredHeight)
                assertEquals(34, size.measuredHeight)
                selector.layout(0, 0, width, selector.measuredHeight)
                assertSame(time, row.choiceView("time"))
                assertSame(size, row.choiceView("size"))
                assertTrue(size.isChecked)
                val textLayout = requireNotNull(size.layout)
                assertEquals(size.text.length, textLayout.getLineEnd(textLayout.lineCount - 1))
                assertTrue(textLayout.height <= size.height - size.compoundPaddingTop - size.compoundPaddingBottom)
            }
            assertRemeasure(400)
            selector.forceLayout()
            assertRemeasure(400)
        } finally { selector.close() }
    }

    private fun windowContext(locale: Locale, fontScale: Float = 1f): Context {
        val context = RuntimeEnvironment.getApplication()
        return context.createConfigurationContext(Configuration(context.resources.configuration).apply {
            setLocales(LocaleList(locale))
            densityDpi = DisplayMetrics.DENSITY_MEDIUM
            this.fontScale = fontScale
        })
    }

    private fun environment(context: Context, dark: Boolean, appearance: NativeUiAppearance) =
        ReverbViewEnvironment.withTestAppearance(Density(context), createFontFamilyResolver(context), ReverbPalette.fallback(dark),
            appearance = appearance).forSettings()

    private fun layout(view: View, width: Int = 480) {
        view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        view.layout(0, 0, view.measuredWidth, view.measuredHeight)
    }

    private fun bitmap(view: View): Bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        .also { view.draw(Canvas(it)) }

    private fun choices(root: View): NativeSegmentedChoice = requireNotNull(findChoices(root))

    private fun findChoices(view: View): NativeSegmentedChoice? = when (view) {
        is NativeSegmentedChoice -> view
        is ViewGroup -> (0 until view.childCount).firstNotNullOfOrNull { findChoices(view.getChildAt(it)) }
        else -> null
    }
}

private fun createSettingsThemeSelector(
    context: Context,
    environment: ReverbViewEnvironment,
    selected: NativeThemeMode,
    inputAllowed: () -> Boolean,
    onSelectionRequest: (NativeThemeMode) -> Boolean,
): NativeThemeSelector = ReverbThemeSelector(context, environment.theme, selected,
    inputAllowed, onSelectionRequest).view as NativeThemeSelector
