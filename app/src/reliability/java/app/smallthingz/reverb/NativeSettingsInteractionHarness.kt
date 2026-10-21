package app.smallthingz.reverb

import android.Manifest
import android.app.ActivityManager
import android.app.Application
import android.app.Instrumentation
import android.content.ComponentName
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.FrameLayout
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ComposeViewContext
import androidx.compose.ui.platform.ExperimentalComposeViewContextApi
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getAllSemanticsNodes
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.font.createFontFamilyResolver
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import app.smallthingz.reverb.nativeui.*
import java.io.File
import java.util.IdentityHashMap
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.math.roundToInt
import org.json.JSONArray
import org.json.JSONObject

/** Finite QA leaf host, never a production renderer or owner. */
internal data class SettingsInteractionNativeContent(
    val view: View,
    val close: () -> Unit,
    val pointerNodes: (View) -> List<NativeViewPhasePointerNode> = { emptyList() },
)

internal class SettingsInteractionSuite(
    val instrumentation: Instrumentation,
    private val report: StringBuilder,
    private val selector: String,
) {
    private val context = instrumentation.targetContext
    val directory = File(context.filesDir, "$selector-${System.currentTimeMillis()}")
    private val runs = JSONArray()
    private val result = JSONObject().put("passed", false).put("runs", runs)
        .put("scope", "Paired released/native control leaves; own-root synthetic dispatch; no recorder/persistence")
        .put("unqualified", JSONArray(listOf("full Settings list arbitration", "physical input dispatch",
            "TalkBack delivery", "live calibration/audio edges", "durable Settings Save", "system tiles")))
    private val records = mutableListOf<MutableList<Pair<File, JSONObject>>>()
    private var host: NativeParityActivity? = null
    private var current: SettingsInteractionRender? = null
    private val prefs = getRecorderPreferences(context)
    private val before = prefs.all.toMap()
    private val grants = listOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS,
        Manifest.permission.READ_MEDIA_AUDIO, Manifest.permission.READ_EXTERNAL_STORAGE,
        Manifest.permission.WRITE_EXTERNAL_STORAGE).associateWith(context::checkSelfPermission)
    private val components = (0 until QUICK_SAVE_TILE_COUNT).associateWith {
        context.packageManager.getComponentEnabledSetting(ComponentName(context, quickSaveTileClass(it)))
    }
    init {
        check(context.packageName == "app.smallthingz.reverb.nativeparity.reliability")
        check(context.applicationContext.javaClass == Application::class.java)
        check(directory.mkdir())
        write()
    }
    fun run(flow: (SettingsInteractionSuite, NativeParityActivity, Boolean) -> Unit) {
        var failure: Throwable? = null
        try {
            preservation()
            val activity = launchNativeParityActivity(instrumentation).also { host = it }
            awaitNativeParityDraw(instrumentation, activity.fixtureRoot)
            for (reference in listOf(true, false)) {
                val captures = mutableListOf<Pair<File, JSONObject>>()
                records += captures
                runs.put(JSONObject().put("renderer", if (reference) "released" else "native")
                    .put("steps", JSONArray()))
                var flowFailure: Throwable? = null
                try { flow(this, activity, reference) }
                catch (error: Throwable) { flowFailure = error; throw error }
                finally { try { closeRender() } catch (cleanup: Throwable) {
                    if (flowFailure == null) throw cleanup
                    if (flowFailure !== cleanup) flowFailure.addSuppressed(cleanup)
                } }
                preservation()
            }
            check(records[0].size == records[1].size)
            val comparisons = JSONArray()
            var mismatches = 0
            for (index in records[0].indices) {
                val (left, source) = records[0][index]
                val (right, native) = records[1][index]
                var a: Bitmap? = null
                var b: Bitmap? = null
                val pixels = try {
                    a = checkNotNull(BitmapFactory.decodeFile(left.path))
                    b = checkNotNull(BitmapFactory.decodeFile(right.path))
                    compareNativeParityPixels(a, b)
                } finally { a?.recycle(); b?.recycle() }
                val equal = source.getString("step") == native.getString("step") &&
                    source.getJSONObject("state").toString() == native.getJSONObject("state").toString() &&
                    source.getJSONArray("callbacks").toString() == native.getJSONArray("callbacks").toString()
                val passed = equal && pixels.getLong("different_pixels") == 0L
                comparisons.put(JSONObject().put("step", source.getString("step")).put("pixels", pixels)
                    .put("state_and_callbacks_equal", equal).put("passed", passed))
                if (!passed) mismatches++
            }
            result.put("comparisons", comparisons)
            check(mismatches == 0) { "$mismatches paired control checkpoints differ" }
        } catch (error: Throwable) { failure = error }
        finally {
            fun cleanup(block: () -> Unit) { try { block() } catch (error: Throwable) {
                val primary = failure
                if (primary == null) failure = error else if (primary !== error) primary.addSuppressed(error)
            } }
            cleanup(::closeRender)
            cleanup { host?.let { nativeParityOnMain(instrumentation) { it.finish() } } }
            cleanup(::preservation)
            result.put("passed", failure == null).put("failure", failure?.stackTraceToString() ?: JSONObject.NULL)
            var evidence = false
            try { write(); evidence = true } catch (error: Throwable) {
                val primary = failure
                if (primary == null) failure = error else if (primary !== error) primary.addSuppressed(error)
                result.put("passed", false).put("failure", checkNotNull(failure).stackTraceToString())
                try { write(); evidence = true } catch (secondary: Throwable) {
                    if (secondary !== failure) checkNotNull(failure).addSuppressed(secondary)
                }
            }
            report.appendLine("${if (evidence) "PARITY_EVIDENCE" else "PARITY_EVIDENCE_WRITE_FAILED"} ${directory.name}/result.json")
        }
        failure?.let { throw it }
        report.appendLine("PASS $selector paired leaves; full Settings and live audio remain unqualified")
    }
    fun mount(
        activity: NativeParityActivity, reference: Boolean, audioGroup: Boolean = true,
        source: @Composable () -> Unit,
        native: (SettingsInteractionRender) -> SettingsInteractionNativeContent,
    ): SettingsInteractionRender {
        closeRender()
        return nativeParityOnMain(instrumentation) {
            SettingsInteractionRender(activity, reference, audioGroup, source, native) { current = it }
        }
    }
    fun closeRender() {
        val retired = current ?: return
        current = null
        var failure: Throwable? = null
        try { nativeParityOnMain(instrumentation) { retired.close() } } catch (error: Throwable) { failure = error }
        try { retired.awaitClosed() } catch (error: Throwable) {
            val primary = failure
            if (primary == null) failure = error else if (primary !== error) primary.addSuppressed(error)
        }
        failure?.let { throw it }
    }
    fun main(action: () -> Unit) {
        requireNativeParityHost(instrumentation, checkNotNull(host))
        nativeParityOnMain(instrumentation) { current?.checkAsync(); action(); current?.checkAsync() }
    }
    fun await(reason: String, predicate: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 8_000
        while (SystemClock.uptimeMillis() < deadline) {
            requireNativeParityHost(instrumentation, checkNotNull(host))
            if (nativeParityOnMain(instrumentation) { current?.checkAsync(); predicate() }) return
            Thread.sleep(16)
        }
        error("Own control readiness timed out: $reason")
    }
    fun capture(step: String, callbacks: () -> List<String>, state: () -> JSONObject) {
        preservation()
        val render = checkNotNull(current)
        val bitmap = settled(render)
        try {
            val label = if (render.reference) "released" else "native"
            val file = File(directory, "$label-$step.png")
            saveNativeParityBitmap(directory, file.name, bitmap)
            val entry = nativeParityOnMain(instrumentation) { render.evidence()
                .put("step", step).put("state", state()).put("callbacks", JSONArray(callbacks())) }
            File(directory, "$label-$step.json").writeText(entry.toString(2))
            records.last().add(file to entry)
            runs.getJSONObject(runs.length() - 1).getJSONArray("steps").put(entry)
            write()
        } finally { bitmap.recycle() }
    }
    fun observeDraws(step: String, count: Int = 4) {
        val render = checkNotNull(current)
        val frames = JSONArray()
        runs.getJSONObject(runs.length() - 1).put("draws_$step", frames)
        write()
        repeat(count) {
            requireNativeParityHost(instrumentation, render.activity)
            awaitNativeParityDraw(instrumentation, render.board)
            frames.put(nativeParityOnMain(instrumentation) {
                render.checkAsync()
                val views = JSONArray()
                settingsInteractionViews(render.board).forEach { view ->
                    views.put(JSONObject().put("class", view.javaClass.name)
                        .put("identity", System.identityHashCode(view)).put("width", view.width)
                        .put("height", view.height).put("visibility", view.visibility)
                        .put("alpha", view.alpha.toDouble()).put("attached", view.isAttachedToWindow)
                        .put("focused", view.isFocused).put("has_focus", view.hasFocus())
                        .put("focusable", view.isFocusable).put("touch_focusable", view.isFocusableInTouchMode)
                        .put("default_focus_highlight", view.defaultFocusHighlightEnabled)
                        .put("pressed", view.isPressed).put("hovered", view.isHovered)
                        .put("in_touch_mode", view.isInTouchMode))
                }
                JSONObject().put("uptime_ms", SystemClock.uptimeMillis()).put("views", views)
            })
            write()
        }
    }
    fun settled(render: SettingsInteractionRender = checkNotNull(current)): Bitmap {
        val deadline = SystemClock.uptimeMillis() + 8_000
        var previous: Bitmap? = null
        var repeats = 0
        try {
            while (SystemClock.uptimeMillis() < deadline) {
                val next = captureNativeParityContent(instrumentation, render.activity) {
                    render.checkAsync(); render.refreshAccessibility(); render.board
                }
                val same = try { previous?.sameAs(next) == true } catch (error: Throwable) { next.recycle(); throw error }
                previous?.recycle(); previous = next
                repeats = if (same) repeats + 1 else 0
                if (repeats >= 2) return next.also { previous = null }
            }
            error("Control did not produce three equal settled captures")
        } finally { previous?.recycle() }
    }
    private fun preservation() {
        check(prefs.all == before) { "QA preferences changed during draft-only control fixture" }
        check(grants.keys.associateWith(context::checkSelfPermission) == grants)
        check(components.keys.associateWith {
            context.packageManager.getComponentEnabledSetting(ComponentName(context, quickSaveTileClass(it)))
        } == components)
        @Suppress("DEPRECATION")
        check(context.getSystemService(ActivityManager::class.java).getRunningServices(Int.MAX_VALUE)
            .none { it.service.packageName == context.packageName }) { "QA recorder/service work is outside this fixture" }
    }
    private fun write() {
        val pending = File(directory, "result.json.pending")
        pending.writeText(result.toString(2))
        check(pending.renameTo(File(directory, "result.json")))
    }
}

@OptIn(ExperimentalComposeViewContextApi::class)
internal class SettingsInteractionRender(
    val activity: NativeParityActivity,
    val reference: Boolean,
    audioGroup: Boolean,
    source: @Composable () -> Unit,
    createNative: (SettingsInteractionRender) -> SettingsInteractionNativeContent,
    onOwned: (SettingsInteractionRender) -> Unit,
) : AutoCloseable {
    val board = FrameLayout(activity)
    val runtime = NativeAboutWindowRuntime()
    private val asyncFailure = AtomicReference<Throwable?>(null)
    val scope = CoroutineScope(runtime.scope.coroutineContext + CoroutineExceptionHandler { _, error ->
        if (!asyncFailure.compareAndSet(null, error)) {
            asyncFailure.get()?.let { first -> if (first !== error) first.addSuppressed(error) }
        }
    })
    private val recomposer = Recomposer(scope.coroutineContext)
    private var recomposerJob: Job? = null
    private val colors = if (Build.VERSION.SDK_INT >= 31) dynamicDarkColorScheme(activity) else darkColorScheme()
    private val typography = Class.forName("app.smallthingz.reverb.ReverbThemeKt")
        .getDeclaredField("ReverbTypography").apply { isAccessible = true }.get(null) as Typography
    val density = Density(activity.resources.displayMetrics.density, activity.resources.configuration.fontScale)
    val environment = ReverbViewEnvironment(density, createFontFamilyResolver(activity), ReverbPalette.resolve(activity, true))
    private var composition: ComposeViewContext? = null
    private var compose: ComposeView? = null
    private var native: SettingsInteractionNativeContent? = null
    private var nativeFocus: NativeSourceFocusPolicy? = null
    private var pointer: SettingsInteractionPointers? = null
    private var text: NativePageTextAccessibilityOwner? = null
    private var closed = false
    lateinit var inputRoot: View
        private set
    private val lifecycle = LifecycleEventObserver { _, event ->
        if (event == Lifecycle.Event.ON_START) runtime.setStarted(true)
        if (event == Lifecycle.Event.ON_STOP) runtime.setStarted(false)
    }
    init {
        try {
            // Register ownership before work that may fail; Suite can join cancelled children even
            // when construction aborts after starting the reference composition or a capacity read.
            onOwned(this)
            board.setBackgroundColor(environment.palette.surface)
            board.defaultFocusHighlightEnabled = false
            board.clipChildren = false; board.clipToPadding = false
            if (Build.VERSION.SDK_INT >= 29) board.isForceDarkAllowed = false
            activity.fixtureRoot.addView(board, FrameLayout.LayoutParams(-1, -1))
            activity.lifecycle.addObserver(lifecycle)
            runtime.setStarted(activity.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED))
            if (reference) {
                val view = ComposeView(activity); compose = view; inputRoot = view
                val context = ComposeViewContext(board, recomposer); composition = context
                recomposerJob = scope.launch { recomposer.runRecomposeAndApplyChanges() }
                view.setContent {
                    CompositionLocalProvider(LocalContext provides activity, LocalDensity provides density) {
                        // Same exact released typography/palette as the leaves, without ThemeBase's
                        // full-screen noise or system-bar side effects on this isolated flat board.
                        MaterialTheme(colorScheme = colors, typography = typography) {
                            Column(Modifier.fillMaxWidth().padding(top = 12.dp)) {
                                if (audioGroup) SettingsAudioGroup { source() }
                                else Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) { source() }
                            }
                        }
                    }
                }
                view.createComposition(context)
                board.addView(view, FrameLayout.LayoutParams(-1, -1))
            } else {
                lateinit var root: NativePagerTouchRoot
                val geometry = NativeWindowPointerCoordinates { root }
                val routing = SettingsInteractionPointers({ root }, geometry, density.density) {
                    native?.pointerNodes?.invoke(it).orEmpty()
                }
                pointer = routing
                root = object : NativePagerTouchRoot(activity, geometry::rootToScreen, geometry::coordinates,
                    routing.layout, geometry::begin, mouseButtons = true) {
                    private val focus = NativeSourceFocusPolicy(this,
                        takeOwnerFocus = { super.requestFocus(FOCUS_DOWN, null) },
                        clearOwnerFocus = { super.clearFocus() }).also { nativeFocus = it }
                    override fun focusableViewAvailable(view: View) {
                        if (focus.reportsAvailability(view)) super.focusableViewAvailable(view)
                    }
                    override fun requestFocus(direction: Int, previouslyFocusedRect: Rect?): Boolean =
                        focus.requestFocus(direction, previouslyFocusedRect)
                    override fun requestChildFocus(child: View, focused: View) {
                        super.requestChildFocus(child, focused)
                        focus.childFocused(focused)
                    }
                    override fun clearChildFocus(child: View) {
                        super.clearChildFocus(child)
                        focus.childCleared(child)
                    }
                    override fun dispatchKeyEvent(event: KeyEvent): Boolean =
                        if (focus.hasPendingInvalidation) false else super.dispatchKeyEvent(event)
                    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                        focus.beforeMeasure()
                        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
                    }
                }
                // Match the released AndroidComposeView and actual NativeActivityContentView
                // before attaching children; controls retain their normal keyboard focus policy.
                root.isFocusable = true
                root.isFocusableInTouchMode = true
                root.defaultFocusHighlightEnabled = false
                inputRoot = root
                board.addView(root, FrameLayout.LayoutParams(-1, -1))
                val leaf = createNative(this).also { native = it }
                val wrapped = if (audioGroup) ReverbGroupLayout(activity, 16f).apply {
                    setPalette(environment.palette.field, environment.palette.border)
                    addRow(leaf.view, ReverbGroupLayout.LayoutParams(-1, -2))
                } else leaf.view
                root.addView(wrapped, FrameLayout.LayoutParams(-1, -2).apply {
                    leftMargin = (16 * density.density).roundToInt(); rightMargin = leftMargin
                    topMargin = (12 * density.density).roundToInt()
                })
                val clips = NativeTextSemanticsClipPolicy { view ->
                    if (view is SettingsExpansionView ||
                        (view.parent is ReverbGroupLayout && (view.parent as ReverbGroupLayout).pointerContent === view))
                        NativeTextSemanticsClip.Rectangle(androidx.compose.ui.geometry.Rect(0f, 0f, view.width.toFloat(), view.height.toFloat()))
                    else NativeTextSemanticsClip.Unclipped
                }
                text = NativeWindowTextAccessibilityOwner(board, clips).bindContent(root, clips, sourceExposure = { it.isShown })
            }
        } catch (error: Throwable) {
            try { close() } catch (cleanup: Throwable) { if (cleanup !== error) error.addSuppressed(cleanup) }
            throw error
        }
    }
    fun refreshAccessibility() { text?.refresh() }
    fun wheel(): RangeDurationWheelView = settingsInteractionViews(board).filterIsInstance<RangeDurationWheelView>().single()
    fun click(label: String) {
        requireCurrent()
        refreshAccessibility()
        if (reference) {
            val node = sourceNodes().filter { label in names(it) &&
                it.config.getOrNull(SemanticsActions.OnClick)?.action != null &&
                it.config.getOrNull(SemanticsProperties.Disabled) == null }.single()
            check(node.config.getOrNull(SemanticsActions.OnClick)?.action?.invoke() == true)
        } else {
            val view = nativeTargets(label).filter { target ->
                val info = target.createAccessibilityNodeInfo() ?: return@filter false
                try { info.isEnabled && info.isClickable } finally { @Suppress("DEPRECATION") info.recycle() }
            }.single()
            check(view.performAccessibilityAction(AccessibilityNodeInfo.ACTION_CLICK, null))
        }
    }
    fun setProgress(label: String, value: Float): Boolean {
        requireCurrent(); refreshAccessibility()
        val nativeWheel = settingsInteractionViews(board).filterIsInstance<RangeDurationWheelView>()
            .singleOrNull { it.contentDescription?.toString()?.startsWith(label + ", ") == true }
        if (nativeWheel != null) return nativeWheel.performAccessibilityAction(
            AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.id,
            Bundle().apply { putFloat(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE, value) })
        if (reference) {
            val node = sourceNodes().filter { label in names(it) &&
                it.config.getOrNull(SemanticsActions.SetProgress)?.action != null }.single()
            return checkNotNull(node.config.getOrNull(SemanticsActions.SetProgress)?.action).invoke(value)
        }
        val view = nativeTargets(label).filter { it.createAccessibilityNodeInfo()?.let { info ->
            try { info.rangeInfo != null } finally { @Suppress("DEPRECATION") info.recycle() }
        } == true }.single()
        return view.performAccessibilityAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.id,
            Bundle().apply { putFloat(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE, value) })
    }
    fun wheelStep(forward: Boolean): Boolean {
        requireCurrent()
        return wheel().performAccessibilityAction(if (forward) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
            else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD, null)
    }
    fun target(label: String): Rect {
        requireCurrent(); refreshAccessibility()
        settingsInteractionViews(board).filterIsInstance<RangeDurationWheelView>()
            .singleOrNull { it.contentDescription?.toString()?.startsWith(label + ", ") == true }?.let { child ->
                val origin = IntArray(2).also(child::getLocationOnScreen)
                return Rect(origin[0], origin[1], origin[0] + child.width, origin[1] + child.height)
            }
        if (reference) {
            val root = settingsInteractionViews(board).filterIsInstance<ViewRootForTest>().single()
            val node = sourceNodes().filter { label in names(it) &&
                (it.config.getOrNull(SemanticsActions.OnClick)?.action != null ||
                    it.config.getOrNull(SemanticsActions.SetProgress)?.action != null) }.single()
            val origin = IntArray(2); (root as View).getLocationOnScreen(origin)
            val b = node.boundsInRoot
            return Rect(origin[0] + b.left.roundToInt(), origin[1] + b.top.roundToInt(),
                origin[0] + b.right.roundToInt(), origin[1] + b.bottom.roundToInt())
        }
        val view = nativeTargets(label).filter { it.createAccessibilityNodeInfo()?.let { info ->
            try { info.isClickable || info.rangeInfo != null } finally { @Suppress("DEPRECATION") info.recycle() }
        } == true }.single()
        val out = Rect(); val origin = IntArray(2); view.getLocationOnScreen(origin)
        out.set(origin[0], origin[1], origin[0] + view.width, origin[1] + view.height)
        return out
    }
    fun motion(action: Int, downTime: Long, xScreen: Float, yScreen: Float): Boolean {
        requireCurrent()
        val origin = IntArray(2); board.getLocationOnScreen(origin)
        val event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, xScreen, yScreen, 0)
        event.source = InputDevice.SOURCE_TOUCHSCREEN
        event.offsetLocation(-origin[0].toFloat(), -origin[1].toFloat())
        return try { board.dispatchTouchEvent(event) } finally { event.recycle() }
    }
    fun checkAsync() { asyncFailure.get()?.let { throw it } }
    fun awaitClosed() {
        check(closed)
        runBlocking { withTimeout(5_000) { checkNotNull(runtime.scope.coroutineContext[Job]).join() } }
        checkAsync()
    }
    private fun requireCurrent() {
        checkAsync()
        check(!closed && activity.hasWindowFocus() && board.isShown && board.isAttachedToWindow)
    }
    private fun sourceNodes(): List<SemanticsNode> = settingsInteractionViews(board).filterIsInstance<ViewRootForTest>().single()
        .semanticsOwner.getAllSemanticsNodes(mergingEnabled = true).filter {
            it.boundsInRoot.width > 0 && it.boundsInRoot.height > 0 && generateSequence(it) { node -> node.parent }
                .none { node -> node.config.getOrNull(SemanticsProperties.HideFromAccessibility) != null }
        }
    private fun names(node: SemanticsNode): List<String> =
        node.config.getOrNull(SemanticsProperties.Text)?.map { it.text }.orEmpty() +
            node.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty()
    private fun nativeTargets(label: String): List<View> = settingsInteractionViews(board).filter { view ->
        val info = view.createAccessibilityNodeInfo() ?: return@filter false
        try { info.isVisibleToUser && (info.isClickable || info.rangeInfo != null) &&
            settingsInteractionViews(view).any { child -> child.createAccessibilityNodeInfo()?.let { node ->
                try { node.isVisibleToUser && (node.text?.toString() == label || node.contentDescription?.toString() == label) }
                finally { @Suppress("DEPRECATION") node.recycle() }
            } == true }
        } finally { @Suppress("DEPRECATION") info.recycle() }
    }.toList()
    fun evidence(): JSONObject {
        refreshAccessibility()
        val nodes = JSONArray()
        if (reference) sourceNodes().forEach { node -> nodes.put(JSONObject().put("names", JSONArray(names(node)))
            .put("disabled", node.config.getOrNull(SemanticsProperties.Disabled) != null)
            .put("focused", node.config.getOrNull(SemanticsProperties.Focused))
            .put("bounds", node.boundsInRoot.toString())) }
        else settingsInteractionViews(board).forEach { view -> view.createAccessibilityNodeInfo()?.let { info ->
            try { if (info.isVisibleToUser) { val bounds = Rect(); info.getBoundsInScreen(bounds)
                nodes.put(JSONObject().put("text", info.text?.toString()).put("description", info.contentDescription?.toString())
                    .put("enabled", info.isEnabled).put("checked", info.isChecked).put("bounds", bounds.toShortString())
                    .put("focused", info.isFocused).put("accessibility_focused", info.isAccessibilityFocused)
                    .put("view_focused", view.isFocused).put("view_pressed", view.isPressed)
                    .put("view_hovered", view.isHovered).put("default_focus_highlight", view.defaultFocusHighlightEnabled))
            } } finally { @Suppress("DEPRECATION") info.recycle() }
        } }
        val focused = activity.window.decorView.findFocus()
        return JSONObject().put("nodes", nodes).put("dark", true).put("density", density.density.toDouble())
            .put("font_scale", density.fontScale.toDouble())
            .put("focus", JSONObject().put("window_focused", activity.hasWindowFocus())
                .put("in_touch_mode", board.isInTouchMode)
                .put("native_class", focused?.javaClass?.name)
                .put("native_identity", focused?.let { System.identityHashCode(it) })
                .put("native_description", focused?.contentDescription?.toString())
                .put("input_root_focused", inputRoot.isFocused)
                .put("input_root_focusable", inputRoot.isFocusable)
                .put("input_root_touch_focusable", inputRoot.isFocusableInTouchMode)
                .put("input_root_default_highlight", inputRoot.defaultFocusHighlightEnabled))
    }
    override fun close() {
        if (closed) return
        closed = true
        var failure: Throwable? = null
        fun release(action: () -> Unit) { try { action() } catch (error: Throwable) {
            val primary = failure
            if (primary == null) failure = error else if (primary !== error) primary.addSuppressed(error)
        } }
        release { if (::inputRoot.isInitialized) (inputRoot as? NativePagerTouchRoot)?.cancelPointerInput() }
        release { nativeFocus?.close() }
        release { pointer?.close() }; release { native?.close?.invoke() }; release { text?.close() }
        release { compose?.disposeComposition() }; composition = null
        release { recomposer.cancel() }; release { recomposerJob?.cancel() }
        release { activity.lifecycle.removeObserver(lifecycle) }; release(runtime::close)
        release { (board.parent as? ViewGroup)?.removeView(board) }
        release(::checkAsync)
        failure?.let { throw it }
    }
}

internal fun settingsInteractionViews(root: View): Sequence<View> = sequence {
    yield(root)
    if (root is ViewGroup) for (i in 0 until root.childCount) yieldAll(settingsInteractionViews(root.getChildAt(i)))
}

/** Same phase-node adapters as NativeSettingsPointerBinding, limited to these known control leaves. */
private class SettingsInteractionPointers(
    private val root: () -> ViewGroup,
    private val geometry: NativeWindowPointerCoordinates,
    private val density: Float,
    private val custom: (View) -> List<NativeViewPhasePointerNode>,
) : AutoCloseable {
    private val cache = IdentityHashMap<View, Layout>()
    val layout: NativeViewPointerHitLayout = object : NativeViewPointerHitLayout {
        override val placed get() = root().isAttachedToWindow && root().isLaidOut
        override val size get() = Size(root().width.toFloat(), root().height.toFloat())
        override val minimumTouchTarget get() = Size(48 * density, 48 * density)
        override val nodes = emptyList<NativeViewPhasePointerNode>()
        override val childrenInHitOrder get() = listOf(entry(root()))
        override fun screenToLocal(screen: Offset) = geometry.coordinates(root()).local(screen)
        override fun withinLayerBounds(local: Offset) = true
    }
    private fun entry(view: View): Layout = cache.getOrPut(view) { Layout(view) }
    private inner class Layout(private val view: View) : NativeViewPointerHitLayout, AutoCloseable {
        private val click = (view as? NativeClickPhaseHost)?.let { NativeClickPhaseNode(view, it.nativeClickEndpoint) { false } }
        private val interop = if (view is RangeDurationWheelView || view is SilenceCurveView)
            NativeAndroidInteropNode(view, (view.parent as NativeAndroidInteropHost).nativeInteropRequests) else null
        override val placed get() = view.isAttachedToWindow && view.isLaidOut && view.visibility == View.VISIBLE
        override val size get() = Size(view.width.toFloat(), view.height.toFloat())
        override val minimumTouchTarget get() = Size(48 * density, 48 * density)
        override val nodes get() = custom(view).ifEmpty { interop?.let(::listOf) ?: click?.let(::listOf) ?: emptyList() }
        override val childrenInHitOrder: List<NativeViewPointerHitLayout> get() =
            if (view !is ViewGroup || interop != null) emptyList() else (view.childCount - 1 downTo 0)
                .map(view::getChildAt).filter { it.visibility == View.VISIBLE }.map(::entry)
        override fun screenToLocal(screen: Offset) = geometry.coordinates(view).local(screen)
        override fun withinLayerBounds(local: Offset): Boolean = when (view) {
            is ReverbGroupLayout -> view.containsVisualPoint(local.x, local.y)
            is NativePointerLayerShape -> view.containsPointerLayer(local.x, local.y)
            is SettingsExpansionView -> local.x >= 0 && local.y >= 0 && local.x < view.width && local.y < view.height
            else -> true
        }
        override fun close() { try { click?.cancel() } finally { interop?.close() } }
    }
    override fun close() {
        var failure: Throwable? = null
        val retiring = cache.values.toList(); cache.clear()
        retiring.forEach { entry -> try { entry.close() } catch (error: Throwable) {
            val primary = failure
            if (primary == null) failure = error else if (primary !== error) primary.addSuppressed(error)
        } }
        failure?.let { throw it }
    }
}
