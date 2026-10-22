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
import android.view.KeyEvent
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
import androidx.compose.ui.platform.LocalResources
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
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import app.smallthingz.reverb.nativeui.*
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

/**
 * First assembled Main route check. The isolated QA package supplies permissions=false, so the
 * released CaptureScreen/native Capture owner are never mounted. Settings is opened through its
 * actual semantic action; captured edits are an unsaved theme preview followed by Undo and Back.
 * After all captures, the final native pass checks page-key dispatch using an unsaved draft.
 * No permission, picker, recording, tile, Save, or production Activity route is invoked.
 */
internal fun verifyNativeMainSettingsParity(instrumentation: Instrumentation, report: StringBuilder) {
    verifyNativeMainSettingsParity(instrumentation, report, commonGrainInput = false)
}

/** Supplemental exact comparison with only the explicitly changed grain input shared. */
internal fun verifyNativeMainSettingsCommonGrainParity(instrumentation: Instrumentation, report: StringBuilder) {
    verifyNativeMainSettingsParity(instrumentation, report, commonGrainInput = true)
}

private fun verifyNativeMainSettingsParity(
    instrumentation: Instrumentation,
    report: StringBuilder,
    commonGrainInput: Boolean,
) {
    check(instrumentation.targetContext.packageName == "app.smallthingz.reverb.nativeparity.reliability")
    check(instrumentation.targetContext.applicationContext.javaClass == Application::class.java)
    val directoryPrefix = if (commonGrainInput) "native-main-settings-common-grain" else "native-main-settings"
    val directory = File(instrumentation.targetContext.filesDir, "$directoryPrefix-${System.currentTimeMillis()}")
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
    if (commonGrainInput) result.put("selector", "native_main_settings_parity_common_grain")
        .put("reference_input_override", "settings_noise_tile only: exact native procedural grain")
        .put("raw_grain_parity", "Unqualified by this supplemental selector; raw selector remains separate")
    var activity: NativeParityActivity? = null
    var render: MainSettingsParityRender? = null
    var failure: Throwable? = null
    var comparisonSucceeded = false
    var commonGrain: NativeParityCommonGrainSession? = null
    val all = ArrayList<List<Pair<File, JSONObject>>>()
    try {
        if (commonGrainInput) commonGrain = NativeParityCommonGrainSession()
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
                    MainSettingsParityRender(host, reference, initialMode, systemDark, commonGrain)
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
                // Acquiring keyboard focus changes touch mode; do it only after every parity capture.
                if (pass == 3) {
                    val pageKeys = JSONObject()
                    run.put("settings_page_key_dispatch", pageKeys)
                    verifyMainSettingsPageKeyDispatch(instrumentation, host, current, pageKeys)
                }
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
        commonGrain?.verifyReferenceLoads()
        commonGrain?.verifyNativeReady()
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
        try { commonGrain?.let { result.put("common_grain", it.evidence()) } }
        catch (evidenceError: Throwable) { record(evidenceError) }
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
    val selector = if (commonGrainInput) "native_main_settings_parity_common_grain" else "native_main_settings_parity"
    report.append("PASS ").append(selector)
        .append(" exact settled route/preview/Undo; physical input unqualified\n")
}

/** Regression for the assembled dispatcher, not a direct call to Slider.onKeyDown. */
private fun verifyMainSettingsPageKeyDispatch(
    instrumentation: Instrumentation,
    host: NativeParityActivity,
    render: MainSettingsParityRender,
    result: JSONObject,
): JSONObject {
    val focusRequests = JSONArray()
    result.put("dispatch", "NativeMainScreenView.dispatchKeyEvent")
        .put("focus_requests", focusRequests).put("passed", false)
        .put("focus_contract", "Existing native keyboard leaf; public requestFocusFromTouch; no focusability or mode override")
    check(!render.reference)
    fun <T> main(action: () -> T): T {
        requireNativeParityHost(instrumentation, host)
        return nativeParityOnMain(instrumentation, action)
    }
    main { render.click(host.getString(R.string.open_settings)) }
    awaitMainParityAction(instrumentation, host, render, host.getString(R.string.onboarding_back))
    val root = main { mainParityViews(render.board).filterIsInstance<NativeMainScreenView>().single() }
    val settings = main { mainParityViews(root).filterIsInstance<NativeSettingsScreenContent>().single() }
    val viewport = main { checkNotNull(settings.listViewport) }
    val baseline = main {
        check(settings.inputAllowed && !settings.owner.state.hasUnsavedChanges)
        settings.owner.snapshot
    }
    var slider: View? = null
    var toggle: SettingsToggleRowView? = null
    var fallbackPixels = 0
    var fallbackAnimated = false
    val scrollEvents = object : RecyclerView.OnScrollListener() {
        override fun onScrolled(view: RecyclerView, dx: Int, dy: Int) { fallbackPixels += dy }
        override fun onScrollStateChanged(view: RecyclerView, state: Int) {
            if (state == RecyclerView.SCROLL_STATE_SETTLING) fallbackAnimated = true
        }
    }
    fun focusSnapshot(target: View): JSONObject {
        val bounds = Rect()
        val visible = target.getGlobalVisibleRect(bounds)
        val focused = root.findFocus()
        return JSONObject().put("enabled", target.isEnabled).put("focusable", target.isFocusable)
            .put("attached_to_window", target.isAttachedToWindow).put("shown", target.isShown)
            .put("globally_visible", visible)
            .put("bounds", JSONArray(listOf(bounds.left, bounds.top, bounds.right, bounds.bottom)))
            .put("in_touch_mode", target.isInTouchMode).put("target_focused", focused === target)
            .put("current_focus_class", focused?.javaClass?.name ?: JSONObject.NULL)
            .put("current_focus_identity", focused?.let(System::identityHashCode) ?: JSONObject.NULL)
    }
    fun requestTargetFocus(target: View, name: String) {
        val before = focusSnapshot(target)
        val evidence = JSONObject().put("target", name).put("target_class", target.javaClass.name)
            .put("target_identity", System.identityHashCode(target)).put("before", before)
            .put("request_attempted", false).put("request_focus_return", JSONObject.NULL)
        focusRequests.put(evidence)
        try {
            check(before.getBoolean("enabled")) { "$name focus target is disabled" }
            check(before.getBoolean("focusable")) { "$name focus target is not focusable" }
            check(before.getBoolean("attached_to_window")) { "$name focus target is detached" }
            evidence.put("request_attempted", true)
            val accepted = target.requestFocusFromTouch()
            evidence.put("request_focus_return", accepted)
            check(accepted) { "$name focus request was rejected" }
            check(root.findFocus() === target) { "$name focus request did not focus the actual target" }
        } finally {
            evidence.put("after", focusSnapshot(target))
        }
    }
    fun settledPosition(): SettingsListSavedPosition {
        var previous: SettingsListSavedPosition? = null
        var stable = 0
        repeat(100) {
            requireNativeParityHost(instrumentation, host)
            awaitNativeParityDraw(instrumentation, render.board)
            val position = main {
                settings.savePosition().takeIf {
                    viewport.scrollState == RecyclerView.SCROLL_STATE_IDLE && !viewport.isLayoutRequested
                }
            }
            stable = if (position != null && position == previous) stable + 1 else 0
            previous = position
            if (stable >= 3) return checkNotNull(position)
        }
        error("Settings list did not settle within 100 observed frames")
    }
    fun dispatchPair(code: Int): Pair<Boolean, Boolean> = main {
        val downTime = SystemClock.uptimeMillis()
        val down = root.dispatchKeyEvent(KeyEvent(downTime, downTime, KeyEvent.ACTION_DOWN, code, 0))
        val up = root.dispatchKeyEvent(KeyEvent(downTime, SystemClock.uptimeMillis(), KeyEvent.ACTION_UP, code, 0))
        down to up
    }
    try {
        main {
            settings.owner.outputCompressionChanged(settings.owner.state.activation, true)
            settings.owner.compressionLevelChanged(settings.owner.state.activation, 4)
            check(settings.owner.snapshot.format == ExportFormat.FLAC && settings.owner.snapshot.flacCompression == 4)
            val adapter = checkNotNull(viewport.adapter)
            val recording = (0 until adapter.itemCount).single {
                adapter.getItemViewType(it) == SettingsSectionKey.RECORDING.ordinal
            }
            (viewport.layoutManager as LinearLayoutManager).scrollToPositionWithOffset(recording, 0)
        }
        settledPosition()
        val focusedSlider = main {
            // The wrapper owns layout; its existing pointerView owns focus, accessibility and keys.
            mainParityViews(settings).filterIsInstance<CompressionSliderView>().single().pointerView.also {
                slider = it
                requestTargetFocus(it, "compression_slider")
            }
        }
        settledPosition()
        val sliderEvents = ArrayList<Pair<Int, Int>>()
        main {
            check(focusedSlider.getGlobalVisibleRect(Rect())) { "Focused compression slider is not visible" }
            focusedSlider.setOnKeyListener { _, code, event ->
                if (code == KeyEvent.KEYCODE_PAGE_UP || code == KeyEvent.KEYCODE_PAGE_DOWN)
                    sliderEvents += code to event.action
                false // Observe; the real CompressionSliderView must consume each event.
            }
        }
        for ((code, expected) in listOf(KeyEvent.KEYCODE_PAGE_UP to 3, KeyEvent.KEYCODE_PAGE_DOWN to 4)) {
            val before = settledPosition()
            main { check(root.findFocus() === focusedSlider) }
            check(dispatchPair(code) == (true to true)) { "Focused slider did not consume both page-key events" }
            main { check(settings.owner.snapshot.flacCompression == expected) { "Page key did not edit compression once" } }
            check(settledPosition() == before) { "Consumed slider page key also scrolled Settings" }
        }
        check(sliderEvents == listOf(KeyEvent.KEYCODE_PAGE_UP to KeyEvent.ACTION_DOWN,
            KeyEvent.KEYCODE_PAGE_UP to KeyEvent.ACTION_UP, KeyEvent.KEYCODE_PAGE_DOWN to KeyEvent.ACTION_DOWN,
            KeyEvent.KEYCODE_PAGE_DOWN to KeyEvent.ACTION_UP)) { "Slider received missing or duplicate page-key dispatch" }
        result.put("slider_page_up_level", 3).put("slider_page_down_level", 4)
            .put("slider_events_once", true).put("slider_scroll_unchanged", true)

        val toggleEvents = ArrayList<Int>()
        main {
            focusedSlider.setOnKeyListener(null)
            val compression = mainParityViews(settings).filterIsInstance<SettingsOutputCompressionView>().single()
            toggle = mainParityViews(compression).filterIsInstance<SettingsToggleRowView>().single().also {
                requestTargetFocus(it, "compression_toggle")
                it.setOnKeyListener { _, code, event ->
                    if (code == KeyEvent.KEYCODE_PAGE_DOWN) toggleEvents += event.action
                    false // The actual toggle declines PageDown, so Settings owns the fallback.
                }
            }
        }
        val beforeFallback = settledPosition()
        main {
            check(root.findFocus() === toggle)
            check(viewport.canScrollVertically(1)) { "Fixture lacks room to observe Settings page fallback" }
            viewport.addOnScrollListener(scrollEvents)
        }
        check(dispatchPair(KeyEvent.KEYCODE_PAGE_DOWN).first) { "Unconsumed PageDown did not reach Settings" }
        check(settledPosition() != beforeFallback) { "Unconsumed PageDown did not scroll Settings" }
        main {
            check(settings.owner.snapshot.flacCompression == 4)
            check(!fallbackAnimated && fallbackPixels in 1..viewport.height &&
                (fallbackPixels == viewport.height || !viewport.canScrollVertically(1))) {
                "Page fallback used stock animation or exceeded one source viewport: $fallbackPixels/${viewport.height}"
            }
        }
        check(toggleEvents.count { it == KeyEvent.ACTION_DOWN } == 1 &&
            toggleEvents.count { it == KeyEvent.ACTION_UP } <= 1) { "Toggle received missing DOWN or duplicate dispatch" }
        // Scrolling can detach the old focused row before UP; dispatch still runs once through Main.
        result.put("unconsumed_page_down_scrolled", true).put("fallback_changed_compression", false)
            .put("fallback_pixels", fallbackPixels).put("fallback_viewport_height", main { viewport.height })
            .put("fallback_used_stock_animation", fallbackAnimated)
            .put("fallback_key_up_delivered_to_retained_target", KeyEvent.ACTION_UP in toggleEvents)
    } catch (error: Throwable) {
        result.put("failure", error.stackTraceToString())
        throw error
    } finally {
        main {
            viewport.removeOnScrollListener(scrollEvents)
            slider?.setOnKeyListener(null)
            toggle?.setOnKeyListener(null)
            if (settings.owner.state.hasUnsavedChanges)
                settings.owner.navigateBack(settings.owner.state.activation) // Existing Undo, never Save.
            check(settings.owner.snapshot == baseline && !settings.owner.state.hasUnsavedChanges) {
                "Keyboard regression did not discard its unsaved Settings draft"
            }
            result.put("draft_discarded", true)
        }
    }
    return result.put("draft_discarded", true).put("passed", true)
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
    private val commonGrain: NativeParityCommonGrainSession?,
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
            if (commonGrain == null) {
                view.setContent {
                    CompositionLocalProvider(LocalContext provides activity, LocalDensity provides density) {
                        ReverbTheme(darkTheme = effectiveDark) {
                            ReleasedMainScreenParityOracle(false, false, ::forbidden, ::themeChanged)
                        }
                    }
                }
            } else {
                // Keep Activity, LocalContext, theme and geometry identical; only the oracle's
                // explicit LocalResources grain decode receives the supplementary input.
                val referenceResources = commonGrain.referenceResources(activity.resources)
                view.setContent {
                    CompositionLocalProvider(LocalContext provides activity, LocalDensity provides density,
                        LocalResources provides referenceResources) {
                        ReverbTheme(darkTheme = effectiveDark) {
                            ReleasedMainScreenParityOracle(false, false, ::forbidden, ::themeChanged)
                        }
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
