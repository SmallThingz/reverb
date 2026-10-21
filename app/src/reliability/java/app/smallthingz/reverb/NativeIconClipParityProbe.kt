package app.smallthingz.reverb

import android.app.Application
import android.app.Instrumentation
import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Build
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.MonotonicFrameClock
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.AndroidUiDispatcher
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import app.smallthingz.reverb.nativeui.NativeVerticalNestedHost
import app.smallthingz.reverb.nativeui.NativeVerticalScrollSource
import app.smallthingz.reverb.nativeui.NativeVerticalScrollState
import app.smallthingz.reverb.nativeui.NativeVerticalViewport
import app.smallthingz.reverb.nativeui.OnboardingIconView
import app.smallthingz.reverb.nativeui.ReverbGroupLayout
import app.smallthingz.reverb.nativeui.ReverbIcon
import app.smallthingz.reverb.nativeui.ReverbPalette
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.json.JSONArray
import org.json.JSONObject

/*
 * Diagnostic geometry is the actual hosts11 source layout payload, not a changed production layout:
 * dark-rtl-2.0-readyfalse-initial-compose.json, geometry.raw. Source viewport [63,153,954,1954],
 * Surface [63,710,954,3304], Recovery status Icon [105,2052,63,63]. Its bottom eight pixels are
 * outside the viewport. Removing text/other rows isolates the same released Icon, ScrollNode and
 * Surface draw boundaries. The full-screen oracle and its failed receipt remain unchanged.
 * That capture's physical display uses 421 dpi, as its saved activity dump
 * records. The unrelated 1072px virtual display uses 420 dpi; it is not this fixture's source.
 *
 * Exact source primitives: MainActivity.kt900-909 Surface/Column and932-940 Icon; foundation1.11.2
 * ScrollNode/VerticalScrollableClipShape; M3 1.4.0 Icon; ui1.11.2 VectorComponent/DrawCache.
 * This is a source-isolating diagnostic, not a new whole-onboarding, input, or accessibility pass.
 */
private const val CLIP_VIEWPORT_LEFT = 63
private const val CLIP_VIEWPORT_TOP = 153
private const val CLIP_VIEWPORT_WIDTH = 954
private const val CLIP_VIEWPORT_HEIGHT = 1954
private const val CLIP_GROUP_TOP = 557
private const val CLIP_GROUP_HEIGHT = 3304
private const val CLIP_ICON_LEFT = 42
private const val CLIP_ICON_TOP = 1342
private const val CLIP_ICON_SIZE = 63
private const val CLIP_SOURCE_DENSITY_DPI = 421

private data class IconClipCase(val dark: Boolean, val grouped: Boolean, val clipped: Boolean) {
    val name get() = "${if (dark) "dark" else "light"}-${if (grouped) "grouped" else "plain"}-${if (clipped) "clipped" else "whole"}"
    val viewportHeight get() = CLIP_VIEWPORT_HEIGHT + if (clipped) 0 else 8
}

internal fun verifyNativeIconClipParity(instrumentation: Instrumentation, report: StringBuilder) {
    val context = instrumentation.targetContext
    check(context.packageName == "app.smallthingz.reverb.nativeparity.reliability")
    check(context.applicationContext.javaClass == Application::class.java)
    val directory = File(context.filesDir, "native-icon-clip-parity-${System.currentTimeMillis()}")
    check(directory.mkdir())
    val cases = JSONArray()
    val result = JSONObject()
        .put("baseline", "3e16f8ddb78dca15df4b436d34d330801ce83ffd")
        .put("app_icons_sha256", "baeb2191e2c0e3c98de368c2ab112044abc719f2888fe5527c23d3fab4643273")
        .put("scope", "Exact partial icon clip isolation; actual retained source coordinates and existing native primitives")
        .put("comparison", "Exact full-window ARGB and ABAB stability; no masks or tolerance")
        .put("not_qualified", "Whole-screen behavior, accessibility, gestures and other densities")
        .put("cases", cases).put("passed", false)
    var host: NativeParityActivity? = null
    var active: IconClipRender? = null
    var primary: Throwable? = null
    var completed = 0
    var mismatches = 0
    try {
        val activity = launchNativeParityActivity(instrumentation)
        host = activity
        awaitNativeParityDraw(instrumentation, activity.fixtureRoot)
        requireNativeParityHost(instrumentation, activity)
        nativeParityOnMain(instrumentation) {
            val metrics = activity.resources.displayMetrics
            val configuration = activity.resources.configuration
            // ResourcesImpl multiplies by the rounded Float reciprocal, not densityDpi / 160f.
            val expectedDensity = configuration.densityDpi * (1f / DisplayMetrics.DENSITY_DEFAULT)
            result.put("observed_configuration", JSONObject()
                .put("width_px", activity.fixtureRoot.width)
                .put("height_px", activity.fixtureRoot.height)
                .put("metrics_density_dpi", metrics.densityDpi)
                .put("configuration_density_dpi", configuration.densityDpi)
                .put("density", metrics.density))
            check(activity.fixtureRoot.width == 1080 && activity.fixtureRoot.height == 2400 &&
                metrics.densityDpi == CLIP_SOURCE_DENSITY_DPI &&
                configuration.densityDpi == CLIP_SOURCE_DENSITY_DPI &&
                metrics.density == expectedDensity) {
                "This diagnostic reproduces retained physical0 1080x2400,421dpi source geometry; do not rescale it"
            }
        }
        for (dark in listOf(false, true)) for (grouped in listOf(false, true)) for (clipped in listOf(false, true)) {
            val case = IconClipCase(dark, grouped, clipped)
            val entry = JSONObject().put("name", case.name).put("passed", false)
                .put("dark", dark).put("grouped", grouped).put("clipped", clipped)
            cases.put(entry)
            val captures = ArrayList<Bitmap>()
            val geometry = JSONArray()
            try {
                repeat(4) { pass ->
                    val reference = pass % 2 == 0
                    captures += captureNativeParityContent(instrumentation, activity) {
                        val previous = active
                        active = null
                        previous?.close()
                        showIconClipCase(activity, case, reference).also { active = it }.root
                    }
                    val label = listOf("compose", "native", "compose-repeat", "native-repeat")[pass]
                    saveNativeParityBitmap(directory, "${case.name}-$label.png", captures.last())
                    requireNativeParityHost(instrumentation, activity)
                    geometry.put(nativeParityOnMain(instrumentation) { checkNotNull(active).evidence() })
                    File(directory, "${case.name}-$label.json").writeText(geometry.getJSONObject(pass).toString(2))
                }
                val pixels = compareNativeParityPixels(captures[0], captures[1])
                val referenceStable = compareNativeParityPixels(captures[0], captures[2])
                val nativeStable = compareNativeParityPixels(captures[1], captures[3])
                val geometryMatches = (0..3).all { index ->
                    val icon = geometry.getJSONObject(index).getJSONObject("icon_geometry")
                    icon.getJSONArray("position").let { it.getDouble(0) == 105.0 && it.getDouble(1) == 2052.0 } &&
                        icon.getJSONArray("size").let { it.getInt(0) == 63 && it.getInt(1) == 63 } &&
                        icon.getJSONArray("clipped").let {
                            it.getDouble(0) == 105.0 && it.getDouble(1) == 2052.0 &&
                                it.getDouble(2) == 168.0 && it.getDouble(3) == if (clipped) 2107.0 else 2115.0
                        }
                }
                val passed = pixels.getLong("different_pixels") == 0L &&
                    referenceStable.getLong("different_pixels") == 0L &&
                    nativeStable.getLong("different_pixels") == 0L && geometryMatches
                entry.put("pixels", pixels).put("reference_stability", referenceStable)
                    .put("native_stability", nativeStable).put("geometry_matches_recorded_source", geometryMatches).put("passed", passed)
                completed++
                if (!passed) mismatches++
                report.append(if (passed) "PASS " else "FAIL ").append("native_icon_clip_parity ")
                    .append(case.name).append(" pixel_diff=").append(pixels.getLong("different_pixels")).append('\n')
            } finally {
                captures.forEach(Bitmap::recycle)
            }
        }
        check(completed == 8 && mismatches == 0) {
            "Icon clip diagnostic has $mismatches exact mismatches in $completed scenes"
        }
    } catch (error: Throwable) {
        primary = error
        result.put("terminal_error", android.util.Log.getStackTraceString(error))
    } finally {
        fun retain(error: Throwable) {
            val earlier = primary
            if (earlier == null) primary = error else if (earlier !== error) earlier.addSuppressed(error)
        }
        host?.let { activity ->
            try {
                nativeParityOnMain(instrumentation) {
                    var closeFailure: Throwable? = null
                    try {
                        val previous = active
                        active = null
                        previous?.close()
                    } catch (error: Throwable) { closeFailure = error }
                    try { activity.finish() } catch (error: Throwable) {
                        val previous = closeFailure
                        if (previous == null) closeFailure = error else if (previous !== error) previous.addSuppressed(error)
                    }
                    closeFailure?.let { throw it }
                }
            } catch (error: Throwable) { retain(error); result.put("cleanup_error", android.util.Log.getStackTraceString(error)) }
        }
        result.put("completed_cases", completed).put("mismatches", mismatches)
            .put("passed", primary == null && completed == 8 && mismatches == 0)
        try { File(directory, "result.json").writeText(result.toString(2)) }
        catch (error: Throwable) { retain(error) }
        report.append("PARITY_EVIDENCE ").append(directory.name).append("/result.json\n")
    }
    primary?.let { throw it }
}

private class IconClipRender(
    val root: View,
    val evidence: () -> JSONObject,
    private val release: () -> Unit,
) : AutoCloseable {
    override fun close() = release()
}

private fun showIconClipCase(host: NativeParityActivity, case: IconClipCase, reference: Boolean): IconClipRender {
    check(host.hasWindowFocus())
    val config = Configuration(host.resources.configuration).apply {
        fontScale = 2f
        uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
            if (case.dark) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
    }
    val context = host.createConfigurationContext(config)
    val density = Density(context.resources.displayMetrics.density, 2f)
    val colors = when {
        Build.VERSION.SDK_INT >= 31 && case.dark -> dynamicDarkColorScheme(context)
        Build.VERSION.SDK_INT >= 31 -> dynamicLightColorScheme(context)
        case.dark -> darkColorScheme()
        else -> lightColorScheme()
    }
    val palette = ReverbPalette.resolve(context, case.dark)
    val root = FrameLayout(context).apply {
        clipChildren = false
        clipToPadding = false
        layoutDirection = View.LAYOUT_DIRECTION_RTL
        if (Build.VERSION.SDK_INT >= 29) isForceDarkAllowed = false
        setBackgroundColor(if (reference) colors.background.toArgb() else palette.background)
    }
    var iconGeometry: JSONObject? = null
    var nativeIcon: View? = null
    var scope: CoroutineScope? = null
    var compose: ComposeView? = null
    val viewport: View
    try {
        if (reference) {
            viewport = ComposeView(context).also { view ->
                compose = view
                view.layoutDirection = View.LAYOUT_DIRECTION_RTL
                view.setContent {
                    CompositionLocalProvider(
                        LocalContext provides context, LocalConfiguration provides config,
                        LocalDensity provides density, LocalLayoutDirection provides LayoutDirection.Rtl,
                    ) {
                        MaterialTheme(colorScheme = colors) {
                            val chrome = appChrome()
                            Column(Modifier.fillMaxWidth().verticalScroll(ScrollState(0))) {
                                Spacer(Modifier.height(with(density) { CLIP_GROUP_TOP.toDp() }))
                                val content: @Composable () -> Unit = {
                                    ClipDiagnosticIconBody(
                                        Modifier.onGloballyPositioned { coordinates ->
                                            val position = coordinates.positionInWindow()
                                            val clipped = coordinates.boundsInWindow()
                                            iconGeometry = JSONObject().put("position", JSONArray(listOf(position.x, position.y)))
                                                .put("size", JSONArray(listOf(coordinates.size.width, coordinates.size.height)))
                                                .put("clipped", JSONArray(listOf(clipped.left, clipped.top, clipped.right, clipped.bottom)))
                                        },
                                    )
                                }
                                if (case.grouped) {
                                    Surface(shape = RoundedCornerShape(20.dp), color = chrome.field,
                                        border = BorderStroke(1.dp, chrome.border)) { content() }
                                } else {
                                    Layout(content = content, modifier = Modifier.background(chrome.field)) { items, _ ->
                                        val child = items.single().measure(Constraints.fixed(CLIP_VIEWPORT_WIDTH, CLIP_GROUP_HEIGHT))
                                        layout(CLIP_VIEWPORT_WIDTH, CLIP_GROUP_HEIGHT) { child.place(0, 0) }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        } else {
            val coroutineContext = AndroidUiDispatcher.Main + SupervisorJob()
            val viewportScope = CoroutineScope(coroutineContext).also { scope = it }
            val icon = OnboardingIconView(context, ReverbIcon.Unchecked, palette.onSurfaceVariant)
            nativeIcon = icon
            val body = IconClipBody(context, icon)
            val group = if (case.grouped) ReverbGroupLayout(context, cornerRadiusDp = 20f).apply {
                setPalette(palette.field, palette.border)
                addRow(body, ReverbGroupLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, CLIP_GROUP_HEIGHT))
            } else body.apply { setBackgroundColor(palette.field) }
            val column = IconClipColumn(context, group)
            viewport = NativeVerticalViewport(context, NativeVerticalScrollState(), density,
                checkNotNull(coroutineContext[MonotonicFrameClock]), viewportScope, IconClipNoNested).apply {
                layoutDirection = View.LAYOUT_DIRECTION_RTL
                setContent(column)
            }
        }
        root.addView(viewport, FrameLayout.LayoutParams(CLIP_VIEWPORT_WIDTH, case.viewportHeight,
            Gravity.TOP or Gravity.LEFT).apply {
            leftMargin = CLIP_VIEWPORT_LEFT
            topMargin = CLIP_VIEWPORT_TOP
        })
        host.fixtureRoot.removeAllViews()
        host.fixtureRoot.addView(root, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    } catch (error: Throwable) {
        try { compose?.disposeComposition() } catch (cleanup: Throwable) { error.addSuppressed(cleanup) }
        try { scope?.cancel() } catch (cleanup: Throwable) { error.addSuppressed(cleanup) }
        throw error
    }
    return IconClipRender(root, {
        val geometry = if (reference) checkNotNull(iconGeometry) else {
            val icon = checkNotNull(nativeIcon)
            val position = IntArray(2).also(icon::getLocationInWindow)
            val clipped = Rect()
            check(icon.getGlobalVisibleRect(clipped))
            JSONObject().put("position", JSONArray(position.toList()))
                .put("size", JSONArray(listOf(icon.width, icon.height)))
                .put("clipped", JSONArray(listOf(clipped.left, clipped.top, clipped.right, clipped.bottom)))
        }
        JSONObject().put("reference", reference).put("icon_geometry", geometry)
            .put("native_view_tree", iconClipViewTree(root))
    }) {
        var failure: Throwable? = null
        fun release(block: () -> Unit) {
            try { block() } catch (error: Throwable) {
                val earlier = failure
                if (earlier == null) failure = error else if (earlier !== error) earlier.addSuppressed(error)
            }
        }
        release { host.fixtureRoot.removeView(root) }
        release { compose?.disposeComposition() }
        release { scope?.cancel() }
        failure?.let { throw it }
    }
}

@Composable
private fun ClipDiagnosticIconBody(iconModifier: Modifier) {
    Layout(content = {
        Icon(AppIcons.unchecked, contentDescription = "Clip diagnostic",
            tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = iconModifier.size(24.dp))
    }) { children, _ ->
        val icon = children.single().measure(Constraints.fixed(CLIP_ICON_SIZE, CLIP_ICON_SIZE))
        layout(CLIP_VIEWPORT_WIDTH, CLIP_GROUP_HEIGHT) {
            // Physical left/top is intentional: these are the recorded RTL source placement facts.
            icon.place(CLIP_ICON_LEFT, CLIP_ICON_TOP)
        }
    }
}

private class IconClipBody(context: Context, private val icon: View) : ViewGroup(context) {
    init { clipChildren = false; clipToPadding = false; addView(icon) }
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        icon.measure(exactIconClip(CLIP_ICON_SIZE), exactIconClip(CLIP_ICON_SIZE))
        setMeasuredDimension(CLIP_VIEWPORT_WIDTH, CLIP_GROUP_HEIGHT)
    }
    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        icon.layout(CLIP_ICON_LEFT, CLIP_ICON_TOP, CLIP_ICON_LEFT + CLIP_ICON_SIZE, CLIP_ICON_TOP + CLIP_ICON_SIZE)
    }
}

private class IconClipColumn(context: Context, private val body: View) : ViewGroup(context) {
    init { clipChildren = false; clipToPadding = false; addView(body) }
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        body.measure(exactIconClip(CLIP_VIEWPORT_WIDTH), exactIconClip(CLIP_GROUP_HEIGHT))
        setMeasuredDimension(CLIP_VIEWPORT_WIDTH, CLIP_GROUP_TOP + CLIP_GROUP_HEIGHT)
    }
    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        body.layout(0, CLIP_GROUP_TOP, CLIP_VIEWPORT_WIDTH, CLIP_GROUP_TOP + CLIP_GROUP_HEIGHT)
    }
}

private fun exactIconClip(value: Int) = View.MeasureSpec.makeMeasureSpec(value, View.MeasureSpec.EXACTLY)

private object IconClipNoNested : NativeVerticalNestedHost {
    override fun preScroll(availableY: Float, source: NativeVerticalScrollSource) = 0f
    override fun postScroll(consumedY: Float, availableY: Float, source: NativeVerticalScrollSource) = 0f
    override suspend fun preFling(availableY: Float) = 0f
    override suspend fun postFling(consumedY: Float, availableY: Float) = 0f
}

private fun iconClipViewTree(view: View): JSONObject {
    val position = IntArray(2).also(view::getLocationInWindow)
    val visible = Rect()
    val visibleResult = view.getGlobalVisibleRect(visible)
    return JSONObject().put("class", view.javaClass.name)
        .put("position", JSONArray(position.toList())).put("size", JSONArray(listOf(view.width, view.height)))
        .put("clip_to_outline", view.clipToOutline).put("layer_type", view.layerType)
        .put("visible_rect", JSONArray(listOf(visible.left, visible.top, visible.right, visible.bottom)))
        .put("global_visible_result", visibleResult)
        .also { json ->
            if (view is ViewGroup) {
                json.put("clip_children", view.clipChildren).put("clip_to_padding", view.clipToPadding)
                json.put("children", JSONArray().apply {
                    for (index in 0 until view.childCount) put(iconClipViewTree(view.getChildAt(index)))
                })
            }
        }
}
