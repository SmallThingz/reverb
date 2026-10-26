package app.smallthingz.reverb

import android.app.Application
import android.app.Instrumentation
import android.content.res.Configuration
import android.graphics.Bitmap
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.foundation.ScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.CompositionLocalProvider
import smallthingz.ui.runtime.MonotonicFrameClock
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.AndroidUiDispatcher
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import smallthingz.ui.text.font.createFontFamilyResolver
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import app.smallthingz.reverb.nativeui.OnboardingPageActions
import app.smallthingz.reverb.nativeui.OnboardingPageContentState
import app.smallthingz.reverb.nativeui.OnboardingPageContentView
import app.smallthingz.reverb.nativeui.OnboardingPageTypography
import app.smallthingz.reverb.nativeui.OnboardingPermissionStatus
import app.smallthingz.reverb.nativeui.NativeWindowTextAccessibilityOwner
import app.smallthingz.reverb.nativeui.NativeTextSemanticsClip
import app.smallthingz.reverb.nativeui.NativeTextSemanticsClipPolicy
import app.smallthingz.reverb.nativeui.NativeVerticalScrollState
import app.smallthingz.reverb.nativeui.NativeVerticalScrollSource
import app.smallthingz.reverb.nativeui.NativeVerticalNestedHost
import app.smallthingz.reverb.nativeui.ReverbPalette
import app.smallthingz.reverb.nativeui.ReverbParagraphStyles
import app.smallthingz.reverb.nativeui.ReverbTextRole
import java.io.File
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.json.JSONArray
import org.json.JSONObject

private data class PageParityCase(
    val page: Int, val dark: Boolean, val fontScale: Float, val rtl: Boolean,
    val state: OnboardingPageContentState,
) {
    val name: String get() =
        "page$page-${if (dark) "dark" else "light"}-font$fontScale-${if (rtl) "rtl" else "ltr"}"
}

private fun pageParityCases(): List<PageParityCase> {
    val denied = OnboardingPermissionStatus(false, true, false, true, false, true, false, false, true)
    val allowed = denied.copy(microphoneAllowed = true, storageAllowed = true, recoveryAllowed = true,
        notificationsAllowed = true, batteryOptimizationAllowed = true)
    fun state(permissions: OnboardingPermissionStatus = allowed) = OnboardingPageContentState(
        permissions, oneShotEnabled = true, loopingEnabled = true, outputCompression = false,
        skipSilence = false, compressionAvailable = true, silenceValid = true,
        controlsEnabled = true, interactionEnabled = true,
    )
    return listOf(
        PageParityCase(0, false, 1f, false, state(denied)),
        PageParityCase(0, true, 1f, false, state(allowed)),
        PageParityCase(0, false, 2f, true, state(allowed.copy(
            storageRequired = false, recoveryRequired = false, notificationPermissionRequired = false))),
        PageParityCase(0, true, 2f, true, state(denied.copy(
            microphoneAllowed = true, recoveryAllowed = true, notificationsAllowed = true))),
        PageParityCase(1, false, 1f, false, state()),
        PageParityCase(1, true, 1f, false, state().copy(loopingEnabled = false)),
        PageParityCase(1, false, 2f, true, state().copy(oneShotEnabled = false)),
        PageParityCase(1, true, 2f, true, state().copy(controlsEnabled = false, interactionEnabled = false)),
        PageParityCase(2, false, 1f, false, state()),
        PageParityCase(2, true, 1f, false, state().copy(outputCompression = true, skipSilence = true)),
        PageParityCase(2, false, 2f, true, state().copy(
            skipSilence = true, compressionAvailable = false, silenceValid = false)),
        PageParityCase(2, true, 2f, true, state().copy(
            outputCompression = true, skipSilence = true, compressionAvailable = false,
            silenceValid = false, controlsEnabled = false, interactionEnabled = false)),
    )
}

private class PageRender(
    val content: View,
    val scrollPosition: () -> Int,
    val maximumScroll: () -> Int,
    private val dispose: () -> Unit,
) : AutoCloseable {
    private var closed = false
    override fun close() {
        if (closed) return
        closed = true
        dispose()
    }
}

/**
 * Isolated reliability fixture; exact oracle provenance is verified before compilation.
 * Compilation and actual device results, rather than this fixture's presence, establish parity.
 * No grant requests, app preferences, recorder, source recordings, navigation or system UI input.
 */
internal fun verifyNativeOnboardingPageParity(instrumentation: Instrumentation, report: StringBuilder) {
    val context = instrumentation.targetContext
    check(context.packageName == "app.smallthingz.reverb.nativeparity.reliability")
    check(context.applicationContext.javaClass == Application::class.java)
    val typography = Class.forName("app.smallthingz.reverb.ReverbThemeKt")
        .getDeclaredField("ReverbTypography").apply { isAccessible = true }.get(null) as Typography
    val directory = File(context.filesDir, "native-onboarding-page-parity-${System.currentTimeMillis()}")
    check(directory.mkdir())
    val entries = JSONArray()
    val result = JSONObject()
        .put("baseline", "3e16f8ddb78dca15df4b436d34d330801ce83ffd")
        .put("generated_oracle_sha256", "344ee0dfd7b4cb30e244b1e0a75dc76a182f980068947bf9ce29b2ba80d87e33")
        .put("scope", "Released page content including scroll viewport; excludes screen footer/pager/system insets/background noise")
        .put("comparison", "Exact ARGB; repeat stability; exact text geometry and native exposed-node payloads; no masks")
        .put("cases", entries).put("passed", false)
    writeOnboardingParityEvidence(directory, result, null)
    var activity: NativeParityActivity? = null
    var activeRender: PageRender? = null
    var primary: Throwable? = null
    var failedSlices = 0
    var completedSlices = 0
    try {
        val host = launchNativeParityActivity(instrumentation)
        activity = host
        awaitNativeParityDraw(instrumentation, host.fixtureRoot)
        val textWindow = nativeParityOnMain(instrumentation) {
            NativeWindowTextAccessibilityOwner(host.fixtureRoot, NativeTextSemanticsClipPolicy { view ->
                // Page is the root's direct child; its source viewport/group clips are supplied by
                // bindPage. An unknown extra host ancestor must not acquire guessed geometry.
                if (view === host.fixtureRoot) NativeTextSemanticsClip.Unclipped else null
            })
        }
        fun show(case: PageParityCase, reference: Boolean, scroll: Int): PageRender {
            // A detached page deliberately retains its owner for real pager reattachment. This
            // fixture replaces pages permanently, so close the old ownership lease explicitly.
            val old = activeRender
            activeRender = null
            old?.close()
            return showOnboardingPageCase(host, typography, case, reference, scroll, textWindow)
                .also { activeRender = it }
        }
        for (case in pageParityCases()) {
            // Derive a finite, complete slice list from the released scroll range. No offscreen
            // bitmap is counted as actual rendering; every slice is shown on the physical viewport.
            var firstRender: PageRender? = null
            captureNativeParityContent(instrumentation, host) {
                show(case, true, 0).also { firstRender = it }.content
            }.recycle()
            val (maximum, viewportHeight) = nativeParityOnMain(instrumentation) {
                val render = checkNotNull(firstRender)
                render.maximumScroll() to render.content.height
            }
            check(viewportHeight > 0 && maximum >= 0 && maximum != Int.MAX_VALUE) {
                "Reference scroll layout is not measured"
            }
            val offsets = mutableListOf(0)
            while (offsets.last() < maximum) {
                offsets += (offsets.last() + viewportHeight).coerceAtMost(maximum)
                check(offsets.size <= 12) { "Unexpected page extent; do not truncate coverage: ${case.name}" }
            }
            for ((slice, offset) in offsets.withIndex()) {
                val entry = JSONObject().put("case", case.name).put("page", case.page)
                    .put("dark", case.dark).put("font_scale", case.fontScale.toDouble()).put("rtl", case.rtl)
                    .put("slice", slice).put("requested_scroll", offset).put("reference_max_scroll", maximum)
                    .put("passed", false)
                entries.put(entry)
                val bitmaps = mutableListOf<Bitmap>()
                val evidence = mutableListOf<JSONObject>()
                var sliceFailure: Throwable? = null
                try {
                    for (pass in 0..3) {
                        var rendered: PageRender? = null
                        bitmaps += captureNativeParityContent(instrumentation, host) {
                            show(case, pass % 2 == 0, offset)
                                .also { rendered = it }.content
                        }
                        requireNativeParityHost(instrumentation, host)
                        val render = checkNotNull(rendered)
                        val geometry = nativeParityOnMain(instrumentation) {
                            onboardingGeometryEvidence(render.content, pass % 2 == 0)
                                .put("actual_scroll", render.scrollPosition())
                                .put("max_scroll", render.maximumScroll())
                        }
                        val nodes = onboardingNativeNodeEvidence(instrumentation, host, render.content) { partial ->
                            val label = listOf("compose", "native", "compose-repeat", "native-repeat")[pass]
                            val stem = "${case.name}-slice$slice-$label-failed"
                            entry.put("failed_capture", stem)
                            File(directory, "$stem-nodes.json").writeText(partial.toString(2))
                            File(directory, "$stem-geometry.json").writeText(geometry.toString(2))
                            saveNativeParityBitmap(directory, "$stem.png", bitmaps.last())
                        }
                        evidence += JSONObject().put("geometry", geometry).put("nodes", nodes)
                    }
                    val labels = listOf("compose", "native", "compose-repeat", "native-repeat")
                    labels.forEachIndexed { index, label ->
                        val stem = "${case.name}-slice$slice-$label"
                        saveNativeParityBitmap(directory, "$stem.png", bitmaps[index])
                        File(directory, "$stem.json").writeText(evidence[index].toString(2))
                    }
                    val referenceStable = compareNativeParityPixels(bitmaps[0], bitmaps[2])
                    val nativeStable = compareNativeParityPixels(bitmaps[1], bitmaps[3])
                    val pixels = compareNativeParityPixels(bitmaps[0], bitmaps[1])
                    val textGeometryEqual = onboardingTextGeometrySignature(evidence[0]) ==
                        onboardingTextGeometrySignature(evidence[1])
                    val nodesEqual = onboardingNodeSignature(evidence[0]) == onboardingNodeSignature(evidence[1])
                    val evidenceStable = onboardingStableSignature(evidence[0]) == onboardingStableSignature(evidence[2]) &&
                        onboardingStableSignature(evidence[1]) == onboardingStableSignature(evidence[3])
                    val scrollEqual = evidence.all {
                        val geometry = it.getJSONObject("geometry")
                        geometry.getInt("actual_scroll") == offset && geometry.getInt("max_scroll") == maximum
                    }
                    val passed = pixels.getLong("different_pixels") == 0L &&
                        referenceStable.getLong("different_pixels") == 0L &&
                        nativeStable.getLong("different_pixels") == 0L &&
                        textGeometryEqual && nodesEqual && evidenceStable && scrollEqual
                    entry.put("pixels", pixels).put("reference_stability", referenceStable)
                        .put("native_stability", nativeStable).put("text_geometry_equal", textGeometryEqual)
                        .put("nodes_equal", nodesEqual).put("evidence_stable", evidenceStable)
                        .put("scroll_equal", scrollEqual).put("passed", passed)
                    completedSlices++
                    if (!passed) failedSlices++
                    report.append(if (passed) "PASS " else "FAIL ").append("native_onboarding_page ")
                        .append(case.name).append(" slice=").append(slice)
                        .append(" pixel_diff=").append(pixels.getLong("different_pixels"))
                        .append(" text_geometry=").append(textGeometryEqual)
                        .append(" nodes=").append(nodesEqual).append('\n')
                } catch (error: Throwable) {
                    sliceFailure = error
                    entry.put("error", android.util.Log.getStackTraceString(error))
                    throw error
                } finally {
                    bitmaps.forEach(Bitmap::recycle)
                    writeOnboardingParityEvidence(directory, result, sliceFailure)
                }
            }
        }
        check(failedSlices == 0 && completedSlices >= 12) {
            "Onboarding parity failed: $failedSlices of $completedSlices slices; evidence=${directory.name}"
        }
    } catch (error: Throwable) {
        primary = error
        result.put("terminal_error", android.util.Log.getStackTraceString(error))
        throw error
    } finally {
        var cleanupFailure: Throwable? = null
        try {
            activity?.let { host -> nativeParityOnMain(instrumentation) {
                val old = activeRender
                activeRender = null
                var cleanup = captureNativeParityCleanup(null) { old?.close() }
                cleanup = captureNativeParityCleanup(cleanup) { host.finish() }
                cleanup?.let { throw it }
            } }
        } catch (error: Throwable) {
            cleanupFailure = error
            appendNativeParityFailure(primary, error)
            result.put("cleanup_error", android.util.Log.getStackTraceString(error))
        }
        result.put("completed_slices", completedSlices).put("failed_slices", failedSlices)
            .put("passed", primary == null && cleanupFailure == null && completedSlices >= 12 && failedSlices == 0)
        report.append("PARITY_EVIDENCE ").append(directory.name).append("/result.json\n")
        writeOnboardingParityEvidence(directory, result, primary ?: cleanupFailure)
        if (primary == null) cleanupFailure?.let { throw it }
    }
}

private fun writeOnboardingParityEvidence(directory: File, result: JSONObject, failure: Throwable?) {
    try {
        writeQaEvidenceAtomically(File(directory, "result.json")) { output ->
            output.write(result.toString(2).toByteArray(Charsets.UTF_8))
        }
    }
    catch (error: Throwable) { if (failure == null) throw error else appendNativeParityFailure(failure, error) }
}

private fun showOnboardingPageCase(
    host: NativeParityActivity, typography: Typography, case: PageParityCase,
    reference: Boolean, scroll: Int,
    textWindow: NativeWindowTextAccessibilityOwner,
): PageRender {
    check(host.hasWindowFocus())
    val configuration = Configuration(host.resources.configuration).apply {
        fontScale = case.fontScale
        uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
            if (case.dark) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
    }
    val context = host.createConfigurationContext(configuration)
    val density = Density(context.resources.displayMetrics.density, case.fontScale)
    val resolver = createFontFamilyResolver(context)
    val palette = ReverbPalette.resolve(context, case.dark)
    val colors = when {
        Build.VERSION.SDK_INT >= 31 && case.dark -> dynamicDarkColorScheme(context)
        Build.VERSION.SDK_INT >= 31 -> dynamicLightColorScheme(context)
        case.dark -> darkColorScheme()
        else -> lightColorScheme()
    }
    val horizontal = (24f * density.density).roundToInt()
    val vertical = (20f * density.density).roundToInt()
    val width = host.fixtureRoot.width - 2 * horizontal
    val height = host.fixtureRoot.height - 2 * vertical
    check(width > 0 && height > 0)
    val direction = if (case.rtl) View.LAYOUT_DIRECTION_RTL else View.LAYOUT_DIRECTION_LTR
    val state = case.state
    host.fixtureRoot.removeAllViews()
    host.fixtureRoot.setBackgroundColor(if (reference) colors.background.toArgb() else palette.background)
    val render = if (reference) {
        val scrollState = ScrollState(scroll)
        val permissions = state.permissions
        val view = ComposeView(context).apply {
            layoutDirection = direction
            setContent {
                CompositionLocalProvider(
                    LocalContext provides context, LocalConfiguration provides configuration,
                    LocalDensity provides density,
                    LocalLayoutDirection provides if (case.rtl) LayoutDirection.Rtl else LayoutDirection.Ltr,
                ) {
                    MaterialTheme(colorScheme = colors, typography = typography) {
                        ReleasedOnboardingPage(
                            case.page, scrollState,
                            ReleasedOnboardingStatus(
                                permissions.microphoneAllowed, permissions.storageAllowed, permissions.storageRequired,
                                permissions.recoveryAllowed, permissions.recoveryRequired, permissions.notificationsAllowed,
                                permissions.notificationPermissionRequired, permissions.batteryOptimizationAllowed,
                            ),
                            state.oneShotEnabled, state.loopingEnabled, state.outputCompression, state.skipSilence,
                            state.compressionAvailable, state.silenceValid, state.controlsEnabled, state.interactionEnabled,
                            {}, {}, {}, {}, {}, {}, {}, {}, {},
                        )
                    }
                }
            }
        }
        PageRender(view, { scrollState.value }, { scrollState.maxValue }, view::disposeComposition)
    } else {
        val roles = OnboardingPageTypography(
            ReverbParagraphStyles[ReverbTextRole.HEADLINE_MEDIUM],
            ReverbParagraphStyles[ReverbTextRole.TITLE_MEDIUM],
            ReverbParagraphStyles[ReverbTextRole.BODY_LARGE],
            ReverbParagraphStyles[ReverbTextRole.BODY_MEDIUM],
            ReverbParagraphStyles[ReverbTextRole.BODY_SMALL],
        )
        val scrollContext = (AndroidUiDispatcher.Main + SupervisorJob()).withNativeParityClock()
        val scrollScope = CoroutineScope(scrollContext)
        var connection: AutoCloseable? = null
        var accessibility: AutoCloseable? = null
        fun dispose() = closeOnboardingPageResources(connection, accessibility,
            AutoCloseable { scrollScope.cancel() })
        try {
            val view = OnboardingPageContentView(context, case.page, roles, density.nativeParityDensity(), resolver, state, palette,
                scrollState = NativeVerticalScrollState(initial = scroll),
                scrollFrames = checkNotNull(scrollContext[MonotonicFrameClock]),
                scrollScope = scrollScope, scrollNested = IsolatedOnboardingNestedHost).apply {
                layoutDirection = direction
                // No test input is sent. All callbacks are confined to this fixture and do no backend work.
                connection = connect(host, OnboardingPageActions({}, {}, {}, {}, {}))
                accessibility = bindTextAccessibility(textWindow) { true }
            }
            PageRender(view, view::verticalScrollPosition, view::maximumVerticalScrollPosition, ::dispose)
        } catch (error: Throwable) {
            captureNativeParityCleanup(error) { dispose() }
            throw error
        }
    }
    try {
        host.fixtureRoot.addView(render.content, FrameLayout.LayoutParams(width, height, Gravity.TOP or Gravity.LEFT).apply {
            leftMargin = horizontal
            topMargin = vertical
        })
        return render
    } catch (error: Throwable) {
        captureNativeParityCleanup(error) { render.close() }
        captureNativeParityCleanup(error) { host.fixtureRoot.removeView(render.content) }
        throw error
    }
}

/** The isolated page has no scrollable ancestors, exactly like the released oracle fixture. */
private object IsolatedOnboardingNestedHost : NativeVerticalNestedHost {
    override fun preScroll(availableY: Float, source: NativeVerticalScrollSource): Float = 0f
    override fun postScroll(consumedY: Float, availableY: Float, source: NativeVerticalScrollSource): Float = 0f
    override suspend fun preFling(availableY: Float): Float = 0f
    override suspend fun postFling(consumedY: Float, availableY: Float): Float = 0f
}

private fun closeOnboardingPageResources(vararg resources: AutoCloseable?) {
    var failure: Throwable? = null
    resources.forEach { resource ->
        failure = captureNativeParityCleanup(failure) { resource?.close() }
    }
    failure?.let { throw it }
}

internal fun onboardingViewDescendants(root: View): List<View> = buildList {
    fun visit(view: View) {
        add(view)
        if (view is ViewGroup) for (index in 0 until view.childCount) visit(view.getChildAt(index))
    }
    visit(root)
}
