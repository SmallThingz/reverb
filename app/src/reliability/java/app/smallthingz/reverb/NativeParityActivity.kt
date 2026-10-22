package app.smallthingz.reverb

import android.app.Application
import android.app.Instrumentation
import android.content.Context
import android.content.res.Configuration
import android.graphics.Canvas as AndroidCanvas
import android.graphics.Paint
import android.os.Bundle
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import app.smallthingz.reverb.nativeui.ReverbGroupLayout
import app.smallthingz.reverb.nativeui.ReverbGroupRow
import app.smallthingz.reverb.nativeui.ReverbPalette
import java.util.ArrayDeque
import kotlin.math.roundToInt

/** Keep assertion failures on the instrumentation thread so fixture cleanup and reports still run. */
internal fun <T> nativeParityOnMain(instrumentation: Instrumentation, block: () -> T): T {
    var outcome: Result<T>? = null
    instrumentation.runOnMainSync { outcome = runCatching(block) }
    return checkNotNull(outcome) { "Main-thread fixture dispatch returned without a result" }.getOrThrow()
}

internal data class NativeGroupCase(
    val dark: Boolean,
    val rtl: Boolean,
    val densityDpi: Int,
    val widthDp: Int,
) {
    val name: String get() = "${if (dark) "dark" else "light"}-${if (rtl) "rtl" else "ltr"}-${densityDpi}dpi-${widthDp}dp"
}

/** UI-only QA host. No MainActivity, recorder, settings, provider, or permission flow. */
class NativeParityActivity : ComponentActivity() {
    internal lateinit var fixtureRoot: FrameLayout
        private set
    internal var legacyPaletteOracleStatus: String = "Not checked"
        private set
    private val parityFocusEvents = ArrayDeque<String>(32)
    private var parityCaptureOrdinal = 0

    private fun recordParityFocusEvent(event: String) {
        if (parityFocusEvents.size == 32) parityFocusEvents.removeFirst()
        parityFocusEvents.addLast("${SystemClock.uptimeMillis()}:$event")
    }

    internal fun recordParityHostCheck(phase: String, beginCapture: Boolean) {
        if (beginCapture) parityCaptureOrdinal++
        recordParityFocusEvent("capture=$parityCaptureOrdinal phase=$phase")
    }

    internal fun parityFocusDiagnostics(): String =
        "capture=$parityCaptureOrdinal trace=${parityFocusEvents.joinToString(prefix = "[", postfix = "]")}"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        recordParityFocusEvent("onCreate")
        check(packageName == "app.smallthingz.reverb.nativeparity.reliability")
        check(application.javaClass == Application::class.java) {
            "Build with -PreverbNativeParity=true; production Application initialization is forbidden here"
        }
        fixtureRoot = FrameLayout(this)
        if (android.os.Build.VERSION.SDK_INT >= 29) {
            // Match AndroidComposeView's explicit-palette contract; do not change global
            // night mode, inversion, accessibility settings, or the device configuration.
            fixtureRoot.isForceDarkAllowed = false
        }
        setContentView(fixtureRoot)
    }

    override fun onStart() {
        super.onStart()
        recordParityFocusEvent("onStart")
    }

    override fun onResume() {
        super.onResume()
        recordParityFocusEvent("onResume")
    }

    override fun onPause() {
        super.onPause()
        recordParityFocusEvent("onPause")
    }

    override fun onStop() {
        super.onStop()
        recordParityFocusEvent("onStop")
    }

    override fun onDestroy() {
        super.onDestroy()
        recordParityFocusEvent("onDestroy")
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        recordParityFocusEvent("onWindowFocusChanged=$hasFocus")
    }

    internal fun showCase(case: NativeGroupCase, composeReference: Boolean): View {
        check(hasWindowFocus()) { "Parity host lost window focus; do not reclaim another owner's focus" }
        val configuration = Configuration(resources.configuration).apply {
            densityDpi = case.densityDpi
            uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
                if (case.dark) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
        }
        val localContext = createConfigurationContext(configuration)
        val colors = referenceColors(localContext, case.dark)
        val palette = ReverbPalette.resolve(localContext, case.dark)
        assertNativePaletteMatches(colors, palette)
        assertNativePaletteMatches(
            if (case.dark) darkColorScheme() else lightColorScheme(),
            ReverbPalette.fallback(case.dark),
        )
        legacyPaletteOracleStatus = compareLegacyPalette(localContext, case.dark)
        val density = localContext.resources.displayMetrics.density
        val width = (case.widthDp * density).roundToInt()
        val rowHeights = intArrayOf(40, 48, 56)
        val height = rowHeights.sumOf { (it * density).roundToInt() }
        check(width <= fixtureRoot.width && height <= fixtureRoot.height) {
            "Fixture ${width}x${height} exceeds actual host ${fixtureRoot.width}x${fixtureRoot.height}"
        }
        val fills = intArrayOf(
            colors.primary.copy(alpha = 0.21f).toArgb(),
            colors.secondary.copy(alpha = 0.31f).toArgb(),
            colors.tertiary.copy(alpha = 0.41f).toArgb(),
            colors.onSurface.copy(alpha = 0.09f).toArgb(),
        )
        val direction = if (case.rtl) View.LAYOUT_DIRECTION_RTL else View.LAYOUT_DIRECTION_LTR
        fixtureRoot.removeAllViews()
        fixtureRoot.setBackgroundColor(colors.surface.toArgb())
        val content = if (composeReference) {
            ComposeView(localContext).apply {
                layoutDirection = direction
                setContent {
                    CompositionLocalProvider(
                        LocalContext provides localContext,
                        LocalConfiguration provides configuration,
                        LocalDensity provides Density(density, configuration.fontScale),
                        LocalLayoutDirection provides if (case.rtl) LayoutDirection.Rtl else LayoutDirection.Ltr,
                    ) {
                        MaterialTheme(colorScheme = colors) {
                            Box(Modifier.fillMaxSize().background(colors.surface)) {
                                // Invoke the released implementation directly. The build guard pins
                                // both functions to v0.1.2, rather than duplicating the oracle.
                                SettingsAudioGroup {
                                    Canvas(Modifier.fillMaxWidth().height(rowHeights[0].dp)) {
                                        drawRect(Color(fills[0]))
                                    }
                                    Row(Modifier.fillMaxWidth().height(rowHeights[1].dp)
                                        .graphicsLayer { alpha = 0.65f }.settingsAudioDivider(top = true)) {
                                        Canvas(Modifier.weight(1f).fillMaxSize()) { drawRect(Color(fills[1])) }
                                        Canvas(Modifier.weight(1f).fillMaxSize().settingsAudioDivider(start = true)) {
                                            drawRect(Color(fills[2]))
                                        }
                                    }
                                    Canvas(Modifier.fillMaxWidth().height(rowHeights[2].dp).settingsAudioDivider(top = true)) {
                                        drawRect(Color(fills[3]))
                                    }
                                }
                            }
                        }
                    }
                }
            }
        } else {
            FrameLayout(localContext).apply {
                layoutDirection = direction
                setBackgroundColor(colors.surface.toArgb())
                val group = ReverbGroupLayout(localContext).apply {
                    layoutDirection = direction
                    setPalette(palette.field, palette.border)
                    addRow(SyntheticFillView(localContext, fills[0]),
                        ReverbGroupLayout.LayoutParams(-1, (rowHeights[0] * density).roundToInt()))
                    val split = ReverbGroupRow(localContext).apply {
                        layoutDirection = direction
                        alpha = 0.65f
                        setDividerColor(palette.border)
                        addView(SyntheticFillView(localContext, fills[1]),
                            ReverbGroupLayout.LayoutParams(0, -1, 1f))
                        addView(SyntheticFillView(localContext, fills[2]),
                            ReverbGroupLayout.LayoutParams(0, -1, 1f).apply { dividerStart = true })
                    }
                    addRow(split, ReverbGroupLayout.LayoutParams(-1, (rowHeights[1] * density).roundToInt())
                        .apply { dividerTop = true })
                    addRow(SyntheticFillView(localContext, fills[3]),
                        ReverbGroupLayout.LayoutParams(-1, (rowHeights[2] * density).roundToInt())
                            .apply { dividerTop = true })
                }
                val inset = (16 * density).roundToInt()
                addView(group, FrameLayout.LayoutParams(-1, -1).apply {
                    leftMargin = inset
                    rightMargin = inset
                })
            }
        }
        content.layoutDirection = direction
        fixtureRoot.addView(content, FrameLayout.LayoutParams(width, height, Gravity.CENTER))
        return content
    }
}

private fun compareLegacyPalette(context: Context, dark: Boolean): String {
    if (android.os.Build.VERSION.SDK_INT < 31) return "Unavailable: API31 palette resources absent"
    val oracle = try {
        val owner = Class.forName("androidx.compose.material3.DynamicTonalPaletteKt")
        val tonalType = Class.forName("androidx.compose.material3.TonalPalette")
        val tonal = owner.getDeclaredMethod("dynamicTonalPalette", Context::class.java)
            .apply { isAccessible = true }.invoke(null, context)
        owner.getDeclaredMethod(
            if (dark) "dynamicDarkColorScheme31" else "dynamicLightColorScheme31", tonalType,
        ).apply { isAccessible = true }.invoke(null, tonal) as ColorScheme
    } catch (error: ReflectiveOperationException) {
        return "Unverified: pinned API31 oracle inaccessible (${error.javaClass.simpleName})"
    }
    assertNativePaletteMatches(oracle, ReverbPalette.resolve(dark, 31) { id ->
        context.resources.getColor(id, context.theme)
    })
    return "All48 API31 mappings equal pinned Compose oracle; physical device SDK unchanged"
}

private class SyntheticFillView(context: Context, color: Int) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color }
    override fun onDraw(canvas: AndroidCanvas) {
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
    }
}

private fun referenceColors(context: Context, dark: Boolean): ColorScheme =
    if (android.os.Build.VERSION.SDK_INT >= 31) {
        if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    } else {
        if (dark) darkColorScheme() else lightColorScheme()
    }

/** Reflection is confined to the QA oracle; native production code has no role map/renderer. */
private fun assertNativePaletteMatches(reference: ColorScheme, native: ReverbPalette) {
    val getters = reference.javaClass.methods.filter {
        it.parameterCount == 0 && it.returnType == java.lang.Long.TYPE && it.name.startsWith("get")
    }
    val fields = native.javaClass.declaredFields.filter {
        it.type == Integer.TYPE && !java.lang.reflect.Modifier.isStatic(it.modifiers)
    }
    check(fields.isNotEmpty()) { "Native palette has no comparable roles" }
    for (field in fields) {
        val getterName = "get" + field.name.replaceFirstChar { it.uppercase() }
        val getter = getters.single { it.name.substringBefore('-') == getterName }
        val expected = Color((getter.invoke(reference) as Long).toULong()).toArgb()
        field.isAccessible = true
        check(field.getInt(native) == expected) {
            "Palette mismatch ${field.name}: native=${field.getInt(native).toUInt().toString(16)} expected=${expected.toUInt().toString(16)}"
        }
    }
    check(native.field == reference.surfaceContainerLow.toArgb())
    check(native.raised == reference.surfaceContainerHigh.toArgb())
    check(native.ink == reference.onSurface.toArgb())
    check(native.muted == reference.onSurfaceVariant.toArgb())
    check(native.border == reference.onSurface.copy(alpha = 0.14f).toArgb())
}
