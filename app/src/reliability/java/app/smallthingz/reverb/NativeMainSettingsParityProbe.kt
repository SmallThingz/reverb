package app.smallthingz.reverb

import android.animation.ValueAnimator
import android.app.Application
import android.app.Instrumentation
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Rect
import android.os.Build
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.FrameLayout
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
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
import app.smallthingz.nativeui.text.font.createFontFamilyResolver
import androidx.compose.ui.unit.Density
import androidx.core.view.WindowCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import app.smallthingz.reverb.nativeui.*
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

/**
 * First assembled Main route check. The isolated QA package supplies permissions=false, so the
 * released CaptureScreen/native Capture owner are never mounted. Settings is opened through its
 * actual semantic action; the only edit is an unsaved theme preview followed by Undo and Back.
 * No permission, picker, recording, tile, Save, or production Activity route is invoked.
 */
internal fun verifyNativeMainSettingsParity(instrumentation: Instrumentation, report: StringBuilder) {
    check(instrumentation.targetContext.packageName == "app.smallthingz.reverb.nativeparity.reliability")
    check(instrumentation.targetContext.applicationContext.javaClass == Application::class.java)
    val directory = File(instrumentation.targetContext.filesDir, "native-main-settings-${System.currentTimeMillis()}")
    check(directory.mkdir())
    val runs = JSONArray()
    val comparisons = JSONArray()
    val result = JSONObject()
        .put("oracle_sha256", "614856c6ad8a0a54027c932d44f1361003e73f1bf7faafaf7b902e9e8c6d117f")
        .put("dispatch", "Own focused QA window semantic actions; no physical input or permission request")
        .put("scope", "Main -> Settings -> opposite theme preview -> Undo -> Back")
        .put("unqualified", JSONArray(listOf("physical gesture/frame-phase parity", "TalkBack delivery",
            "keyboard/IME", "Library paths (separate probe)", "production startup/capture")))
        .put("runs", runs).put("comparisons", comparisons).put("passed", false)
    var activity: NativeParityActivity? = null
    var render: MainSettingsParityRender? = null
    var failure: Throwable? = null
    var comparisonSucceeded = false
    val all = ArrayList<List<Pair<File, JSONObject>>>()
    try {
        val host = launchNativeParityActivity(instrumentation)
        activity = host
        awaitNativeParityDraw(instrumentation, host.fixtureRoot)
        nativeParityOnMain(instrumentation) {
            // The release Activity's ReverbTheme uses these own-window policies. Both renderers
            // receive the same dispatched insets; neither is given fabricated zero bar sizes.
            WindowCompat.setDecorFitsSystemWindows(host.window, false)
            @Suppress("DEPRECATION")
            host.window.statusBarColor = android.graphics.Color.TRANSPARENT
            @Suppress("DEPRECATION")
            host.window.navigationBarColor = android.graphics.Color.TRANSPARENT
        }
        val initialMode = getConfiguredThemeMode(host)
        val systemDark = host.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
            Configuration.UI_MODE_NIGHT_YES
        val initialDark = mainParityDark(initialMode, systemDark)
        val target = if (initialDark) AppThemeMode.LIGHT else AppThemeMode.DARK
        result.put("configured_theme_before", initialMode.name).put("system_dark", systemDark)
            .put("preview_target", target.name)
        for (pass in 0..3) {
            val reference = pass % 2 == 0
            val label = listOf("compose", "native", "compose-repeat", "native-repeat")[pass]
            val run = JSONObject().put("renderer", label).put("steps", JSONArray())
            runs.put(run)
            val captures = ArrayList<Pair<File, JSONObject>>()
            all += captures
            var passFailure: Throwable? = null
            try {
                requireNativeParityHost(instrumentation, host)
                render = nativeParityOnMain(instrumentation) {
                    host.fixtureRoot.removeAllViews()
                    MainSettingsParityRender(host, reference, initialMode, systemDark)
                }
                val current = checkNotNull(render)
                fun capture(step: String, readyAction: Int) {
                    awaitMainParityAction(instrumentation, host, current, host.getString(readyAction))
                    val bitmap = settledMainParityBitmap(instrumentation, host, current, directory, "$label-$step-unsettled.png")
                    try {
                        val evidence = nativeParityOnMain(instrumentation) { current.evidence() }
                            .put("step", step).put("configured_theme", getConfiguredThemeMode(host).name)
                        check(getConfiguredThemeMode(host) == initialMode) {
                            "Unsaved preview altered the QA package's persisted theme"
                        }
                        val file = File(directory, "$label-$step.png")
                        saveNativeParityBitmap(directory, file.name, bitmap)
                        File(directory, "$label-$step.json").writeText(evidence.toString(2))
                        run.getJSONArray("steps").put(evidence)
                        captures += file to evidence
                    } finally { bitmap.recycle() }
                }
                fun click(resource: Int) {
                    requireNativeParityHost(instrumentation, host)
                    nativeParityOnMain(instrumentation) { current.click(host.getString(resource)) }
                }
                capture("main", R.string.open_settings)
                click(R.string.open_settings)
                capture("settings", target.labelRes)
                click(target.labelRes)
                capture("preview", R.string.undo)
                check(nativeParityOnMain(instrumentation) { current.effectiveDark } == !initialDark)
                click(R.string.undo)
                capture("undo", R.string.onboarding_back)
                check(nativeParityOnMain(instrumentation) { current.effectiveDark } == initialDark)
                click(R.string.onboarding_back)
                capture("main-restored", R.string.open_settings)
                check(nativeParityOnMain(instrumentation) { current.forbiddenCalls == 0 })
            } catch (error: Throwable) {
                passFailure = error
                throw error
            } finally {
                try {
                    nativeParityOnMain(instrumentation) { cleanupMainParity(render, host, finish = false) }
                } catch (cleanup: Throwable) {
                    val primary = passFailure
                    if (primary == null) throw cleanup
                    if (primary !== cleanup) primary.addSuppressed(cleanup)
                } finally { render = null }
            }
        }
        var mismatches = 0
        for (step in all[0].indices) {
            val pair = mainParityCompare(all[0][step].first, all[1][step].first)
            val referenceStable = mainParityCompare(all[0][step].first, all[2][step].first)
            val nativeStable = mainParityCompare(all[1][step].first, all[3][step].first)
            fun callbacks(pass: Int) = all[pass][step].second.getJSONArray("theme_callbacks").toString()
            val callbacksEqual = callbacks(0) == callbacks(1)
            val callbacksStable = callbacks(0) == callbacks(2) && callbacks(1) == callbacks(3)
            val passed = listOf(pair, referenceStable, nativeStable).all {
                it.getLong("different_pixels") == 0L
            } && callbacksEqual && callbacksStable
            comparisons.put(JSONObject().put("step", all[0][step].second.getString("step"))
                .put("pixels", pair).put("reference_stability", referenceStable)
                .put("native_stability", nativeStable).put("callbacks_equal", callbacksEqual)
                .put("callbacks_stable", callbacksStable).put("passed", passed))
            if (!passed) mismatches++
        }
        check(mismatches == 0) { "Assembled Main/Settings differs in $mismatches steps; retain raw evidence" }
        comparisonSucceeded = true
    } catch (error: Throwable) {
        failure = error
    } finally {
        fun record(error: Throwable) {
            val primary = failure
            if (primary == null) failure = error else if (primary !== error) primary.addSuppressed(error)
        }
        try {
            nativeParityOnMain(instrumentation) { cleanupMainParity(render, activity, finish = true) }
        } catch (cleanup: Throwable) { record(cleanup) }
        result.put("passed", comparisonSucceeded && failure == null)
            .put("failure", failure?.stackTraceToString() ?: JSONObject.NULL)
        try { File(directory, "result.json").writeText(result.toString(2)) }
        catch (writeError: Throwable) {
            record(writeError)
            result.put("passed", false).put("failure", failure?.stackTraceToString())
        }
        report.append("Main/Settings evidence: ").append(directory.absolutePath).append('\n')
    }
    failure?.let { throw it }
    report.append("PASS native_main_settings_parity exact settled route/preview/Undo; physical input unqualified\n")
}

private fun cleanupMainParity(render: MainSettingsParityRender?, host: NativeParityActivity?, finish: Boolean) {
    var failure: Throwable? = null
    fun release(action: () -> Unit) {
        try { action() } catch (error: Throwable) {
            val primary = failure
            if (primary == null) failure = error else if (primary !== error) primary.addSuppressed(error)
        }
    }
    release { render?.close() }
    release { host?.fixtureRoot?.removeAllViews() }
    if (finish) release { host?.finish() }
    failure?.let { throw it }
}

@OptIn(ExperimentalComposeViewContextApi::class)
private class MainSettingsParityRender(
    private val activity: NativeParityActivity,
    val reference: Boolean,
    private val initialMode: AppThemeMode,
    private val systemDark: Boolean,
) : AutoCloseable {
    val board = FrameLayout(activity)
    private val density = Density(activity.resources.displayMetrics.density, activity.resources.configuration.fontScale)
    private val resolver = createFontFamilyResolver(activity)
    private val previewState = mutableStateOf<AppThemeMode?>(null)
    private val callbacks = JSONArray()
    private var native: NativeMainScreenView? = null
    private var compose: ComposeView? = null
    private var compositionContext: ComposeViewContext? = null
    private var runtime: NativeAboutWindowRuntime? = null
    private val runtimeLifecycle = LifecycleEventObserver { _, event ->
        when (event) {
            Lifecycle.Event.ON_START -> runtime?.setStarted(true)
            Lifecycle.Event.ON_STOP -> runtime?.setStarted(false)
            else -> Unit
        }
    }
    private var insetBinding: NativeWindowInsetsBinding? = null
    private var closed = false
    var forbiddenCalls = 0
        private set
    val effectiveDark: Boolean get() = mainParityDark(previewState.value ?: initialMode, systemDark)
    private val noise = ReverbNoiseDrawable(activity.resources, palette().surface)

    init {
        try {
        board.clipChildren = false
        board.clipToPadding = false
        board.isFocusable = true
        board.isFocusableInTouchMode = true
        board.defaultFocusHighlightEnabled = false
        if (Build.VERSION.SDK_INT >= 29) board.isForceDarkAllowed = false
        board.background = noise
        activity.fixtureRoot.addView(board, FrameLayout.LayoutParams(-1, -1))
        applyWindowAppearance()
        if (reference) {
            // The isolated attached host owns this pass's ImageVectorCache. No shared mutable
            // resource configuration and no recoloring of the release oracle.
            val context = ComposeViewContext(board)
            compositionContext = context
            val view = ComposeView(activity)
            compose = view
            view.setContent {
                CompositionLocalProvider(LocalContext provides activity, LocalDensity provides density) {
                    ReverbTheme(darkTheme = effectiveDark) {
                        ReleasedMainScreenParityOracle(false, false, ::forbidden, ::themeChanged)
                    }
                }
            }
            view.createComposition(context)
            board.addView(view, FrameLayout.LayoutParams(-1, -1))
        } else {
            val frames = NativeAboutWindowRuntime()
            runtime = frames
            activity.lifecycle.addObserver(runtimeLifecycle)
            frames.setStarted(activity.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED))
            val text = NativeWindowTextAccessibilityOwner(board, NativeTextSemanticsClipPolicy {
                NativeTextSemanticsClip.Unclipped
            })
            val view = NativeMainScreenView.create(activity, environment(), NativeMainScreenInput(false, false),
                activity, activity.onBackPressedDispatcher, frames.scope,
                NativeActivityAccessibilityManager(activity), board, text, null, ::forbidden, ::themeChanged,
                registerDirectoryResult = {
                    NativeDirectoryPickerRegistration(launch = { forbidden() }, unregister = {})
                }, releaseInputFocus = {})
            native = view
            board.addView(view, FrameLayout.LayoutParams(-1, -1))
            insetBinding = NativeWindowInsetsBinding(board) { view.setWindowInsets(it) }
            view.start()
        }
        } catch (error: Throwable) {
            try { close() } catch (cleanup: Throwable) { if (cleanup !== error) error.addSuppressed(cleanup) }
            throw error
        }
    }

    private fun forbidden() {
        forbiddenCalls++
        error("Fixture tried a permission or picker action; do not launch external UI")
    }
    private fun palette() = ReverbPalette.resolve(activity, effectiveDark)
    private fun environment() = ReverbViewEnvironment(density.nativeParityDensity(), resolver, palette())
    private fun themeChanged(mode: AppThemeMode?) {
        callbacks.put(mode?.name ?: JSONObject.NULL)
        previewState.value = mode
        if (closed) return
        noise.setBackgroundColor(palette().surface)
        applyWindowAppearance()
        native?.bind(NativeMainScreenInput(false, false), environment())
    }
    private fun applyWindowAppearance() {
        WindowCompat.getInsetsController(activity.window, board).apply {
            isAppearanceLightStatusBars = !effectiveDark
            isAppearanceLightNavigationBars = !effectiveDark
        }
    }

    fun click(label: String) {
        check(!closed && board.isShown && activity.hasWindowFocus())
        if (reference) {
            val nodes = sourceActions(label)
            check(nodes.size == 1) { "Expected one enabled source action '$label'; found ${nodes.size}" }
            check(nodes.single().config.getOrNull(SemanticsActions.OnClick)?.action?.invoke() == true)
        } else {
            val views = nativeActions(label)
            check(views.size == 1) { "Expected one enabled native action '$label'; found ${views.size}" }
            check(views.single().performAccessibilityAction(AccessibilityNodeInfo.ACTION_CLICK, null))
        }
    }
    fun actionCount(label: String): Int = if (reference) sourceActions(label).size else nativeActions(label).size
    private fun sourceActions(label: String): List<SemanticsNode> {
        val roots = mainParityViews(board).filterIsInstance<ViewRootForTest>().toList()
        if (roots.size != 1) return emptyList()
        return roots.single().semanticsOwner.getAllSemanticsNodes(mergingEnabled = true).filter { node ->
            val names = node.config.getOrNull(SemanticsProperties.Text)?.map { it.text }.orEmpty() +
                node.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty()
            label in names && node.config.getOrNull(SemanticsActions.OnClick)?.action != null &&
                node.config.getOrNull(SemanticsProperties.Disabled) == null &&
                node.boundsInRoot.width > 0f && node.boundsInRoot.height > 0f &&
                generateSequence(node) { it.parent }.none {
                    it.config.getOrNull(SemanticsProperties.HideFromAccessibility) != null
                }
        }
    }
    private fun nativeActions(label: String): List<View> = mainParityViews(board).filter { view ->
        val info = view.createAccessibilityNodeInfo() ?: return@filter false
        try {
            info.isVisibleToUser && info.isEnabled && info.isClickable &&
                mainParityViews(view).any { descendant ->
                    val child = descendant.createAccessibilityNodeInfo() ?: return@any false
                    try {
                        child.isVisibleToUser && (child.text?.toString() == label ||
                            child.contentDescription?.toString() == label)
                    } finally { @Suppress("DEPRECATION") child.recycle() }
                }
        } finally { @Suppress("DEPRECATION") info.recycle() }
    }.toList()

    fun evidence(): JSONObject {
        val entries = JSONArray()
        for (view in mainParityViews(board)) {
            val info = view.createAccessibilityNodeInfo() ?: continue
            try {
                if (info.isVisibleToUser && (info.isClickable || info.text != null || info.contentDescription != null)) {
                    val bounds = Rect()
                    info.getBoundsInScreen(bounds)
                    entries.put(JSONObject().put("class", info.className?.toString())
                        .put("text", info.text?.toString()).put("description", info.contentDescription?.toString())
                        .put("clickable", info.isClickable).put("enabled", info.isEnabled)
                        .put("selected", info.isSelected).put("bounds", JSONArray(listOf(
                            bounds.left, bounds.top, bounds.right, bounds.bottom))))
                }
            } finally { @Suppress("DEPRECATION") info.recycle() }
        }
        val sourceNodes = JSONArray()
        if (reference) {
            for (root in mainParityViews(board).filterIsInstance<ViewRootForTest>()) {
                for (node in root.semanticsOwner.getAllSemanticsNodes(mergingEnabled = false)) {
                    val bounds = node.boundsInRoot
                    sourceNodes.put(JSONObject().put("text", JSONArray(
                        node.config.getOrNull(SemanticsProperties.Text)?.map { it.text }.orEmpty()))
                        .put("description", JSONArray(node.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty()))
                        .put("role", node.config.getOrNull(SemanticsProperties.Role)?.toString())
                        .put("selected", node.config.getOrNull(SemanticsProperties.Selected) ?: JSONObject.NULL)
                        .put("disabled", node.config.getOrNull(SemanticsProperties.Disabled) != null)
                        .put("hidden", generateSequence(node) { it.parent }.any {
                            it.config.getOrNull(SemanticsProperties.HideFromAccessibility) != null
                        })
                        .put("clickable", node.config.getOrNull(SemanticsActions.OnClick)?.action != null)
                        .put("bounds_in_root", JSONArray(listOf(bounds.left, bounds.top, bounds.right, bounds.bottom))))
                }
            }
        }
        return JSONObject().put("source_semantics", sourceNodes).put("width", board.width).put("height", board.height)
            .put("effective_dark", effectiveDark).put("surface_argb", palette().surface.toLong() and 0xffffffffL)
            .put("theme_callbacks", JSONArray(callbacks.toString())).put("native_view_nodes", entries)
            .put("forbidden_calls", forbiddenCalls)
    }

    override fun close() {
        if (closed) return
        closed = true
        var failure: Throwable? = null
        fun release(action: () -> Unit) {
            try { action() } catch (error: Throwable) {
                if (failure == null) failure = error else if (failure !== error) failure!!.addSuppressed(error)
            }
        }
        release { compose?.disposeComposition() }
        release { native?.close() }
        release { insetBinding?.close() }
        release { activity.lifecycle.removeObserver(runtimeLifecycle) }
        release { runtime?.close() }
        release { board.removeAllViews(); (board.parent as? ViewGroup)?.removeView(board) }
        failure?.let { throw it }
    }
}

private fun mainParityDark(mode: AppThemeMode, systemDark: Boolean) = when (mode) {
    AppThemeMode.SYSTEM -> systemDark
    AppThemeMode.LIGHT -> false
    AppThemeMode.DARK -> true
}
private fun mainParityViews(root: View): Sequence<View> = sequence {
    yield(root)
    if (root is ViewGroup) for (i in 0 until root.childCount) yieldAll(mainParityViews(root.getChildAt(i)))
}
private fun awaitMainParityAction(instrumentation: Instrumentation, host: NativeParityActivity,
                                  render: MainSettingsParityRender, label: String) {
    repeat(100) {
        requireNativeParityHost(instrumentation, host)
        awaitNativeParityDraw(instrumentation, render.board)
        val count = nativeParityOnMain(instrumentation) { render.actionCount(label) }
        check(count <= 1) { "Ambiguous current Main/Settings action '$label': $count" }
        if (count == 1) return
    }
    error("Main/Settings did not expose enabled '$label' within 100 observed frame boundaries")
}
private fun settledMainParityBitmap(instrumentation: Instrumentation, host: NativeParityActivity,
                                    render: MainSettingsParityRender, directory: File, failedName: String): Bitmap {
    // Main's released settle tween is 220ms. Observe at least that source duration under the
    // unchanged animator scale before testing pixel stability; an initially unchanged frame is
    // not proof that an asynchronously launched panel transition has completed.
    val earliest = SystemClock.uptimeMillis() + kotlin.math.ceil(220.0 * ValueAnimator.getDurationScale()).toLong()
    var reachedBoundary = false
    for (frame in 0 until 200) {
        requireNativeParityHost(instrumentation, host)
        awaitNativeParityDraw(instrumentation, render.board)
        if (SystemClock.uptimeMillis() >= earliest) { reachedBoundary = true; break }
    }
    check(reachedBoundary) { "Source panel duration did not elapse within 200 observed frames at current animator scale" }
    var previous: Bitmap? = null
    var equal = 0
    try {
        repeat(40) {
            val next = captureNativeParityContent(instrumentation, host) { render.board }
            val old = previous
            equal = if (old != null && compareNativeParityPixels(old, next).getLong("different_pixels") == 0L)
                equal + 1 else 0
            old?.recycle()
            previous = next
            if (equal >= 2) {
                previous = null
                return next
            }
        }
        previous?.let { saveNativeParityBitmap(directory, failedName, it) }
        error("Main/Settings did not produce three identical settled captures; no frame was suppressed")
    } finally { previous?.recycle() }
}
private fun mainParityCompare(first: File, second: File): JSONObject {
    val a = checkNotNull(BitmapFactory.decodeFile(first.absolutePath))
    try {
        val b = checkNotNull(BitmapFactory.decodeFile(second.absolutePath))
        try { return compareNativeParityPixels(a, b) } finally { b.recycle() }
    } finally { a.recycle() }
}
