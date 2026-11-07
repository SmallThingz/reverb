package app.smallthingz.reverb

import android.app.Application
import android.app.Instrumentation
import android.graphics.Rect
import android.graphics.RectF
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.LinearLayoutManager
import app.smallthingz.reverb.nativeui.*
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

/** Current contract through Main's real Settings hit path; microphone use is a separate selector. */
internal fun verifyNativeSettingsCalibrationCurrent(
    instrumentation: Instrumentation,
    report: StringBuilder,
    liveCalibration: Boolean = false,
) {
    val context = instrumentation.targetContext
    val currentUi=context.packageName == "app.smallthingz.reverb.uitheme.reliability"
    check(context.packageName == "app.smallthingz.reverb.nativeparity.reliability" || currentUi)
    check(!currentUi || !liveCalibration) { "Current UI calibration probe is synthetic only" }
    check(context.applicationContext.javaClass == Application::class.java)
    val preferences = getRecorderPreferences(context)
    val original = preferences.all.toMap()
    val directory = File(context.filesDir, "native-settings-calibration-current-${System.currentTimeMillis()}")
    check(directory.mkdir())
    val cases = JSONArray()
    val evidence = JSONObject().put("passed", false).put("cases", cases)
        .put("live_microphone_requested", liveCalibration)
        .put("scope", "Own-root synthetic MotionEvents, real Main/Settings pointer binding and draft; explicit synthetic calibration geometry states")
    fun publish() = writeQaEvidenceAtomically(File(directory, "result.json")) {
        it.write(evidence.toString(2).toByteArray(Charsets.UTF_8))
    }
    publish()
    val host = if(currentUi)launchCurrentUiSmokeActivity(instrumentation) else launchNativeParityActivity(instrumentation)
    var render: MainSettingsParityRender? = null
    var failure: Throwable? = null
    try {
        val current = nativeParityOnMain(instrumentation) {
            MainSettingsParityRender(host, false, AppThemeMode.LIGHT, false, null)
        }.also { render = it }
        fun main(action: () -> Unit) = nativeParityOnMain(instrumentation, action)
        fun await(label: String, timeout: Long = 5_000L, diagnostics: () -> String = { "" }, condition: () -> Boolean) {
            val deadline = SystemClock.uptimeMillis() + timeout
            while (!nativeParityOnMain(instrumentation, condition)) {
                check(SystemClock.uptimeMillis() < deadline) { "Timed out: $label ${diagnostics()}" }
                try {
                    awaitNativeParityDraw(instrumentation, current.board)
                } catch (error: Exception) {
                    throw IllegalStateException("While awaiting: $label ${diagnostics()}", error)
                }
            }
        }
        fun settle() { awaitNativeParityDraw(instrumentation, current.board); instrumentation.waitForIdleSync() }
        fun bounds(view: View): Rect {
            val p = IntArray(2); view.getLocationOnScreen(p)
            return Rect(p[0], p[1], p[0] + view.width, p[1] + view.height)
        }
        fun motion(action: Int, down: Long, x: Float, y: Float, time: Long = SystemClock.uptimeMillis()) {
            check(host.hasWindowFocus() && current.board.isShown)
            val origin = IntArray(2); current.board.getLocationOnScreen(origin)
            val event = MotionEvent.obtain(down, time, action, x, y, 0)
            event.source = InputDevice.SOURCE_TOUCHSCREEN
            event.offsetLocation(-origin[0].toFloat(), -origin[1].toFloat())
            try { check(current.board.dispatchTouchEvent(event)) } finally { event.recycle() }
        }
        var settingsReadiness = "not sampled"
        await("Settings action readiness", diagnostics = { settingsReadiness }) {
            val shown = current.board.isShown
            val focused = host.hasWindowFocus()
            val actions = current.actionCount(host.getString(R.string.open_settings))
            settingsReadiness = "shown=$shown focused=$focused actions=$actions " +
                "board=${current.board.width}x${current.board.height}"
            shown && focused && actions == 1
        }
        main { current.click(host.getString(R.string.open_settings)) }
        lateinit var settings: NativeSettingsScreenContent
        await("Settings hydration") {
            calibrationCurrentViews(current.board).filterIsInstance<NativeSettingsScreenContent>()
                .firstOrNull { it.inputAllowed }?.also { settings = it } != null
        }
        main {
            val owner = settings.owner
            val config = decodeSilenceGate(owner.snapshot.silenceGate)
            owner.silenceChanged(owner.state.activation, config, owner.state.editRevision, config.copy(enabled = true))
            val list = checkNotNull(settings.listViewport)
            val index = SettingsSectionKey.visible(owner.batteryOptimizationRestricted).indexOf(SettingsSectionKey.RECORDING)
            (list.layoutManager as LinearLayoutManager).scrollToPositionWithOffset(index, 0)
        }
        lateinit var silence: SettingsSilenceView
        await("Expanded silence section") {
            calibrationCurrentViews(settings).filterIsInstance<SettingsSilenceView>()
                .firstOrNull { it.calibrationPointerNode != null }?.also { silence = it } != null
        }
        await("Silence expansion settled") {
            val expansion = calibrationCurrentViews(silence).filterIsInstance<SettingsExpansionView>().single()
            val body = expansion.content?.view
            body != null && !silence.isLayoutRequested && !expansion.isLayoutRequested &&
                expansion.height == body.measuredHeight && body.alpha == 1f
        }
        settle()
        fun reveal(view: View) {
            main {
                val viewport = checkNotNull(settings.listViewport)
                val target = bounds(view); val area = bounds(viewport)
                viewport.scrollBy(0, target.centerY() - area.centerY())
            }
            settle()
            main { check(view.getGlobalVisibleRect(Rect())) }
        }
        val curve = nativeParityOnMain(instrumentation) {
            calibrationCurrentViews(silence).filterIsInstance<SilenceCurveView>().single()
        }
        // The histogram is optional. A drag must commit through the real section callback/draft.
        reveal(curve)
        main {
            val owner = settings.owner
            check(decodeSilenceCalibrationHistogram(owner.snapshot.silenceHistogram) == null) {
                "Manual-before-calibration case requires a fresh isolated QA histogram"
            }
            val before = decodeSilenceGate(owner.snapshot.silenceGate)
            val revision = owner.state.editRevision
            check(curve.isEnabled)
            val field = SilenceCurveView::class.java.getDeclaredField("plot").apply { isAccessible = true }
            val plot = field.get(curve) as RectF
            val point = before.points[1]
            val b = bounds(curve)
            val x = b.left + plot.left + silenceFrequencyFraction(point.hz.toDouble()).toFloat() * plot.width()
            val y = b.top + plot.top + (-point.db / 96f) * plot.height()
            val dy = if(point.db < -48) -plot.height() / 5 else plot.height() / 5
            val down = SystemClock.uptimeMillis()
            val scroll = settings.savePosition()
            motion(MotionEvent.ACTION_DOWN, down, x, y, down)
            motion(MotionEvent.ACTION_MOVE, down, x, y + dy, down + 30)
            motion(MotionEvent.ACTION_UP, down, x, y + dy, down + 60)
            val after = decodeSilenceGate(owner.snapshot.silenceGate)
            check(after.points != before.points && owner.state.editRevision > revision)
            check(settings.savePosition() == scroll) { "Curve drag scrolled Settings" }
            cases.put(JSONObject().put("case", "manual_drag_without_histogram").put("passed", true))
        }
        settle()
        val button = nativeParityOnMain(instrumentation) { checkNotNull(silence.calibrationPointerNode).view }
        reveal(button)
        if (liveCalibration) {
            var down = 0L
            lateinit var startBounds: Rect
            var startHeight = 0
            main {
                startBounds = bounds(button); startHeight = silence.height; down = SystemClock.uptimeMillis()
                motion(MotionEvent.ACTION_DOWN, down, startBounds.exactCenterX(), startBounds.exactCenterY(), down)
                check(settings.owner.calibration.running && settings.owner.calibration.holding)
            }
            settle()
            main {
                check(bounds(button) == startBounds && silence.height == startHeight)
                motion(MotionEvent.ACTION_UP, down, startBounds.exactCenterX(), startBounds.exactCenterY(), down + 80)
                check(settings.owner.calibration.running && !settings.owner.calibration.holding)
            }
            await("Bounded live calibration terminal", 15_000L) { !settings.owner.calibration.running }
            settle()
            main {
                check(silence.height == startHeight)
                val controller = settings.owner.calibration
                cases.put(JSONObject().put("case", "live_controller_calibration")
                    .put("measured", controller.measured != null).put("error", controller.error ?: JSONObject.NULL))
                check(controller.measured != null) { "Live calibration failed: ${controller.error}" }
            }
        }
        // Rebind only this real attached section's callback seam: native hit routing remains unchanged.
        var state = nativeParityOnMain(instrumentation) {
            SettingsSilenceState(decodeSilenceGate(settings.owner.snapshot.silenceGate))
        }
        var taps = 0
        var holds = 0
        var cancels = 0
        var begins = 0
        var directStarts = 0
        lateinit var actions: SettingsSilenceActions
        fun update(next: SettingsSilenceState) {
            state = next; silence.bind(state, settings.pointerEnvironment, actions)
        }
        actions = object : SettingsSilenceActions {
            override fun configChanged(config: SilenceGateConfig) = update(state.copy(config = config))
            override fun calibrate() { directStarts++; update(state.copy(calibrating = true, calibrationProgress = 0f)) }
            override fun beginHeld(): Boolean {
                begins++
                update(state.copy(calibrating = true, holdingCalibration = true)); return true
            }
            override fun completeTap() { taps++; update(state.copy(holdingCalibration = false, calibrationProgress = 0f)) }
            override fun finishHeld() { holds++; update(state.copy(calibrating = false, holdingCalibration = false)) }
            override fun cancel() { cancels++; update(state.copy(calibrating = false, holdingCalibration = false, calibrationProgress = null)) }
        }
        main { update(state) }
        settle()
        val initial = nativeParityOnMain(instrumentation) { silence.height to bounds(button) }
        fun stable(label: String) {
            settle()
            main {
                check(silence.height == initial.first && bounds(button) == initial.second) { "Calibration geometry changed at $label" }
                val row = button.parent as View
                val body = row.parent as View
                check(row.bottom == body.height) { "Calibration retains a footer at $label" }
                check(calibrationCurrentViews(body).filterIsInstance<ReverbParagraphView>().none {
                    it.visibility == View.VISIBLE && it.top >= row.bottom && it.parent === body
                }) { "Nonerror status text remains below calibration at $label" }
                cases.put(JSONObject().put("case", label).put("height", silence.height).put("passed", true))
            }
        }
        for (held in listOf(false, true)) {
            val down = SystemClock.uptimeMillis()
            main { motion(MotionEvent.ACTION_DOWN, down, initial.second.exactCenterX(), initial.second.exactCenterY(), down) }
            stable(if (held) "held_after_layout" else "tap_down_after_layout")
            main {
                val duration = if (held) android.view.ViewConfiguration.getLongPressTimeout().toLong() + 20 else 80L
                motion(MotionEvent.ACTION_UP, down, initial.second.exactCenterX(), initial.second.exactCenterY(), down + duration)
            }
            stable(if (held) "held_finished" else "timed_running")
            if (!held) {
                main {
                    check(state.calibrating && !state.holdingCalibration && taps == 1 && cancels == 0 && directStarts == 0)
                    check(calibrationCurrentViews(silence).filterIsInstance<SettingsCalibrationActionView>().count() == 1)
                    check(calibrationCurrentViews(button).filterIsInstance<ReverbParagraphView>()
                        .any { it.getText() == host.getString(R.string.cancel) })
                    cases.put(JSONObject().put("case", "start_up_preserves_timed_sample_same_button_cancel_label").put("passed", true))
                    // Losing new-sample admission must not disable Cancel for the accepted sample.
                    update(state.copy(calibrationAllowed = false))
                }
                stable("timed_cancel_enabled_without_new_sample_admission")
                val cancelDown = SystemClock.uptimeMillis()
                main {
                    motion(MotionEvent.ACTION_DOWN, cancelDown, initial.second.exactCenterX(), initial.second.exactCenterY(), cancelDown)
                    check(state.calibrating && begins == 1 && cancels == 0)
                    motion(MotionEvent.ACTION_UP, cancelDown, initial.second.exactCenterX(), initial.second.exactCenterY(), cancelDown + 80)
                    check(!state.calibrating && cancels == 1 && begins == 1 && taps == 1 && directStarts == 0)
                    check(calibrationCurrentViews(button).filterIsInstance<ReverbParagraphView>()
                        .any { it.getText() == host.getString(R.string.silence_calibrate) })
                    cases.put(JSONObject().put("case", "later_same_button_cancel_once_without_restarting").put("passed", true))
                    update(state.copy(calibrationAllowed = true))
                }
                stable("cancel_returns_idle_same_button_bounds")
            }
            main { update(state.copy(calibrating = false, holdingCalibration = false, calibrationProgress = null)) }
            stable("idle_after_${if (held) "hold" else "tap"}")
        }
        main { check(taps == 1 && holds == 1 && cancels == 1 && begins == 2 && directStarts == 0) }
        val longError=actionableErrorMessage(host,host.getString(R.string.silence_sample_failed),
            java.io.IOException("The audio input stopped delivering samples while the room calibration was listening. " +
                "Check the selected input and try again. Diagnostic source content://private/audio token=fixture-secret"))
        fun assertFullExplanation(label:String,expectedButton:Rect) {
            settle()
            main {
                check(bounds(button)==expectedButton) { "Calibration button moved at $label" }
                val text=calibrationCurrentViews(silence).filterIsInstance<ReverbParagraphView>()
                    .single {it.visibility==View.VISIBLE&&it.getText()==longError}
                check(text.width>0 && text.intrinsicTextHeight(text.width)<=text.height) {
                    "Full actionable explanation was clipped at $label"
                }
                val body=checkNotNull((text.parent as? View))
                check(text.bottom<=body.height) { "Explanation extends beyond its scrollable body" }
                check(silence.height>initial.first) { "Long explanation did not expand its terminal status area" }
                check(!longError.contains("fixture-secret")&&!longError.contains("content://private"))
                cases.put(JSONObject().put("case",label).put("height",silence.height)
                    .put("error_height",text.height).put("passed",true))
            }
        }
        if(!liveCalibration) {
            main { update(state.copy(calibrationError=longError)) }
            assertFullExplanation("full_terminal_error_fixed_button",initial.second)
        }
        main { update(state.copy(calibrationError = null, sampledPeaks = List(SILENCE_GRAPH_BINS) { -70f })) }
        stable("histogram_complete")
        val dial = nativeParityOnMain(instrumentation) {
            calibrationCurrentViews(silence).filterIsInstance<SettingsCalibrationFitDial>().single()
        }
        reveal(dial)
        main {
            val before=state.config.points
            val b=bounds(dial);val down=SystemClock.uptimeMillis()
            motion(MotionEvent.ACTION_DOWN,down,b.exactCenterX(),b.exactCenterY(),down)
            motion(MotionEvent.ACTION_MOVE,down,b.exactCenterX()+30f,b.exactCenterY()-30f,down+30)
            motion(MotionEvent.ACTION_CANCEL,down,b.exactCenterX()+30f,b.exactCenterY()-30f,down+60)
            check(!state.calibrating && state.config.points!=before)
            check(taps==1 && holds==1 && cancels==1 && begins==2 && directStarts==0) { "Fit adjustment started calibration" }
            val node=dial.createAccessibilityNodeInfo()
            check(node.rangeInfo.min==-6f && node.rangeInfo.max==18f)
            node.recycle()
            fun setFit(value:Float) {
                val args=android.os.Bundle().apply {putFloat(android.view.accessibility.AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE,value)}
                dial.performAccessibilityAction(android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.id,args)
                check(state.config.clearanceDb==value)
            }
            val density = settings.pointerEnvironment.textDensity.density
            val dialScroll = settings.savePosition()
            fun checkFit(expected:Float,label:String) {
                check(kotlin.math.abs(state.config.clearanceDb-expected)<.0001f) {
                    "$label: expected $expected, observed ${state.config.clearanceDb}"
                }
                check(settings.savePosition()==dialScroll) { "$label scrolled Settings" }
                check(begins==2 && taps==1 && holds==1 && cancels==1 && directStarts==0)
            }
            for(start in listOf(-5f,1f,6f,11f,17f)) {
                val angle=Math.toRadians(135.0+270.0*(start+6)/24.0)
                val tangentX=-kotlin.math.sin(angle).toFloat()
                val tangentY=kotlin.math.cos(angle).toFloat()
                for(direction in listOf(-1f,1f)) {
                    setFit(start)
                    val box=bounds(dial);val time=SystemClock.uptimeMillis()
                    val x=box.exactCenterX();val y=box.exactCenterY()
                    val endX=x+tangentX*direction*2f*density
                    val endY=y+tangentY*direction*2f*density
                    motion(MotionEvent.ACTION_DOWN,time,x,y,time)
                    motion(MotionEvent.ACTION_MOVE,time,endX,endY,time+20)
                    checkFit(start+direction*.2f,"tangent $start/$direction")
                    motion(MotionEvent.ACTION_UP,time,endX,endY,time+40)
                    checkFit(start+direction*.2f,"tangent release $start/$direction")
                }
                for(direction in listOf(-1f,1f)) {
                    setFit(start)
                    val unchanged=state.config.points
                    val box=bounds(dial);val time=SystemClock.uptimeMillis()
                    val x=box.exactCenterX();val y=box.exactCenterY()
                    val endX=x+kotlin.math.cos(angle).toFloat()*direction*2f*density
                    val endY=y+kotlin.math.sin(angle).toFloat()*direction*2f*density
                    motion(MotionEvent.ACTION_DOWN,time,x,y,time)
                    motion(MotionEvent.ACTION_MOVE,time,endX,endY,time+20)
                    motion(MotionEvent.ACTION_UP,time,endX,endY,time+40)
                    checkFit(start,"radial $start/$direction")
                    check(state.config.points==unchanged) { "Radial movement refitted the curve" }
                }
            }
            cases.put(JSONObject().put("case","fit_live_tangent_forward_backward_and_radial_orthogonality")
                .put("start_positions",5).put("synthetic_gestures",20).put("passed",true))
            for(edge in listOf(-6f,18f)) {
                setFit(edge)
                val angle=Math.toRadians(135.0+270.0*(edge+6)/24.0)
                val direction=if(edge==18f)1f else -1f
                val tx=-kotlin.math.sin(angle).toFloat()*direction
                val ty=kotlin.math.cos(angle).toFloat()*direction
                val box=bounds(dial);val time=SystemClock.uptimeMillis()
                val x=box.exactCenterX();val y=box.exactCenterY()
                motion(MotionEvent.ACTION_DOWN,time,x,y,time)
                motion(MotionEvent.ACTION_MOVE,time,x+tx*5f*density,y+ty*5f*density,time+20)
                checkFit(edge,"clamped $edge")
                // Reverse from the last pointer position, not the pre-clamp DOWN anchor.
                motion(MotionEvent.ACTION_MOVE,time,x+tx*3f*density,y+ty*3f*density,time+40)
                checkFit(edge-direction*.2f,"immediate clamp reversal $edge")
                motion(MotionEvent.ACTION_CANCEL,time,x+tx*3f*density,y+ty*3f*density,time+60)
                checkFit(edge-direction*.2f,"cancel preserves adjustment $edge")
            }
            cases.put(JSONObject().put("case","fit_both_clamps_reverse_without_dead_travel")
                .put("synthetic_gestures",2).put("passed",true))
            setFit(-6f);val tight=state.config.points
            setFit(18f);check(state.config.points!=tight)
            dial.onKeyDown(android.view.KeyEvent.KEYCODE_MOVE_HOME,android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN,android.view.KeyEvent.KEYCODE_MOVE_HOME))
            checkFit(-6f,"keyboard minimum")
            dial.onKeyDown(android.view.KeyEvent.KEYCODE_MOVE_END,android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN,android.view.KeyEvent.KEYCODE_MOVE_END))
            checkFit(18f,"keyboard maximum")
            dial.performAccessibilityAction(R.id.silence_fit_reset,null)
            check(state.config.clearanceDb==6f)
            dial.onKeyDown(android.view.KeyEvent.KEYCODE_DPAD_UP,android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN,android.view.KeyEvent.KEYCODE_DPAD_UP))
            check(state.config.clearanceDb==7f)
            dial.onKeyDown(android.view.KeyEvent.KEYCODE_R,android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN,android.view.KeyEvent.KEYCODE_R))
            check(state.config.clearanceDb==6f)
            val manual=state.config.copy(points=state.config.points.map {it.copy(db=it.db-1)})
            update(state.copy(config=manual))
            check(state.config.points==manual.points) { "Rebind refitted manual points" }
            cases.put(JSONObject().put("case","fit_drag_cancel_keyboard_accessibility_actual_curve").put("passed",true))
        }
        reveal(dial)
        main { check(dial.requestFocusFromTouch()) }
        settle()
        val keyboardScroll = nativeParityOnMain(instrumentation) { settings.savePosition() }
        for ((key, expected) in listOf(
            android.view.KeyEvent.KEYCODE_MOVE_HOME to -6f,
            android.view.KeyEvent.KEYCODE_MOVE_HOME to -6f,
            android.view.KeyEvent.KEYCODE_MOVE_END to 18f,
            android.view.KeyEvent.KEYCODE_MOVE_END to 18f,
            android.view.KeyEvent.KEYCODE_R to 6f,
            android.view.KeyEvent.KEYCODE_DPAD_UP to 7f,
            android.view.KeyEvent.KEYCODE_DPAD_DOWN to 6f,
        )) {
            main {
                check(dial.hasFocus() && host.hasWindowFocus())
                check(host.dispatchKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN,key)))
                check(state.config.clearanceDb == expected)
                check(host.dispatchKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_UP,key)))
                check(state.config.clearanceDb == expected) { "Key-up adjusted the dial twice" }
                check(settings.listViewport?.layoutManager?.isSmoothScrolling != true) {
                    "Dial key-up started ancestor scrolling"
                }
            }
            settle()
            main {
                check(dial.hasFocus() && dial.getGlobalVisibleRect(Rect()))
                check(settings.savePosition() == keyboardScroll) { "Dial keyboard adjustment moved Settings" }
            }
        }
        cases.put(JSONObject().put("case","fit_window_key_down_up_preserves_focus_and_scroll")
            .put("key_pairs",7).put("passed",true))
        reveal(button)
        val progressBounds=nativeParityOnMain(instrumentation) {bounds(button)}
        for(progress in listOf(0f,.25f,.75f,1f)) {
            main {update(state.copy(calibrating=true,calibrationProgress=progress))}
            settle()
            main {
                check(bounds(button)==progressBounds)
                check(!dial.isEnabled)
                check(calibrationCurrentViews(button).any {
                    androidx.core.view.ViewCompat.getStateDescription(it)?.toString()=="${(progress*100).toInt()}%"
                })
            }
        }
        // Software View.draw evidence, sampled on real frame delivery; not compositor capture.
        fun requireAnimationScale() {
            val scale = if (android.os.Build.VERSION.SDK_INT >= 33) android.animation.ValueAnimator.getDurationScale()
                else android.provider.Settings.Global.getFloat(host.contentResolver,
                    android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
            check(android.animation.ValueAnimator.areAnimatorsEnabled() && scale == 1f) {
                "Rendered-motion probe requires unchanged animator scale1; observed=$scale"
            }
            evidence.put("animator_duration_scale", scale.toDouble())
        }
        main { requireAnimationScale() }
        val visual = nativeParityOnMain(instrumentation) {
            calibrationCurrentViews(button).single { it.javaClass.simpleName == "CalibrationActionSurface" }
        }
        val fillField = visual.javaClass.getDeclaredField("fill").apply { isAccessible = true }
        val pulseField = visual.javaClass.getDeclaredField("heldOpacity").apply { isAccessible = true }
        fun renderedBand(determinate: Boolean): Pair<Float,String> = nativeParityOnMain(instrumentation) {
            requireAnimationScale()
            val motionValue = if (determinate)
                (fillField.get(visual) as smallthingz.ui.animation.SpringValue<*, *>).value as Float
                else pulseField.getFloat(visual)
            val bitmap = android.graphics.Bitmap.createBitmap(button.width, button.height,
                android.graphics.Bitmap.Config.ARGB_8888)
            try {
                button.draw(android.graphics.Canvas(bitmap))
                val y = (button.height * .82f).toInt().coerceIn(0, button.height - 1)
                val pixels = IntArray(button.width)
                bitmap.getPixels(pixels, 0, button.width, 0, y, button.width, 1)
                motionValue to pixels.joinToString(",")
            } finally { bitmap.recycle() }
        }
        fun collectRenderedFrames(label: String, determinate: Boolean) {
            val frames = linkedSetOf<String>()
            val values = mutableListOf<Float>()
            val intermediateBands = linkedSetOf<String>()
            val started = SystemClock.uptimeMillis()
            do {
                awaitNativeParityDraw(instrumentation, current.board)
                val (value, band) = renderedBand(determinate)
                check(value.isFinite())
                if(determinate) {
                    check(value >= (values.lastOrNull() ?: 0f) - .00001f && value <= .75001f) {
                        "Determinate fill reversed or overshot: $value"
                    }
                    if(value > 0f && value < .75f) intermediateBands += band
                }
                values += value
                frames += band
                check(SystemClock.uptimeMillis() - started < 5_000L) { "Frame delivery stalled at $label" }
            } while (SystemClock.uptimeMillis() - started < 700L)
            check(frames.size>=3 && values.distinct().size>=3) { "No intermediate rendered motion at $label" }
            if(determinate)check(intermediateBands.size>=3) { "Palette animation without intermediate progress" }
            cases.put(JSONObject().put("case",label).put("distinct_software_view_bands",frames.size)
                .put("motion_values",JSONArray(values)).put("intermediate_fill_bands",intermediateBands.size)
                .put("elapsed_ms",SystemClock.uptimeMillis()-started).put("passed",true))
        }
        main { update(state.copy(calibrating=false,holdingCalibration=false,calibrationProgress=null)) }
        settle()
        main { update(state.copy(calibrating=true,holdingCalibration=false,calibrationProgress=.75f)) }
        collectRenderedFrames("determinate_fill_has_intermediate_frames", true)
        main { update(state.copy(calibrating=true,holdingCalibration=true,calibrationProgress=null)) }
        val heldWarmup = SystemClock.uptimeMillis()
        do {
            awaitNativeParityDraw(instrumentation, current.board)
            main { requireAnimationScale() }
        } while (SystemClock.uptimeMillis() - heldWarmup < 400L)
        collectRenderedFrames("held_fill_pulses_on_real_frames", false)
        // Existing geometry checks also cover this real-time hold, rather than a future UP timestamp.
        stable("held_real_time_animation_keeps_bounds")
        main {
            update(state.copy(calibrating=false,holdingCalibration=false,calibrationProgress=null,sampledPeaks=null))
            val manual=state.config.points
            dial.onKeyDown(android.view.KeyEvent.KEYCODE_DPAD_UP,android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN,android.view.KeyEvent.KEYCODE_DPAD_UP))
            check(state.config.points==manual) { "Fit without matching measurement replaced manual curve" }
            check(!state.calibrating)
        }
        cases.put(JSONObject().put("case","button_progress_inside_stable_bounds").put("passed",true))
        if(!liveCalibration) {
            val before=nativeParityOnMain(instrumentation) {bounds(button)}
            val down=SystemClock.uptimeMillis()
            main {
                motion(MotionEvent.ACTION_DOWN,down,before.exactCenterX(),before.exactCenterY(),down)
                update(state.copy(calibrating=false,holdingCalibration=false,calibrationProgress=null,
                    calibrationError=longError))
            }
            assertFullExplanation("admission_error_while_pointer_down_fixed_button",before)
            main {motion(MotionEvent.ACTION_CANCEL,down,before.exactCenterX(),before.exactCenterY(),down+80)}
            assertFullExplanation("admission_error_after_pointer_cancel_fixed_button",before)
        }
        main { check(current.forbiddenCalls == 0) }
        check(preferences.all == original) { "Probe changed durable QA preferences" }
        evidence.put("passed", true)
    } catch (error: Throwable) {
        failure = error; evidence.put("failure", error.stackTraceToString())
    } finally {
        failure = captureNativeParityCleanup(failure) { nativeParityOnMain(instrumentation) { render?.close() } }
        failure = captureNativeParityCleanup(failure) { nativeParityOnMain(instrumentation) { host.finish() } }
        failure = captureNativeParityCleanup(failure) { check(preferences.all == original) }
        if (failure != null) evidence.put("passed", false).put("failure", failure!!.stackTraceToString())
        publish()
        report.appendLine("SETTINGS_CALIBRATION_CURRENT_EVIDENCE ${directory.absolutePath}")
    }
    failure?.let { throw it }
    report.appendLine("PASS native_settings_calibration_current real Settings root drag and stable calibration geometry; live=$liveCalibration")
}

private fun calibrationCurrentViews(root: View): Sequence<View> = sequence {
    yield(root)
    if (root is ViewGroup) for (index in 0 until root.childCount) yieldAll(calibrationCurrentViews(root.getChildAt(index)))
}
