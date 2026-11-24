package app.smallthingz.reverb

import android.app.Application
import android.app.Instrumentation
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.os.Build
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import smallthingz.ui.components.NativeGroupLayout
import smallthingz.ui.controls.textSurfaceForeground
import smallthingz.ui.graphics.NoiseDrawable
import smallthingz.ui.reverb.ReverbTextSurface
import smallthingz.ui.reverb.ReverbTextSurfaceScope
import smallthingz.ui.reverb.ReverbTextSurfaceTarget
import smallthingz.ui.reverb.ReverbEnvironment
import smallthingz.ui.reverb.settings.ReverbRetentionMode
import smallthingz.ui.reverb.settings.ReverbRetentionSection
import kotlin.math.roundToInt

/** Attached View/PixelCopy contract, not a frame-time benchmark or a typography contrast test. */
internal fun verifyNativeTextSurfaceInvalidation(instrumentation: Instrumentation, report: StringBuilder) {
    val context = instrumentation.targetContext
    check(context.packageName == "app.smallthingz.reverb.uitheme.reliability")
    check(context.applicationContext.javaClass == Application::class.java)
    val preferences = getRecorderPreferences(context).all.toMap()
    val directory = File(context.filesDir, "native-text-surface-invalidation-${System.currentTimeMillis()}")
    check(directory.mkdir())
    val cases = JSONArray()
    val result = JSONObject().put("selector", "native_text_surface_invalidation")
        .put("sdk", Build.VERSION.SDK_INT).put("passed", false).put("cases", cases)
        .put("scope", "Attached real Views: exact ink-invalidation callback counts and software/hardware ink/underlay pixels")
        .put("pixel_contract", "Deterministic flat swatches use the production current-surface sampler and ink resolver; not font raster, animation timing, or whole Settings contrast")
        .put("timing_improvement_claimed", false)
    var host: NativeParityActivity? = null
    var fixture: TextSurfaceInvalidationFixture? = null
    var failure: Throwable? = null
    fun <T> main(action: () -> T): T = nativeParityOnMain(instrumentation, action)
    fun persist() = writeQaEvidenceAtomically(File(directory, "result.json")) {
        it.write(result.toString(2).toByteArray())
    }
    try {
        persist()
        val activity = launchCurrentUiSmokeActivity(instrumentation).also { host = it }
        val scene = main {
            TextSurfaceInvalidationFixture(activity).also {
                fixture = it
                activity.fixtureRoot.addView(it.root,
                    FrameLayout.LayoutParams(192, it.targets.size * INK_ROW_HEIGHT, Gravity.CENTER))
            }
        }
        awaitNativeParityDraw(instrumentation, scene.root)
        main {
            check(scene.root.isAttachedToWindow && scene.root.isShown && scene.root.isHardwareAccelerated)
            check(scene.targets.values.all { it.isAttachedToWindow && it.width > 40 && it.height == INK_ROW_HEIGHT })
            check(scene.groups.values.none { it.hasOwnTextSurfaceInkInvalidator })
        }
        // Expected colors are independent, explicit paint states, not sampled from the renderer.
        val ink = scene.targets.keys.associateWith { Color.BLACK }.toMutableMap()
        val underlay = scene.targets.keys.associateWith { Color.WHITE }.toMutableMap()
        ink["alpha"] = 0xff808080.toInt()
        underlay["progress"] = Color.BLACK

        fun scenario(name: String, reached: Set<String>, action: () -> Unit) {
            val entry = JSONObject().put("name", name).put("passed", false)
            cases.put(entry)
            persist()
            var scenarioFailure: Throwable? = null
            try {
                requireNativeParityHost(instrumentation, activity, "ink-$name")
                main {
                    scene.targets.values.forEach { it.inkInvalidations = 0 }
                    action()
                    val counts = JSONObject()
                    scene.targets.forEach { (key, view) -> counts.put(key, view.inkInvalidations) }
                    entry.put("ink_invalidations", counts).put("total_ink_invalidations",
                        scene.targets.values.sumOf { it.inkInvalidations })
                    for ((key, view) in scene.targets) {
                        check(view.inkInvalidations == if (key in reached) 1 else 0) {
                            "$name: $key received ${view.inkInvalidations} ink invalidations; expected ${key in reached}"
                        }
                    }
                }
                val hardware = captureNativeParityContent(instrumentation, activity) { scene.root }
                try {
                    saveNativeParityBitmap(directory, "$name-hardware.png", hardware)
                    entry.put("hardware", main { scene.verifyPixels(hardware, ink, underlay) })
                    val software = main {
                        val bitmap = Bitmap.createBitmap(scene.root.width, scene.root.height, Bitmap.Config.ARGB_8888)
                        try { scene.root.draw(Canvas(bitmap)); bitmap }
                        catch (error: Throwable) { bitmap.recycle(); throw error }
                    }
                    try {
                        saveNativeParityBitmap(directory, "$name-software.png", software)
                        entry.put("software", main { scene.verifyPixels(software, ink, underlay) })
                    } finally { software.recycle() }
                } finally { hardware.recycle() }
                main {
                    check(scene.targets.all { (key, view) -> view.inkInvalidations == if (key in reached) 1 else 0 }) {
                        "$name: capture unexpectedly changed ink invalidation counts"
                    }
                }
                entry.put("passed", true)
            } catch (error: Throwable) {
                entry.put("failure", error.stackTraceToString())
                scenarioFailure = error
            } finally { scenarioFailure = captureNativeParityCleanup(scenarioFailure) { persist() } }
            scenarioFailure?.let { throw it }
        }

        val conservative = setOf("direct", "default", "arbitrary", "translucent", "unknown", "progress", "alpha")
        scenario("before-opt-in", scene.targets.keys.toSet()) { ReverbTextSurface.invalidate(scene.root) }
        main {
            for (key in listOf("known", "outer", "nested", "translucent", "unknown", "progress", "alpha")) {
                ReverbTextSurface.ownGroupInk(scene.groups.getValue(key))
                check(scene.groups.getValue(key).hasOwnTextSurfaceInkInvalidator)
            }
            check(!scene.groups.getValue("default").hasOwnTextSurfaceInkInvalidator)
            check(!scene.groups.getValue("arbitrary").hasOwnTextSurfaceInkInvalidator)
        }
        scenario("page-black-known-boundaries", conservative) {
            scene.page.setBackgroundColor(Color.BLACK)
            ReverbTextSurface.invalidate(scene.root)
            ink["direct"] = Color.WHITE
            ink["alpha"] = Color.BLACK
            underlay["direct"] = Color.BLACK
            underlay["unknown"] = Color.BLACK
            underlay["translucent"] = 0xff808080.toInt()
            underlay["alpha"] = 0xff808080.toInt()
        }
        result.put("ancestor_callbacks_before_opt_in", scene.targets.size)
            .put("ancestor_callbacks_after_opt_in", conservative.size)
        check(conservative.size < scene.targets.size)
        // A real negative control for the hardware oracle: keep counting the owner's
        // notification, but deliberately omit the child's invalidate. Software draw would
        // recalculate ink regardless, so it must not run during this observation.
        val negative = JSONObject().put("name", "missed-ink-invalidation-negative-control").put("passed", false)
        cases.put(negative)
        val known = scene.targets.getValue("known")
        val beforeDraws = main {
            known.inkInvalidations = 0
            known.suppressInkInvalidation = true
            known.drawCount.also {
                scene.groups.getValue("known").setPaletteImmediately(Color.BLACK, Color.TRANSPARENT)
                check(known.inkInvalidations == 1)
            }
        }
        try {
            val hardware = captureNativeParityContent(instrumentation, activity) { scene.root }
            try {
                saveNativeParityBitmap(directory, "missed-ink-negative-control-hardware.png", hardware)
                negative.put("hardware_with_stale_ink", main {
                    scene.verifyPixels(hardware, ink, underlay + ("known" to Color.BLACK))
                })
                main {
                    negative.put("draws_before", beforeDraws).put("draws_after", known.drawCount)
                    check(known.drawCount == beforeDraws) {
                        "Hardware capture redrew the deliberately uninvalidated target; negative control is inconclusive"
                    }
                }
                negative.put("passed", true).put("missing_notification_detected", true)
            } finally { hardware.recycle() }
        } finally {
            main {
                // Restore its original paint without refreshing the child display list. The
                // next scenario uses the real notification and must produce fresh white ink.
                scene.groups.getValue("known").setPaletteImmediately(Color.WHITE, Color.TRANSPARENT)
                known.suppressInkInvalidation = false
            }
            persist()
        }
        scenario("known-own-fill-callback", setOf("known")) {
            scene.groups.getValue("known").setPaletteImmediately(Color.BLACK, Color.TRANSPARENT)
            ink["known"] = Color.WHITE
            underlay["known"] = Color.BLACK
        }
        scenario("outer-own-callback-prunes-nested", setOf("outer")) {
            scene.groups.getValue("outer").setPaletteImmediately(Color.BLACK, Color.TRANSPARENT)
            ink["outer"] = Color.WHITE
            underlay["outer"] = Color.BLACK
        }
        scenario("nested-current-intermediate-paint", setOf("nested")) {
            scene.groups.getValue("nested").setPaletteImmediately(0xff202020.toInt(), Color.TRANSPARENT)
            ink["nested"] = Color.WHITE
            underlay["nested"] = 0xff202020.toInt()
        }
        scenario("nested-reverse-white", setOf("nested")) {
            scene.groups.getValue("nested").setPaletteImmediately(Color.WHITE, Color.TRANSPARENT)
            ink["nested"] = Color.BLACK
            underlay["nested"] = Color.WHITE
        }
        scenario("opaque-origin-never-pruned", setOf("known")) {
            ReverbTextSurface.invalidate(scene.groups.getValue("known"))
        }
        scenario("arbitrary-callback-is-not-ink-ownership", conservative) {
            val before = scene.arbitraryPaintCalls
            scene.groups.getValue("arbitrary").setPaletteImmediately(Color.BLACK, Color.TRANSPARENT)
            check(scene.arbitraryPaintCalls == before + 1)
            check(scene.targets.getValue("arbitrary").inkInvalidations == 0)
            ReverbTextSurface.invalidate(scene.root)
            ink["arbitrary"] = Color.WHITE
            underlay["arbitrary"] = Color.BLACK
        }
        scenario("unchanged-fill-no-publication", emptySet()) {
            scene.groups.getValue("known").setPaletteImmediately(Color.BLACK, Color.TRANSPARENT)
        }
        scenario("progress-exit-own-callback", setOf("progress")) {
            scene.groups.getValue("progress").setBackgroundProgress(0f, Color.BLACK)
            underlay["progress"] = Color.WHITE
        }
        val withoutProgress = conservative - "progress"
        scenario("progress-now-opaque-boundary", withoutProgress) { ReverbTextSurface.invalidate(scene.root) }
        scenario("removed-ownership-is-conservative", withoutProgress + "known") {
            scene.groups.getValue("known").setTextSurfaceInkInvalidator(null)
            ReverbTextSurface.invalidate(scene.root)
        }
        scenario("detached-origin-and-fill-no-notification", emptySet()) {
            val group = scene.groups.getValue("known")
            ReverbTextSurface.ownGroupInk(group)
            scene.root.removeView(group)
            check(!group.isAttachedToWindow && !scene.targets.getValue("known").isAttachedToWindow)
            group.setPaletteImmediately(Color.WHITE, Color.TRANSPARENT)
            ReverbTextSurface.invalidate(group)
            ink["known"] = Color.BLACK
            underlay["known"] = Color.WHITE
        }
        scenario("reattached-current-paint-settled", emptySet()) {
            scene.root.addView(scene.groups.getValue("known"), 1, LinearLayout.LayoutParams(-1, INK_ROW_HEIGHT))
            check(scene.targets.getValue("known").isAttachedToWindow)
        }
        verifyRetentionCallerInk(instrumentation, activity, cases, ::persist)
        result.put("passed", true)
    } catch (error: Throwable) { failure = error }
    finally {
        failure = captureNativeParityCleanup(failure) { main { fixture?.let { host?.fixtureRoot?.removeView(it.root) } } }
        failure = captureNativeParityCleanup(failure) { main { host?.finish() } }
        failure = captureNativeParityCleanup(failure) { check(getRecorderPreferences(context).all == preferences) }
        if (failure != null) result.put("passed", false).put("failure", failure!!.stackTraceToString())
        failure = captureNativeParityCleanup(failure) { persist() }
        report.appendLine("TEXT_SURFACE_INVALIDATION_EVIDENCE ${directory.absolutePath}")
    }
    failure?.let { throw it }
    report.appendLine("PASS native_text_surface_invalidation attached ownership, conservative fallbacks, current-paint pixels; no timing claim")
}

private fun verifyRetentionCallerInk(instrumentation: Instrumentation, activity: NativeParityActivity,
                                     cases: JSONArray, persist: () -> Unit) {
    // The production case runs first. An unchanged c777 constructor must fail its first
    // page refresh; the later deliberate control cannot relax that assertion.
    for (oldBoundary in listOf(false, true)) {
        val name = if (oldBoundary) "retention-caller-old-boundary-negative-control" else "retention-caller-page-and-local-paint"
        val entry = JSONObject().put("name", name).put("passed", false)
            .put("scope", "Attached actual ReverbRetentionSection; cached page paint and TextView ink, without capture")
        val steps = JSONArray().also { entry.put("steps", it) }
        cases.put(entry)
        persist()
        requireNativeParityHost(instrumentation, activity, name)
        fun <T> main(action: () -> T): T = nativeParityOnMain(instrumentation, action)
        val page = NoiseDrawable(Color.BLACK, null)
        val root = main { TextSurfaceScopeLayout(activity).apply { background = page } }
        val caller = main { RetentionPageInkProbe(activity, page) }
        var section: ReverbRetentionSection? = null
        var modeRequests = 0
        var failure: Throwable? = null
        try {
            main {
                val environment = ReverbEnvironment(activity, false)
                section = ReverbRetentionSection(activity, ReverbRetentionMode.TIME, environment, { "Retention" },
                    { true }, { modeRequests++; error("Paint changed retention mode") }, caller,
                    TextView(activity).apply { text = "Looping value" }).also {
                    root.addView(it, LinearLayout.LayoutParams(-1, -2))
                }
                activity.fixtureRoot.addView(root,
                    FrameLayout.LayoutParams((320 * environment.textDensity.density).roundToInt(), -2, Gravity.CENTER))
            }
            awaitNativeParityDraw(instrumentation, root)
            main {
                check(caller.isAttachedToWindow && caller.isShown && caller.width > 0 && caller.height > 0)
                val surface = generateSequence(caller.parent as? View) { it.parent as? View }
                    .filterIsInstance<NativeGroupLayout>().first()
                val groupPaint = surface.paintedTextSurface
                check(groupPaint ushr 56 == 255L && (groupPaint ushr 24 and 255L) == 255L && surface.alpha == 1f)
                caller.invalidateTextSurfaceInk()
                caller.refreshes = 0
                val initialPage = page.paintedTextSurface
                check(caller.sampledPage == initialPage && caller.currentTextColor == Color.WHITE)
                entry.put("initial_group_paint", groupPaint).put("initial_page_paint", initialPage)
                    .put("caller_width", caller.width).put("caller_height", caller.height)
                if (oldBoundary) {
                    // Recreate precisely the former ancestor-pruning opt-in on the actual
                    // section's group. Its local callback is never triggered in this control.
                    ReverbTextSurface.ownGroupInk(surface)
                    page.setBackgroundColor(Color.WHITE)
                    check(page.paintedTextSurface != initialPage)
                    ReverbTextSurface.invalidate(root)
                    steps.put(caller.snapshot("first-page-white", page))
                    check(caller.refreshes == 0 && caller.sampledPage == initialPage && caller.currentTextColor == Color.WHITE) {
                        "Old ownership boundary must expose the first missed refresh"
                    }
                    check(surface.paintedTextSurface == groupPaint)
                    entry.put("first_missed_refresh_detected", true)
                } else {
                    for (color in listOf(Color.WHITE, Color.BLACK)) {
                        val beforeRefreshes = caller.refreshes
                        val beforePaint = caller.sampledPage
                        page.setBackgroundColor(color)
                        check(page.paintedTextSurface != beforePaint)
                        ReverbTextSurface.invalidate(root)
                        steps.put(caller.snapshot(if (color == Color.WHITE) "page-white" else "page-black", page))
                        check(caller.refreshes == beforeRefreshes + 1) { "Page paint failed to reach caller through opaque retention group" }
                        check(caller.sampledPage == page.paintedTextSurface)
                        check(caller.currentTextColor == if (color == Color.WHITE) Color.BLACK else Color.WHITE)
                        check(surface.paintedTextSurface == groupPaint && surface.alpha == 1f)
                    }
                    val beforeRefreshes = caller.refreshes
                    val nextFill = if (surface.paintedFillColor == Color.BLACK) Color.WHITE else Color.BLACK
                    surface.setPaletteImmediately(nextFill, Color.TRANSPARENT)
                    steps.put(caller.snapshot("local-fill", page))
                    check(surface.paintedFillColor == nextFill && surface.paintedTextSurface != groupPaint)
                    check(caller.refreshes == beforeRefreshes + 1) { "Retention local fill lost its ordinary ink callback" }
                    check(caller.sampledPage == page.paintedTextSurface && caller.currentTextColor == Color.WHITE)
                }
                check(modeRequests == 0)
                entry.put("mode_requests", modeRequests)
            }
        } catch (error: Throwable) { failure = error }
        finally {
            failure = captureNativeParityCleanup(failure) { main { section?.close() } }
            failure = captureNativeParityCleanup(failure) { main { activity.fixtureRoot.removeView(root) } }
            if (failure == null) entry.put("passed", true) else entry.put("failure", failure!!.stackTraceToString())
            failure = captureNativeParityCleanup(failure) { persist() }
        }
        failure?.let { throw it }
    }
}

private class RetentionPageInkProbe(context: Context, private val page: NoiseDrawable) : TextView(context), ReverbTextSurfaceTarget {
    var refreshes = 0
    var sampledPage = 0L
        private set
    init { text = "Page-dependent retention value" }
    override fun invalidateTextSurfaceInk() {
        refreshes++
        sampledPage = page.paintedTextSurface
        setTextColor(textSurfaceForeground(sampledPage, Color.BLACK))
    }
    fun snapshot(stage: String, page: NoiseDrawable): JSONObject = JSONObject().put("stage", stage)
        .put("refreshes", refreshes).put("cached_page_paint", sampledPage)
        .put("current_page_paint", page.paintedTextSurface).put("ink_argb", currentTextColor)
}

private const val INK_ROW_HEIGHT = 24

private class TextSurfaceScopeLayout(context: Context) : LinearLayout(context), ReverbTextSurfaceScope {
    init { orientation = VERTICAL; if (Build.VERSION.SDK_INT >= 29) isForceDarkAllowed = false }
}

private class TextSurfaceInkProbeView(context: Context) : View(context), ReverbTextSurfaceTarget {
    var inkInvalidations = 0
    var suppressInkInvalidation = false
    var drawCount = 0
        private set
    private val ink = Paint()
    init { if (Build.VERSION.SDK_INT >= 29) isForceDarkAllowed = false }
    override fun invalidateTextSurfaceInk() {
        inkInvalidations++
        if (!suppressInkInvalidation) invalidate()
    }
    override fun onDraw(canvas: Canvas) {
        drawCount++
        // Like retained production ink, resolve the actual underlay at draw time, not target palette.
        ink.color = textSurfaceForeground(ReverbTextSurface.sample(this), Color.BLACK)
        canvas.drawRect(4f, 4f, 12f, 20f, ink)
    }
}

private class TextSurfaceInvalidationFixture(context: Context) {
    val root = TextSurfaceScopeLayout(context)
    val page = NoiseDrawable(Color.WHITE, null)
    val targets = linkedMapOf<String, TextSurfaceInkProbeView>()
    val groups = linkedMapOf<String, NativeGroupLayout>()
    var arbitraryPaintCalls = 0
        private set

    init {
        root.background = page
        fun target(name: String) = TextSurfaceInkProbeView(context).also { targets[name] = it }
        fun group(name: String, color: Int? = Color.WHITE, callback: (View) -> Unit = {}): NativeGroupLayout =
            NativeGroupLayout(context, cornerRadiusDp = 0f, onPaintedSurfaceChanged = callback).also {
                groups[name] = it
                color?.let { color -> it.setPaletteImmediately(color, Color.TRANSPARENT) }
                it.addRow(target(name), NativeGroupLayout.LayoutParams(-1, INK_ROW_HEIGHT))
            }
        fun row(view: View, height: Int = INK_ROW_HEIGHT) = root.addView(view, LinearLayout.LayoutParams(-1, height))
        row(target("direct"))
        row(group("known"))
        val outer = group("outer")
        outer.addRow(group("nested"), NativeGroupLayout.LayoutParams(-1, INK_ROW_HEIGHT))
        row(outer, INK_ROW_HEIGHT * 2)
        row(group("default"))
        row(group("arbitrary", callback = { arbitraryPaintCalls++ }))
        row(group("translucent", 0x80ffffff.toInt()))
        row(group("unknown", Color.TRANSPARENT))
        row(group("progress").apply { setBackgroundProgress(.5f, Color.BLACK) })
        row(group("alpha").apply { alpha = .5f })
    }

    fun verifyPixels(bitmap: Bitmap, inks: Map<String, Int>, underlays: Map<String, Int>): JSONArray {
        check(bitmap.width == root.width && bitmap.height == root.height)
        val evidence = JSONArray()
        for ((name, target) in targets) {
            if (!target.isAttachedToWindow) {
                check(name == "known") { "Unexpected detached target $name" }
                evidence.put(JSONObject().put("target", name).put("detached", true))
                continue
            }
            val bounds = Rect(0, 0, target.width, target.height)
            root.offsetDescendantRectToMyCoords(target, bounds)
            check(bounds.left >= 0 && bounds.top >= 0 && bounds.right <= bitmap.width && bounds.bottom <= bitmap.height)
            fun verify(x: Int, expected: Int): Int {
                val actual = bitmap.getPixel(bounds.left + x, bounds.top + 12)
                // One channel step admits integer alpha-composition rounding, not stale black/white ink.
                check(listOf(0, 8, 16, 24).all { shift ->
                    kotlin.math.abs((actual ushr shift and 255) - (expected ushr shift and 255)) <= 1
                }) { "$name x=$x expected=${Integer.toHexString(expected)} actual=${Integer.toHexString(actual)} bounds=$bounds" }
                return actual
            }
            evidence.put(JSONObject().put("target", name).put("bounds", bounds.toShortString())
                .put("ink_argb", verify(8, inks.getValue(name)))
                .put("underlay_argb", verify(40, underlays.getValue(name))))
        }
        return evidence
    }
}
