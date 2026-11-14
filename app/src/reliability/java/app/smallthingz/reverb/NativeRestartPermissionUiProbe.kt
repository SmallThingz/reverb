package app.smallthingz.reverb

import smallthingz.ui.components.NativeActionSheet

import android.app.Application
import android.app.Instrumentation
import android.content.ComponentName
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Display
import android.view.ContextThemeWrapper
import android.view.PixelCopy
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import androidx.lifecycle.Lifecycle
import app.smallthingz.reverb.nativeui.*
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.json.JSONArray
import org.json.JSONObject
import smallthingz.ui.text.font.createFontFamilyResolver
import smallthingz.ui.text.view.ParagraphView
import smallthingz.ui.unit.Density

/** Actual presentation only: disabled boot components, inert callbacks, no service or permission launch. */
internal fun verifyNativeRestartPermissionUi(instrumentation: Instrumentation, report: StringBuilder) {
    val context = instrumentation.targetContext
    check(context.packageName == "app.smallthingz.reverb.uitheme.reliability")
    check(context.applicationContext.javaClass == Application::class.java)
    val flags = PackageManager.MATCH_DISABLED_COMPONENTS
    @Suppress("DEPRECATION")
    check(!context.packageManager.getActivityInfo(ComponentName(context, BootResumeActivity::class.java), flags).enabled)
    @Suppress("DEPRECATION")
    check(!context.packageManager.getReceiverInfo(ComponentName(context, BootCompletedReceiver::class.java), flags).enabled)
    val preferences = getRecorderPreferences(context).all.toMap()
    val directory = File(context.filesDir, "native-restart-permission-ui-${System.currentTimeMillis()}")
    check(directory.mkdir())
    val cases = JSONArray()
    val result = JSONObject().put("selector", "native_restart_permission_ui").put("sdk", Build.VERSION.SDK_INT)
        .put("passed", false).put("cases", cases).put("boot_components_disabled", true)
        .put("scope", "Exact boot content and startup sheet presentation/accessibility callbacks; not real reboot, PCM admission or owner lifecycle")
    val host = launchCurrentUiSmokeActivity(instrumentation)
    var sheet: CaptureTextSheet? = null
    var failure: Throwable? = null
    fun <T> main(block: () -> T) = nativeParityOnMain(instrumentation, block)
    try {
        for (dark in listOf(true, false)) {
            val theme = if (dark) "dark" else "light"
            val palette = ReverbPalette.fallback(dark)
            val content = main {
                BootResumeContentView(host, palette).also {
                    host.fixtureRoot.removeAllViews()
                    host.fixtureRoot.addView(it, ViewGroup.LayoutParams(-1, -1))
                    androidx.core.view.ViewCompat.requestApplyInsets(it)
                }
            }
            var pendingBrandBounds: Rect? = null
            for (completed in listOf(false, true)) {
                if (completed) main { content.showSuccess() }
                repeat(3) { awaitNativeParityDraw(instrumentation, content) }
                val entry = main {
                    val brand = restartUiViews(content).filterIsInstance<ReverbBrandMarkView>().single()
                    check(brand.isShown && brand.contentDescription == context.getString(R.string.app_name))
                    val brandBounds = Rect()
                    check(brand.getGlobalVisibleRect(brandBounds))
                    if (completed) check(brandBounds == pendingBrandBounds) {
                        "Resume success moved the brand: $pendingBrandBounds -> $brandBounds"
                    } else pendingBrandBounds = brandBounds
                    val title = restartUiViews(content).filterIsInstance<TextView>().single {
                        it.text.toString() == context.getString(if (completed) R.string.boot_resume_success else R.string.boot_resume_in_progress)
                    }
                    check(title.isAccessibilityHeading && title.isShown)
                    check(restartUiViews(content).none { it.isClickable })
                    val progress = restartUiViews(content).filterIsInstance<ProgressBar>().single()
                    check(progress.visibility == if (completed) View.INVISIBLE else View.VISIBLE)
                    JSONObject().put("name", "boot-$theme-${if (completed) "success" else "pending"}")
                        .put("heading_accessible", true).put("open_action_present", false)
                        .put("reverb_brand_visible", true).put("cancel_action_present", false)
                        .put("brand_bounds", brandBounds.toShortString())
                        .put("boot_activity_launched", false)
                        .also { if (completed) it.put("success_keeps_brand_position", true) }
                }
                val bitmap = captureNativeParityContent(instrumentation, host) { content }
                try { saveNativeParityBitmap(directory, entry.getString("name") + "-hardware.png", bitmap) }
                finally { bitmap.recycle() }
                cases.put(entry)
            }
            var dismissed = 0
            var reviewed = 0
            lateinit var closeButton: LibrarySheetButton
            lateinit var reviewButton: LibrarySheetButton
            val current = main {
                val environment = ReverbViewEnvironment(Density(host), createFontFamilyResolver(host), palette)
                CaptureTextSheet(content, environment,
                    context.getString(R.string.onboarding_restart_resume_title),
                    context.getString(R.string.onboarding_restart_resume_body),
                    { dismissed++; sheet?.close() },
                ) { actions ->
                    closeButton = LibrarySheetButton(host, actions, environment, false, context.getString(R.string.restart_access_not_now), onClick = {
                        dismissed++; sheet?.close()
                    })
                    reviewButton = LibrarySheetButton(host, actions, environment, true, context.getString(R.string.background_review), onClick = {
                        reviewed++; sheet?.close()
                    })
                    listOf(closeButton, reviewButton)
                }.also { sheet = it; it.show() }
            }
            try {
                val dialog = main { restartUiField(current, "dialog") as NativeActionSheet }
                val state = main { checkNotNull(restartUiField(dialog, "state")) }
                val deadline = SystemClock.uptimeMillis() + 8000
                while (!main { restartUiField(state, "currentValue").toString() == "EXPANDED" &&
                    restartUiField(state, "dragTarget") == null && dialog.hasCurrentWindowFocus }) {
                    check(SystemClock.uptimeMillis() < deadline) { "Startup invitation did not settle in its owned window" }
                    awaitNativeParityDraw(instrumentation, dialog.contentContext.inputRoot)
                }
                val buttonSemantics = main {
                    val facts = JSONArray()
                    for ((button, label) in listOf(closeButton to context.getString(R.string.restart_access_not_now),
                        reviewButton to context.getString(R.string.background_review))) {
                        check(button.isAccessibilityNodePresent())
                        val surface = button.accessibilitySemanticView as ViewGroup
                        val children = ArrayList<View>().also(surface::addChildrenForAccessibility)
                        check(children.size == 2 && children[0] is ParagraphView)
                        @Suppress("DEPRECATION")
                        val node = checkNotNull(surface.createAccessibilityNodeInfo())
                        try {
                            check(node.isClickable && node.isEnabled && node.isVisibleToUser)
                            check(node.actionList.any { it.id == AccessibilityNodeInfo.ACTION_CLICK })
                            check(node.childCount == children.size)
                            // The public merged-row graph carries its name on the advertised label child.
                            val labelNode = checkNotNull(children[0].createAccessibilityNodeInfo())
                            try {
                                check(labelNode.isVisibleToUser && labelNode.isEnabled && labelNode.isImportantForAccessibility)
                                check(labelNode.text?.toString() == label)
                                val roleNode = checkNotNull(children[1].createAccessibilityNodeInfo())
                                try {
                                    check(roleNode.isVisibleToUser && roleNode.className?.toString() == "android.widget.Button")
                                    check(!roleNode.isClickable)
                                    facts.put(JSONObject().put("label", labelNode.text.toString())
                                        .put("label_visible", true).put("parent_clickable", true)
                                        .put("advertised_children", node.childCount).put("role", roleNode.className.toString()))
                                } finally { @Suppress("DEPRECATION") roleNode.recycle() }
                            } finally { @Suppress("DEPRECATION") labelNode.recycle() }
                        } finally { @Suppress("DEPRECATION") node.recycle() }
                    }
                    facts
                }
                val bitmap = captureRestartSheet(instrumentation, host, checkNotNull(dialog.window))
                try { saveNativeParityBitmap(directory, "startup-invitation-$theme-hardware.png", bitmap) }
                finally { bitmap.recycle() }
                main {
                    val action = if (dark) closeButton else reviewButton
                    check(action.accessibilitySemanticView.performAccessibilityAction(AccessibilityNodeInfo.ACTION_CLICK, null))
                    check(!dialog.isShowing)
                    check(dismissed == if (dark) 1 else 0)
                    check(reviewed == if (dark) 0 else 1)
                }
                cases.put(JSONObject().put("name", "startup-invitation-$theme")
                    .put("buttons_accessible", true).put("callback_once", true).put("window_retired", true)
                    .put("button_semantics", buttonSemantics)
                    .put("action", if (dark) "not-now" else "review").put("permission_activity_launched", false))
            } finally { main { current.close(); sheet = null } }
            awaitNativeParityDraw(instrumentation, content)
            requireNativeParityHost(instrumentation, host)
        }
        val stressed = main {
            val configuration = Configuration(host.resources.configuration).apply { fontScale = 1.8f }
            val styled = ContextThemeWrapper(host.createConfigurationContext(configuration), R.style.Theme_Reverb_Dark)
            BootResumeContentView(styled, ReverbPalette.fallback(true)).also {
                host.fixtureRoot.removeAllViews()
                val density = styled.resources.displayMetrics.density
                host.fixtureRoot.addView(it, ViewGroup.LayoutParams(
                    minOf(host.fixtureRoot.width, (320 * density).toInt()),
                    minOf(host.fixtureRoot.height, (180 * density).toInt())))
                it.showSuccess()
                androidx.core.view.ViewCompat.requestApplyInsets(it)
            }
        }
        repeat(3) { awaitNativeParityDraw(instrumentation, stressed) }
        main {
            val status = restartUiViews(stressed).filterIsInstance<TextView>().single()
            status.requestRectangleOnScreen(Rect(0, 0, status.width, status.height), true)
        }
        val stressBitmap = captureNativeParityContent(instrumentation, host) { stressed }
        try { saveNativeParityBitmap(directory, "boot-compact-large-font-hardware.png", stressBitmap) }
        finally { stressBitmap.recycle() }
        cases.put(main {
            val status = restartUiViews(stressed).filterIsInstance<TextView>().single()
            val visible = Rect()
            check(status.getGlobalVisibleRect(visible) && visible.width() == status.width && visible.height() == status.height)
            val scroll = restartUiViews(stressed).filterIsInstance<ScrollView>().single()
            check(scroll.scrollY > 0)
            val layout = checkNotNull(status.layout)
            for (line in 0 until layout.lineCount) {
                check(layout.getEllipsisCount(line) == 0 && layout.getLineLeft(line) >= 0f && layout.getLineRight(line) <= layout.width)
            }
            JSONObject().put("name", "boot-compact-large-font").put("font_scale", 1.8)
                .put("width", stressed.width).put("height", stressed.height).put("scroll_y", scroll.scrollY)
                .put("status_fully_visible", true).put("status_lines", layout.lineCount)
                .put("status_horizontal_clipping", false).put("global_configuration_changed", false)
        })
        result.put("passed", true)
    } catch (error: Throwable) { failure = error }
    finally {
        failure = captureNativeParityCleanup(failure) { main { sheet?.close(); host.finish() } }
        failure = captureNativeParityCleanup(failure) { check(getRecorderPreferences(context).all == preferences) }
        if (failure != null) result.put("passed", false).put("failure", failure!!.stackTraceToString())
        failure = captureNativeParityCleanup(failure) {
            writeQaEvidenceAtomically(File(directory, "result.json")) { it.write(result.toString(2).toByteArray()) }
        }
        report.appendLine("RESTART_PERMISSION_UI_EVIDENCE ${directory.absolutePath}")
    }
    failure?.let { throw it }
    report.appendLine("PASS native_restart_permission_ui exact boot content and startup invitation rendering, accessibility and callbacks")
}

private fun restartUiField(owner: Any, name: String): Any? = owner.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(owner)
private fun restartUiViews(view: View): Sequence<View> = sequence {
    yield(view)
    if (view is ViewGroup) for (index in 0 until view.childCount) yieldAll(restartUiViews(view.getChildAt(index)))
}

/** Same bounded PixelCopy/late-callback ownership as existing parity captures, for this exact dialog window. */
private fun captureRestartSheet(instrumentation: Instrumentation, host: NativeParityActivity, window: Window): Bitmap {
    fun guardOnMain() {
        val root = window.decorView
        check(!host.isFinishing && host.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
        check(root.isAttachedToWindow && root.isShown && root.hasWindowFocus() && root.isHardwareAccelerated)
        check(root.display?.displayId == Display.DEFAULT_DISPLAY && root.isLaidOut && !root.isLayoutRequested)
    }
    fun guard() = nativeParityOnMain(instrumentation) { guardOnMain() }
    guard()
    awaitNativeParityDraw(instrumentation, window.decorView)
    val rectangle = nativeParityOnMain(instrumentation) { Rect(0, 0, window.decorView.width, window.decorView.height) }
    val bitmap = Bitmap.createBitmap(rectangle.width(), rectangle.height(), Bitmap.Config.ARGB_8888)
    val done = CountDownLatch(1)
    var status = Int.MIN_VALUE
    var submitted = false
    var recycleLater = false
    try {
        nativeParityOnMain(instrumentation) {
            guardOnMain()
            PixelCopy.request(window, rectangle, bitmap, { code ->
                status = code; done.countDown(); if (recycleLater) bitmap.recycle()
            }, Handler(Looper.getMainLooper()))
            submitted = true
        }
        check(done.await(5, TimeUnit.SECONDS)) { "Startup invitation PixelCopy timed out" }
        check(status == PixelCopy.SUCCESS) { "Startup invitation PixelCopy failed: $status" }
        guard()
        return bitmap
    } catch (error: Throwable) {
        try {
            nativeParityOnMain(instrumentation) { if (!submitted || done.count == 0L) bitmap.recycle() else recycleLater = true }
        } catch (cleanup: Throwable) {
            error.addSuppressed(cleanup)
        }
        throw error
    }
}
