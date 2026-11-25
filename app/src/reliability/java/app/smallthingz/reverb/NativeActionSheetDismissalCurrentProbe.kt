package app.smallthingz.reverb

import android.app.Application
import android.app.Instrumentation
import android.graphics.Point
import android.graphics.Rect
import android.graphics.drawable.ColorDrawable
import android.os.SystemClock
import android.view.Display
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Space
import android.widget.TextView
import androidx.lifecycle.Lifecycle
import app.smallthingz.reverb.nativeui.ReverbPalette
import app.smallthingz.reverb.nativeui.ReverbViewEnvironment
import app.smallthingz.reverb.nativeui.createReverbActionSheet
import java.io.File
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.roundToInt
import org.json.JSONArray
import org.json.JSONObject
import smallthingz.ui.components.NativeActionSheet
import smallthingz.ui.components.NativeFloatingDialog
import smallthingz.ui.components.NativeFloatingDialogContent
import smallthingz.ui.components.NativeModalPaintedSurface
import smallthingz.ui.components.NativeSheetDragHandle
import smallthingz.ui.controls.textSurfaceRange
import smallthingz.ui.graphics.NoiseDrawable
import smallthingz.ui.interaction.view.readCachedAnimatorDurationScale
import smallthingz.ui.text.font.createFontFamilyResolver
import smallthingz.ui.unit.Density

/** Instance-scoped AndroidX Back completion and dialog touch; no process-global input. */
internal fun verifyNativeActionSheetDismissalCurrent(instrumentation: Instrumentation, report: StringBuilder) {
    val context = instrumentation.targetContext
    check(context.packageName == "app.smallthingz.reverb.uitheme.reliability")
    check(context.applicationContext.javaClass == Application::class.java)
    val beforePreferences = getRecorderPreferences(context).all.toMap()
    val directory = File(context.filesDir, "native-action-sheet-dismissal-${System.currentTimeMillis()}")
    check(directory.mkdir())
    val cases = JSONArray()
    val scrimTargets = JSONArray()
    val handleGeometry = JSONArray()
    val handleTargets = JSONArray()
    val surfacePaint = JSONArray()
    val backgroundOwnership = JSONArray()
    val handleIndication = JSONArray()
    val result = JSONObject().put("selector", "native_action_sheet_dismissal_current")
        .put("passed", false).put("cases", cases).put("scrim_targets", scrimTargets)
        .put("handle_geometry", handleGeometry).put("handle_targets", handleTargets).put("surface_paint", surfacePaint)
        .put("background_ownership", backgroundOwnership)
        .put("handle_indication", handleIndication)
        .put("scope", "Reverb action sheet thin handle, accessibility dismiss, Back, scrim, and content drag admission; floating dialog retirement")
        .put("input_route", "Owned dialog Back dispatcher and instance touch dispatch")
        .put("unqualified", JSONArray(listOf("physical/system Back delivery", "platform OnBackInvoked registration/delivery",
            "predictive system Back gesture", "IME", "production recorder")))
    val host = launchCurrentUiSmokeActivity(instrumentation)
    var currentSheet: NativeActionSheet? = null
    var currentFloating: NativeFloatingDialog? = null
    var failure: Throwable? = null
    fun <T> main(block: () -> T) = nativeParityOnMain(instrumentation, block)
    try {
        val anchor = main {
            TextView(host).also {
                it.text = "Action sheet dismissal fixture"
                host.fixtureRoot.removeAllViews()
                host.fixtureRoot.addView(it, ViewGroup.LayoutParams(-1, -1))
            }
        }
        awaitNativeParityDraw(instrumentation, anchor)
        var lightHandleGeometry: List<Int>? = null
        for (route in listOf("androidx-back-dispatcher", "scrim", "content-drag", "scroll-content-drag",
            "handle-above", "handle-below", "generic-custom", "generic-grain")) {
            val dragRoute = route.endsWith("content-drag")
            val handleRoute = route.startsWith("handle-")
            val genericRoute = route.startsWith("generic-")
            val flatPaintRoute = route == "androidx-back-dispatcher" || route == "scrim"
            val customBackground = if (route == "generic-custom") ColorDrawable(0xff675142.toInt()) else null
            requireNativeParityHost(instrumentation, host, "before-$route-sheet")
            var busy = true
            var ownerDismissals = 0
            var windowDismissals = 0
            lateinit var dialog: NativeActionSheet
            lateinit var body: TextView
            main {
                val environment = ReverbViewEnvironment(Density(host), createFontFamilyResolver(host), ReverbPalette.fallback(route != "androidx-back-dispatcher"))
                val dismiss = {
                    ownerDismissals++
                    dialog.dismiss()
                }
                dialog = if (genericRoute) {
                    val style = smallthingz.ui.reverb.reverbActionSheetStyle(environment.theme).let {
                        it.copy(recipe = it.recipe.copy(grainSeed = NoiseDrawable.SEED_BACKGROUND))
                    }
                    NativeActionSheet(anchor, style, "Dismissal admission", dismiss,
                        closeIcon = null, contentBackground = customBackground)
                } else createReverbActionSheet(anchor, environment, "Dismissal admission", dismiss)
                if (flatPaintRoute) {
                    val style = dialog.style
                    dialog.updateStyle(style.copy(recipe = style.recipe.copy(grainSeed = null)))
                }
                currentSheet = dialog
                body = TextView(host).apply {
                    text = "Busy operations must keep this window open."
                    minHeight = ceil(96 * host.resources.displayMetrics.density).toInt()
                }
                val actions = Space(host)
                dialog.setContent(body, actions, emptyList(), scrollable = route == "scroll-content-drag")
                dialog.setDismissalAdmission { !busy }
                dialog.setOnDismissListener { windowDismissals++ }
                dialog.show()
            }
            val decor = main { checkNotNull(dialog.window).decorView }
            val deadline = SystemClock.uptimeMillis() + 8000
            while (!main { dialog.hasCurrentWindowFocus && actionSheetFixtureExpanded(dialog) }) {
                check(SystemClock.uptimeMillis() < deadline) { "$route sheet did not settle in its own window" }
                awaitNativeParityDraw(instrumentation, decor)
            }
            fun guard() {
                check(host.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) && !host.isFinishing)
                check(dialog.isShowing && dialog.hasCurrentWindowFocus)
                check(decor.isAttachedToWindow && decor.isShown && decor.isHardwareAccelerated)
                check(decor.display?.displayId == Display.DEFAULT_DISPLAY)
            }
            val expandedBounds = main {
                guard()
                val descendants = descendantsOf(dialog.contentView)
                val handle = descendants.filterIsInstance<NativeSheetDragHandle>().single()
                val closeText = context.getString(smallthingz.ui.R.string.close_sheet)
                check(descendants.none { it.contentDescription == closeText }) { "Sheet installed an X action" }
                val node = checkNotNull(handle.createAccessibilityNodeInfo())
                try {
                    check(node.actionList.any { it.id == AccessibilityNodeInfo.ACTION_DISMISS })
                    val mark = handle.getChildAt(0)
                    val density = host.resources.displayMetrics.density
                    val column = handle.parent as ViewGroup
                    val content = (0 until column.childCount).map(column::getChildAt).single { it !== handle }
                    val expectedHeight = 2 * (16 * density).roundToInt() + (2 * density).roundToInt().coerceAtLeast(1)
                    val expectedTop = ceil((48 * density - expectedHeight).coerceAtLeast(0f) / 2f).toInt()
                    val bounds = Rect().also(node::getBoundsInScreen)
                    handleGeometry.put(JSONObject().put("route", route).put("density", density)
                        .put("handle_height", handle.height).put("handle_top", handle.top).put("content_top", content.top)
                        .put("mark_width", mark.width).put("mark_height", mark.height)
                        .put("expected_handle_height", expectedHeight).put("expected_content_top", expectedTop + expectedHeight)
                        .put("nominal_content_top_dp", 41).put("accessible_bounds", bounds.toShortString()))
                    check(mark.height in 1 until ceil(4 * density).toInt() && mark.width > mark.height)
                    check(handle.height < 40 * density) { "Handle still adds the old tall spacer" }
                    check(handle.height == expectedHeight && handle.top == expectedTop &&
                        content.top == expectedTop + expectedHeight) { "Compact handle/content spacing differs from rounded 41dp recipe" }
                    check(bounds.height() >= (48 * density).toInt()) { "Compact handle lost its accessible touch target" }
                    check(handle.background == null && mark.background == null)
                    if (flatPaintRoute) {
                        val geometry = listOf(handle.left, handle.top, handle.width, handle.height,
                            mark.left, mark.top, mark.width, mark.height, content.top)
                        if (route == "androidx-back-dispatcher") lightHandleGeometry = geometry
                        else check(geometry == checkNotNull(lightHandleGeometry)) { "Light and Dark handle geometry differs" }
                    }
                } finally { node.recycle() }
                Rect().also { check(dialog.contentView.getGlobalVisibleRect(it) && !it.isEmpty) }
            }
            if (genericRoute) main {
                guard()
                val handle = descendantsOf(dialog.contentView).filterIsInstance<NativeSheetDragHandle>().single()
                val column = handle.parent as ViewGroup
                val content = (0 until column.childCount).map(column::getChildAt).single { it !== handle }
                val initial = dialog.style
                val paintedSurface = generateSequence<View>(column) { it.parent as? View }
                    .filterIsInstance<NativeModalPaintedSurface>().first()
                val texturedRange = paintedSurface.paintedTextSurface
                val evidence = JSONObject().put("route", route).put("passed", false)
                    .put("paint_owner", paintedSurface.javaClass.name)
                    .put("column_background", column.background?.javaClass?.name ?: JSONObject.NULL)
                    .put("body_background", content.background?.javaClass?.name ?: JSONObject.NULL)
                backgroundOwnership.put(evidence)
                for (seed in listOf<Int?>(null, NoiseDrawable.SEED_TOP_BAR, initial.recipe.grainSeed)) {
                    dialog.updateStyle(initial.copy(recipe = initial.recipe.copy(grainSeed = seed)))
                    check(content.background == null && handle.background == null) {
                        "Sheet background belongs to a body/handle sibling"
                    }
                    check(column.background === customBackground) { "Sheet column changed its caller background" }
                    if (customBackground != null) check(customBackground.callback === column)
                    val range = paintedSurface.paintedTextSurface
                    val expected = if (seed == null) textSurfaceRange(initial.palette.surfaceContainerLow)
                        else texturedRange
                    check(range == expected) { "Shared surface paint did not follow the grain recipe" }
                    if (seed == null) check(range != texturedRange) { "Textured fixture had a flat paint range" }
                }
                dialog.updateStyle(initial)
                evidence.put("seed_disable_reenable_preserved_owner", true).put("passed", true)
            }
            if (flatPaintRoute || genericRoute) {
                val paintCase = JSONObject().put("route", route).put("passed", false)
                surfacePaint.put(paintCase)
                val bitmap = captureRestartSheet(instrumentation, host, checkNotNull(dialog.window))
                try {
                    val filename = "${if (route == "generic-grain") "grain" else "plain"}-sheet-$route.png"
                    saveNativeParityBitmap(directory, filename, bitmap)
                    paintCase.put("png", filename).put("bitmap_width", bitmap.width).put("bitmap_height", bitmap.height)
                    val samples = main {
                        val location = IntArray(2)
                        dialog.contentView.getLocationInWindow(location)
                        val density = host.resources.displayMetrics.density
                        // Same x, clear of glyph, title and body text at the right surface edge.
                        val x = location[0] + dialog.contentView.width - (12 * density).roundToInt()
                        intArrayOf(x, location[1] + (24 * density).toInt(), location[1] + (70 * density).toInt())
                    }
                    paintCase.put("window_x", samples[0]).put("handle_window_y", samples[1])
                        .put("body_window_y", samples[2]).put("patch_radius_px", 1)
                    check(samples[0] in 1 until bitmap.width - 1 &&
                        samples[1] in 1 until bitmap.height - 1 && samples[2] in 1 until bitmap.height - 1)
                    val handlePixels = JSONArray()
                    val bodyPixels = JSONArray()
                    val expected = bitmap.getPixel(samples[0], samples[1])
                    var mismatches = 0
                    for (dy in -1..1) for (dx in -1..1) {
                        val upper = bitmap.getPixel(samples[0] + dx, samples[1] + dy)
                        val lower = bitmap.getPixel(samples[0] + dx, samples[2] + dy)
                        handlePixels.put(upper); bodyPixels.put(lower)
                        if (upper != expected || lower != expected) mismatches++
                    }
                    paintCase.put("handle_argb", handlePixels).put("body_argb", bodyPixels).put("mismatches", mismatches)
                    if (route != "generic-grain") {
                        check(mismatches == 0) { "Handle and dialog body have different backgrounds" }
                        if (customBackground != null) check(expected == customBackground.color) { "Shared custom background was replaced by theme paint" }
                    }
                    paintCase.put("oracle", if (route == "generic-grain") "Shared owner asserted; textured PNG retained for visual review" else "Uniform clear patches")
                    paintCase.put("passed", true)
                } finally { bitmap.recycle() }
            }
            if (flatPaintRoute) {
                verifySheetHandleIndication(instrumentation, host, dialog, body, directory, route, handleIndication)
            }
            fun dispatchOrdinary(cancelled: Boolean = false) = main {
                guard()
                if (handleRoute) {
                    val handle = descendantsOf(dialog.contentView).filterIsInstance<NativeSheetDragHandle>().single()
                    val origin = Point()
                    val windowBounds = Rect().also { check(decor.getGlobalVisibleRect(it, origin) && !it.isEmpty) }
                    val bounds = Rect().also { check(handle.getGlobalVisibleRect(it) && !it.isEmpty) }
                    val expansion = (48 * host.resources.displayMetrics.density - handle.height) / 2f
                    val x = bounds.exactCenterX() - origin.x
                    val globalY = if (route == "handle-above") bounds.top - expansion / 2f else bounds.bottom + expansion / 2f
                    val y = globalY - origin.y
                    val target = JSONObject().put("route", route).put("busy", busy).put("cancelled", cancelled)
                        .put("visual_bounds", bounds.toShortString()).put("window_bounds", windowBounds.toShortString())
                        .put("decor_origin_x", origin.x).put("decor_origin_y", origin.y)
                        .put("dispatch_x", x).put("dispatch_y", y).put("expansion_px", expansion)
                    handleTargets.put(target)
                    check(expansion > 0 && (globalY < bounds.top || globalY > bounds.bottom))
                    check(x >= 0 && x < decor.width && y >= 0 && y < decor.height)
                    val now = SystemClock.uptimeMillis()
                    for (action in listOf(MotionEvent.ACTION_DOWN, if (cancelled) MotionEvent.ACTION_CANCEL else MotionEvent.ACTION_UP)) {
                        val contact = MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_FINGER }
                        val position = MotionEvent.PointerCoords().apply { this.x = x; this.y = y; pressure = 1f; size = 1f }
                        val event = MotionEvent.obtain(now, now + if (action == MotionEvent.ACTION_DOWN) 0 else 16,
                            action, 1, arrayOf(contact), arrayOf(position), 0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0)
                        try { check(dialog.dispatchTouchEvent(event)) { "$route expanded finger event was not routed" } }
                        finally { event.recycle() }
                    }
                } else if (route == "androidx-back-dispatcher") {
                    // Direct key dispatch skips the modern framework Back input stage.
                    check(dialog.onBackPressedDispatcher.hasEnabledCallbacks())
                    dialog.onBackPressedDispatcher.onBackPressed()
                } else {
                    val now = SystemClock.uptimeMillis()
                    // Both visible rectangles and this origin use the same root coordinates.
                    val origin = Point()
                    val windowBounds = Rect().also { check(decor.getGlobalVisibleRect(it, origin) && !it.isEmpty) }
                    val bounds = Rect().also { check(dialog.contentView.getGlobalVisibleRect(it) && !it.isEmpty) }
                    val clipped = Rect(bounds).also { check(it.intersect(windowBounds)) }
                    val margin = ceil(24 * host.resources.displayMetrics.density).toInt()
                    val regions = listOf(
                        "top" to Rect(windowBounds.left, windowBounds.top, windowBounds.right, clipped.top),
                        "bottom" to Rect(windowBounds.left, clipped.bottom, windowBounds.right, windowBounds.bottom),
                        "left" to Rect(windowBounds.left, windowBounds.top, clipped.left, windowBounds.bottom),
                        "right" to Rect(clipped.right, windowBounds.top, windowBounds.right, windowBounds.bottom),
                    )
                    val target = regions.mapNotNull { (side, region) ->
                        Rect(region).apply { inset(margin, margin) }.takeUnless { it.isEmpty }?.let { side to it }
                    }.maxByOrNull { (_, region) -> minOf(region.width(), region.height()) }
                    checkNotNull(target) { "No safe measured scrim area: window=$windowBounds surface=$bounds margin=$margin" }
                    val (side, safeBounds) = target
                    val x = safeBounds.exactCenterX() - origin.x
                    val y = safeBounds.exactCenterY() - origin.y
                    check(x >= 0f && x < decor.width && y >= 0f && y < decor.height)
                    scrimTargets.put(JSONObject().put("busy", busy).put("side", side)
                        .put("coordinate_space", "root rectangles; dispatch coordinates relative to decor")
                        .put("window_bounds", windowBounds.toShortString()).put("surface_bounds", bounds.toShortString())
                        .put("safe_scrim_bounds", safeBounds.toShortString()).put("margin_px", margin)
                        .put("decor_origin_x", origin.x).put("decor_origin_y", origin.y)
                        .put("dispatch_x", x).put("dispatch_y", y))
                    for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
                        val event = MotionEvent.obtain(now, now, action, x, y, 0).apply {
                            source = InputDevice.SOURCE_TOUCHSCREEN
                        }
                        try { check(dialog.dispatchTouchEvent(event)) { "$route event was not consumed" } }
                        finally { event.recycle() }
                    }
                }
            }
            fun dragFromBody(expectMotion: Boolean, cancelled: Boolean = false) {
                val points = main {
                    guard()
                    val origin = Point()
                    val windowBounds = Rect().also { check(decor.getGlobalVisibleRect(it, origin)) }
                    val bodyBounds = Rect().also { check(body.getGlobalVisibleRect(it) && !it.isEmpty) }
                    val x = bodyBounds.exactCenterX() - origin.x
                    val start = bodyBounds.top + bodyBounds.height() * .2f - origin.y
                    val end = windowBounds.bottom - origin.y - 1f
                    check(end - start > android.view.ViewConfiguration.get(host).scaledTouchSlop * 2)
                    Triple(x, start, end)
                }
                val (x, start, end) = points
                val downTime = SystemClock.uptimeMillis()
                fun send(action: Int, y: Float, offsetMillis: Long) = main {
                    guard()
                    val contact = MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_FINGER }
                    val position = MotionEvent.PointerCoords().apply { this.x = x; this.y = y; pressure = 1f; size = 1f }
                    val event = MotionEvent.obtain(downTime, downTime + offsetMillis, action, 1,
                        arrayOf(contact), arrayOf(position), 0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0)
                    try { check(dialog.dispatchTouchEvent(event)) { "Body drag event was not routed" } }
                    finally { event.recycle() }
                }
                send(MotionEvent.ACTION_DOWN, start, 0)
                send(MotionEvent.ACTION_MOVE, start + (end - start) * .45f, 20)
                send(MotionEvent.ACTION_MOVE, start + (end - start) * .8f, 40)
                awaitNativeParityDraw(instrumentation, decor)
                main {
                    guard()
                    val moved = Rect().also { check(dialog.contentView.getGlobalVisibleRect(it)) }
                    val dismissThresholdPx = 56f * host.resources.displayMetrics.density
                    check(if (expectMotion) moved.top - expandedBounds.top > dismissThresholdPx else moved == expandedBounds) {
                        "Body drag motion mismatch. Expected movement=$expectMotion, bounds=$expandedBounds → $moved"
                    }
                }
                send(MotionEvent.ACTION_MOVE, end - 2f, 56)
                send(if (cancelled) MotionEvent.ACTION_CANCEL else MotionEvent.ACTION_UP, end, 64)
            }
            if (dragRoute) dragFromBody(expectMotion = false) else dispatchOrdinary()
            repeat(2) { awaitNativeParityDraw(instrumentation, decor) }
            main {
                guard()
                check(ownerDismissals == 0 && windowDismissals == 0) { "$route bypassed busy dismissal veto" }
                check(actionSheetFixtureExpanded(dialog)) { "$route moved a vetoed sheet toward hidden" }
                val retained = Rect().also { check(dialog.contentView.getGlobalVisibleRect(it)) }
                check(retained == expandedBounds) { "$route changed vetoed sheet bounds: $expandedBounds -> $retained" }
                busy = false
                dialog.refreshDismissalAdmission()
            }
            if (handleRoute) {
                dispatchOrdinary(cancelled = true)
                repeat(2) { awaitNativeParityDraw(instrumentation, decor) }
                main {
                    guard()
                    check(ownerDismissals == 0 && windowDismissals == 0 && actionSheetFixtureExpanded(dialog)) {
                        "$route cancelled expanded-target tap dismissed or moved the sheet"
                    }
                    val retained = Rect().also { check(dialog.contentView.getGlobalVisibleRect(it)) }
                    check(retained == expandedBounds) { "$route cancelled tap changed sheet bounds" }
                }
            }
            if (dragRoute) {
                dragFromBody(expectMotion = true, cancelled = true)
                val returnDeadline = SystemClock.uptimeMillis() + 8000
                while (!main {
                    guard()
                    check(ownerDismissals == 0 && windowDismissals == 0) { "$route cancelled drag dismissed" }
                    actionSheetFixtureExpanded(dialog) &&
                        Rect().let { dialog.contentView.getGlobalVisibleRect(it) && it == expandedBounds }
                }) {
                    check(SystemClock.uptimeMillis() < returnDeadline) { "$route cancelled drag did not return" }
                    awaitNativeParityDraw(instrumentation, decor)
                }
                dragFromBody(expectMotion = true)
            } else dispatchOrdinary()
            val dismissDeadline = SystemClock.uptimeMillis() + 8000
            while (!main { !dialog.isShowing && ownerDismissals == 1 && windowDismissals == 1 }) {
                check(SystemClock.uptimeMillis() < dismissDeadline) {
                    "$route did not finish exactly once after admission changed"
                }
                awaitNativeParityDraw(instrumentation, anchor)
            }
            main { dialog.dismiss(); currentSheet = null }
            repeat(2) { awaitNativeParityDraw(instrumentation, anchor) }
            main {
                check(ownerDismissals == 1 && windowDismissals == 1) { "$route duplicated terminal delivery" }
                check(!decor.isAttachedToWindow) { "$route left its dialog window attached" }
            }
            requireNativeParityHost(instrumentation, host, "after-$route-sheet")
            cases.put(JSONObject().put("route", route).put("busy_veto_retained_window", true)
                .put("veto_retained_bounds", expandedBounds.toShortString())
                .put("cancelled_drag_returned_from_beyond_threshold", if (dragRoute) true else JSONObject.NULL)
                .put("expanded_finger_cancel_retained_window", if (handleRoute) true else JSONObject.NULL)
                .put("expanded_finger_up_dismissed", if (handleRoute) true else JSONObject.NULL)
                .put("owner_dismissals", ownerDismissals).put("window_dismissals", windowDismissals)
                .put("admitted_dismiss_removed_window", true).put("duplicate_terminal", false))
        }
        requireNativeParityHost(instrumentation, host, "before-floating-dialog")
        var floatingOwnerDismissals = 0
        var floatingWindowDismissals = 0
        var floatingDisposals = 0
        lateinit var floating: NativeFloatingDialog
        lateinit var floatingBody: TextView
        main {
            floating = NativeFloatingDialog(anchor, host.resources.displayMetrics.density,
                onDismiss = { floatingOwnerDismissals++; floating.dismiss() },
                createContent = { themed, _, _ ->
                    floatingBody = TextView(themed).apply { text = "Floating dialog lifecycle fixture" }
                    NativeFloatingDialogContent(floatingBody, bindWindowAccessibility = {}, dispose = { floatingDisposals++ })
                })
            currentFloating = floating
            floating.setOnDismissListener { floatingWindowDismissals++ }
            floating.show()
        }
        val floatingDecor = main { checkNotNull(floating.window).decorView }
        val floatingDeadline = SystemClock.uptimeMillis() + 8000
        var floatingAlphaSamples = 0
        var floatingTransientAlphaObserved = false
        while (!main {
            val motion = floating.javaClass.getDeclaredField("visibilityMotion").apply { isAccessible = true }.get(floating)
            val expectedAlpha = motion.javaClass.getDeclaredMethod("getAlpha").apply { isAccessible = true }.invoke(motion) as Float
            check(abs(floatingBody.alpha - expectedAlpha) < .0001f) {
                "Same-view predictive layer replaced entry alpha: ${floatingBody.alpha} != $expectedAlpha"
            }
            floatingAlphaSamples++
            if (expectedAlpha > 0f && expectedAlpha < .99f) floatingTransientAlphaObserved = true
            floatingDecor.hasWindowFocus() && floatingBody.alpha >= .99f
        }) {
            check(SystemClock.uptimeMillis() < floatingDeadline) { "Floating dialog did not enter its focused window" }
            awaitNativeParityDraw(instrumentation, floatingDecor)
        }
        main {
            check(host.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) && !host.isFinishing)
            check(floating.isShowing && floatingDecor.hasWindowFocus())
            check(floatingDecor.isAttachedToWindow && floatingDecor.isShown && floatingDecor.isHardwareAccelerated)
            check(floatingDecor.display?.displayId == Display.DEFAULT_DISPLAY)
            check(floating.onBackPressedDispatcher.hasEnabledCallbacks())
            floating.onBackPressedDispatcher.onBackPressed()
        }
        val floatingDismissDeadline = SystemClock.uptimeMillis() + 8000
        while (!main { !floating.isShowing && floatingOwnerDismissals == 1 && floatingWindowDismissals == 1 && floatingDisposals == 1 }) {
            check(SystemClock.uptimeMillis() < floatingDismissDeadline) { "Floating dialog dispatcher completion did not retire each owner exactly once" }
            awaitNativeParityDraw(instrumentation, anchor)
        }
        main { floating.dismiss(); floating.dismiss(); currentFloating = null }
        repeat(2) { awaitNativeParityDraw(instrumentation, anchor) }
        main {
            check(floatingOwnerDismissals == 1 && floatingWindowDismissals == 1 && floatingDisposals == 1)
            check(!floatingDecor.isAttachedToWindow)
        }
        requireNativeParityHost(instrumentation, host, "after-floating-dialog")
        cases.put(JSONObject().put("route", "floating-dialog-androidx-back-dispatcher")
            .put("owner_dismissals", floatingOwnerDismissals).put("window_dismissals", floatingWindowDismissals)
            .put("same_view_visibility_alpha_samples", floatingAlphaSamples)
            .put("transient_entry_alpha_observed", floatingTransientAlphaObserved)
            .put("content_disposals", floatingDisposals).put("duplicate_terminal", false))
        val presentationDismissal = JSONArray().also { result.put("presentation_dismissal", it) }
        verifySheetPresentationDismissal(instrumentation, host, anchor, presentationDismissal)
        result.put("toggle_icons", verifySettingsToggleIcons(instrumentation, host, directory))
        result.put("passed", true)
    } catch (error: Throwable) { failure = error }
    finally {
        failure = captureNativeParityCleanup(failure) { main { currentSheet?.dismiss() } }
        failure = captureNativeParityCleanup(failure) { main { currentFloating?.dismiss() } }
        failure = captureNativeParityCleanup(failure) { main { host.finish() } }
        failure = captureNativeParityCleanup(failure) { check(getRecorderPreferences(context).all == beforePreferences) }
        if (failure != null) result.put("passed", false).put("failure", failure!!.stackTraceToString())
        failure = captureNativeParityCleanup(failure) {
            writeQaEvidenceAtomically(File(directory, "result.json")) { it.write(result.toString(2).toByteArray()) }
        }
        report.appendLine("ACTION_SHEET_DISMISSAL_EVIDENCE ${directory.absolutePath}")
    }
    failure?.let { throw it }
    report.appendLine("PASS native_action_sheet_dismissal_current Back, scrim, content drag, thin accessible handle, and floating dialog retirement")
}

/** State is driven on the actual owned handle; PixelCopy reads that same dialog window. */
private fun verifySheetHandleIndication(
    instrumentation: Instrumentation, host: NativeParityActivity, dialog: NativeActionSheet,
    focusParking: View, directory: File, route: String, cases: JSONArray,
) {
    fun <T> main(block: () -> T) = nativeParityOnMain(instrumentation, block)
    val window = main { checkNotNull(dialog.window) }
    val handle = main { descendantsOf(dialog.contentView).filterIsInstance<NativeSheetDragHandle>().single() }
    val originalFocus = main { window.decorView.findFocus() }
    val originalHandleTouchFocus = main { handle.isFocusableInTouchMode }
    val originalParkingTouchFocus = main { focusParking.isFocusableInTouchMode }
    val originalParkingFocus = main { focusParking.isFocusable }
    fun hover(action: Int) {
        val now = SystemClock.uptimeMillis()
        val contact = MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_MOUSE }
        val point = MotionEvent.PointerCoords().apply { x = handle.width / 2f; y = handle.height / 2f }
        val event = MotionEvent.obtain(now, now, action, 1, arrayOf(contact), arrayOf(point),
            0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_MOUSE, 0)
        try { handle.dispatchGenericMotionEvent(event) } finally { event.recycle() }
    }
    var failure: Throwable? = null
    var idleMark: Int? = null
    try {
        for (state in listOf("idle", "focus", "hover", "idle-restored")) {
            val evidence = JSONObject().put("route", route).put("state", state).put("passed", false)
                .put("state_route", "Actual View focus request and owned-handle mouse hover dispatch")
            cases.put(evidence)
            val scale = main {
                check(dialog.isShowing && dialog.hasCurrentWindowFocus)
                hover(MotionEvent.ACTION_HOVER_EXIT)
                focusParking.isFocusableInTouchMode = true
                check(focusParking.requestFocus())
                handle.isFocusableInTouchMode = true
                if (state == "focus") check(handle.requestFocus())
                if (state == "hover") hover(MotionEvent.ACTION_HOVER_ENTER)
                evidence.put("focused", handle.isFocused).put("hovered", handle.isHovered)
                    .put("parent_foreground", handle.foreground?.javaClass?.name ?: JSONObject.NULL)
                    .put("default_focus_highlight", handle.defaultFocusHighlightEnabled)
                check(handle.isFocused == (state == "focus"))
                check(handle.isHovered == (state == "hover"))
                readCachedAnimatorDurationScale(host)
            }
            // State-layer transitions are at most 45ms * animator scale; allow frame/queue latency.
            val settleUntil = SystemClock.uptimeMillis() + ceil(100f * scale).toLong() + 32L
            do { awaitNativeParityDraw(instrumentation, window.decorView) }
            while (SystemClock.uptimeMillis() < settleUntil)
            val bitmap = captureRestartSheet(instrumentation, host, window)
            try {
                val filename = "handle-$route-$state.png"
                saveNativeParityBitmap(directory, filename, bitmap)
                evidence.put("png", filename).put("animator_scale", scale)
                val samples = main {
                    val mark = handle.getChildAt(0)
                    val markLocation = IntArray(2).also(mark::getLocationInWindow)
                    val surfaceLocation = IntArray(2).also(dialog.contentView::getLocationInWindow)
                    val d = host.resources.displayMetrics.density
                    val gap = ceil(3 * d).toInt()
                    intArrayOf(markLocation[0] + mark.width / 2, markLocation[1] + mark.height / 2,
                        markLocation[1] - gap, markLocation[1] + mark.height + gap,
                        surfaceLocation[0] + dialog.contentView.width - (12 * d).roundToInt())
                }
                evidence.put("window_samples", JSONArray(samples.toList()))
                val (x, centerY, aboveY, belowY, referenceX) = samples
                check(x in 1 until bitmap.width - 1 && referenceX in 0 until bitmap.width &&
                    aboveY in 1 until bitmap.height - 1 && belowY in 1 until bitmap.height - 1 &&
                    centerY in 0 until bitmap.height)
                val background = bitmap.getPixel(referenceX, centerY)
                val markInk = bitmap.getPixel(x, centerY)
                val adjacent = JSONArray()
                var mismatch = 0
                for (y in listOf(aboveY, belowY)) for (dy in -1..1) for (dx in -1..1) {
                    val color = bitmap.getPixel(x + dx, y + dy)
                    adjacent.put(color)
                    if (color != background) mismatch++
                }
                evidence.put("background_argb", background).put("mark_argb", markInk)
                    .put("adjacent_argb", adjacent).put("adjacent_mismatches", mismatch)
                check(mismatch == 0) { "$route/$state indication painted outside the handle pill" }
                check(markInk != background) { "$route/$state handle mark disappeared" }
                when (state) {
                    "idle" -> idleMark = markInk
                    "idle-restored" -> check(markInk == idleMark) { "$route handle state cue did not retire" }
                    else -> check(markInk != idleMark) { "$route/$state lost the visible pill state cue" }
                }
                evidence.put("passed", true)
            } finally { bitmap.recycle() }
        }
    } catch (error: Throwable) { failure = error }
    finally {
        failure = captureNativeParityCleanup(failure) { main { hover(MotionEvent.ACTION_HOVER_EXIT) } }
        failure = captureNativeParityCleanup(failure) { main { handle.clearFocus() } }
        failure = captureNativeParityCleanup(failure) { main {
            handle.isFocusableInTouchMode = originalHandleTouchFocus
            focusParking.isFocusableInTouchMode = originalParkingTouchFocus
            focusParking.isFocusable = originalParkingFocus
            originalFocus?.requestFocus()
        } }
    }
    failure?.let { throw it }
}

private fun descendantsOf(view: View): Sequence<View> = sequence {
    yield(view)
    if (view is ViewGroup) for (index in 0 until view.childCount) yieldAll(descendantsOf(view.getChildAt(index)))
}

/** Observe readiness only; transitions use the instance dispatcher or dialog touch dispatch. */
internal fun actionSheetFixtureExpanded(dialog: NativeActionSheet): Boolean {
    val state = dialog.javaClass.getDeclaredField("state").apply { isAccessible = true }.get(dialog)
    fun property(name: String): Any? = state.javaClass.getDeclaredMethod(name).apply { isAccessible = true }.invoke(state)
    return property("getCurrentValue").toString() == "EXPANDED" &&
        property("getTargetValue").toString() == "EXPANDED" && property("getAnimationRunning") == false
}
