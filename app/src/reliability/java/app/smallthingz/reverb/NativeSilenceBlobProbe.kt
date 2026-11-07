package app.smallthingz.reverb

import android.app.Application
import android.app.Instrumentation
import android.os.Build
import android.os.SystemClock
import android.view.Gravity
import android.widget.FrameLayout
import app.smallthingz.reverb.nativeui.ReverbPalette
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.sin
import org.json.JSONArray
import org.json.JSONObject

/** Real hardware renderer, synthetic PCM/gate only: no Service, microphone, settings writes or stores. */
internal fun verifyNativeSilenceBlob(instrumentation: Instrumentation, report: StringBuilder, motionOnly: Boolean = false) {
    val context = instrumentation.targetContext
    check(context.packageName == "app.smallthingz.reverb.uitheme.reliability")
    check(context.applicationContext.javaClass == Application::class.java)
    check(Build.VERSION.SDK_INT >= 33) { "Both AGSL and fallback qualification requires API 33+" }
    val preferences = getRecorderPreferences(context).all.toMap()
    val directory = File(context.filesDir, "native-silence-blob-${System.currentTimeMillis()}")
    check(directory.mkdir())
    val scenes = JSONArray()
    val selector = if (motionOnly) "native_blob_feel_current" else "native_silence_blob_current"
    val result = JSONObject().put("selector", selector).put("passed", false)
        .put("scope", if (motionOnly) "Real AudioBlobView, controller and Home overlay; synthetic visual timeline; no microphone or PCM claim"
            else "Real AudioBlobView and controller; actual synthetic-PCM SilenceGateStream decisions; no live Service routing or microphone claim")
        .put("scenes", scenes)
    val rendererField = AudioBlobView::class.java.getDeclaredField("renderer").apply { isAccessible = true }
    val amountField = AudioBlobView::class.java.getDeclaredField("currentSilence").apply { isAccessible = true }
    val lifeField = AudioBlobView::class.java.getDeclaredField("currentLife").apply { isAccessible = true }
    val postedField = AudioBlobView::class.java.getDeclaredField("framePosted").apply { isAccessible = true }
    val timeField = AudioBlobView::class.java.getDeclaredField("shaderTimeSeconds").apply { isAccessible = true }
    fun <T> main(action: () -> T): T = nativeParityOnMain(instrumentation, action)
    var failure: Throwable? = null
    val host = launchCurrentUiSmokeActivity(instrumentation)
    try {
        for (dark in listOf(false, true)) for (fallback in listOf(false, true)) {
            val name = "${if (dark) "dark" else "light"}-${if (fallback) "fallback" else "agsl"}"
            val scene = JSONObject().put("name", name)
            scenes.put(scene)
            val palette = ReverbPalette.resolve(host, dark)
            scene.put("primary_argb", palette.primary).put("tertiary_argb", palette.tertiary)
            val controller = AudioBlobController()
            val blob = main { AudioBlobView(host) }
            var blobFailure: Throwable? = null
            try {
                main {
                    val view = blob
                    if (fallback) {
                        // QA-only selection of the existing production fallback, before first layout/draw.
                        val type = AudioBlobView::class.java.declaredClasses.single { it.simpleName == "FallbackRenderer" }
                        val replacement = type.getDeclaredConstructor().apply { isAccessible = true }.newInstance()
                        rendererField.set(view, replacement)
                        check(rendererField.get(view) === replacement)
                    }
                    val renderer = rendererField.get(view).javaClass.simpleName
                    check(renderer == if (fallback) "FallbackRenderer" else "ShaderRenderer") { "Unexpected renderer $renderer" }
                    scene.put("renderer", renderer)
                    host.fixtureRoot.setBackgroundColor(palette.surface)
                    val size = (minOf(host.resources.displayMetrics.widthPixels,
                        host.resources.displayMetrics.heightPixels) * .72f).toInt().coerceAtLeast(1)
                    host.fixtureRoot.addView(view, FrameLayout.LayoutParams(size, size, Gravity.CENTER))
                    controller.attach(view)
                    view.updateState(!motionOnly, true, false, true, palette.primary, palette.tertiary,
                        palette.surfaceContainerHighest, skipSilenceEnabled = true)
                }
                val readyDeadline = SystemClock.uptimeMillis() + 5000
                while (!main { blob.isLaidOut && blob.isShown && blob.hasWindowFocus() && blob.isHardwareAccelerated }) {
                    check(SystemClock.uptimeMillis() < readyDeadline) { "Blob host did not become focused and ready" }
                    awaitNativeParityDraw(instrumentation, blob)
                }
                if (motionOnly) {
                    captureNativeBlobMotionTimeline(instrumentation, blob, controller, palette, name, directory, scene)
                } else {
                verifyNativeBlobRestartClock(instrumentation, blob, palette, scene)
                val gate = SilenceGateStream(16_000, 1, PcmSampleFormat.PCM_16,
                    SilenceGateConfig(true, listOf(SilencePoint(20, -50), SilencePoint(20_000, -50))),
                    object : SilenceGateOutput {
                        override fun audio(bytes: ByteArray, offset: Int, count: Int, sourceFrame: Long) = Unit
                        override fun gap() = Unit
                    })
                val tone = ByteBuffer.allocate(4096 * 2).order(ByteOrder.nativeOrder()).apply {
                    repeat(4096) { putShort((sin(2 * PI * 1000 * it / 16_000) * 16_000).toInt().toShort()) }
                }.array()
                val quiet = ByteArray(16_000)
                val amounts = JSONArray()
                scene.put("samples", amounts)
                fun submit(bytes: ByteArray, suppressed: Boolean) {
                    gate.accept(bytes, 0, bytes.size)
                    check(gate.suppressingSilence == suppressed)
                    main {
                        // Zero visual activity isolates color and proves silence itself owns the finite animation.
                        controller.submit(ReverbService.VisualizationFrame(0f, FloatArray(8), gate.suppressingSilence,
                            ReverbService.BufferSlot.ONE_SHOT, 1L, 1L))
                    }
                }
                fun settled(target: Float) {
                    val deadline = SystemClock.uptimeMillis() + 5000
                    do {
                        awaitNativeParityDraw(instrumentation, blob)
                        val done = main {
                            check(blob.isShown && blob.hasWindowFocus() && blob.isHardwareAccelerated)
                            val amount = amountField.getFloat(blob)
                            amounts.put(JSONObject().put("target", target).put("amount", amount)
                                .put("time_ms", SystemClock.uptimeMillis()))
                            amount == target && lifeField.getFloat(blob) == 1f && !postedField.getBoolean(blob)
                        }
                        if (done) break
                        check(SystemClock.uptimeMillis() < deadline) { "Blob transition did not settle" }
                    } while (true)
                    val time = main { timeField.getFloat(blob) }
                    repeat(4) { awaitNativeParityDraw(instrumentation, blob) }
                    main {
                        check(!postedField.getBoolean(blob) && timeField.getFloat(blob) == time) {
                            "Settled zero-signal blob kept scheduling animation"
                        }
                    }
                }
                fun capture(state: String): IntArray {
                    val bitmap = captureNativeParityContent(instrumentation, host) { blob }
                    var captureFailure: Throwable? = null
                    try {
                        saveNativeParityBitmap(directory, "$name-$state.png", bitmap)
                        // Retain a fixed grid, not three full-screen bitmaps.
                        return IntArray(64 * 64) { index ->
                            val x = ((index % 64 + .5) * bitmap.width / 64).toInt().coerceIn(0, bitmap.width - 1)
                            val y = ((index / 64 + .5) * bitmap.height / 64).toInt().coerceIn(0, bitmap.height - 1)
                            bitmap.getPixel(x, y)
                        }
                    } catch (error: Throwable) {
                        captureFailure = error
                        throw error
                    } finally {
                        val terminal = captureNativeParityCleanup(captureFailure) { bitmap.recycle() }
                        if (captureFailure == null) terminal?.let { throw it }
                    }
                }
                submit(tone, false)
                settled(0f)
                val normalPixels = capture("normal")
                submit(quiet, true)
                main { check(postedField.getBoolean(blob)) { "Digital silence did not schedule its color transition" } }
                settled(1f)
                val silentPixels = capture("silent")
                submit(tone, false)
                settled(0f)
                val recoveredPixels = capture("recovered")
                verifySilenceBlobPixels(normalPixels, silentPixels, recoveredPixels, scene)
                // Real renderer frames with controlled visual energy, independent of the gate oracle above.
                val motionEvidence = JSONArray()
                scene.put("audible_motion", motionEvidence)
                fun changedPixels(a: IntArray, b: IntArray): Int = a.indices.count { index ->
                    val x = a[index]; val y = b[index]
                    kotlin.math.abs((x ushr 16 and 255) - (y ushr 16 and 255)) +
                        kotlin.math.abs((x ushr 8 and 255) - (y ushr 8 and 255)) +
                        kotlin.math.abs((x and 255) - (y and 255)) > 12
                }
                var lowEnergyPixels: IntArray? = null
                for (highEnergy in listOf(false, true)) {
                    val energyName = if(highEnergy) "high-energy" else "low-energy"
                    val visual = ReverbService.VisualizationFrame(if(highEnergy) .75f else .12f,
                        if(highEnergy) floatArrayOf(.8f,.7f,.6f,.5f,.7f,.8f,.6f,.5f)
                        else floatArrayOf(.2f,.16f,.12f,.1f,.04f,.03f,.02f,.01f),
                        false, ReverbService.BufferSlot.ONE_SHOT, 1L, 1L)
                    fun advanceFor(durationMillis: Long) {
                        val start = SystemClock.uptimeMillis()
                        do {
                            main { controller.submit(visual) }
                            awaitNativeParityDraw(instrumentation, blob)
                            check(SystemClock.uptimeMillis() - start < 5000L) { "Audible frames stalled" }
                        } while(SystemClock.uptimeMillis() - start < durationMillis)
                    }
                    advanceFor(400L)
                    val beforeTime = main { timeField.getFloat(blob) }
                    val a = capture("$energyName-a")
                    advanceFor(350L)
                    val b = capture("$energyName-b")
                    val afterTime = main { timeField.getFloat(blob) }
                    val changed = changedPixels(a,b)
                    check(afterTime > beforeTime && changed > 8) { "Audible blob remained visually static at $energyName" }
                    val energyDifference = lowEnergyPixels?.let { changedPixels(it,a) }
                    if(energyDifference != null)check(energyDifference > 24) { "Energy did not change the rendered material" }
                    else lowEnergyPixels = a
                    motionEvidence.put(JSONObject().put("energy",energyName).put("changed_grid_pixels",changed)
                        .put("energy_difference_grid_pixels",energyDifference ?: JSONObject.NULL)
                        .put("before_shader_time",beforeTime.toDouble()).put("after_shader_time",afterTime.toDouble()))
                }
                main {
                    blob.updateState(true, true, false, false, palette.primary, palette.tertiary,
                        palette.surfaceContainerHighest, skipSilenceEnabled = true)
                    check(!postedField.getBoolean(blob)) { "Hidden audible blob retained a frame callback" }
                    blob.updateState(true, true, false, true, palette.primary, palette.tertiary,
                        palette.surfaceContainerHighest, skipSilenceEnabled = true)
                }
                submit(quiet, true)
                settled(1f)
                main {
                    blob.updateState(true, true, false, false, palette.primary, palette.tertiary,
                        palette.surfaceContainerHighest, skipSilenceEnabled = true)
                    check(!postedField.getBoolean(blob)) { "Hidden blob retained a frame callback" }
                }
                scene.put("retained_frames", gate.retainedFrames).put("skipped_frames", gate.skippedFrames)
                    .put("zero_activity_transition_settled", true).put("hidden_frame_stopped", true)
                gate.finish()
                check(!gate.suppressingSilence)
                verifyNativeBlobReadoutContrast(instrumentation, host, blob, controller, palette,
                    name, directory, scene)
                }
            } catch (error: Throwable) {
                blobFailure = error
            } finally {
                blobFailure = captureNativeParityCleanup(blobFailure) { main { controller.detach(blob) } }
                blobFailure = captureNativeParityCleanup(blobFailure) { main { host.fixtureRoot.removeView(blob) } }
            }
            blobFailure?.let { throw it }
        }
        check(getRecorderPreferences(context).all == preferences)
    } catch (error: Throwable) {
        failure = error
    } finally {
        failure = captureNativeParityCleanup(failure) { main { host.finish() } }
        failure = captureNativeParityCleanup(failure) {
            result.put("passed", failure == null)
            failure?.let { result.put("error", android.util.Log.getStackTraceString(it)) }
            File(directory, "result.json").writeText(result.toString(2))
        }
    }
    failure?.let { throw it }
    report.appendLine("PASS $selector real AGSL/fallback ${if (motionOnly) "continuous synthetic visual timeline" else "gate-derived silent/recovery pixels"}; evidence ${directory.absolutePath}")
}

/** Interior samples exclude the moving edge, glow, and background; normal pixels own the palette baseline. */
private fun verifySilenceBlobPixels(normal: IntArray, silent: IntArray, recovered: IntArray, scene: JSONObject) {
    check(normal.size == 4096 && silent.size == normal.size && recovered.size == normal.size)
    class Region {
        var count = 0
        var normalLight = 0.0
        var silentLight = 0.0
        var normalChroma = 0.0
        var silentChroma = 0.0
        var recoveryError = 0.0
        fun lightRatio() = silentLight / normalLight
        fun evidence() = JSONObject().put("samples", count).put("silent_light_ratio", lightRatio())
            .put("normal_chroma", normalChroma / count).put("silent_chroma", silentChroma / count)
            .put("recovery_mean_channel_error", recoveryError / count / 3)
    }
    val core = Region()
    val rim = Region()
    fun red(color: Int) = color ushr 16 and 255
    fun green(color: Int) = color ushr 8 and 255
    fun blue(color: Int) = color and 255
    fun light(color: Int) = .2126 * red(color) + .7152 * green(color) + .0722 * blue(color)
    fun chroma(color: Int) = maxOf(red(color), green(color), blue(color)) - minOf(red(color), green(color), blue(color))
    for (index in normal.indices) {
        val x = (index % 64 + .5) / 64 - .5
        val y = (index / 64 + .5) / 64 - .5
        // Settled life=1 and activity=0 imply base radius exactly 0.33 of this square View.
        val radius = kotlin.math.sqrt(x * x + y * y) / .33
        val region = when {
            radius <= .18 -> core
            radius >= .72 && radius <= .85 -> rim
            else -> continue
        }
        val a = normal[index]; val b = silent[index]; val c = recovered[index]
        check(a ushr 24 == 255 && b ushr 24 == 255 && c ushr 24 == 255)
        region.count++
        region.normalLight += light(a); region.silentLight += light(b)
        region.normalChroma += chroma(a); region.silentChroma += chroma(b)
        region.recoveryError += kotlin.math.abs(red(a) - red(c)) + kotlin.math.abs(green(a) - green(c)) +
            kotlin.math.abs(blue(a) - blue(c))
    }
    check(core.count >= 24 && rim.count >= 100 && core.normalLight > 0 && rim.normalLight > 0)
    scene.put("pixel_oracle", JSONObject().put("core", core.evidence()).put("rim", rim.evidence())
        .put("sample_grid", "64x64; core r<=0.18; rim 0.72<=r<=0.85; edge/glow excluded")
        .put("comparison", "Ratios of encoded Rec.709-weighted RGB against same-renderer normal pixels"))
    check(rim.lightRatio() < .82 && core.lightRatio() < .96) { "Silence did not visibly darken the blob" }
    check(core.lightRatio() - rim.lightRatio() > .10) { "Color/light did not concentrate toward the core" }
    // Normalize chroma loss by luminance loss: mere dimming must not pass as desaturation.
    if (rim.normalChroma / rim.count >= 4.0) {
        val normalizedChromaRatio = (rim.silentChroma / rim.normalChroma) / rim.lightRatio()
        scene.getJSONObject("pixel_oracle").put("normalized_rim_chroma_ratio", normalizedChromaRatio)
        check(normalizedChromaRatio < .70) { "Silent rim did not visibly desaturate" }
    } else {
        scene.getJSONObject("pixel_oracle").put("desaturation_limit", "Actual palette nearly neutral; no positive chroma-loss claim")
        check(rim.silentChroma <= rim.normalChroma + rim.count) { "Silence introduced chroma into a neutral palette" }
    }
    check(core.recoveryError / core.count / 3 <= 4.0 && rim.recoveryError / rim.count / 3 <= 4.0) {
        "Recovered interior pixels did not return near the normal palette"
    }
    scene.getJSONObject("pixel_oracle").put("passed", true)
}
