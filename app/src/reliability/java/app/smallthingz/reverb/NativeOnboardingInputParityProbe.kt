package app.smallthingz.reverb

import android.app.Application
import android.app.Instrumentation
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.SystemClock
import android.util.AtomicFile
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.BackEventCompat
import androidx.compose.material3.Typography
import androidx.compose.ui.geometry.Rect as ComposeRect
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getAllSemanticsNodes
import androidx.compose.ui.semantics.getOrNull
import app.smallthingz.reverb.nativeui.NativeMergedRowAccessibilityHost
import app.smallthingz.reverb.nativeui.OnboardingPageContentView
import app.smallthingz.reverb.nativeui.ReverbParagraphView
import java.io.File
import kotlin.math.abs
import kotlin.math.roundToInt
import org.json.JSONArray
import org.json.JSONObject

/**
 * Four bounded actual-View input flows, sharing the unchanged released screen oracle.
 * No ADB touch, permission request, Finish, backend, capture or preference mutation.
 * Ordinary intermediate traces are observations, not presentation-phase/FPS equivalence.
 */
internal fun verifyNativeOnboardingInputParity(instrumentation: Instrumentation, report: StringBuilder) {
    val context = instrumentation.targetContext
    check(context.packageName == "app.smallthingz.reverb.nativeparity.reliability")
    check(context.applicationContext.javaClass == Application::class.java)
    val directory = File(context.filesDir, "native-onboarding-input-${System.currentTimeMillis()}")
    check(directory.mkdir())
    val result = JSONObject().put("passed", false).put("baseline", "3e16f8ddb78dca15df4b436d34d330801ce83ffd")
        .put("dispatch", "Synthetic MotionEvents to own current View root; own-window Back dispatcher")
        .put("scope", "Commit/cancel, axis arbitration, card input during motion, Back/interruption")
        .put("limits", "No system predictive transport, real permission UI, TalkBack delivery or intermediate presentation-phase/FPS proof")
        .put("cases", JSONArray())
    var host: NativeParityActivity? = null
    var route: OnboardingInputRoute? = null
    var failure: Throwable? = null
    var completed = 0
    var mismatches = 0
    try {
        writeOnboardingInputEvidence(directory, result)
        val activity = launchNativeParityActivity(instrumentation).also { host = it }
        awaitNativeParityDraw(instrumentation, activity.fixtureRoot)
        val typography = Class.forName("app.smallthingz.reverb.ReverbThemeKt")
            .getDeclaredField("ReverbTypography").apply { isAccessible = true }.get(null) as Typography
        val scale = android.animation.ValueAnimator.getDurationScale()
        result.put("observed_animator_scale", scale.toDouble())
        check(scale.isFinite() && scale > 0f && scale <= 10f) {
            "An in-flight transition requires a positive current duration scale within this bounded observation; do not change it"
        }
        for (flow in listOf("horizontal", "axis", "cards", "back")) {
            val case = ScreenParityCase(true, flow == "axis", if (flow == "axis") 2f else 1f, false)
            val entry = JSONObject().put("name", flow).put("runs", JSONArray()).put("passed", false)
            result.getJSONArray("cases").put(entry)
            val observations = mutableListOf<LinkedHashMap<String, Pair<File, JSONObject>>>()
            for (reference in listOf(true, false)) {
                val run = JSONObject().put("renderer", if (reference) "compose" else "native")
                    .put("input", JSONArray()).put("frames", JSONArray()).put("checkpoints", JSONArray())
                entry.getJSONArray("runs").put(run)
                val current = OnboardingInputRoute(instrumentation, activity, typography, case, reference, flow, directory, run)
                    .also { route = it }
                current.open()
                when (flow) {
                    "horizontal" -> current.horizontal()
                    "axis" -> current.axis()
                    "cards" -> current.cards()
                    "back" -> current.back()
                }
                observations += current.captures
                current.assertNoEffects()
                route = null
                current.close()
                run.put("closed", true)
            }
            check(observations[0].keys == observations[1].keys)
            val comparisons = JSONArray()
            entry.put("comparisons", comparisons)
            var equal = true
            for (name in observations[0].keys) {
                val left = observations[0].getValue(name)
                val right = observations[1].getValue(name)
                val a = android.graphics.BitmapFactory.decodeFile(left.first.path)
                val b = android.graphics.BitmapFactory.decodeFile(right.first.path)
                val pixels = try { compareNativeParityPixels(checkNotNull(a), checkNotNull(b)) }
                    finally { a?.recycle(); b?.recycle() }
                val text = onboardingTextGeometrySignature(left.second) == onboardingTextGeometrySignature(right.second)
                val nodes = onboardingNodeSignature(left.second) == onboardingNodeSignature(right.second)
                val state = left.second.getJSONObject("observable_state").toString() ==
                    right.second.getJSONObject("observable_state").toString()
                val passed = pixels.getLong("different_pixels") == 0L && text && nodes && state
                comparisons.put(JSONObject().put("checkpoint", name).put("pixels", pixels)
                    .put("text_geometry_equal", text).put("canonical_nodes_equal", nodes)
                    .put("observable_state_equal", state).put("passed", passed))
                equal = equal && passed
            }
            entry.put("passed", equal)
            completed++
            if (!equal) mismatches++
            writeOnboardingInputEvidence(directory, result)
        }
        check(completed == 4 && mismatches == 0) { "Onboarding input differs in $mismatches/$completed paired flows" }
    } catch (error: Throwable) { failure = error }
    finally {
        fun cleanup(block: () -> Unit) {
            try { block() } catch (error: Throwable) {
                val earlier = failure
                if (earlier == null) failure = error else if (earlier !== error) earlier.addSuppressed(error)
            }
        }
        val remaining = route
        route = null
        cleanup { remaining?.close() }
        cleanup { host?.let { activity -> nativeParityOnMain(instrumentation) { activity.finish() } } }
        result.put("completed_flows", completed).put("mismatched_flows", mismatches)
            .put("passed", failure == null && completed == 4 && mismatches == 0)
        failure?.let { result.put("failure", it.stackTraceToString()) }
        var written = false
        try { writeOnboardingInputEvidence(directory, result); written = true }
        catch (error: Throwable) {
            val earlier = failure
            if (earlier == null) failure = error else if (earlier !== error) earlier.addSuppressed(error)
            result.put("passed", false).put("failure", checkNotNull(failure).stackTraceToString())
            try { writeOnboardingInputEvidence(directory, result); written = true }
            catch (writeFailure: Throwable) { if (writeFailure !== failure) checkNotNull(failure).addSuppressed(writeFailure) }
        }
        report.append(if (written) "PARITY_EVIDENCE " else "PARITY_EVIDENCE_WRITE_FAILED ")
            .append(directory.name).append("/result.json\n")
    }
    failure?.let { throw it }
    report.append("PASS native_onboarding_input_parity paired_flows=4; intermediate presentation phase unqualified\n")
}

private class OnboardingInputRoute(
    private val instrumentation: Instrumentation,
    private val host: NativeParityActivity,
    private val typography: Typography,
    private val case: ScreenParityCase,
    private val reference: Boolean,
    private val flow: String,
    private val directory: File,
    private val evidence: JSONObject,
) : AutoCloseable {
    private var render: ScreenParityRender? = null
    private var requests = 0
    private var finishes = 0
    private var downTime = 0L
    private var pointerDown = false
    private var lastX = 0f
    private var lastY = 0f
    private var closed = false
    private val renderer get() = if (reference) "compose" else "native"
    val captures = linkedMapOf<String, Pair<File, JSONObject>>()
    private val view get() = checkNotNull(render).view

    fun open() {
        requireNativeParityHost(instrumentation, host)
        render = nativeParityOnMain(instrumentation) {
            showOnboardingScreen(host, typography, case, reference, null,
                { requests++ }, { finishes++ }, mutableQaDraft = true)
        }
        awaitOnboardingScreenSettled(instrumentation, host, checkNotNull(render), 0)
        checkpoint("initial")
    }

    fun assertNoEffects() { check(requests == 0 && finishes == 0) { "Input accepted a permission/Finish action" } }

    private fun guard() {
        requireNativeParityHost(instrumentation, host)
        nativeParityOnMain(instrumentation) {
            check(!closed && view.isAttachedToWindow && view.isShown && view.hasWindowFocus())
            check(view.display?.displayId == 0)
            assertNoEffects()
        }
    }

    private fun nodes(): List<SemanticsNode> = descendants(view).filterIsInstance<ViewRootForTest>()
        .single().semanticsOwner.getAllSemanticsNodes(mergingEnabled = false)

    private fun pagerBounds(): ComposeRect {
        if (reference) return nodes().single {
            it.config.getOrNull(SemanticsProperties.HorizontalScrollAxisRange) != null
        }.boundsInWindow.relativeTo(view)
        return boundsInWindow(descendants(view).filterIsInstance<NativeOnboardingPlacedPagerViewport>().single()).relativeTo(view)
    }

    private fun fraction(): Float = if (reference) {
        val pager = nodes().single { it.config.getOrNull(SemanticsProperties.HorizontalScrollAxisRange) != null }
        pager.config[SemanticsProperties.HorizontalScrollAxisRange].value() / pager.size.width
    } else (view as NativeOnboardingScreenView).saveViewportState().let { it.page + it.offsetFraction }

    private fun visibleVertical(): Pair<Float, Float> {
        if (reference) {
            val viewport = pagerBounds()
            val candidates = nodes().filter {
                it.config.getOrNull(SemanticsProperties.VerticalScrollAxisRange) != null
            }
            val selected = candidates.maxBy { intersectionArea(it.boundsInWindow.relativeTo(view), viewport) }
            val range = selected.config[SemanticsProperties.VerticalScrollAxisRange]
            return range.value() to range.maxValue()
        }
        val page = descendants(view).filterIsInstance<OnboardingPageContentView>()
            .maxBy { intersectionArea(boundsInWindow(it).relativeTo(view), pagerBounds()) }
        return page.verticalScrollPosition().toFloat() to page.maximumVerticalScrollPosition().toFloat()
    }

    private fun control(label: String): JSONObject? {
        // Both routes read the actual exposed AccessibilityNodeInfo, including adjusted bounds.
        // Raw source semantics can retain unplaced pager pages and pre-occlusion preview bounds;
        // those remain in geometry evidence but are not an exposed control observation.
        val matches = if (reference) {
            val root = descendants(view).filterIsInstance<ViewRootForTest>().single()
            val provider = checkNotNull(root.view.accessibilityNodeProvider)
            root.semanticsOwner.getAllSemanticsNodes(mergingEnabled = true)
                .filter { it.layoutInfo.isAttached && it.layoutInfo.isPlaced }
                .filter { node ->
                    node.config.getOrNull(SemanticsProperties.Text).orEmpty().any { it.text == label } &&
                        node.config.getOrNull(SemanticsProperties.ToggleableState) != null
                }.mapNotNull { node -> observedControl(provider.createAccessibilityNodeInfo(node.id)) }
        } else {
            descendants(view).filterIsInstance<NativeMergedRowAccessibilityHost>().mapNotNull { candidate ->
                val target = candidate as View
                if (descendants(target).filterIsInstance<ReverbParagraphView>().none { it.getText() == label })
                    return@mapNotNull null
                observedControl(target.createAccessibilityNodeInfo())
            }.toList()
        }
        if (matches.isEmpty()) return null
        check(matches.size == 1) { "Expected one exposed control for $label, found ${matches.size}" }
        return matches.single()
    }

    @Suppress("DEPRECATION")
    private fun observedControl(info: AccessibilityNodeInfo?): JSONObject? {
        if (info == null) return null
        try {
            if (!info.isVisibleToUser || !info.isCheckable) return null
            check(info.packageName?.toString() == host.packageName) { "Foreign control in own fixture" }
            val screen = Rect().also(info::getBoundsInScreen)
            val origin = IntArray(2).also(view::getLocationOnScreen)
            val bounds = ComposeRect((screen.left-origin[0]).toFloat(),(screen.top-origin[1]).toFloat(),
                (screen.right-origin[0]).toFloat(),(screen.bottom-origin[1]).toFloat())
            // A predictive preview is exposed beside the translated foreground pager. The fixture
            // viewport, rather than the moving foreground rectangle, admits both actual branches.
            val boundary = ComposeRect(0f,0f,view.width.toFloat(),view.height.toFloat())
            if (intersectionArea(bounds,boundary) <= 0f) return null
            return JSONObject().put("enabled",info.isEnabled).put("checked",info.isChecked)
                .put("bounds",bounds.array())
        } finally { info.recycle() }
    }

    private fun snapshot(): JSONObject {
        val vertical = visibleVertical()
        val controls = JSONObject()
        for (id in listOf(R.string.onboarding_one_shot_title, R.string.onboarding_looping_title)) {
            val label = host.getString(id)
            control(label)?.let {
                controls.put(label, JSONObject().put("enabled", it.getBoolean("enabled")).put("checked", it.getBoolean("checked")))
            }
        }
        return JSONObject().put("page_position", fraction().toDouble())
            .put("vertical_position", vertical.first.toDouble()).put("vertical_max", vertical.second.toDouble())
            .put("controls", controls).put("permission_requests", requests).put("finishes", finishes)
    }

    private fun movingFooter(): JSONObject {
        val result = JSONObject()
        for (resource in listOf(R.string.onboarding_back, R.string.onboarding_continue)) {
            val label = host.getString(resource)
            val state = if (reference) {
                val root = descendants(view).filterIsInstance<ViewRootForTest>().single()
                val node = root.semanticsOwner.getAllSemanticsNodes(mergingEnabled = true).single {
                    it.config.getOrNull(SemanticsProperties.Text)?.any { text -> text.text == label } == true &&
                        it.config.contains(SemanticsActions.OnClick)
                }
                JSONObject().put("enabled", node.config.getOrNull(SemanticsProperties.Disabled) == null)
                    .put("clickable", node.config.contains(SemanticsActions.OnClick))
            } else {
                val target = descendants(view).single { candidate ->
                    candidate is NativeMergedRowAccessibilityHost && descendants(candidate)
                        .filterIsInstance<ReverbParagraphView>().any { it.getText() == label }
                }
                val node = checkNotNull(target.createAccessibilityNodeInfo())
                try { JSONObject().put("enabled", node.isEnabled).put("clickable", node.isClickable) }
                finally { node.recycle() }
            }
            check(state.getBoolean("enabled") && state.getBoolean("clickable")) {
                "Existing page1 footer must remain enabled/clickable during pager movement"
            }
            result.put(label, state)
        }
        return result
    }

    private fun requireMovingPosition(): Float = fraction().also { position ->
        check(abs(position - position.roundToInt()) > .00001f) {
            "Expected an actual moving pager offset at this input boundary; no gesture retry"
        }
    }

    private fun frame(label: String) {
        guard()
        awaitNativeParityDraw(instrumentation, view)
        val value = nativeParityOnMain(instrumentation) { snapshot() }
        evidence.getJSONArray("frames").put(JSONObject().put("label", label)
            .put("uptime_ms", SystemClock.uptimeMillis()).put("observed", value))
    }

    private fun framesUntil(label: String, ready: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 8_000L
        do {
            frame(label)
            if (nativeParityOnMain(instrumentation, ready)) return
        } while (SystemClock.uptimeMillis() < deadline)
        error("Onboarding observation did not settle: $label; no input replay")
    }

    private fun stablePage() {
        var previous: JSONObject? = null
        var count = 0
        framesUntil("settle") {
            val current = snapshot()
            val p = fraction()
            val settled = abs(p - p.roundToInt()) < .00001f
            count = if (settled && previous?.toString() == current.toString()) count + 1 else 0
            previous = current
            count >= 3
        }
    }

    private fun navigate(page: Int) {
        check(page in 0..2)
        repeat(2) {
            val before = nativeParityOnMain(instrumentation) { fraction() }
            check(before == before.roundToInt().toFloat()) { "Navigation requires an observed settled page" }
            if (before.roundToInt() == page) return
            guard()
            val forward = before < page
            nativeParityOnMain(instrumentation) {
                invokeOnboardingSemanticClick(checkNotNull(render),
                    host.getString(if (forward) R.string.onboarding_continue else R.string.onboarding_back))
            }
            stablePage()
            val expected = before + if (forward) 1f else -1f
            check(nativeParityOnMain(instrumentation) { fraction() } == expected) {
                "Navigation did not advance exactly one page; no input replay"
            }
        }
        check(nativeParityOnMain(instrumentation) { fraction() } == page.toFloat())
    }

    private fun send(
        action: Int, x: Float, y: Float, requireMoving: Boolean = false,
        currentPosition: (() -> Pair<Float, Float>)? = null,
    ) {
        guard()
        nativeParityOnMain(instrumentation) {
            val movingPosition = if (requireMoving) requireMovingPosition() else null
            val (eventX,eventY) = currentPosition?.invoke() ?: (x to y)
            if (action == MotionEvent.ACTION_DOWN) {
                check(!pointerDown)
                downTime = SystemClock.uptimeMillis()
                pointerDown = true
            } else check(pointerDown)
            val pointer = MotionEvent.PointerProperties().apply { id=0;toolType=MotionEvent.TOOL_TYPE_FINGER }
            val point = MotionEvent.PointerCoords().apply { this.x=eventX;this.y=eventY;pressure=1f;size=1f }
            val event = MotionEvent.obtain(downTime,SystemClock.uptimeMillis(),action,1,arrayOf(pointer),arrayOf(point),
                0,0,1f,1f,0,0,InputDevice.SOURCE_TOUCHSCREEN,0)
            lastX=eventX;lastY=eventY
            try {
                evidence.getJSONArray("input").put(JSONObject().put("action",action).put("x",eventX.toDouble())
                    .put("y",eventY.toDouble()).put("event_ms",event.eventTime)
                    .put("moving_position_before_dispatch",movingPosition ?: JSONObject.NULL)
                    .put("handled",view.dispatchTouchEvent(event)))
            } finally {
                event.recycle()
                if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) pointerDown=false
            }
        }
    }

    private fun hold(milliseconds: Long) {
        val until=SystemClock.uptimeMillis()+milliseconds
        do { frame("held") } while(SystemClock.uptimeMillis()<until)
    }

    private fun horizontalDrag(forward: Boolean, distance: Float, cancel: Boolean=false) {
        val bounds=nativeParityOnMain(instrumentation){pagerBounds()}
        val sign=(if(case.rtl) 1f else -1f)*(if(forward)1f else -1f)
        val start=if(sign<0)bounds.left+bounds.width*.82f else bounds.left+bounds.width*.18f
        val y=bounds.top+minOf(bounds.height*.10f,host.resources.displayMetrics.density*40f)
        send(MotionEvent.ACTION_DOWN,start,y);frame("down")
        send(MotionEvent.ACTION_MOVE,start+sign*bounds.width*.06f,y);frame("slop")
        send(MotionEvent.ACTION_MOVE,start+sign*bounds.width*distance,y);frame("drag")
        hold(140) // A deliberately slow release isolates displacement from a one-frame high-velocity fling.
        send(if(cancel)MotionEvent.ACTION_CANCEL else MotionEvent.ACTION_UP,start+sign*bounds.width*distance,y)
        stablePage()
    }

    fun horizontal() {
        horizontalDrag(true,.68f);check(nativeParityOnMain(instrumentation){fraction()}==1f);checkpoint("committed1")
        horizontalDrag(false,.68f);check(nativeParityOnMain(instrumentation){fraction()}==0f);checkpoint("returned0")
        // Pinned PagerState uses min(56dp, pageWidth / 2) as its positional threshold. The
        // short flow ends at half that source threshold; it does not guess a universal fraction.
        val shortDistance = nativeParityOnMain(instrumentation) {
            nativePagerPositionThresholdFraction(pagerBounds().width.roundToInt(), view.resources.displayMetrics.density) / 2f
        }
        horizontalDrag(true,shortDistance);check(nativeParityOnMain(instrumentation){fraction()}==0f);checkpoint("short-return0")
        horizontalDrag(true,.30f,cancel=true);checkpoint("cancelled")
    }

    fun axis() {
        val before=nativeParityOnMain(instrumentation){visibleVertical()}
        check(before.second>0f){"Existing large-font source page must really overflow"}
        val b=nativeParityOnMain(instrumentation){pagerBounds()}
        val x=b.center.x
        val y=b.top+b.height*.78f
        send(MotionEvent.ACTION_DOWN,x,y);frame("vertical-down")
        send(MotionEvent.ACTION_MOVE,x+4f,y-b.height*.08f);frame("vertical-slop")
        send(MotionEvent.ACTION_MOVE,x+4f,y-b.height*.42f);frame("vertical-drag")
        hold(140);send(MotionEvent.ACTION_UP,x+4f,y-b.height*.42f);stablePage()
        check(nativeParityOnMain(instrumentation){fraction()}==0f)
        check(nativeParityOnMain(instrumentation){visibleVertical().first}>before.first)
        checkpoint("vertical-only")
        horizontalDrag(true,.68f);checkpoint("horizontal-after-vertical")
    }

    fun cards() {
        navigate(1)
        val label=host.getString(R.string.onboarding_looping_title)
        fun currentCard()=nativeParityOnMain(instrumentation){checkNotNull(control(label))}
        val before=currentCard().getBoolean("checked")
        val rect=currentCard().getJSONArray("bounds").rect()
        val b=nativeParityOnMain(instrumentation){pagerBounds()}
        val startX=rect.center.x
        val y=rect.center.y
        send(MotionEvent.ACTION_DOWN,startX,y);frame("card-down")
        send(MotionEvent.ACTION_MOVE,startX-b.width*.08f,y);frame("card-slop")
        send(MotionEvent.ACTION_MOVE,startX-b.width*.25f,y);frame("card-move")
        val moving=nativeParityOnMain(instrumentation) {
            val position=requireMovingPosition()
            checkNotNull(control(label)).put("observed_page_position",position.toDouble())
                .put("footer",movingFooter())
        }
        check(moving.getBoolean("enabled"))
        check(moving.getBoolean("checked")==before)
        evidence.put("during_motion_control",moving)
        send(MotionEvent.ACTION_UP,startX-b.width*.25f,y)
        // Resolve transformed target bounds in the same main turn as the new DOWN. The card can
        // move between instrumentation calls; a previous frame's center is not this target.
        send(MotionEvent.ACTION_DOWN,0f,0f,requireMoving=true) {
            val visible=checkNotNull(control(label)).getJSONArray("bounds").rect()
            val currentBounds=pagerBounds()
            visible.center.x.coerceIn(currentBounds.left+1f,currentBounds.right-1f) to
                visible.center.y.coerceIn(currentBounds.top+1f,currentBounds.bottom-1f)
        }
        frame("interrupting-card-down")
        send(MotionEvent.ACTION_UP,lastX,lastY);stablePage();checkpoint("card-interruption")
        navigate(1)
        val settledBefore=currentCard().getBoolean("checked")
        val settled=currentCard().getJSONArray("bounds").rect()
        send(MotionEvent.ACTION_DOWN,settled.center.x,settled.center.y);frame("settled-card-down")
        send(MotionEvent.ACTION_UP,settled.center.x,settled.center.y)
        framesUntil("settled-card-state"){
            control(label)?.let { it.getBoolean("checked") != settledBefore } == true
        }
        checkpoint("whole-card-tap")
    }

    fun back() {
        navigate(1)
        guard()
        nativeParityOnMain(instrumentation) {
            invokeOnboardingSemanticClick(checkNotNull(render),host.getString(R.string.onboarding_continue))
        }
        framesUntil("continue-moving"){abs(fraction()-fraction().roundToInt())>.00001f}
        guard()
        nativeParityOnMain(instrumentation){host.onBackPressedDispatcher.onBackPressed()}
        stablePage();checkpoint("back-during-continue")
        navigate(2)
        predictive(.4f)
        checkpoint("predictive-held-cancel")
        guard();nativeParityOnMain(instrumentation){host.onBackPressedDispatcher.dispatchOnBackCancelled()}
        stablePage();checkpoint("predictive-cancelled")
        navigate(2)
        predictive(.4f)
        checkpoint("predictive-held-commit")
        guard();nativeParityOnMain(instrumentation){host.onBackPressedDispatcher.onBackPressed()}
        stablePage();checkpoint("predictive-committed")
    }

    private fun predictive(progress:Float) {
        guard()
        nativeParityOnMain(instrumentation) {
            host.onBackPressedDispatcher.dispatchOnBackStarted(BackEventCompat(0f,0f,0f,BackEventCompat.EDGE_LEFT))
        }
        frame("back-start")
        guard()
        nativeParityOnMain(instrumentation) {
            host.onBackPressedDispatcher.dispatchOnBackProgressed(BackEventCompat(0f,0f,progress,BackEventCompat.EDGE_LEFT))
        }
        repeat(3){frame("back-held")}
    }

    private fun checkpoint(name:String) {
        guard()
        val image=settledImage()
        try {
            val stem="$flow-$renderer-$name"
            val file=File(directory,"$stem.png")
            saveNativeParityBitmap(directory,file.name,image)
            val geometry=nativeParityOnMain(instrumentation){onboardingGeometryEvidence(view,reference)}
            val nodes=onboardingNativeNodeEvidence(instrumentation,host,view){partial->
                File(directory,"$stem-failed-nodes.json").writeText(partial.toString(2))
            }
            val state=nativeParityOnMain(instrumentation){snapshot()}
            val value=JSONObject().put("geometry",geometry).put("nodes",nodes).put("observable_state",state)
            File(directory,"$stem.json").writeText(value.toString(2))
            captures[name]=file to value
            evidence.getJSONArray("checkpoints").put(JSONObject().put("name",name).put("state",state))
        } finally{image.recycle()}
    }

    private fun settledImage():Bitmap {
        val deadline=SystemClock.uptimeMillis()+8_000L
        var previous:Bitmap?=null
        var count=0
        try {
            do {
                guard()
                val image=captureNativeParityContent(instrumentation,host){view}
                count=if(previous?.sameAs(image)==true)count+1 else 0
                previous?.recycle();previous=image
                if(count>=2)return image.also{previous=null}
            } while(SystemClock.uptimeMillis()<deadline)
            error("Onboarding image did not settle; no frame suppression")
        } finally{previous?.recycle()}
    }

    override fun close() {
        if(closed)return
        closed=true
        val current=render;render=null
        nativeParityOnMain(instrumentation) {
            var failure:Throwable?=null
            fun release(block:()->Unit){try{block()}catch(error:Throwable){
                val prior=failure;if(prior==null)failure=error else if(prior!==error)prior.addSuppressed(error)}}
            // Retire only this fixture's pending pointer; never send input to a foreign/system window.
            if(pointerDown && current!=null)release{
                val pointer=MotionEvent.PointerProperties().apply{id=0;toolType=MotionEvent.TOOL_TYPE_FINGER}
                val point=MotionEvent.PointerCoords().apply{x=lastX;y=lastY;pressure=1f;size=1f}
                val event=MotionEvent.obtain(downTime,SystemClock.uptimeMillis(),MotionEvent.ACTION_CANCEL,
                    1,arrayOf(pointer),arrayOf(point),0,0,1f,1f,0,0,InputDevice.SOURCE_TOUCHSCREEN,0)
                try{current.view.dispatchTouchEvent(event)}finally{event.recycle();pointerDown=false}
            }
            release{current?.close()}
            release{current?.view?.let(host.fixtureRoot::removeView)}
            failure?.let{throw it}
        }
    }
}

private fun descendants(view:View):Sequence<View> = sequence{
    yield(view);if(view is ViewGroup)for(index in 0 until view.childCount)yieldAll(descendants(view.getChildAt(index)))
}
private fun boundsInWindow(view:View):ComposeRect {
    val location=IntArray(2).also(view::getLocationInWindow)
    return ComposeRect(location[0].toFloat(),location[1].toFloat(),(location[0]+view.width).toFloat(),(location[1]+view.height).toFloat())
}
private fun ComposeRect.relativeTo(root:View):ComposeRect {
    val position=IntArray(2).also(root::getLocationInWindow)
    return translate(-position[0].toFloat(),-position[1].toFloat())
}
private fun intersectionArea(a:ComposeRect,b:ComposeRect):Float =
    (minOf(a.right,b.right)-maxOf(a.left,b.left)).coerceAtLeast(0f)*
        (minOf(a.bottom,b.bottom)-maxOf(a.top,b.top)).coerceAtLeast(0f)
private fun ComposeRect.array()=JSONArray(listOf(left.toDouble(),top.toDouble(),right.toDouble(),bottom.toDouble()))
private fun JSONArray.rect()=ComposeRect(getDouble(0).toFloat(),getDouble(1).toFloat(),getDouble(2).toFloat(),getDouble(3).toFloat())
private fun writeOnboardingInputEvidence(directory:File,result:JSONObject) {
    val atomic=AtomicFile(File(directory,"result.json"))
    val stream=atomic.startWrite()
    try{stream.write(result.toString(2).toByteArray(Charsets.UTF_8));atomic.finishWrite(stream)}
    catch(error:Throwable){try{atomic.failWrite(stream)}catch(cleanup:Throwable){if(cleanup!==error)error.addSuppressed(cleanup)};throw error}
}
