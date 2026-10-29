package app.smallthingz.reverb

import android.app.Application
import android.app.Instrumentation
import android.content.res.Configuration
import android.graphics.Rect
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.ui.unit.Density
import app.smallthingz.reverb.nativeui.*
import java.io.File
import java.util.Locale
import kotlin.math.max
import kotlin.math.roundToInt
import org.json.JSONArray
import org.json.JSONObject

/** Actual Android font/layout regression. No permissions, recorder commands or preference writes. */
internal fun verifyNativeOnboardingResponsive(instrumentation: Instrumentation, report: StringBuilder) {
    val context = instrumentation.targetContext
    check(context.packageName == "app.smallthingz.reverb.nativeparity.reliability")
    check(context.applicationContext.javaClass == Application::class.java)
    val original = getRecorderPreferences(context).all.toMap()
    val directory = File(context.filesDir, "native-onboarding-responsive-${System.currentTimeMillis()}")
    check(directory.mkdir())
    val cases = JSONArray()
    val result = JSONObject().put("passed", false).put("cases", cases)
        .put("scope", "Actual Android onboarding buffer page renders and controlled real View measurements")
        .put("unqualified", JSONArray(listOf("released pixel parity", "TalkBack delivery", "physical input", "arbitrary pathological window heights")))
    fun publish() = writeQaEvidenceAtomically(File(directory, "result.json")) {
        it.write(result.toString(2).toByteArray(Charsets.UTF_8))
    }
    publish()
    var failure: Throwable? = null
    try {
        for ((widthDp, fontScale, rtl) in listOf(Triple(320, 1.3f, false), Triple(480, 1f, false), Triple(320, 2f, true))) {
            val host = launchNativeParityActivity(instrumentation)
            awaitNativeParityDraw(instrumentation, host.fixtureRoot)
            val runtime = NativeAboutWindowRuntime(host).apply { setStarted(true) }
            var screen: NativeOnboardingScreenView? = null
            var controlled: NativeOnboardingScreenView? = null
            var forbidden = 0
            val name = "${widthDp}dp-font$fontScale-${if (rtl) "rtl" else "ltr"}"
            val entry = JSONObject().put("name", name).put("measurements", JSONArray())
            val reportedPages = JSONArray()
            entry.put("reported_pages", reportedPages)
            cases.put(entry)
            var caseFailure: Throwable? = null
            try {
                lateinit var themed: android.content.Context
                lateinit var environment: ReverbViewEnvironment
                lateinit var state: OnboardingPageContentState
                lateinit var actions: OnboardingPageActions
                var density = 0f
                nativeParityOnMain(instrumentation) {
                    val configuration = Configuration(host.resources.configuration).apply {
                        uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
                            if (widthDp == 480) Configuration.UI_MODE_NIGHT_NO else Configuration.UI_MODE_NIGHT_YES
                        this.fontScale = fontScale
                        setLocale(Locale.ENGLISH)
                        setLayoutDirection(if (rtl) Locale("ar") else Locale.ENGLISH)
                    }
                    themed = host.createConfigurationContext(configuration)
                    density = themed.resources.displayMetrics.density
                    val palette = ReverbPalette.resolve(themed, widthDp != 480)
                    environment = nativeParityEnvironment(themed, Density(density, fontScale), palette)
                    host.fixtureRoot.setBackgroundColor(palette.background)
                    state = OnboardingPageContentState(
                        OnboardingPermissionStatus(true, false, true, false, true, false, true, true, true),
                        true, true, false, false, true, true, true, true,
                    )
                    actions = OnboardingPageActions(
                        requestPermission = { forbidden++ }, oneShotChanged = { forbidden++ },
                        loopingChanged = { forbidden++ }, compressionChanged = { forbidden++ },
                        skipSilenceChanged = { forbidden++ }, pageChanged = { reportedPages.put(it) },
                    )
                    screen = NativeOnboardingScreenView.create(themed, environment, state, host,
                        actions, { forbidden++ }, runtime.scope, host.onBackPressedDispatcher,
                        NativeOnboardingViewportSavedState(page = 1)).apply {
                        layoutDirection = if (rtl) View.LAYOUT_DIRECTION_RTL else View.LAYOUT_DIRECTION_LTR
                    }
                    host.fixtureRoot.addView(screen, FrameLayout.LayoutParams((widthDp * density).roundToInt(), -1))
                }
                val rendered = checkNotNull(screen)
                awaitNativeParityDraw(instrumentation, rendered)
                nativeParityOnMain(instrumentation) {
                    fun dp(value: Int) = (value * density).roundToInt()
                    check(rendered.width == dp(widthDp)) { "Host did not provide requested fixture width" }
                    check(rendered.saveViewportState().page == 1)
                    val row = responsiveDescendants(rendered).filterIsInstance<OnboardingBufferRow>().first {
                        (it.getChildAt(1) as ReverbParagraphView).getText() == themed.getString(R.string.onboarding_one_shot_title)
                    }
                    val footer = responsiveDescendants(rendered).filterIsInstance<OnboardingFooterView>().single()
                    val title = row.getChildAt(1) as ReverbParagraphView
                    val continueLabel = responsiveDescendants(footer).filterIsInstance<ReverbParagraphView>().single { it.getText() == themed.getString(R.string.onboarding_continue) }
                    entry.put("rendered", JSONObject().put("screen", responsiveBounds(rendered))
                        .put("row", responsiveRowEvidence(row)).put("footer", responsiveFooterEvidence(footer))
                        .put("layout_direction", rendered.layoutDirection))
                    check(title.currentParagraph?.lineCount == 1) { "One-shot still splits at $name" }
                    check(continueLabel.currentParagraph?.lineCount == 1) { "Continue still splits at $name" }
                    check(row.getChildAt(0).width == dp(44) && row.getChildAt(3).width == dp(52))
                    check(row.getChildAt(3).height == dp(32))
                    responsiveAssertNoOverlap(row)
                    responsiveAssertButtons(footer, dp(48))
                    if (widthDp == 480) {
                        check(row.getChildAt(0).left == dp(16))
                        val remaining = footer.width - dp(10)
                        val unit = (remaining / 2f).roundToInt()
                        val expectedBack = unit + (remaining - unit * 2).coerceIn(-1, 1)
                        check(footer.getChildAt(0).width == expectedBack)
                        check(footer.getChildAt(1).width == remaining - expectedBack)
                        check(footer.getChildAt(0).top == footer.getChildAt(1).top)
                        check(footer.height == dp(16) + dp(8) + dp(14) + dp(48))
                        entry.put("normal_geometry_control", "16dp card padding; original equal-weight odd-pixel distribution; original footer height")
                    }
                }
                var completeCapture = false
                nativeParityOnMain(instrumentation) {
                    val location = IntArray(2)
                    val rootLocation = IntArray(2)
                    rendered.getLocationInWindow(location)
                    host.fixtureRoot.getLocationInWindow(rootLocation)
                    completeCapture = location[0] >= rootLocation[0] && location[1] >= rootLocation[1] &&
                        location[0] + rendered.width <= rootLocation[0] + host.fixtureRoot.width &&
                        location[1] + rendered.height <= rootLocation[1] + host.fixtureRoot.height
                    entry.put("physical_host_bounds", responsiveBounds(host.fixtureRoot))
                        .put("capture_scope", if (completeCapture) "complete hardware screen view" else "physical host crop; full requested width qualified by geometry only")
                }
                val bitmap = captureNativeParityContent(instrumentation, host) { if (completeCapture) rendered else host.fixtureRoot }
                try { saveNativeParityBitmap(directory, "$name.png", bitmap) } finally { bitmap.recycle() }
                // Separate controlled layout: real screen parent, explicit zero safe insets, no window-inset observer.
                // These are actual Android component/layout checks; they are not natural fullscreen renders.
                nativeParityOnMain(instrumentation) {
                    fun dp(value: Int) = (value * density).roundToInt()
                    controlled = NativeOnboardingScreenView.create(themed, environment, state, host,
                        actions, { forbidden++ }, runtime.scope, host.onBackPressedDispatcher,
                        NativeOnboardingViewportSavedState(page = 1), observeWindowInsets = false).apply {
                        layoutDirection = if (rtl) View.LAYOUT_DIRECTION_RTL else View.LAYOUT_DIRECTION_LTR
                        setSafeDrawingInsets(Rect())
                    }
                    val sample = checkNotNull(controlled)
                    host.fixtureRoot.removeView(rendered)
                    rendered.close()
                    screen = null
                    host.fixtureRoot.addView(sample, FrameLayout.LayoutParams(dp(widthDp), dp(700)))
                    responsiveMeasure(sample, dp(widthDp), dp(700), View.MeasureSpec.EXACTLY)
                    val row = responsiveDescendants(sample).filterIsInstance<OnboardingBufferRow>().first {
                        (it.getChildAt(1) as ReverbParagraphView).getText() == themed.getString(R.string.onboarding_one_shot_title)
                    }
                    val title = row.getChildAt(1) as ReverbParagraphView
                    val measurements = entry.getJSONArray("measurements")
                    val regular = title.maxIntrinsicTextWidth() + 2 * dp(16) + dp(44) + dp(52) + 2 * dp(14)
                    val compact = title.maxIntrinsicTextWidth() + 2 * dp(12) + dp(44) + dp(52) + 2 * dp(8)
                    for ((label, threshold) in listOf("regular-card" to regular, "compact-card" to compact)) {
                        for (delta in -1..1) {
                            responsiveMeasure(row, threshold + delta, 0, View.MeasureSpec.UNSPECIFIED)
                            measurements.put(JSONObject().put("kind", label).put("delta_px", delta)
                                .put("threshold_px", threshold).put("row", responsiveRowEvidence(row)))
                            check(title.currentParagraph?.lineCount == 1)
                            responsiveAssertNoOverlap(row)
                            val stacked = title.top >= row.getChildAt(0).bottom
                            check(stacked == (label == "compact-card" && delta < 0))
                            val expectedPadding = if (label == "regular-card" && delta >= 0) dp(16) else dp(12)
                            val marker = row.getChildAt(0)
                            check((if (rtl) row.width - marker.right else marker.left) == expectedPadding)
                        }
                    }
                    val footer = responsiveDescendants(sample).filterIsInstance<OnboardingFooterView>().single()
                    val backLabel = responsiveDescendants(footer.getChildAt(0)).filterIsInstance<ReverbParagraphView>().single()
                    val nextLabel = responsiveDescendants(footer.getChildAt(1)).filterIsInstance<ReverbParagraphView>().single()
                    val minimumBack = max(dp(48), backLabel.maxIntrinsicTextWidth() + 2 * dp(12))
                    val minimumNext = max(dp(48), nextLabel.maxIntrinsicTextWidth() + 2 * dp(12))
                    val footerThreshold = minimumBack + minimumNext + dp(10)
                    for (delta in -1..1) {
                        responsiveMeasure(footer, footerThreshold + delta, dp(700), View.MeasureSpec.AT_MOST)
                        measurements.put(JSONObject().put("kind", "compact-footer").put("delta_px", delta)
                            .put("threshold_px", footerThreshold).put("footer", responsiveFooterEvidence(footer)))
                        check((footer.getChildAt(1).top > footer.getChildAt(0).top) == (delta < 0))
                        check(backLabel.currentParagraph?.lineCount == 1 && nextLabel.currentParagraph?.lineCount == 1)
                        responsiveAssertButtons(footer, dp(48))
                    }
                    val narrowWidth = footerThreshold - 1
                    responsiveMeasure(footer, narrowWidth, 0, View.MeasureSpec.UNSPECIFIED)
                    val stackedHeight = footer.height
                    check(footer.getChildAt(1).top > footer.getChildAt(0).top)
                    if (fontScale <= 1.3f) {
                        responsiveMeasure(footer, narrowWidth, dp(120), View.MeasureSpec.AT_MOST)
                        measurements.put(JSONObject().put("kind", "footer-120dp").put("footer", responsiveFooterEvidence(footer)))
                        check(footer.getChildAt(1).top == footer.getChildAt(0).top)
                        responsiveAssertButtons(footer, dp(48))
                    } else {
                        measurements.put(JSONObject().put("kind", "footer-120dp").put("scope", "not asserted at font2: preexisting horizontal wrapped-label height may exceed120dp"))
                    }
                    for (reserve in listOf(0, 1, dp(48))) {
                        val budget = stackedHeight + reserve
                        responsiveMeasure(sample, narrowWidth + 2 * dp(24), budget + 2 * dp(20), View.MeasureSpec.EXACTLY)
                        val pageHeight = footer.top - dp(20)
                        val stacked = footer.getChildAt(1).top > footer.getChildAt(0).top
                        measurements.put(JSONObject().put("kind", "controlled-screen-content-reserve")
                            .put("safe_insets", "explicit zero; window observer disabled")
                            .put("content_budget_px", budget).put("stacked_footer_desired_px", stackedHeight)
                            .put("reserve_px", reserve).put("page_height_px", pageHeight)
                            .put("footer", responsiveFooterEvidence(footer)))
                        check(stacked == (reserve == dp(48)))
                        check(pageHeight > 0) { "Footer exhausted controlled screen content budget" }
                        if (stacked) check(pageHeight == dp(48))
                        check(sample.height - dp(20) == footer.bottom)
                        responsiveAssertButtons(footer, dp(48))
                    }
                    check(forbidden == 0) { "Static fixture unexpectedly invoked a user action" }
                }
                entry.put("passed", true)
                publish()
            } catch (error: Throwable) {
                caseFailure = error
                throw error
            } finally {
                var cleanupFailure: Throwable? = null
                nativeParityOnMain(instrumentation) {
                    for (cleanup in listOf<() -> Unit>(
                        { screen?.close() }, { controlled?.close() }, { runtime.close() }, { host.finish() },
                    )) {
                        try { cleanup() } catch (error: Throwable) {
                            val previous = cleanupFailure
                            if (previous == null) cleanupFailure = error else previous.addSuppressed(error)
                        }
                    }
                }
                cleanupFailure?.let { cleanup -> caseFailure?.addSuppressed(cleanup) ?: throw cleanup }
            }
        }
        check(getRecorderPreferences(context).all.toMap() == original)
        result.put("preferences_unchanged", true).put("passed", true)
    } catch (error: Throwable) {
        failure = error
        result.put("failure", error.stackTraceToString())
    } finally {
        result.put("preferences_unchanged", getRecorderPreferences(context).all.toMap() == original)
        publish()
        report.append("NATIVE_ONBOARDING_RESPONSIVE_EVIDENCE ").append(directory.absolutePath).append('\n')
    }
    failure?.let { throw it }
    report.append("NATIVE_ONBOARDING_RESPONSIVE_PASS\n")
}

private fun responsiveDescendants(view: View): Sequence<View> = sequence {
    yield(view)
    if (view is ViewGroup) for (index in 0 until view.childCount) yieldAll(responsiveDescendants(view.getChildAt(index)))
}

private fun responsiveMeasure(view: View, width: Int, height: Int, heightMode: Int) {
    view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
        View.MeasureSpec.makeMeasureSpec(height, heightMode))
    view.layout(0, 0, view.measuredWidth, view.measuredHeight)
}

private fun responsiveBounds(view: View) = JSONArray(listOf(view.left, view.top, view.right, view.bottom))

private fun responsiveRowEvidence(row: OnboardingBufferRow): JSONObject = JSONObject()
    .put("bounds", responsiveBounds(row)).put("marker", responsiveBounds(row.getChildAt(0)))
    .put("title", responsiveBounds(row.getChildAt(1))).put("body", responsiveBounds(row.getChildAt(2)))
    .put("switch", responsiveBounds(row.getChildAt(3)))
    .put("title_lines", (row.getChildAt(1) as ReverbParagraphView).currentParagraph?.lineCount)

private fun responsiveFooterEvidence(footer: OnboardingFooterView): JSONObject = JSONObject()
    .put("bounds", responsiveBounds(footer)).put("back", responsiveBounds(footer.getChildAt(0)))
    .put("continue", responsiveBounds(footer.getChildAt(1)))
    .put("label_lines", JSONArray(responsiveDescendants(footer).filterIsInstance<ReverbParagraphView>()
        .map { JSONObject().put("text", it.getText()).put("lines", it.currentParagraph?.lineCount) }.toList()))

private fun responsiveAssertNoOverlap(row: OnboardingBufferRow) {
    val views = (0..3).map { row.getChildAt(it) }
    for (index in views.indices) for (other in index + 1 until views.size) {
        val first = views[index]
        val second = views[other]
        check(!Rect.intersects(Rect(first.left, first.top, first.right, first.bottom),
            Rect(second.left, second.top, second.right, second.bottom))) { "Card content overlaps" }
    }
    views.forEach { check(it.left >= 0 && it.top >= 0 && it.right <= row.width && it.bottom <= row.height) }
}

private fun responsiveAssertButtons(footer: OnboardingFooterView, minimumHeight: Int) {
    val back = footer.getChildAt(0)
    val next = footer.getChildAt(1)
    check(back.height >= minimumHeight && next.height >= minimumHeight)
    check(!Rect.intersects(Rect(back.left, back.top, back.right, back.bottom),
        Rect(next.left, next.top, next.right, next.bottom)))
    listOf(back, next).forEach {
        check(it.left >= 0 && it.top >= 0 && it.right <= footer.width && it.bottom <= footer.height)
    }
}
