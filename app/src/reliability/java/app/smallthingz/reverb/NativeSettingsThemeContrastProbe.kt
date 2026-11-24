package app.smallthingz.reverb

import android.app.Application
import android.app.Instrumentation
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.os.SystemClock
import android.view.Choreographer
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import app.smallthingz.reverb.nativeui.*
import java.io.File
import java.lang.reflect.Field
import java.util.function.LongSupplier
import kotlin.math.pow
import org.json.JSONArray
import org.json.JSONObject
import smallthingz.ui.animation.ColorTransition
import smallthingz.ui.animation.view.ViewColorTransition
import smallthingz.ui.components.NativeDropdown
import smallthingz.ui.controls.textSurfaceForeground
import smallthingz.ui.reverb.settings.ReverbRetentionInputView
import smallthingz.ui.reverb.settings.ReverbRetentionMode
import smallthingz.ui.text.RetentionLineHeightSpan
import smallthingz.ui.text.view.ParagraphView
import smallthingz.ui.graphics.Color as NativeColor
import smallthingz.ui.graphics.toArgb
import smallthingz.ui.unit.Density

/** Deterministic colors on the existing mounted Settings owners; no production probe hooks. */
internal fun verifyNativeSettingsThemeContrast(instrumentation: Instrumentation, report: StringBuilder) {
    val context = instrumentation.targetContext
    check(context.packageName == "app.smallthingz.reverb.uitheme.reliability")
    check(context.applicationContext.javaClass == Application::class.java)
    val preferences = getRecorderPreferences(context).all.toMap()
    val directory = File(context.filesDir, "native-settings-theme-contrast-${System.currentTimeMillis()}")
    check(directory.mkdir())
    val cases = JSONArray()
    val result = JSONObject().put("selector", "native_settings_theme_contrast").put("sdk", Build.VERSION.SDK_INT)
        .put("passed", false).put("cases", cases)
        .put("scope", "Real mounted Settings, production noise publication, deterministic retained color samples; not device callback timing")
        .put("grain_contract", "Actual pixels within conservative alpha17 black/white bounds; best achievable uniform ink, not universal4.5")
    val host = launchCurrentUiSmokeActivity(instrumentation)
    var render: MainSettingsParityRender? = null
    var failure: Throwable? = null
    fun <T> main(block: () -> T) = nativeParityOnMain(instrumentation, block)
    fun settle(view: View) { repeat(3) { awaitNativeParityDraw(instrumentation, view); instrumentation.waitForIdleSync() } }
    try {
        cases.put(main { verifyRetentionEditorPaletteGeometry(host) })
        for (scene in listOf(SettingsSectionKey.RETENTION, SettingsSectionKey.RECORDING)) {
            val current = main {
                MainSettingsParityRender(host, false, AppThemeMode.SYSTEM, true, null,
                    productionWindowFocus = true, animatedSurfaceForProbe = true)
            }.also { render = it }
            try {
                val deadline = SystemClock.uptimeMillis() + 5000
                while (!main { current.actionCount(host.getString(R.string.open_settings)) == 1 }) {
                    check(SystemClock.uptimeMillis() < deadline); settle(current.board)
                }
                main { current.click(host.getString(R.string.open_settings)) }
                var screen: NativeSettingsScreenContent? = null
                val hydrated = SystemClock.uptimeMillis() + 5000
                while (screen == null) {
                    screen = main { themeViews(current.board).filterIsInstance<NativeSettingsScreenContent>().firstOrNull { it.inputAllowed } }
                    check(SystemClock.uptimeMillis() < hydrated); settle(current.board)
                }
                val settings = checkNotNull(screen)
                if (scene == SettingsSectionKey.RETENTION) {
                    settledMainParityBitmap(instrumentation, host, current, directory,
                        "background-open-unsettled.png").recycle()
                    cases.put(verifySettingsBackgroundWarnings(instrumentation, host, current, settings, directory))
                }
                main {
                    settings.owner.retentionModeChanged(settings.owner.state.activation, RetentionMode.SIZE)
                    val list = checkNotNull(settings.listViewport)
                    val adapter = checkNotNull(list.adapter)
                    val position = (0 until adapter.itemCount).single { adapter.getItemViewType(it) == scene.ordinal }
                    (list.layoutManager as LinearLayoutManager).scrollToPositionWithOffset(position, 0)
                }
                settledMainParityBitmap(instrumentation, host, current, directory,
                    "${scene.name.lowercase()}-unsettled.png").recycle()
                main {
                    val root = themeViews(current.board).filterIsInstance<NativeMainScreenView>().single()
                    val panel = themeField(root, "settingsMotion").get(root) as NativeMainPanelMotion
                    check(panel.panelProgress == 1f && panel.settledProgress == 1f)
                    val home = themeField(root, "home").get(root) as View
                    check(home.translationY >= root.height && !current.board.isLayoutRequested)
                }
                val probe = main { SettingsThemeSamples(current, settings, scene) }
                try {
                    settle(current.board) // Focus acquisition may request an ordinary retained-editor reveal.
                    main { probe.bindLightTarget() }
                    val poses = listOf(ThemeContrastPose("dark-light", 55), ThemeContrastPose("light-dark", 57, reverse = true)) +
                        if (scene == SettingsSectionKey.RETENTION) listOf(
                            ThemeContrastPose("interrupted-reversal", 80, retargetAt = 40, requiresOldFailure = false),
                            ThemeContrastPose("zero-scale", 0, scale = 0f, requiresOldFailure = false),
                        ) else emptyList()
                    for (pose in poses) {
                        val label = "${scene.name.lowercase()}-${pose.name}"
                        val entry = JSONObject().put("name", label).put("sample_millis", pose.millis)
                            .put("reverse", pose.reverse).put("retarget_at", pose.retargetAt ?: JSONObject.NULL).put("scale", pose.scale.toDouble())
                        cases.put(entry)
                        main { probe.pose(pose) }
                        val mask = main { probe.masks() }
                        val underlay = main { probe.withoutGlyphs { probe.bitmap() } }
                        try {
                            val software = main { probe.bitmap() }
                            try {
                                saveNativeParityBitmap(directory, "$label-software.png", software)
                                entry.put("software", main { probe.verify(software, underlay, mask) })
                            } finally { software.recycle() }
                            val hardware = captureNativeParityContent(instrumentation, host) { current.board }
                            try {
                                val hardwareUnderlay = probe.captureWithoutGlyphs(instrumentation, host)
                                try {
                                    saveNativeParityBitmap(directory, "$label-hardware.png", hardware)
                                    saveNativeParityBitmap(directory, "$label-underlay.png", hardwareUnderlay)
                                    entry.put("hardware", main { probe.verify(hardware, hardwareUnderlay, mask) })
                                } finally { hardwareUnderlay.recycle() }
                            } finally { hardware.recycle() }
                            if (scene == SettingsSectionKey.RETENTION && pose.requiresOldFailure) {
                                entry.put("old_raw_negative_control", main { probe.oldRawControl(underlay, mask, File(directory, "$label-old-raw-software.png")) })
                            }
                            entry.put("editor", main { probe.verifyEditor() })
                        } finally { underlay.recycle(); mask.forEach { it.recycle() } }
                    }
                    if (scene == SettingsSectionKey.RETENTION) {
                        val entry = JSONObject().put("name", "parent-only-idle-ink")
                        cases.put(entry)
                        main { probe.primeParentOnly() }
                        // Create masks before priming HWUI. No child invalidation occurs after the parent change.
                        val mask = main { probe.masks() }
                        val primed = captureNativeParityContent(instrumentation, host) { current.board }
                        try { entry.put("primed_raw_ink_pixels", main { probe.parentInkPixels(primed, mask, 0xff333333.toInt()) }) }
                        finally { primed.recycle() }
                        main { probe.changeParentWithoutNotification() }
                        val stale = captureNativeParityContent(instrumentation, host) { current.board }
                        try {
                            saveNativeParityBitmap(directory, "parent-only-no-notification-hardware.png", stale)
                            entry.put("negative_control_cached_raw_ink_pixels", main { probe.parentInkPixels(stale, mask, 0xff333333.toInt()) })
                        } finally { stale.recycle() }
                        main { probe.changeOnlyParent() }
                        val hardware = captureNativeParityContent(instrumentation, host) { current.board }
                        try {
                            val underlay = probe.captureWithoutGlyphs(instrumentation, host)
                            try {
                                saveNativeParityBitmap(directory, "parent-only-idle-ink-hardware.png", hardware)
                                entry.put("hardware", main { probe.verify(hardware, underlay, mask, parentOnly = true) })
                                entry.put("test_invalidated_child_after_parent_change", false)
                            } finally { underlay.recycle() }
                        } finally { hardware.recycle(); mask.forEach { it.recycle() } }
                        entry.put("editor", main { probe.verifyEditor() })
                        cases.put(main { probe.compatibility() })
                    }
                    check(main { current.forbiddenCalls } == 0)
                } finally { main { probe.close() } }
            } finally { main { current.close() }; current.awaitClosed(); render = null }
        }
        result.put("passed", true)
    } catch (error: Throwable) { failure = error }
    finally {
        failure = captureNativeParityCleanup(failure) { main { render?.close() } }
        failure = captureNativeParityCleanup(failure) { main { host.finish() } }
        failure = captureNativeParityCleanup(failure) { check(getRecorderPreferences(context).all == preferences) }
        if (failure != null) result.put("passed", false).put("failure", failure!!.stackTraceToString())
        failure = captureNativeParityCleanup(failure) {
            writeQaEvidenceAtomically(File(directory, "result.json")) { it.write(result.toString(2).toByteArray()) }
        }
        report.appendLine("SETTINGS_THEME_CONTRAST_EVIDENCE ${directory.absolutePath}")
    }
    failure?.let { throw it }
    report.appendLine("PASS native_settings_theme_contrast actual Settings glyphs, grain bounds, parent-only hardware invalidation and retained editor")
}

private fun verifySettingsBackgroundWarnings(instrumentation: Instrumentation, host: NativeParityActivity,
    render: MainSettingsParityRender, settings: NativeSettingsScreenContent, directory: File): JSONObject {
    fun <T> main(block: () -> T) = nativeParityOnMain(instrumentation, block)
    val owner = settings.owner
    val battery = themeField(owner, "batteryOptimizationRestricted")
    val blocked = themeField(owner, "resumeNotificationBlocked")
    val restart = themeField(owner, "restartAccessMissing")
    val changed = owner.javaClass.getDeclaredMethod("notifyChanged").apply { isAccessible = true }
    val original = main { Triple(battery.getBoolean(owner), blocked.getBoolean(owner), restart.getBoolean(owner)) }
    val list = main { checkNotNull(settings.listViewport) }
    val rows = JSONArray()
    try {
        for (bits in 0..7) {
            val batteryRestricted = bits and 1 != 0
            val notificationBlocked = bits and 2 != 0
            val restartAccessMissing = bits and 4 != 0
            main {
                battery.setBoolean(owner, batteryRestricted); blocked.setBoolean(owner, notificationBlocked)
                restart.setBoolean(owner, restartAccessMissing)
                changed.invoke(owner)
            }
            repeat(3) { awaitNativeParityDraw(instrumentation, render.board) }
            main {
                check(settings.listViewport === list)
                val adapter = checkNotNull(list.adapter)
                check((0 until adapter.itemCount).count { adapter.getItemViewType(it) == SettingsSectionKey.BACKGROUND.ordinal } == if (bits == 0) 0 else 1)
                val retention = (0 until adapter.itemCount).single { adapter.getItemViewType(it) == SettingsSectionKey.RETENTION.ordinal }
                check(retention == SettingsSectionKey.retentionIndex(bits != 0))
                (list.layoutManager as LinearLayoutManager).scrollToPositionWithOffset(0, 0)
            }
            repeat(3) { awaitNativeParityDraw(instrumentation, render.board) }
            val entry = main {
                val first = checkNotNull((list.layoutManager as LinearLayoutManager).findViewByPosition(0))
                val visible = themeViews(first).filterIsInstance<SettingsPermissionReviewRow>().filter { it.visibility == View.VISIBLE }.toList()
                val count = Integer.bitCount(bits)
                check(visible.size == count)
                check(visible.all { it.width > 0 && it.height > 0 })
                visible.zipWithNext().forEach { (first, second) ->
                    check(first.parent === second.parent && first.bottom <= second.top)
                }
                check(themeViews(first).any {
                    it.contentDescription == host.getString(R.string.settings_restart_access) && it.isShown
                } == restartAccessMissing)
                JSONObject().put("battery_restricted", batteryRestricted).put("notification_blocked", notificationBlocked)
                    .put("restart_access_missing", restartAccessMissing)
                    .put("visible_rows", count).put("retained_list_identity", System.identityHashCode(list))
                    .put("retention_index", SettingsSectionKey.retentionIndex(bits != 0))
                    .put("restart_action", if (restartAccessMissing) "review" else "absent")
            }
            rows.put(entry)
            if (batteryRestricted && notificationBlocked && restartAccessMissing) {
                val image = captureNativeParityContent(instrumentation, host) { render.board }
                try { saveNativeParityBitmap(directory, "background-permission-warnings-hardware.png", image) }
                finally { image.recycle() }
            }
            if (bits == 0) {
                val image = captureNativeParityContent(instrumentation, host) { render.board }
                try { saveNativeParityBitmap(directory, "no-background-permission-warnings-hardware.png", image) }
                finally { image.recycle() }
            }
        }
        return JSONObject().put("name", "background-permission-warnings").put("states", rows)
            .put("shared_group_nonoverlap", true).put("permission_activity_launched", false)
            .put("background_section_absent_without_warnings", true)
    } finally {
        main {
            battery.setBoolean(owner, original.first); blocked.setBoolean(owner, original.second)
            restart.setBoolean(owner, original.third); changed.invoke(owner)
        }
    }
}

private fun verifyRetentionEditorPaletteGeometry(host: NativeParityActivity): JSONObject {
    val light = ReverbViewEnvironment(host, false)
    val dark = light.withPalette(ReverbPalette.fallback(true))
    val editor = ReverbRetentionInputView(host, light)
    val draft = "1:23"
    fun bind(environment: ReverbViewEnvironment) {
        editor.bind(draft, ReverbRetentionMode.TIME, host.getString(R.string.buffer_one_shot), environment,
            requestFocus = false, readCurrentRaw = { draft }, onRawChanged = { error("Theme binding edited the draft") })
    }
    fun measureAndPlace() {
        val width = (320f * light.textDensity.density).toInt()
        editor.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        editor.layout(0, 0, editor.measuredWidth, editor.measuredHeight)
    }
    fun lineHeightSpan(): RetentionLineHeightSpan {
        val editable = checkNotNull(editor.editableText)
        return editable.getSpans(0, editable.length, RetentionLineHeightSpan::class.java).single()
    }
    try {
        editor.layoutDirection = View.LAYOUT_DIRECTION_LTR
        bind(light)
        editor.setSelection(1, 3)
        measureAndPlace()
        val originalLayout = checkNotNull(editor.layout)
        val originalSpan = lineHeightSpan()
        val originalInk = editor.currentTextColor
        val originalSize = editor.textSize

        bind(dark)
        check(editor.layout === originalLayout && lineHeightSpan() === originalSpan && !editor.isLayoutRequested) {
            "A palette-only bind replaced the native editor geometry"
        }
        check(editor.currentTextColor == dark.palette.ink && editor.currentTextColor != originalInk)
        check(editor.highlightColor == dark.appearance.editorVisuals.selectionColor(dark.palette.primary))
        check(editor.text.toString() == draft && editor.selectionStart == 1 && editor.selectionEnd == 3)

        val larger = ReverbViewEnvironment(Density(dark.textDensity.density, dark.textDensity.fontScale * 1.4f),
            dark.sharedFontResolver, dark.palette)
        bind(larger)
        measureAndPlace()
        val largerSpan = lineHeightSpan()
        check(largerSpan !== originalSpan && editor.textSize > originalSize)

        editor.layoutDirection = View.LAYOUT_DIRECTION_RTL
        bind(larger)
        measureAndPlace()
        check(editor.layoutDirection == View.LAYOUT_DIRECTION_RTL && lineHeightSpan() !== largerSpan)
        check(editor.selectionStart == 1 && editor.selectionEnd == 3)
        return JSONObject().put("name", "retention_palette_reuses_native_geometry")
            .put("light_ink", originalInk).put("dark_ink", editor.currentTextColor)
            .put("original_text_size_px", originalSize.toDouble()).put("larger_text_size_px", editor.textSize.toDouble())
            .put("same_measured_layout_and_line_height_span_after_palette", true)
            .put("font_scale_and_rtl_replace_line_height_span", true)
    } finally {
        editor.releaseCallbacks()
    }
}

private data class ThemeContrastPose(val name: String, val millis: Int, val reverse: Boolean = false,
    val retargetAt: Int? = null, val scale: Float = 1f, val requiresOldFailure: Boolean = true)

private class SettingsThemeSamples(
    private val render: MainSettingsParityRender,
    private val settings: NativeSettingsScreenContent,
    private val scene: SettingsSectionKey,
) : AutoCloseable {
    private val board = render.board
    private val rootMotion = themeField(render, "noiseColor").get(render) as ViewColorTransition
    private val held = ArrayList<HeldThemeMotion>()
    private val targets: List<View>
    private val editor: SettingsRetentionInputView?
    private val editorRaw: String?
    private val editorSelection: Pair<Int, Int>?
    private val originalSoftInput: Boolean?
    private val parentOnly: ParagraphView
    private val drawColor = themeField(ParagraphView::class.java, "textColor")
    private val oldProviders = HashMap<ParagraphView, LongSupplier?>()

    init {
        val all = themeViews(settings).toList()
        editor = if (scene == SettingsSectionKey.RETENTION)
            all.filterIsInstance<SettingsRetentionInputView>().first { fullyVisible(it) } else null
        originalSoftInput = editor?.showSoftInputOnFocus
        editor?.let { it.showSoftInputOnFocus = false; check(it.requestFocus()) }
        editorRaw = editor?.text?.toString()
        editorSelection = editor?.let { it.selectionStart to it.selectionEnd }
        val paragraphs = all.filterIsInstance<ParagraphView>().filter { view ->
            fullyVisible(view) && (view === settings.topBar.title ||
                if (scene == SettingsSectionKey.RETENTION) view.parent is SettingsRetentionValueView ||
                    view.getText() == view.context.getString(R.string.retention_mode_title)
                else view.parent is NativeDropdown<*>)
        }
        check(paragraphs.size >= 3) { "Missing visible real Settings labels: ${paragraphs.map { it.getText() }}" }
        targets = paragraphs + listOfNotNull(editor)
        parentOnly = if (scene == SettingsSectionKey.RETENTION) paragraphs.single {
            it.getText() == it.context.getString(R.string.retention_mode_title)
        } else settings.topBar.title
        for (view in all) {
            var type: Class<*>? = view.javaClass
            while (type != null && (type.name.startsWith("app.smallthingz") || type.name.startsWith("smallthingz"))) {
                for (field in type.declaredFields) if (field.type == ViewColorTransition::class.java) {
                    field.isAccessible = true
                    (field.get(view) as? ViewColorTransition)?.let(::hold)
                }
                type = type.superclass
            }
            if (view is SettingsRetentionInputView) hold(themeEditorMotion(view))
        }
        hold(rootMotion)
        check(held.isNotEmpty())
    }
    private fun hold(motion: ViewColorTransition) {
        if (held.none { it.motion === motion }) held += HeldThemeMotion(motion)
    }
    fun bindLightTarget() {
        settings.owner.themeChanged(settings.owner.state.activation, AppThemeMode.LIGHT)
        held.forEach { it.captureTarget() }
        check(held.any { !it.start.contentEquals(it.target) })
    }
    fun pose(pose: ThemeContrastPose) {
        held.forEach { it.pose(pose) }
        held.forEach { it.publish() }
        check(targets.all(::fullyVisible))
    }
    fun bitmap(): Bitmap = Bitmap.createBitmap(board.width, board.height, Bitmap.Config.ARGB_8888).also { board.draw(Canvas(it)) }
    fun <T> withoutGlyphs(block: () -> T): T {
        val alphas = targets.map { it.alpha }
        targets.forEach { it.alpha = 0f }
        return try { block() } finally { targets.forEachIndexed { index, view -> view.alpha = alphas[index] } }
    }
    fun captureWithoutGlyphs(instrumentation: Instrumentation, host: NativeParityActivity): Bitmap {
        val alphas = nativeParityOnMain(instrumentation) { targets.map { it.alpha }.also { targets.forEach { v -> v.alpha = 0f } } }
        return try { captureNativeParityContent(instrumentation, host) { board } }
        finally { nativeParityOnMain(instrumentation) { targets.forEachIndexed { i, view -> view.alpha = alphas[i] } } }
    }
    fun masks(): List<Bitmap> = targets.map { view ->
        Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888).also { bitmap ->
            val canvas = Canvas(bitmap)
            if (view is ParagraphView) {
                val color = drawColor.getLong(view)
                val provider = view.paintedTextSurface
                try {
                    view.paintedTextSurface = null
                    drawColor.setLong(view, NativeColor.White.value.toLong())
                    view.draw(canvas)
                } finally { drawColor.setLong(view, color); view.paintedTextSurface = provider }
            } else {
                val text = view as TextView
                val color = text.paint.color
                try {
                    text.paint.color = Color.WHITE
                    canvas.translate((text.compoundPaddingLeft - text.scrollX).toFloat(),
                        (text.extendedPaddingTop - text.scrollY).toFloat())
                    checkNotNull(text.layout).draw(canvas)
                } finally { text.paint.color = color }
            }
        }
    }
    private fun surface(view: View): Long {
        if (view.parent is NativeDropdown<*>) {
            val fill = themeField(view.parent!!, "fill").get(view.parent) as Paint
            return smallthingz.ui.controls.textSurfaceRange(fill.color)
        }
        return settingsTextSurface(view)
    }
    private fun expectedInk(view: View): Int = when (view) {
        is ParagraphView -> {
            val raw = NativeColor(drawColor.getLong(view).toULong()).toArgb()
            if (view.paintedTextSurface == null || themeField(view, "colorTransition").get(view) == null) raw
            else textSurfaceForeground(surface(view), raw)
        }
        is SettingsRetentionInputView -> textSurfaceForeground(surface(view), themeEditorMotion(view).color(0))
        else -> error("Unexpected text owner")
    }
    fun verify(foreground: Bitmap, underlay: Bitmap, masks: List<Bitmap>, parentOnly: Boolean = false): JSONArray {
        check(foreground.width == board.width && foreground.height == board.height)
        val rows = JSONArray()
        targets.forEachIndexed { index, view ->
            if (parentOnly && view !== this.parentOnly) return@forEachIndexed
            val rect = area(view)
            val range = surface(view)
            val low = (range ushr 32).toInt(); val high = range.toInt()
            check(low ushr 24 == 255 && high ushr 24 == 255) { "Unknown actual underlay for ${label(view)}" }
            val ink = expectedInk(view)
            val bound = minOf(themeContrast(ink, low), themeContrast(ink, high))
            val flat = low == high
            if (flat) check(bound >= 4.5)
            var samples = 0; var expectedInkSamples = 0; var mismatchedInkSamples = 0
            var failures = 0; var outside = 0; var minimum = 30.0
            val mask = masks[index]
            for (y in 1 until mask.height - 1) for (x in 1 until mask.width - 1) {
                if (!fullGlyph(mask, x, y)) continue
                val px = rect.left + x; val py = rect.top + y
                val actual = foreground.getPixel(px, py)
                // Every eroded glyph-core pixel participates in the actual contrast gate. Color
                // correspondence is reported separately; mismatches cannot hide low contrast.
                if (actual == ink) expectedInkSamples++ else mismatchedInkSamples++
                val background = underlay.getPixel(px, py)
                samples++
                val luminance = themeLuminance(background)
                if (luminance < minOf(themeLuminance(low), themeLuminance(high)) ||
                    luminance > maxOf(themeLuminance(low), themeLuminance(high))) outside++
                val contrast = themeContrast(actual, background)
                minimum = minOf(minimum, contrast)
                if (contrast < if (flat) 4.5 else bound) failures++
            }
            check(samples >= 8 && expectedInkSamples >= 8) {
                "No nonvacuous expected glyph interior for ${label(view)}: core=$samples expected=$expectedInkSamples mismatched=$mismatchedInkSamples"
            }
            check(outside == 0 && failures == 0) { "Actual text contrast/range failed ${label(view)}: samples=$samples failures=$failures outside=$outside" }
            rows.put(JSONObject().put("label", label(view)).put("samples", samples).put("failures", failures)
                .put("expected_ink_samples", expectedInkSamples).put("mismatched_ink_samples", mismatchedInkSamples)
                .put("outside_surface_bounds", outside).put("flat_surface", flat).put("surface_low", low.toLong() and 0xffffffffL)
                .put("surface_high", high.toLong() and 0xffffffffL).put("ink", ink.toLong() and 0xffffffffL)
                .put("achievable_bound", bound).put("actual_minimum", minimum))
        }
        check(rows.length() > 0)
        return rows
    }
    fun oldRawControl(underlay: Bitmap, masks: List<Bitmap>, file: File): JSONObject {
        val paragraphs = targets.filterIsInstance<ParagraphView>()
        paragraphs.forEach { oldProviders[it] = it.paintedTextSurface; it.paintedTextSurface = null }
        val bitmap = try { bitmap() } finally { paragraphs.forEach { it.paintedTextSurface = oldProviders.remove(it) } }
        try {
            saveNativeParityBitmap(checkNotNull(file.parentFile), file.name, bitmap)
            var samples = 0; var failures = 0
            targets.forEachIndexed { index, view ->
                if (view !is ParagraphView) return@forEachIndexed
                val rect = area(view); val mask = masks[index]
                for (y in 1 until mask.height - 1) for (x in 1 until mask.width - 1) if (fullGlyph(mask, x, y)) {
                    samples++
                    if (themeContrast(bitmap.getPixel(rect.left + x, rect.top + y), underlay.getPixel(rect.left + x, rect.top + y)) < 4.5) failures++
                }
            }
            check(samples >= 8 && failures > 0) { "Former raw interpolation did not expose the causal crossing" }
            return JSONObject().put("samples", samples).put("failures", failures).put("same_views_provider_disabled", true).put("old_apk", false)
        } finally { bitmap.recycle() }
    }
    fun primeParentOnly() {
        val ink = held.single { it.motion === themeField(parentOnly, "colorTransition").get(parentOnly) }
        ink.snap(intArrayOf(0xff333333.toInt()))
        held.single { it.motion === rootMotion }.snap(intArrayOf(Color.WHITE))
        check(!ink.state.running)
    }
    fun changeOnlyParent() {
        val before = held.filter { it.motion !== rootMotion }.map { IntArray(it.state.size) { index -> it.state.color(index) } }
        held.single { it.motion === rootMotion }.snap(intArrayOf(0xff222222.toInt()))
        held.filter { it.motion !== rootMotion }.forEachIndexed { index, motion ->
            check(before[index].contentEquals(IntArray(motion.state.size) { motion.state.color(it) }))
        }
        check(!themeState(themeField(parentOnly, "colorTransition").get(parentOnly) as ViewColorTransition).running)
    }
    fun changeParentWithoutNotification() {
        // Same actual drawable change, deliberately omitting only its production dependent notification.
        (board.background as ReverbNoiseDrawable).setBackgroundColor(0xff222222.toInt())
    }
    fun parentInkPixels(bitmap: Bitmap, masks: List<Bitmap>, ink: Int): Int {
        val index = targets.indexOf(parentOnly); check(index >= 0)
        val mask = masks[index]; val rect = area(parentOnly)
        var count = 0
        for (y in 1 until mask.height - 1) for (x in 1 until mask.width - 1)
            if (fullGlyph(mask, x, y) && bitmap.getPixel(rect.left + x, rect.top + y) == ink) count++
        check(count >= 8) { "Parent-only control did not retain expected opaque ink ($count)" }
        return count
    }
    fun compatibility(): JSONObject {
        val title = settings.topBar.title
        val environment = themeField(settings, "environment").get(settings) as ReverbViewEnvironment
        environment.bindParagraphStyle(title, ReverbTextRole.TITLE_LARGE, environment.palette.error)
        check(title.paintedTextSurface == null)
        environment.bindParagraphStyle(title, ReverbTextRole.TITLE_LARGE, environment.palette.onSurface)
        check(title.paintedTextSurface != null)
        val hint = settings.topBar.navigation.visual.background as ColorDrawable
        val previous = hint.color
        try {
            hint.color = 0x2effffff
            check(settingsTextSurface(settings.topBar.navigation.visual) == 0L)
            hint.color = Color.TRANSPARENT
            check(settingsTextSurface(settings.topBar.navigation.visual) != 0L)
        } finally { hint.color = previous }
        val disabled = 0x61777777
        check(textSurfaceForeground(surface(parentOnly), disabled) == disabled)
        // Explicit immediate custom color ends the shared paragraph's palette ownership.
        title.setTextColor(Color.MAGENTA)
        check(themeField(title, "colorTransition").get(title) == null)
        val custom = Bitmap.createBitmap(title.width, title.height, Bitmap.Config.ARGB_8888)
        try {
            title.draw(Canvas(custom))
            check(countColor(custom, Color.MAGENTA) >= 8)
        } finally { custom.recycle() }
        editor?.let { view ->
            val buffer = checkNotNull(view.text)
            check(buffer.isNotEmpty())
            val explicit = android.text.style.ForegroundColorSpan(Color.CYAN)
            buffer.setSpan(explicit, 0, buffer.length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            val styled = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            try { view.draw(Canvas(styled)); check(countColor(styled, Color.CYAN) >= 8) }
            finally { styled.recycle(); buffer.removeSpan(explicit) }
            verifyEditor()
        }
        return JSONObject().put("name", "custom-disabled-hint-controls").put("error_role_opt_out", true)
            .put("neutral_role_restored", true).put("own_hint_surface_admission_state", true)
            .put("disabled_alpha_state_preserved", true).put("rendered_disabled_hint_qualified", false)
            .put("explicit_custom_color_pixels", true)
            .put("editor_foreground_span_pixels_preserved", editor != null)
    }
    fun verifyEditor(): JSONObject {
        val view = editor ?: return JSONObject().put("present", false)
        check(view.isAttachedToWindow && view.hasFocus() && view.text.toString() == editorRaw)
        check((view.selectionStart to view.selectionEnd) == editorSelection)
        val motion = themeEditorMotion(view)
        val cursor = themeField(view, "cursorDrawable").get(view)
        val paint = themeField(cursor, "paint").get(cursor) as Paint
        check(paint.color == motion.color(1))
        check(view.highlightColor == ((motion.color(1) and 0x00ffffff) or 0x66000000))
        return JSONObject().put("present", true).put("identity", System.identityHashCode(view)).put("raw", editorRaw)
            .put("selection_start", view.selectionStart).put("selection_end", view.selectionEnd)
            .put("focused", true).put("cursor_channel_preserved", true).put("highlight_channel_preserved", true)
    }
    private fun area(view: View): Rect = Rect(0, 0, view.width, view.height).also { board.offsetDescendantRectToMyCoords(view, it) }
    private fun fullyVisible(view: View): Boolean {
        if (!view.isShown || view.width <= 0 || view.height <= 0) return false
        val visible = Rect()
        return view.getGlobalVisibleRect(visible) && visible.width() == view.width && visible.height() == view.height
    }
    private fun label(view: View) = if (view is ParagraphView) view.getText() else "retention-editor"
    override fun close() { editor?.let { it.showSoftInputOnFocus = checkNotNull(originalSoftInput) }; held.forEach { it.stop() } }
}

private class HeldThemeMotion(val motion: ViewColorTransition) {
    val state = themeState(motion)
    val start = IntArray(state.size) { state.color(it) }
    var target = start.copyOf()
    init { stop() }
    fun stop() {
        Choreographer.getInstance().removeFrameCallback(motion)
        themeField(motion, "posted").setBoolean(motion, false)
    }
    fun captureTarget() { target = (themeField(state, "target").get(state) as IntArray).copyOf(); stop() }
    fun pose(pose: ThemeContrastPose) {
        val from = if (pose.reverse) target else start
        state.retarget(from, 0L, 0f)
        state.retarget(if (pose.reverse) start else target, 0L, pose.scale)
        pose.retargetAt?.let { time ->
            state.frame(time * 1_000_000L, pose.scale)
            state.retarget(from, time * 1_000_000L, pose.scale)
        }
        state.frame(pose.millis * 1_000_000L, pose.scale)
        stop()
    }
    fun snap(colors: IntArray) { state.retarget(colors, 0L, 0f); publish(); stop() }
    fun publish() { @Suppress("UNCHECKED_CAST") val callback = themeField(motion, "publish").get(motion) as () -> Unit; callback() }
}
private fun themeState(motion: ViewColorTransition) = themeField(motion, "state").get(motion) as ColorTransition
private fun themeEditorMotion(view: SettingsRetentionInputView) =
    smallthingz.ui.reverb.settings.ReverbRetentionInputView::class.java.getDeclaredMethod("getColorMotion")
        .apply { isAccessible = true }.invoke(view) as ViewColorTransition
private fun themeField(owner: Any, name: String) = themeField(owner.javaClass, name)
private fun themeField(type: Class<*>, name: String): Field = type.getDeclaredField(name).apply { isAccessible = true }
private fun themeViews(view: View): Sequence<View> = sequence {
    yield(view)
    if (view is ViewGroup) for (i in 0 until view.childCount) yieldAll(themeViews(view.getChildAt(i)))
}
private fun fullGlyph(mask: Bitmap, x: Int, y: Int): Boolean =
    mask.getPixel(x, y) ushr 24 == 255 && mask.getPixel(x - 1, y) ushr 24 == 255 &&
        mask.getPixel(x + 1, y) ushr 24 == 255 && mask.getPixel(x, y - 1) ushr 24 == 255 && mask.getPixel(x, y + 1) ushr 24 == 255
private fun countColor(bitmap: Bitmap, color: Int): Int {
    var count = 0
    for (y in 0 until bitmap.height) for (x in 0 until bitmap.width) if (bitmap.getPixel(x, y) == color) count++
    return count
}
private fun themeLuminance(color: Int): Double {
    fun channel(shift: Int): Double {
        val value = (color ushr shift and 255) / 255.0
        return if (value <= .04045) value / 12.92 else ((value + .055) / 1.055).pow(2.4)
    }
    return .2126 * channel(16) + .7152 * channel(8) + .0722 * channel(0)
}
private fun themeContrast(a: Int, b: Int): Double {
    val first = themeLuminance(a); val second = themeLuminance(b)
    return (maxOf(first, second) + .05) / (minOf(first, second) + .05)
}
