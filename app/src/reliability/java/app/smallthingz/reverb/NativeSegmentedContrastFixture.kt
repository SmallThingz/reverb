package app.smallthingz.reverb

import android.app.Application
import android.app.Instrumentation
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import android.text.SpannableString
import android.text.Spannable
import android.text.Spanned
import android.text.Layout
import android.text.method.TransformationMethod
import android.text.style.ForegroundColorSpan
import android.text.style.CharacterStyle
import android.text.style.LineHeightSpan
import android.text.style.ImageSpan
import android.text.style.LeadingMarginSpan
import android.text.style.LineBackgroundSpan
import android.view.Choreographer
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import app.smallthingz.reverb.nativeui.ReverbSegmentedPresentation
import java.io.File
import kotlin.math.pow
import org.json.JSONArray
import org.json.JSONObject
import smallthingz.ui.animation.ColorTransition
import smallthingz.ui.animation.view.ViewColorTransition
import smallthingz.ui.components.NativeChip
import smallthingz.ui.components.NativeChoice
import smallthingz.ui.components.NativeSegmentedChoice
import smallthingz.ui.components.NativeSegmentedControl
import smallthingz.ui.components.NativeSegmentedPresentation
import smallthingz.ui.controls.ControlPalette

/** Real capsule drawing at deterministic animation samples; not a replay of device callback timing. */
internal fun verifyNativeSegmentedContrast(instrumentation: Instrumentation, report: StringBuilder) {
    val context = instrumentation.targetContext
    check(context.packageName == "app.smallthingz.reverb.uitheme.reliability")
    check(context.applicationContext.javaClass == Application::class.java)
    val directory = File(context.filesDir, "native-segmented-contrast-${System.currentTimeMillis()}")
    check(directory.mkdir())
    val cases = JSONArray()
    val result = JSONObject().put("selector", "native_segmented_contrast").put("sdk", Build.VERSION.SDK_INT)
        .put("passed", false).put("cases", cases)
        .put("negative_control", "Same retained View with parent foreground disabled and former StateColorSurface publication; not an old APK")
    var activity: NativeParityActivity? = null
    var board: SegmentedContrastBoard? = null
    var failure: Throwable? = null
    fun <T> main(block: () -> T) = nativeParityOnMain(instrumentation, block)
    try {
        val host = launchCurrentUiSmokeActivity(instrumentation).also { activity = it }
        awaitNativeParityDraw(instrumentation, host.fixtureRoot)
        val fixture = main {
            SegmentedContrastBoard(host).also {
                board = it
                host.fixtureRoot.removeAllViews()
                host.fixtureRoot.addView(it, FrameLayout.LayoutParams(it.boardWidth, it.boardHeight))
            }
        }
        awaitNativeParityDraw(instrumentation, fixture)
        main { fixture.rebindPlainAfterMeasure() }
        result.put("plain_default_labels_rebound_after_measure", true)
        // The first case is the actual-class host probe's failing palette-retarget sample.
        val poses = listOf(
            ContrastPose("palette-retarget-outgoing", 1f, 40, 8, true),
            ContrastPose("incoming-before-coverage", 0f, 40, 8, true),
            ContrastPose("partial-forward", .5f, 40, 8, true),
            ContrastPose("partial-reverse", .35f, 48, 16, false, reverse = true),
            ContrastPose("dark-covered", 0f, 0, -1, false),
            ContrastPose("dark-uncovered", 1f, 0, -1, true),
            ContrastPose("light-settled", 1f, 200, 8, false),
            ContrastPose("zero-scale-target", 1f, 200, 0, false, scale = 0f),
            ContrastPose("rtl-partial", .5f, 40, 8, true, true),
        )
        for (pose in poses) {
            val entry = JSONObject().put("name", pose.name).put("pill_fraction", pose.pill.toDouble())
                .put("sample_millis", pose.millis).put("palette_retarget_millis", pose.paletteAt)
                .put("duration_scale", pose.scale.toDouble()).put("reverse", pose.reverse).put("rtl", pose.rtl)
            cases.put(entry)
            val background = main { fixture.pose(pose); fixture.backgroundBitmap() }
            val mask = main { fixture.glyphMask() }
            try {
                main {
                    val bitmap = fixture.bitmap()
                    try {
                        saveNativeParityBitmap(directory, "${pose.name}-software.png", bitmap)
                        entry.put("software", fixture.verify(bitmap, background, mask, pose.requireOldFailure))
                    } finally { bitmap.recycle() }
                }
                val hardware = captureNativeParityContent(instrumentation, host) {
                    fixture.invalidateContent()
                    fixture
                }
                try {
                    saveNativeParityBitmap(directory, "${pose.name}-hardware.png", hardware)
                    entry.put("hardware", main { fixture.verify(hardware, background, mask, pose.requireOldFailure) })
                } finally { hardware.recycle() }
            } finally { background.recycle(); mask.recycle() }
        }
        val ripple = JSONObject().put("name", "theme-retarget-focused-ripple")
        cases.put(ripple)
        main { fixture.poseRipple() }
        awaitNativeParityDraw(instrumentation, fixture)
        val animatorScale = Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
        check(animatorScale.isFinite() && animatorScale >= 0f)
        ripple.put("observed_animator_scale", animatorScale.toDouble())
        // Patterned focus backgrounds use an 80ms animator that jumpToCurrentState does not end.
        // The later actual underlay equality check remains authoritative if platform timing differs.
        SystemClock.sleep((100 * animatorScale + 100).toLong().coerceIn(100L, 5_000L))
        val rippleMask = main { fixture.glyphMask() }
        try {
            main {
                val foreground = fixture.bitmap()
                val paintBefore = fixture.paintSnapshot()
                fixture.suppressGlyphs()
                try {
                    val underlay = fixture.bitmap()
                    try {
                        check(fixture.paintSnapshot() == paintBefore)
                        saveNativeParityBitmap(directory, "theme-retarget-focused-ripple-software.png", foreground)
                        saveNativeParityBitmap(directory, "theme-retarget-focused-ripple-software-underlay.png", underlay)
                        ripple.put("software", fixture.verify(foreground, underlay, rippleMask, true, true))
                    } finally { underlay.recycle() }
                } finally { fixture.restoreGlyphs(); foreground.recycle() }
            }
            val paintBefore = main { fixture.paintSnapshot().also { fixture.suppressGlyphs() } }
            val firstUnderlay = captureNativeParityContent(instrumentation, host) { fixture.invalidateContent(); fixture }
            try {
                main { fixture.restoreGlyphs() }
                val foreground = captureNativeParityContent(instrumentation, host) { fixture.invalidateContent(); fixture }
                try {
                    main { fixture.suppressGlyphs() }
                    val lastUnderlay = captureNativeParityContent(instrumentation, host) { fixture.invalidateContent(); fixture }
                    try {
                        check(firstUnderlay.sameAs(lastUnderlay)) { "Focused ripple underlay was not stable across foreground capture" }
                        saveNativeParityBitmap(directory, "theme-retarget-focused-ripple-hardware.png", foreground)
                        saveNativeParityBitmap(directory, "theme-retarget-focused-ripple-hardware-underlay.png", firstUnderlay)
                        ripple.put("hardware", main {
                            check(fixture.paintSnapshot() == paintBefore)
                            fixture.verify(foreground, firstUnderlay, rippleMask, true, true)
                                .put("underlay_stable_before_after", true)
                        })
                    } finally { lastUnderlay.recycle() }
                } finally { foreground.recycle() }
            } finally { main { fixture.restoreGlyphs() }; firstUnderlay.recycle() }
        } finally { rippleMask.recycle(); main { fixture.restoreGlyphs(); fixture.releaseRipple() } }
        val styled = JSONObject().put("name", "styled-content-preserved")
        cases.put(styled)
        main {
            fixture.bindStyledContent()
            fixture.pose(ContrastPose("styled-content-preserved", 1f, 40, 8, false))
            val bitmap = fixture.bitmap()
            try {
                saveNativeParityBitmap(directory, "styled-content-preserved-software.png", bitmap)
                styled.put("software", fixture.verifyStyledContent(bitmap))
            } finally { bitmap.recycle() }
        }
        val styledHardware = captureNativeParityContent(instrumentation, host) { fixture.invalidateContent(); fixture }
        try {
            saveNativeParityBitmap(directory, "styled-content-preserved-hardware.png", styledHardware)
            styled.put("hardware", main { fixture.verifyStyledContent(styledHardware) })
        } finally { styledHardware.recycle() }
        cases.put(JSONObject().put("name", "custom-presentation-unknown-paint")
            .put("software", main { JSONObject().put("unknown", fixture.verifyUnknownPresentation())
                .put("translucent", fixture.verifyUnknownPresentation(0x80181818.toInt())) }))
        cases.put(JSONObject().put("name", "mode-exit-restores-ripple")
            .put("software", main { JSONObject().put("styled", fixture.verifyModeExit(true))
                .put("unknown_paint", fixture.verifyModeExit(false)) }))
        cases.put(JSONObject().put("name", "retained-live-styles")
            .put("software", main { fixture.verifyLiveStyledContent() }))
        result.put("passed", true)
    } catch (error: Throwable) { failure = error }
    finally {
        failure = captureNativeParityCleanup(failure) { main { board?.close(); activity?.fixtureRoot?.removeAllViews(); activity?.finish() } }
        if (failure != null) result.put("failure", failure!!.stackTraceToString())
        failure = captureNativeParityCleanup(failure) {
            writeQaEvidenceAtomically(File(directory, "result.json")) { it.write(result.toString(2).toByteArray()) }
        }
        report.appendLine("SEGMENTED_CONTRAST_EVIDENCE ${directory.absolutePath}")
    }
    failure?.let { throw it }
    report.appendLine("PASS native_segmented_contrast software and hardware foreground contrast against actual painted track and pill")
}

private data class ContrastPose(val name: String, val pill: Float, val millis: Int, val paletteAt: Int,
    val requireOldFailure: Boolean, val rtl: Boolean = false, val reverse: Boolean = false, val scale: Float = .5f)

private class SegmentedContrastBoard(context: Context) : FrameLayout(context), AutoCloseable {
    private val dark = 0xff181818.toInt()
    private val light = 0xfffafafa.toInt()
    private val selectedFill = 0xffa9c0ff.toInt()
    private val selectedInk = 0xff182950.toInt()
    private val idleInk = 0xffaaaaaa.toInt()
    private val palette = ControlPalette(dark, dark, idleInk, idleInk, selectedFill, selectedInk, selectedFill, 0)
    private val presentations = List(2) { ReverbSegmentedPresentation(context) }
    private val controls = presentations.map { NativeSegmentedControl(context, palette, it) }
    private val rows = controls.map { field(it, "choices") as NativeSegmentedChoice }
    private val chips get() = rows.flatMap { row -> (0 until row.childCount).map { row.getChildAt(it) as NativeChip } }
    private val rowHeight = (42 * resources.displayMetrics.density).toInt() + 2
    private var suppressedGlyphs: List<Pair<CharSequence, Int>>? = null
    val boardWidth = 960
    val boardHeight = rowHeight * 2 + 48

    init {
        setBackgroundColor(Color.MAGENTA)
        controls.forEachIndexed { index, control ->
            control.bind(listOf("Auto", "Light", "Dark").map {
                NativeChoice(it, it, ColorDrawable(Color.WHITE))
            }, "Light", palette)
            addView(control, LayoutParams(boardWidth - 32, rowHeight).apply { leftMargin = 16; topMargin = 16 + index * rowHeight })
        }
    }

    fun pose(pose: ContrastPose) {
        layoutDirection = if (pose.rtl) View.LAYOUT_DIRECTION_RTL else View.LAYOUT_DIRECTION_LTR
        measure(MeasureSpec.makeMeasureSpec(boardWidth, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(boardHeight, MeasureSpec.EXACTLY))
        layout(0, 0, boardWidth, boardHeight)
        controls.forEachIndexed { rowIndex, _ ->
            val row = rows[rowIndex]
            val indicator = checkNotNull(field(row, "selectionIndicator"))
            Choreographer.getInstance().removeFrameCallback(indicator as Choreographer.FrameCallback)
            setField(indicator, "posted", false)
            setField(indicator, "running", false)
            setField(indicator, "initialized", true)
            val first = row.getChildAt(0)
            val second = row.getChildAt(1)
            val x = first.left + (second.left - first.left) * pose.pill
            (field(indicator, "displayed") as RectF).set(x, 0f, x + first.width, row.height.toFloat())
            val indicatorMotion = field(indicator, "colors") as ViewColorTransition
            indicatorMotion.close()
            val track = ColorTransition(intArrayOf(dark))
            if (pose.paletteAt >= 0) {
                track.retarget(intArrayOf(light), pose.paletteAt * 1_000_000L, pose.scale)
                track.frame(pose.millis * 1_000_000L, pose.scale)
            }
            val surface = track.color(0)
            presentations[rowIndex].surface.setPaletteImmediately(surface, 0)
            val fillState = field(indicatorMotion, "state") as ColorTransition
            fillState.retarget(intArrayOf(selectedFill), 0L, 0f)
            if (rowIndex == 1) {
                (field(row, "uniformSelectionForeground") as? AutoCloseable)?.close()
                setField(row, "uniformSelectionForeground", null)
            }
            for (index in 0 until row.childCount) {
                val chip = row.getChildAt(index) as NativeChip
                val stateSurface = checkNotNull(field(chip, "selectionAnimation"))
                val motion = field(stateSurface, "motion") as ViewColorTransition
                motion.close()
                if (rowIndex == 1) {
                    setField(chip, "uniformSelectionForeground", null)
                    setField(stateSurface, "parentOwnsForeground", false)
                }
                val state = field(motion, "state") as ColorTransition
                val wasSelected = index == 0
                val startInk = if (wasSelected) selectedInk else idleInk
                state.retarget(intArrayOf(if (wasSelected) selectedFill else 0, 0, startInk, 0, startInk, 0, dark), 0L, 0f)
                val targetInk = if (index == 1) selectedInk else idleInk
                val targetFill = if (index == 1) selectedFill else 0
                state.retarget(intArrayOf(targetFill, 0, targetInk, 0, targetInk, 0, dark), 0L, pose.scale)
                if (pose.paletteAt >= 0) {
                    val selected = index == if (pose.reverse) 0 else 1
                    val newInk = if (selected) selectedInk else 0xff606060.toInt()
                    val newFill = if (selected) selectedFill else 0
                    state.retarget(intArrayOf(newFill, 0, newInk, 0, newInk, 0, light), pose.paletteAt * 1_000_000L, pose.scale)
                }
                state.frame(pose.millis * 1_000_000L, pose.scale)
                stateSurface.javaClass.getDeclaredMethod("publish").apply { isAccessible = true }.invoke(stateSurface)
                chip.invalidate()
            }
            row.invalidate()
        }
        invalidate()
    }

    fun bitmap(): Bitmap = Bitmap.createBitmap(boardWidth, boardHeight, Bitmap.Config.ARGB_8888).also { draw(Canvas(it)) }
    fun backgroundBitmap(): Bitmap {
        chips.forEach { it.alpha = 0f }
        return try { bitmap() } finally { chips.forEach { it.alpha = 1f } }
    }
    fun invalidateContent() { chips.forEach { it.invalidate() }; rows.forEach { it.invalidate() }; invalidate() }

    fun rebindPlainAfterMeasure() {
        check(chips.all { it.isLaidOut && it.layout != null })
        controls.forEach { control ->
            control.bind(listOf("Auto", "Light", "Dark").map {
                NativeChoice(it, it, ColorDrawable(Color.WHITE))
            }, "Light", palette)
        }
        check(chips.all { field(it, "uniformSelectionEligible") == true })
    }

    fun verifyUnknownPresentation(suppliedColor: Int? = null): JSONObject {
        val custom = object : NativeSegmentedPresentation {
            override val surface = FrameLayout(context).apply { setBackgroundColor(dark) }
            override fun bindSurface(palette: ControlPalette) = Unit
            override val paintedSurfaceColor: Int? get() = suppliedColor
        }
        val customPalette = palette.copy(surface = light)
        val control = NativeSegmentedControl(context, customPalette, custom)
        try {
            control.bind(listOf(NativeChoice("a", "Plain", ColorDrawable(Color.WHITE)),
                NativeChoice("b", "Selected", ColorDrawable(Color.WHITE))), "b", customPalette)
            control.measure(MeasureSpec.makeMeasureSpec(boardWidth, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(rowHeight, MeasureSpec.EXACTLY))
            control.layout(0, 0, control.measuredWidth, control.measuredHeight)
            val actual = Bitmap.createBitmap(control.width, control.height, Bitmap.Config.ARGB_8888)
            val legacy = Bitmap.createBitmap(control.width, control.height, Bitmap.Config.ARGB_8888)
            try {
                control.draw(Canvas(actual))
                val row = field(control, "choices") as NativeSegmentedChoice
                check((0 until row.childCount).all { field(row.getChildAt(it), "uniformSelectionForeground") == null })
                (field(row, "uniformSelectionForeground") as AutoCloseable).close()
                control.draw(Canvas(legacy))
                check(actual.sameAs(legacy))
                var originalInk = 0
                for (y in 0 until actual.height) for (x in 0 until actual.width)
                    if (actual.getPixel(x, y) == idleInk) originalInk++
                check(originalInk > 50)
                return JSONObject().put("legacy_pixels_equal", true).put("original_ink_pixels", originalInk)
            } finally { actual.recycle(); legacy.recycle() }
        } finally { control.close() }
    }

    fun verifyModeExit(styled: Boolean): JSONObject {
        val gray = 0xff747474.toInt()
        var reportedColor: Int? = gray
        val custom = object : NativeSegmentedPresentation {
            override val surface = FrameLayout(context).apply { setBackgroundColor(gray) }
            override fun bindSurface(palette: ControlPalette) = Unit
            override val paintedSurfaceColor: Int? get() = reportedColor
        }
        val colors = ControlPalette(gray, gray, Color.WHITE, Color.WHITE, Color.WHITE,
            Color.BLACK, Color.WHITE, 0)
        val control = NativeSegmentedControl(context, colors, custom)
        val options = listOf(NativeChoice("a", "Plain"), NativeChoice("b", "Selected"))
        try {
            control.bind(options, "b", colors)
            control.measure(MeasureSpec.makeMeasureSpec(boardWidth, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(rowHeight, MeasureSpec.EXACTLY))
            control.layout(0, 0, control.measuredWidth, control.measuredHeight)
            val bitmap = Bitmap.createBitmap(control.width, control.height, Bitmap.Config.ARGB_8888)
            try {
                control.draw(Canvas(bitmap))
                val row = field(control, "choices") as NativeSegmentedChoice
                val chip = row.getChildAt(0) as NativeChip
                val surface = checkNotNull(field(chip, "selectionAnimation"))
                val motion = field(surface, "motion") as ViewColorTransition
                val state = field(motion, "state") as ColorTransition
                check(!state.running && field(chip, "uniformSelectionForeground") != null)
                val capped = field(surface, "publishedRippleColor") as Int
                check(capped ushr 24 in 1..39)
                val raw = state.color(3)
                check(raw == 0x28ffffff)
                if (styled) {
                    // Identical characters and palette leave all motion targets unchanged.
                    control.bind(listOf(NativeChoice("a", SpannableString("Plain")), options[1]), "b", colors)
                } else {
                    reportedColor = null
                    control.draw(Canvas(bitmap))
                }
                check(field(chip, "uniformSelectionForeground") == null && !state.running)
                check(field(surface, "publishedRippleColor") == raw) { "Idle ownership exit retained a capped ripple publication" }
                return JSONObject().put("capped_alpha", capped ushr 24).put("restored_alpha", raw ushr 24)
                    .put("idle_target_unchanged", true)
            } finally { bitmap.recycle() }
        } finally { control.close() }
    }

    fun verifyLiveStyledContent(): JSONObject {
        rebindPlainAfterMeasure()
        pose(ContrastPose("retained-live-styles", 1f, 40, 8, false))
        val retained = rows.map { checkNotNull(it.choiceView("Auto")) }
        val checks = JSONArray()
        fun sample(name: String, color: Int?, uniform: Boolean) {
            measure(MeasureSpec.makeMeasureSpec(boardWidth, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(boardHeight, MeasureSpec.EXACTLY))
            layout(0, 0, boardWidth, boardHeight)
            val bitmap = bitmap()
            try {
                check((field(retained[0], "uniformSelectionForeground") != null) == uniform)
                val counts = retained.map { chip ->
                    val rect = Rect(0, 0, chip.width, chip.height)
                    offsetDescendantRectToMyCoords(chip, rect)
                    var count = 0
                    if (color != null) for (y in rect.top until rect.bottom) for (x in rect.left until rect.right)
                        if (bitmap.getPixel(x, y) == color) count++
                    count
                }
                if (color != null) check(counts[0] > 10 && counts[0] == counts[1]) {
                    "Live styled RGB differs from the retained legacy renderer: $name $counts"
                }
                checks.put(JSONObject().put("name", name).put("uniform", uniform)
                    .put("fixed_and_legacy_color_pixels", JSONArray(counts)))
            } finally { bitmap.recycle() }
        }
        retained.forEach { chip ->
            chip.text = SpannableString("A#to").apply {
                setSpan(ForegroundColorSpan(Color.YELLOW), 0, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                val image = ColorDrawable(Color.CYAN).apply { setBounds(0, 0, 20, 20) }
                setSpan(ImageSpan(image), 1, 2, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }
        sample("replacement-full-character", Color.YELLOW, false)
        sample("replacement-image", Color.CYAN, false)
        retained.forEach { it.setText("Auto", TextView.BufferType.SPANNABLE) }
        sample("replacement-restored", null, true)
        val buffers = retained.map { checkNotNull(it.text as? Spannable) }
        val lineHeights = buffers.map { buffer -> object : LineHeightSpan {
            override fun chooseHeight(text: CharSequence, start: Int, end: Int, spanstartv: Int,
                lineHeight: Int, fm: Paint.FontMetricsInt) = Unit
        }.also { buffer.setSpan(it, 0, buffer.length, Spanned.SPAN_INCLUSIVE_INCLUSIVE) } }
        sample("synthetic-line-height-compatible", null, true)
        buffers.forEachIndexed { index, buffer -> buffer.removeSpan(lineHeights[index]) }
        sample("synthetic-line-height-restored", null, true)
        val foreground = buffers.map { buffer -> ForegroundColorSpan(Color.YELLOW).also {
            buffer.setSpan(it, 0, buffer.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        } }
        sample("in-place-full-character", Color.YELLOW, false)
        buffers.forEachIndexed { index, buffer -> buffer.removeSpan(foreground[index]) }
        sample("in-place-character-restored", null, true)
        val margins = buffers.map { buffer -> object : LeadingMarginSpan {
            override fun getLeadingMargin(first: Boolean) = 20
            override fun drawLeadingMargin(canvas: Canvas, paint: Paint, x: Int, dir: Int,
                top: Int, baseline: Int, bottom: Int, text: CharSequence, start: Int, end: Int,
                first: Boolean, layout: Layout) {
                val old = paint.color
                try { paint.color = Color.GREEN; canvas.drawRect(x.toFloat(), top.toFloat(), (x + 12).toFloat(), (top + 8).toFloat(), paint) }
                finally { paint.color = old }
            }
        }.also { buffer.setSpan(it, 0, buffer.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE) } }
        sample("in-place-leading-margin", Color.GREEN, false)
        buffers.forEachIndexed { index, buffer -> buffer.removeSpan(margins[index]) }
        sample("in-place-margin-restored", null, true)
        val backgrounds = buffers.map { buffer -> object : LineBackgroundSpan {
            override fun drawBackground(canvas: Canvas, paint: Paint, left: Int, right: Int,
                top: Int, baseline: Int, bottom: Int, text: CharSequence, start: Int, end: Int,
                lineNumber: Int) {
                val old = paint.color
                try { paint.color = Color.GREEN; canvas.drawRect(left.toFloat(), top.toFloat(), (left + 12).toFloat(), (top + 8).toFloat(), paint) }
                finally { paint.color = old }
            }
        }.also { buffer.setSpan(it, 0, buffer.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE) } }
        sample("in-place-line-background", Color.GREEN, false)
        buffers.forEachIndexed { index, buffer -> buffer.removeSpan(backgrounds[index]) }
        sample("in-place-background-restored", null, true)
        var styledTransformation = true
        val transformation = object : TransformationMethod {
            override fun getTransformation(source: CharSequence, view: View): CharSequence {
                if (!styledTransformation) return source.toString()
                return SpannableString(source.toString()).apply {
                    setSpan(ForegroundColorSpan(Color.YELLOW), 0, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    val image = ColorDrawable(Color.CYAN).apply { setBounds(0, 0, 20, 20) }
                    setSpan(ImageSpan(image), 1, 2, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
            }
            override fun onFocusChanged(view: View, sourceText: CharSequence, focused: Boolean,
                direction: Int, previouslyFocusedRect: Rect?) = Unit
        }
        retained.forEach { it.transformationMethod = transformation }
        sample("transformed-full-character", Color.YELLOW, false)
        sample("transformed-image", Color.CYAN, false)
        check(retained.all { chip ->
            val source = chip.text as? Spanned
            (source == null || source.nextSpanTransition(-1, source.length + 1, CharacterStyle::class.java) > source.length) &&
                chip.layout.text is Spanned
        }) { "Transformed style must be absent from the source buffer" }
        styledTransformation = false
        retained.forEach { it.text = "Auto" }
        sample("transformed-plain-restored", null, true)
        retained.forEach { it.transformationMethod = null }
        sample("transformation-removed", null, true)
        retained.forEach { it.setText("Auto", TextView.BufferType.SPANNABLE) }
        val endpointBuffers = retained.map { checkNotNull(it.text as? Spannable) }
        val endpoints = endpointBuffers.map { buffer -> ForegroundColorSpan(Color.YELLOW).also {
            buffer.setSpan(it, buffer.length, buffer.length, Spanned.SPAN_INCLUSIVE_INCLUSIVE)
        } }
        sample("endpoint-character-conservative", null, false)
        endpointBuffers.forEachIndexed { index, buffer -> buffer.removeSpan(endpoints[index]) }
        sample("endpoint-character-restored", null, true)
        retained.forEach { chip ->
            chip.text = SpannableString("").apply {
                setSpan(backgrounds[0], 0, 0, Spanned.SPAN_INCLUSIVE_INCLUSIVE)
            }
        }
        sample("empty-paragraph-conservative", null, false)
        retained.forEach { it.text = "Auto" }
        sample("empty-paragraph-restored", null, true)
        return JSONObject().put("checks", checks).put("public_retained_views", true)
            .put("same_buffer_mutations", true).put("full_range_starts_at_zero", true)
            .put("transformed_display_styles", true).put("endpoint_styles_conservative", true)
            .put("explicit_spannable_mutation_buffers", true).put("synthetic_line_height_span_compatible", true)
    }

    fun poseRipple() {
        pose(ContrastPose("theme-retarget-focused-ripple", 1f, 0, -1, false))
        val lightField = 0xfff7f2fa.toInt(); val darkField = 0xff1d1b20.toInt()
        val lightAccent = 0xff6750a4.toInt(); val darkAccent = 0xffd0bcff.toInt()
        val track = ColorTransition(intArrayOf(lightField))
        track.retarget(intArrayOf(darkField), 0L, 1f); track.frame(81_000_000L, 1f)
        val indication = Class.forName("smallthingz.ui.controls.SelectionContrastKt").getDeclaredMethod(
            "selectionIndication", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
        rows.forEachIndexed { rowIndex, row ->
            presentations[rowIndex].surface.setPaletteImmediately(track.color(0), 0)
            val indicator = checkNotNull(field(row, "selectionIndicator"))
            val indicatorState = field(field(indicator, "colors") as ViewColorTransition, "state") as ColorTransition
            indicatorState.retarget(intArrayOf(lightAccent), 0L, 0f)
            indicatorState.retarget(intArrayOf(darkAccent), 0L, 1f); indicatorState.frame(81_000_000L, 1f)
            for (index in 0 until row.childCount) {
                val chip = row.getChildAt(index) as NativeChip
                val selected = index == 1
                val startInk = if (selected) Color.WHITE else 0xff1d1b20.toInt()
                val endInk = if (selected) 0xff381e72.toInt() else 0xffe6e0e9.toInt()
                val startRipple = if (selected) indication.invoke(null, lightAccent, lightField, startInk, lightAccent) as Int else lightAccent
                val endRipple = if (selected) indication.invoke(null, darkAccent, darkField, endInk, darkAccent) as Int else darkAccent
                val surface = checkNotNull(field(chip, "selectionAnimation"))
                val state = field(field(surface, "motion") as ViewColorTransition, "state") as ColorTransition
                state.retarget(intArrayOf(if (selected) lightAccent else 0, 0, startInk,
                    (startRipple and 0xffffff) or (40 shl 24), startInk, 0, lightField), 0L, 0f)
                state.retarget(intArrayOf(if (selected) darkAccent else 0, 0, endInk,
                    (endRipple and 0xffffff) or (40 shl 24), endInk, 0, darkField), 0L, 1f)
                state.frame(81_000_000L, 1f)
                surface.javaClass.getDeclaredMethod("publish").apply { isAccessible = true }.invoke(surface)
                chip.drawableHotspotChanged(chip.width / 2f, chip.height / 2f)
                // Exercise the actual stationary focus layer on both software and hardware.
                // Patterned pressed foregrounds deliberately do not draw on a software Canvas.
                chip.background.state = intArrayOf(android.R.attr.state_enabled, android.R.attr.state_focused,
                    android.R.attr.state_window_focused, if (selected) android.R.attr.state_selected else 0)
                chip.jumpDrawablesToCurrentState()
            }
        }
        invalidateContent()
    }

    fun paintSnapshot(): List<List<Int>> = chips.map { chip ->
        val surface = checkNotNull(field(chip, "selectionAnimation"))
        val row = chip.parent as NativeSegmentedChoice
        val indicator = checkNotNull(field(row, "selectionIndicator"))
        val bounds = field(indicator, "displayed") as RectF
        val state = field(field(surface, "motion") as ViewColorTransition, "state") as ColorTransition
        listOf(chip.currentTextColor, state.color(3), field(surface, "publishedRippleColor") as Int,
            chip.background.state.contentHashCode(), if (field(chip, "uniformSelectionForeground") == null) 0 else 1,
            chip.measuredWidth, chip.measuredHeight, chip.left, chip.top, chip.right, chip.bottom,
            bounds.left.toBits(), bounds.top.toBits(), bounds.right.toBits(), bounds.bottom.toBits())
    }

    fun suppressGlyphs() {
        check(suppressedGlyphs == null)
        suppressedGlyphs = chips.map { it.text to checkNotNull(it.compoundDrawablesRelative[0]).alpha }
        chips.forEach { chip ->
            // Spaces remove glyphs without introducing caller styles or changing sampled ink.
            // The fixed row geometry, ownership, and actual ripple state are asserted unchanged.
            chip.text = " ".repeat(chip.text.length)
            checkNotNull(chip.compoundDrawablesRelative[0]).alpha = 0
        }
    }

    fun restoreGlyphs() {
        val saved = suppressedGlyphs ?: return
        chips.forEachIndexed { index, chip ->
            chip.text = saved[index].first
            checkNotNull(chip.compoundDrawablesRelative[0]).alpha = saved[index].second
        }
        suppressedGlyphs = null
    }

    fun releaseRipple() { chips.forEach { it.background.state = it.drawableState; it.jumpDrawablesToCurrentState() } }

    fun bindStyledContent() {
        controls.forEach { control ->
            val label = SpannableString("S#tyled").apply {
                setSpan(ForegroundColorSpan(Color.YELLOW), 0, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                val size = (16 * resources.displayMetrics.density).toInt()
                val image = ColorDrawable(Color.CYAN).apply { setBounds(0, 0, size, size) }
                setSpan(ImageSpan(image), 1, 2, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            control.bind(listOf(NativeChoice("Auto", label, ColorDrawable(Color.WHITE)),
                NativeChoice("Light", "Light", ColorDrawable(Color.WHITE)),
                NativeChoice("Dark", "Dark", ColorDrawable(Color.WHITE))), "Light", palette)
        }
    }

    fun verifyStyledContent(bitmap: Bitmap): JSONObject {
        val colors = JSONArray()
        for (row in rows) {
            val chip = row.getChildAt(0) as NativeChip
            check(chip.text is Spanned && field(chip, "uniformSelectionForeground") == null)
            val rect = Rect(0, 0, chip.width, chip.height)
            offsetDescendantRectToMyCoords(chip, rect)
            var yellow = 0; var cyan = 0
            for (y in rect.top until rect.bottom) for (x in rect.left until rect.right) {
                when (bitmap.getPixel(x, y)) { Color.YELLOW -> yellow++; Color.CYAN -> cyan++ }
            }
            check(yellow > 20 && cyan > 100) { "Explicit text/image span RGB was flattened" }
            colors.put(JSONObject().put("yellow_text_pixels", yellow).put("cyan_image_pixels", cyan))
        }
        check(colors.getJSONObject(0).toString() == colors.getJSONObject(1).toString())
        return JSONObject().put("rgb_preserved", true).put("fixed_and_legacy", colors)
    }

    fun glyphMask(): Bitmap {
        val bitmap = Bitmap.createBitmap(boardWidth, boardHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.BLACK)
        val draw = NativeChip::class.java.getDeclaredMethod("onDraw", Canvas::class.java).apply { isAccessible = true }
        for (chip in chips) {
            val oldInk = chip.currentTextColor
            val oldTint = chip.compoundDrawableTintList
            val foreground = field(chip, "uniformSelectionForeground")
            val rect = Rect(0, 0, chip.width, chip.height)
            offsetDescendantRectToMyCoords(chip, rect)
            val save = canvas.save()
            try {
                // The same TextView/icon renderer supplies the coverage mask, without its background.
                setField(chip, "uniformSelectionForeground", null)
                chip.setTextColor(Color.WHITE)
                chip.compoundDrawableTintList = ColorStateList.valueOf(Color.WHITE)
                canvas.translate(rect.left.toFloat(), rect.top.toFloat())
                draw.invoke(chip, canvas)
            } finally {
                canvas.restoreToCount(save)
                chip.setTextColor(oldInk)
                chip.compoundDrawableTintList = oldTint
                setField(chip, "uniformSelectionForeground", foreground)
            }
        }
        return bitmap
    }

    fun verify(bitmap: Bitmap, background: Bitmap, mask: Bitmap, requireOldFailure: Boolean, overlay: Boolean = false): JSONObject {
        check(bitmap.width == boardWidth && bitmap.height == boardHeight)
        val samples = IntArray(2)
        val failures = IntArray(2)
        val textSamples = IntArray(2)
        val textFailures = IntArray(2)
        val overlaySamples = IntArray(2)
        rows.forEachIndexed { rowIndex, row ->
            if (rowIndex == 0) check((0 until row.childCount).all {
                field(row.getChildAt(it), "uniformSelectionForeground") != null
            }) { "Plain measured labels unexpectedly lost parent contrast rendering" }
            val sampledPill = if (overlay) {
                val indicator = checkNotNull(field(row, "selectionIndicator"))
                (field(indicator, "colors") as ViewColorTransition).color(0)
            } else selectedFill
            for (index in 0 until row.childCount) {
                val chip = row.getChildAt(index) as NativeChip
                val icon = checkNotNull(chip.compoundDrawablesRelative[0])
                val iconLeft = if (chip.layoutDirection == View.LAYOUT_DIRECTION_RTL)
                    chip.width - chip.paddingRight - icon.bounds.width() + icon.bounds.left
                else chip.paddingLeft + icon.bounds.left
                val iconTop = chip.paddingTop + (chip.height - chip.paddingTop - chip.paddingBottom - icon.bounds.height()) / 2
                val iconRect = Rect(iconLeft, iconTop, iconLeft + icon.bounds.width(), iconTop + icon.bounds.height())
                offsetDescendantRectToMyCoords(chip, iconRect)
                val rect = Rect(1, 1, chip.width - 1, chip.height - 1)
                offsetDescendantRectToMyCoords(chip, rect)
                for (y in rect.top until rect.bottom) for (x in rect.left until rect.right) {
                    if (mask.getPixel(x, y) != Color.WHITE || mask.getPixel(x - 1, y) != Color.WHITE ||
                        mask.getPixel(x + 1, y) != Color.WHITE || mask.getPixel(x, y - 1) != Color.WHITE ||
                        mask.getPixel(x, y + 1) != Color.WHITE) continue
                    val under = background.getPixel(x, y)
                    // Exclude antialiased pill edge pixels, not low-contrast foreground pixels.
                    if (overlay) {
                        if (under != presentations[rowIndex].surface.paintedFillColor && under != sampledPill) overlaySamples[rowIndex]++
                    } else if (under != presentations[rowIndex].surface.paintedFillColor && under != selectedFill) continue
                    if (iconRect.contains(x, y)) {
                        samples[rowIndex]++
                        if (contrast(bitmap.getPixel(x, y), under) < 4.49) failures[rowIndex]++
                    } else {
                        textSamples[rowIndex]++
                        if (contrast(bitmap.getPixel(x, y), under) < 4.49) textFailures[rowIndex]++
                    }
                }
            }
        }
        check(samples.all { it > 100 }) { "No nonvacuous glyph-interior sample" }
        check(textSamples.all { it > 10 }) { "No nonvacuous text-interior sample" }
        check(failures[0] == 0) { "Fixed capsule has ${failures[0]} low-contrast icon pixels" }
        check(textFailures[0] == 0) { "Fixed capsule has ${textFailures[0]} low-contrast text pixels" }
        if (overlay) check(overlaySamples.all { it > 100 }) { "Actual focused RippleDrawable overlay was not painted" }
        if (requireOldFailure) check(failures[1] > 0) { "Former publication did not reproduce its contrast mismatch" }
        return JSONObject().put("fixed_samples", samples[0]).put("fixed_failures", failures[0])
            .put("old_samples", samples[1]).put("old_failures", failures[1]).put("old_failure_required", requireOldFailure)
            .put("fixed_text_samples", textSamples[0]).put("fixed_text_failures", textFailures[0])
            .put("old_text_samples", textSamples[1]).put("old_text_failures", textFailures[1])
            .put("actual_overlay_samples", JSONArray(overlaySamples.toList()))
    }

    override fun close() { controls.forEach { it.close() } }
    private fun field(owner: Any, name: String): Any? = owner.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(owner)
    private fun setField(owner: Any, name: String, value: Any?) = owner.javaClass.getDeclaredField(name).apply { isAccessible = true }.set(owner, value)
    private fun contrast(a: Int, b: Int): Double {
        fun luminance(color: Int): Double {
            fun channel(shift: Int): Double {
                val c = (color ushr shift and 255) / 255.0
                return if (c <= .04045) c / 12.92 else ((c + .055) / 1.055).pow(2.4)
            }
            return .2126 * channel(16) + .7152 * channel(8) + .0722 * channel(0)
        }
        val x = luminance(a); val y = luminance(b)
        return (maxOf(x, y) + .05) / (minOf(x, y) + .05)
    }
}
