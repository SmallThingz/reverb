package app.smallthingz.reverb

import android.app.Instrumentation
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.core.graphics.ColorUtils
import app.smallthingz.reverb.nativeui.ReverbHomeBlobState
import app.smallthingz.reverb.nativeui.ReverbPalette
import app.smallthingz.reverb.nativeui.ReverbViewEnvironment
import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import smallthingz.ui.text.font.createFontFamilyResolver
import smallthingz.ui.unit.Density
import smallthingz.ui.animation.ColorTransition

/** Actual private Home overlay over the already qualified hardware renderer; no microphone or Service. */
internal fun verifyNativeBlobReadoutContrast(
    instrumentation: Instrumentation,
    host: NativeParityActivity,
    blob: AudioBlobView,
    controller: AudioBlobController,
    palette: ReverbPalette,
    name: String,
    directory: File,
    scene: JSONObject,
) {
    fun <T> main(action: () -> T): T = nativeParityOnMain(instrumentation, action)
    val evidence = JSONArray()
    scene.put("actual_home_readout", evidence)
    val density = host.resources.displayMetrics.density
    val overlayType = Class.forName("app.smallthingz.reverb.nativeui.BlobOverlay")
    val overlay = main {
        val environment = ReverbViewEnvironment(Density(density, host.resources.configuration.fontScale),
            createFontFamilyResolver(host), palette)
        overlayType.getDeclaredConstructor(android.content.Context::class.java, ReverbViewEnvironment::class.java)
            .apply { isAccessible = true }.newInstance(host, environment) as ViewGroup
    }
    val bind = overlayType.getDeclaredMethod("bind", ReverbHomeBlobState::class.java, Float::class.javaPrimitiveType)
        .apply { isAccessible = true }
    val setContentOpacity = overlayType.getDeclaredMethod("setContentOpacity", Float::class.javaPrimitiveType)
        .apply { isAccessible = true }
    val amount = AudioBlobView::class.java.getDeclaredField("currentSilence").apply { isAccessible = true }
    val life = AudioBlobView::class.java.getDeclaredField("currentLife").apply { isAccessible = true }
    val presence = AudioBlobView::class.java.getDeclaredField("currentPresence").apply { isAccessible = true }
    val activity = AudioBlobView::class.java.getDeclaredField("currentActivity").apply { isAccessible = true }
    val advance = AudioBlobView::class.java.getDeclaredMethod("advance", Float::class.javaPrimitiveType)
        .apply { isAccessible = true }
    val appearance = overlayType.getDeclaredMethod("setMaterialAppearance", Boolean::class.javaPrimitiveType,
        Boolean::class.javaPrimitiveType, Boolean::class.javaPrimitiveType)
        .apply { isAccessible = true }
    val footprint = overlayType.getDeclaredMethod("readoutFootprintRadius").apply { isAccessible = true }
    val geometry = overlayType.getDeclaredMethod("updateMaterialGeometry", AudioBlobView::class.java, Int::class.javaPrimitiveType)
        .apply { isAccessible = true }
    val gradientMin = overlayType.getDeclaredField(if (name.endsWith("fallback")) "fallbackGradientMin" else "agslGradientMin")
        .apply { isAccessible = true }
    val gradientMax = overlayType.getDeclaredField(if (name.endsWith("fallback")) "fallbackGradientMax" else "agslGradientMax")
        .apply { isAccessible = true }
    val supportNeeded = overlayType.getDeclaredField("materialNeedsSupport").apply { isAccessible = true }
    val posted = AudioBlobView::class.java.getDeclaredField("framePosted").apply { isAccessible = true }
    val time = AudioBlobView::class.java.getDeclaredField("shaderTimeSeconds").apply { isAccessible = true }
    val childCount = main { overlay.childCount }
    val originalBlobLayout = main { blob.layoutParams }
    val originalWidth = main { blob.width }
    val originalHeight = main { blob.height }
    val wrapper = main {
        FrameLayout(host).apply {
            clipChildren = false
            clipToPadding = false
            addView(overlay, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
        }
    }
    fun verifySharedLayout(requestedWidth: Int, requestedHeight: Int) = main {
        check(blob.width == requestedWidth && blob.height == requestedHeight &&
            wrapper.width == requestedWidth && wrapper.height == requestedHeight) {
            "Readout and renderer dimensions diverged"
        }
        check(blob.left + blob.right == wrapper.left + wrapper.right &&
            blob.top + blob.bottom == wrapper.top + wrapper.bottom) {
            "Readout and renderer centers diverged"
        }
    }
    var failure: Throwable? = null
    try {
        main { host.fixtureRoot.addView(wrapper, FrameLayout.LayoutParams(blob.width, blob.height, Gravity.CENTER)) }
        // Lay out each destination at each width before stepping the production integrator and
        // resolved polarity. If no switch is needed, retain consecutive 100 ms frames instead.
        data class MaterialFrame(val life: Float, val presence: Float, val paused: Int,
            val transitioning: Boolean = true, val silence: Float = 0f, val resolvedPolarity: Boolean? = null)
        for (compact in listOf(false, true)) {
            val requestedWidth = if (compact) minOf(originalWidth, (180 * density).toInt()) else originalWidth
            val requestedHeight = if (compact) requestedWidth else originalHeight
            main {
                blob.layoutParams = FrameLayout.LayoutParams(requestedWidth, requestedHeight, Gravity.CENTER)
                wrapper.layoutParams = FrameLayout.LayoutParams(requestedWidth, requestedHeight, Gravity.CENTER)
                bind.invoke(overlay, ReverbHomeBlobState(controller, true, false, true, true, false,
                    false, "123.4 MB", "12:34 / 60:00", true, true), 1f)
            }
            awaitNativeParityDraw(instrumentation, wrapper)
            verifySharedLayout(requestedWidth, requestedHeight)
            val layoutName = if (compact) "compact" else "normal"
            val trajectoryFrames = linkedMapOf<String, MaterialFrame>()
            var deliveredPolarity = false
            var deliveredTransition = false
            var appearanceCallbacks = 0
            main {
                blob.onReadoutAppearanceChanged = { darkInk, transitioning, supportNeeded ->
                    deliveredPolarity = darkInk
                    deliveredTransition = transitioning
                    appearanceCallbacks++
                    appearance.invoke(overlay, darkInk, transitioning, supportNeeded)
                    Unit
                }
            }
            val zeroBins = FloatArray(8)
            for (destination in listOf("start", "stop", "filled", "start-quiet", "filled-quiet")) {
                val starting = destination.startsWith("start")
                val filling = destination.startsWith("filled")
                val quiet = destination.endsWith("quiet")
                main {
                    bind.invoke(overlay, ReverbHomeBlobState(controller, starting, false, true, true, filling,
                        false, "123.4 MB", if (starting || filling) "12:34 / 60:00" else null, true, true), 1f)
                }
                awaitNativeParityDraw(instrumentation, wrapper)
                main {
                    geometry.invoke(overlay, blob, palette.surface)
                    val colors = ColorTransition(intArrayOf(palette.surfaceContainerHighest))
                    if (filling) colors.retarget(intArrayOf(palette.primaryContainer), 0L, 1f)
                    blob.updateState(starting, true, false, false, palette.primary, palette.tertiary,
                        palette.surfaceContainerHighest, skipSilenceEnabled = true)
                    if (starting) controller.submit(ReverbService.VisualizationFrame(0f, zeroBins, quiet,
                        ReverbService.BufferSlot.ONE_SHOT, 1L, 1L))
                    life.setFloat(blob, if (starting) .38f else 1f)
                    presence.setFloat(blob, if (starting) 0f else 1f)
                    amount.setFloat(blob, if (filling && quiet) 1f else 0f)
                    fun sample() = MaterialFrame(life.getFloat(blob), presence.getFloat(blob), colors.color(0),
                        silence = amount.getFloat(blob), resolvedPolarity = blob.readoutUsesDarkInk())
                    var previous = sample()
                    var before = previous
                    var after = previous
                    var switched = false
                    for (step in 1..120) {
                        colors.frame(step * 1_000_000_000L / 60L, 1f)
                        blob.updateState(starting, true, false, false, palette.primary, palette.tertiary,
                            colors.color(0), skipSilenceEnabled = true)
                        advance.invoke(blob, 1f / 60f)
                        val next = sample()
                        check(deliveredPolarity == next.resolvedPolarity) { "Renderer failed to deliver its material ink polarity" }
                        if (step == 6) before = next
                        if (step == 7) after = next
                        if (previous.resolvedPolarity != next.resolvedPolarity) {
                            before = previous; after = next; switched = true; break
                        }
                        previous = next
                    }
                    val prefix = destination
                    trajectoryFrames["$prefix-before"] = before
                    trajectoryFrames["$prefix-after"] = after
                    scene.put("${layoutName}_${prefix}_trajectory_switch", switched)
                }
            }
            main {
                // Drive the real retained palette owner independently of the geometry clock.
                // No system animation-scale setting is changed and no View frame callback is posted.
                blob.updateState(false, true, false, false, palette.primary, palette.tertiary,
                    palette.surfaceContainerHighest, skipSilenceEnabled = true)
                val motion = AudioBlobView::class.java.getDeclaredField("paletteMotion").apply { isAccessible = true }.get(blob)
                val retainedColors = motion.javaClass.getDeclaredField("state").apply { isAccessible = true }.get(motion) as ColorTransition
                val publishPalette = AudioBlobView::class.java.getDeclaredMethod("publishPalette").apply { isAccessible = true }
                val startNanos = System.nanoTime()
                life.setFloat(blob, 1f)
                presence.setFloat(blob, 1f)
                retainedColors.retarget(intArrayOf(palette.primary, palette.tertiary, palette.primaryContainer), startNanos, 10f)
                retainedColors.frame(startNanos + 800_000_000L, 10f)
                check(retainedColors.isInterpolating) { "Slow filled palette did not interpolate" }
                publishPalette.invoke(blob)
                repeat(180) { advance.invoke(blob, 1f / 60f) }
                check(life.getFloat(blob) == .38f && presence.getFloat(blob) == 0f)
                check(deliveredTransition) { "Filled palette tail lost its material transition owner" }
                trajectoryFrames["filled-tail"] = MaterialFrame(life.getFloat(blob), presence.getFloat(blob), retainedColors.color(2))
                val ownedCallbacks = appearanceCallbacks
                repeat(4) { publishPalette.invoke(blob) }
                check(appearanceCallbacks == ownedCallbacks) { "Unchanged palette appearance rebound the overlay" }
                retainedColors.frame(startNanos + 2_000_000_000L, 10f)
                publishPalette.invoke(blob)
                check(!deliveredTransition) { "Settled palette retained transition support" }
                retainedColors.retarget(intArrayOf(palette.primary, palette.tertiary, palette.surfaceContainerHighest),
                    startNanos + 3_000_000_000L, 10f)
                retainedColors.frame(startNanos + 3_800_000_000L, 10f)
                check(retainedColors.isInterpolating)
                publishPalette.invoke(blob)
                check(!deliveredTransition) { "Idle palette change created a readout support field" }
                trajectoryFrames["idle-theme"] = MaterialFrame(life.getFloat(blob), presence.getFloat(blob), retainedColors.color(2), false)
                val idleCallbacks = appearanceCallbacks
                repeat(4) { publishPalette.invoke(blob) }
                check(appearanceCallbacks == idleCallbacks)
                retainedColors.snapToTarget()
                publishPalette.invoke(blob)
                scene.put("slow_filled_palette_tail_owned", true).put("idle_palette_support_absent", true)
                    .put("palette_duration_scale", 10).put("unchanged_appearance_no_rebind", true)
                trajectoryFrames["filled-settled"] = MaterialFrame(.38f, 0f, palette.primaryContainer, false)
                blob.onReadoutAppearanceChanged = null
                scene.put("trajectory_source", "per-width destination layout and production geometry; consecutive 60 Hz actual advance/ColorTransition steps bracketing readoutUsesDarkInk changes; paired hardware raster")
            }
            for (state in listOf("no-suppression", "high-energy", "transition", "silent", "startup", "start-before", "start-after", "stop-before", "stop-after", "filled-before", "filled-after", "filled-tail", "idle-theme",
                "start-quiet-before", "start-quiet-after", "filled-quiet-before", "filled-quiet-after", "filled-settled")) {
                main {
                    // The outer probe has hidden scheduling; exact render inputs stay fixed for paired captures.
                    val sampledPaused = trajectoryFrames[state]?.paused ?: palette.surfaceContainerHighest
                    blob.updateState(false, true, false, false, palette.primary, palette.tertiary,
                        sampledPaused, skipSilenceEnabled = true)
                    amount.setFloat(blob, when (state) { "silent" -> 1f; "transition" -> .5f
                        else -> trajectoryFrames[state]?.silence ?: 0f })
                    val frameLife = trajectoryFrames[state]?.life ?: if (state == "startup") .2f else 1f
                    val framePresence = trajectoryFrames[state]?.presence ?: 1f
                    life.setFloat(blob, frameLife)
                    presence.setFloat(blob, framePresence)
                    if (state == "high-energy") {
                        blob.updateState(true, true, false, false, palette.primary, palette.tertiary,
                            palette.surfaceContainerHighest, skipSilenceEnabled = true)
                        controller.submit(ReverbService.VisualizationFrame(.8f,
                            floatArrayOf(.8f, .7f, .6f, .5f, .7f, .8f, .6f, .5f), false,
                            ReverbService.BufferSlot.ONE_SHOT, 1L, 1L))
                        life.setFloat(blob, 1f)
                        presence.setFloat(blob, 1f)
                        repeat(30) { advance.invoke(blob, 1f / 60f) }
                        check(activity.getFloat(blob) > .79f) { "High-energy raster lost its submitted signal" }
                    }
                    val filled = state.startsWith("filled-")
                    val active = !state.startsWith("stop-") && !filled && state != "idle-theme"
                    bind.invoke(overlay, ReverbHomeBlobState(controller, active, false, true, true, filled,
                        false, "123.4 MB", if (active || filled) "12:34 / 60:00" else null, true, true), 1f)
                    appearance.invoke(overlay, audioBlobUsesDarkInk(audioBlobMaterialColor(palette.primary),
                        audioBlobMaterialColor(palette.tertiary), sampledPaused, frameLife, framePresence,
                        amount.getFloat(blob), name.endsWith("fallback")),
                        state == "startup" || trajectoryFrames[state]?.transitioning == true, true)
                    blob.invalidate()
                }
                awaitNativeParityDraw(instrumentation, wrapper)
                main {
                    geometry.invoke(overlay, blob, palette.surface)
                    val darkInk = blob.readoutUsesDarkInk()
                    trajectoryFrames[state]?.resolvedPolarity?.let { expected ->
                        check(darkInk == expected) { "Raster geometry changed the sampled polarity for $layoutName/$state" }
                    }
                    appearance.invoke(overlay, darkInk, state == "startup" || trajectoryFrames[state]?.transitioning == true,
                        blob.readoutNeedsSupport(darkInk))
                }
                awaitNativeParityDraw(instrumentation, wrapper)
                val stem = "$name-readout-${if (compact) "compact" else "normal"}-$state"
                val visibleChildren = main { BooleanArray(childCount) { overlay.getChildAt(it).visibility == View.VISIBLE } }
                val ink = captureNativeParityContent(instrumentation, host) { wrapper }
                var inkFailure: Throwable? = null
                try {
                    saveNativeParityBitmap(directory, "$stem.png", ink)
                    main { for (i in 0 until overlay.childCount) overlay.getChildAt(i).visibility = View.INVISIBLE }
                    awaitNativeParityDraw(instrumentation, wrapper)
                    val backing = captureNativeParityContent(instrumentation, host) { wrapper }
                    var backingFailure: Throwable? = null
                    try {
                        saveNativeParityBitmap(directory, "$stem-backing.png", backing)
                        val entry = JSONObject().put("name", stem)
                        main { entry.put("life", life.getFloat(blob).toDouble()).put("presence", presence.getFloat(blob).toDouble())
                            .put("requested_width_px", requestedWidth).put("requested_height_px", requestedHeight)
                            .put("renderer_width_px", blob.width).put("renderer_height_px", blob.height)
                            .put("wrapper_width_px", wrapper.width).put("wrapper_height_px", wrapper.height)
                            .put("silence", amount.getFloat(blob).toDouble())
                            .put("activity", activity.getFloat(blob).toDouble())
                            .put("support_needed", supportNeeded.getBoolean(overlay))
                            .put("readout_radius_px", (footprint.invoke(overlay) as Float).toDouble())
                            .put("gradient_min", gradientMin.getFloat(overlay).toDouble())
                            .put("gradient_max", gradientMax.getFloat(overlay).toDouble())
                            .put("sampled_resolved_polarity", trajectoryFrames[state]?.resolvedPolarity ?: JSONObject.NULL)
                            .put("paused_argb", trajectoryFrames[state]?.paused ?: palette.surfaceContainerHighest) }
                        evidence.put(entry)
                        val children = JSONArray()
                        entry.put("children", children)
                        for (i in 0 until childCount) {
                            if (!visibleChildren[i]) continue
                            val bounds = main {
                                val child = overlay.getChildAt(i)
                                intArrayOf(overlay.left + child.left, overlay.top + child.top,
                                    overlay.left + child.right, overlay.top + child.bottom)
                            }
                            var fullInkPixels = 0
                            var minimum = Double.POSITIVE_INFINITY
                            // All visible readout glyphs use the same opaque material ink, including
                            // settled filled secondary text over the smaller idle body.
                            val tint = main {
                                val icon = overlay.getChildAt(0)
                                icon.javaClass.getDeclaredField("boundTint").apply { isAccessible = true }.getInt(icon)
                            }
                            for (y in bounds[1].coerceAtLeast(0) until bounds[3].coerceAtMost(ink.height)) {
                                for (x in bounds[0].coerceAtLeast(0) until bounds[2].coerceAtMost(ink.width)) {
                                    val foreground = ink.getPixel(x, y)
                                    val background = backing.getPixel(x, y)
                                    val expected = ColorUtils.compositeColors(tint, background)
                                    val error = maxOf(kotlin.math.abs(Color.red(foreground) - Color.red(expected)),
                                        kotlin.math.abs(Color.green(foreground) - Color.green(expected)),
                                        kotlin.math.abs(Color.blue(foreground) - Color.blue(expected)))
                                    if (error <= 1 && foreground != background) {
                                        fullInkPixels++
                                        minimum = minOf(minimum, ColorUtils.calculateContrast(foreground, background))
                                    }
                                }
                            }
                            children.put(JSONObject().put("index", i).put("full_ink_pixels", fullInkPixels)
                                .put("expected_ink_alpha", Color.alpha(tint))
                                .put("bounds", JSONArray().put(bounds[0]).put(bounds[1]).put(bounds[2]).put(bounds[3]))
                                .put("minimum_rendered_contrast", if (minimum.isFinite()) minimum else JSONObject.NULL))
                            check(fullInkPixels >= 8 && minimum >= 4.5) {
                                "$stem child $i: only $fullInkPixels ink samples; contrast $minimum"
                            }
                        }
                    } catch (error: Throwable) {
                        backingFailure = error
                    } finally {
                        backingFailure = captureNativeParityCleanup(backingFailure) { backing.recycle() }
                    }
                    backingFailure?.let { throw it }
                } catch (error: Throwable) {
                    inkFailure = error
                } finally {
                    inkFailure = captureNativeParityCleanup(inkFailure) { ink.recycle() }
                    for (i in 0 until childCount) {
                        inkFailure = captureNativeParityCleanup(inkFailure) {
                            main { overlay.getChildAt(i).visibility = if (visibleChildren[i]) View.VISIBLE else View.GONE }
                        }
                    }
                    inkFailure = captureNativeParityCleanup(inkFailure) {
                        awaitNativeParityDraw(instrumentation, wrapper)
                    }
                }
                inkFailure?.let { throw it }
            }
        }
        main {
            blob.layoutParams = originalBlobLayout
            wrapper.layoutParams = FrameLayout.LayoutParams(originalWidth, originalHeight, Gravity.CENTER)
            bind.invoke(overlay, ReverbHomeBlobState(controller, true, false, true, true, false,
                false, "123.4 MB", "12:34 / 60:00", true, true), 1f)
            blob.updateState(true, true, false, false, palette.primary, palette.tertiary,
                palette.surfaceContainerHighest, skipSilenceEnabled = true)
            amount.setFloat(blob, 0f)
            life.setFloat(blob, 1f)
            presence.setFloat(blob, 1f)
            appearance.invoke(overlay, false, false, true)
            blob.invalidate()
        }
        awaitNativeParityDraw(instrumentation, wrapper)
        verifySharedLayout(originalWidth, originalHeight)
        main {
            geometry.invoke(overlay, blob, palette.surface)
            appearance.invoke(overlay, false, false, blob.readoutNeedsSupport(false))
        }
        awaitNativeParityDraw(instrumentation, wrapper)
        val frozenTime = main { check(!posted.getBoolean(blob)); time.getFloat(blob) }
        val excluded = main {
            val margin = kotlin.math.ceil(2.0 * density).toInt()
            Array(childCount) { index ->
                val child = overlay.getChildAt(index)
                intArrayOf(overlay.left + child.left - margin, overlay.top + child.top - margin,
                    overlay.left + child.right + margin, overlay.top + child.bottom + margin)
            }
        }
        val fades = JSONArray()
        scene.put("actual_home_readout_fades", fades)
        var nearOpaquePixels: IntArray? = null
        var nearOpaqueWidth = 0
        var nearOpaqueHeight = 0
        var fadeFailure: Throwable? = null
        try {
            for (contentAlpha in listOf(.5f, .999f, 1f)) {
                main {
                    setContentOpacity.invoke(overlay, contentAlpha)
                    check(overlay.alpha == 1f) { "Content fade isolated the overlay from the blob" }
                }
                val bitmap = captureNativeParityContent(instrumentation, host) { wrapper }
                var bitmapFailure: Throwable? = null
                try {
                    main { check(!posted.getBoolean(blob) && time.getFloat(blob) == frozenTime) }
                    val stem = "$name-readout-normal-loud-alpha-$contentAlpha"
                    saveNativeParityBitmap(directory, "$stem.png", bitmap)
                    val entry = JSONObject().put("alpha", contentAlpha.toDouble()).put("image", "$stem.png")
                    fades.put(entry)
                    if (contentAlpha == .999f) {
                        nearOpaqueWidth = bitmap.width
                        nearOpaqueHeight = bitmap.height
                        nearOpaquePixels = IntArray(bitmap.width * bitmap.height).also {
                            bitmap.getPixels(it, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
                        }
                    } else if (contentAlpha == 1f) {
                        val previous = checkNotNull(nearOpaquePixels)
                        check(bitmap.width == nearOpaqueWidth && bitmap.height == nearOpaqueHeight)
                        var samples = 0
                        var changedAbove8 = 0
                        var maximum = 0
                        var total = 0L
                        for (y in 0 until bitmap.height) for (x in 0 until bitmap.width) {
                            if (excluded.any { x >= it[0] && y >= it[1] && x < it[2] && y < it[3] }) continue
                            val a = previous[y * bitmap.width + x]
                            val b = bitmap.getPixel(x, y)
                            val difference = maxOf(kotlin.math.abs(Color.red(a) - Color.red(b)),
                                kotlin.math.abs(Color.green(a) - Color.green(b)),
                                kotlin.math.abs(Color.blue(a) - Color.blue(b)))
                            samples++
                            total += difference
                            maximum = maxOf(maximum, difference)
                            if (difference > 8) changedAbove8++
                        }
                        check(samples > 0)
                        // A .001 opacity step changes at most one 8-bit level ideally. Ignore small
                        // raster/dither differences, but reject a material jump across a visible area.
                        val continuous = changedAbove8 <= maxOf(4, samples / 1000)
                        entry.put("near_opaque_outside_glyph_samples", samples)
                            .put("near_opaque_max_channel_delta", maximum)
                            .put("near_opaque_mean_max_channel_delta", total.toDouble() / samples)
                            .put("near_opaque_pixels_above_8", changedAbove8)
                            .put("near_opaque_continuity_passed", continuous)
                        check(continuous) { "$name readout material jumps at .999 -> 1 opacity: $changedAbove8/$samples pixels" }
                    }
                } catch (error: Throwable) {
                    bitmapFailure = error
                } finally {
                    bitmapFailure = captureNativeParityCleanup(bitmapFailure) { bitmap.recycle() }
                }
                bitmapFailure?.let { throw it }
            }
        } catch (error: Throwable) {
            fadeFailure = error
        } finally {
            fadeFailure = captureNativeParityCleanup(fadeFailure) { main { setContentOpacity.invoke(overlay, 1f) } }
        }
        fadeFailure?.let { throw it }
    } catch (error: Throwable) {
        failure = error
    } finally {
        failure = captureNativeParityCleanup(failure) { main { blob.onReadoutAppearanceChanged = null } }
        failure = captureNativeParityCleanup(failure) { main { blob.layoutParams = originalBlobLayout } }
        failure = captureNativeParityCleanup(failure) { main { host.fixtureRoot.removeView(wrapper) } }
        failure = captureNativeParityCleanup(failure) { awaitNativeParityDraw(instrumentation, blob) }
    }
    failure?.let { throw it }
}
