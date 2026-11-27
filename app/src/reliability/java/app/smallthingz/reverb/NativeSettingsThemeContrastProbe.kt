package app.smallthingz.reverb

import android.app.Application
import android.app.Instrumentation
import android.content.Context
import android.content.SharedPreferences
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
import android.view.ViewTreeObserver
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.core.graphics.ColorUtils
import app.smallthingz.reverb.nativeui.*
import java.io.File
import java.lang.reflect.Field
import java.util.function.LongSupplier
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.roundToInt
import kotlin.math.pow
import org.json.JSONArray
import org.json.JSONObject
import smallthingz.ui.animation.ColorTransition
import smallthingz.ui.animation.view.ViewColorTransition
import smallthingz.ui.components.NativeDropdown
import smallthingz.ui.components.NativeBackdropBinding
import smallthingz.ui.R as NativeUiR
import smallthingz.ui.components.NativeColorScheme
import smallthingz.ui.components.NativeDarkSurfacePreset
import smallthingz.ui.components.NativeGrainPreset
import smallthingz.ui.components.NativeMaterialSwitchView
import smallthingz.ui.components.NativeToggleRow
import smallthingz.ui.controls.textSurfaceForeground
import smallthingz.ui.reverb.settings.ReverbRetentionInputView
import smallthingz.ui.reverb.settings.ReverbRetentionMode
import smallthingz.ui.reverb.ReverbTitledTopBarLayout
import smallthingz.ui.reverb.ReverbDesign
import smallthingz.ui.graphics.NoiseDrawable
import smallthingz.ui.graphics.NoiseTexture
import smallthingz.ui.text.RetentionLineHeightSpan
import smallthingz.ui.text.view.ParagraphView
import smallthingz.ui.graphics.Color as NativeColor
import smallthingz.ui.graphics.toArgb
import smallthingz.ui.unit.Density
import smallthingz.ui.unit.sp

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
        val producer = JSONObject().put("name", "reverb_surface_producer_preserves_source_alpha")
        cases.put(producer)
        main { verifyReverbSurfaceProducer(host, producer) }
        cases.put(main { verifyRetentionEditorPaletteGeometry(host) })
        val cachedRecording = JSONObject().put("name", "recording-cache-light-dark-light").put("passed", false)
        cases.put(cachedRecording)
        verifySettingsCachedRecordingTheme(instrumentation, host, directory, cachedRecording)
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
                        val surfaces = main { probe.surfaceRanges() }
                        val mask = main { probe.masks() }
                        val underlay = main { probe.withoutGlyphs { probe.bitmap() } }
                        try {
                            val software = main { probe.bitmap() }
                            try {
                                saveNativeParityBitmap(directory, "$label-software.png", software)
                                saveNativeParityBitmap(directory, "$label-software-underlay.png", underlay)
                                main { probe.checkSurfaceRanges(surfaces) }
                                entry.put("software", main { probe.verify(software, underlay, mask) })
                            } finally { software.recycle() }
                            val hardware = captureNativeParityContent(instrumentation, host) { current.board }
                            try {
                                val hardwareUnderlay = probe.captureWithoutGlyphs(instrumentation, host)
                                try {
                                    saveNativeParityBitmap(directory, "$label-hardware.png", hardware)
                                    saveNativeParityBitmap(directory, "$label-underlay.png", hardwareUnderlay)
                                    main { probe.checkSurfaceRanges(surfaces) }
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
    report.appendLine("PASS native_settings_theme_contrast actual Settings glyphs, grain bounds, cached Recording reattachment, parent-only hardware invalidation and retained editor")
}

/** Uses the app's Main -> Settings bind and its real Recording factory on both sides. */
internal fun verifySettingsCachedRecordingTheme(instrumentation: Instrumentation, host: NativeParityActivity,
    directory: File, result: JSONObject) {
    check(host.packageName == "app.smallthingz.reverb.uitheme.reliability")
    fun <T> main(block: () -> T) = nativeParityOnMain(instrumentation, block)
    val preferences = getRecorderPreferences(host)
    val originalPreferences = preferences.all.toMap()
    val committedTheme = processCommittedTheme.state.value
    val preferenceChanges = AtomicInteger()
    val preferenceListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> preferenceChanges.incrementAndGet() }
    val rounds = JSONArray()
    result.put("rounds", rounds).put("animation_scale", 1)
        .put("sample", "Software switch draw at first tree OnDraw after real cached attachment; color callbacks held at attachment")
    var failure: Throwable? = null
    var switchActions = 0
    val callbackField = themeField(NativeToggleRow::class.java, "onChanged")
    val originalCallbacks = mutableListOf<Pair<NativeToggleRow, Any?>>()
    val originalScales = mutableListOf<Pair<ViewColorTransition, Any?>>()
    var freshSection: NativeSettingsSection? = null
    val current = main {
        MainSettingsParityRender(host, false, AppThemeMode.LIGHT, false, null,
            productionWindowFocus = true, productionEnvironmentForProbe = true)
    }
    fun motion(view: NativeMaterialSwitchView) = themeField(view, "colorMotion").get(view) as ViewColorTransition
    fun switches(view: View) = themeViews(view).filterIsInstance<NativeMaterialSwitchView>().toList()
    fun geometry(view: NativeMaterialSwitchView): List<Int> {
        val row = view.parent as NativeToggleRow
        return listOf(row.width, row.height, row.paddingLeft, row.paddingTop, row.paddingRight, row.paddingBottom,
            view.left, view.top, view.right, view.bottom, view.measuredWidth, view.measuredHeight, view.layoutDirection)
    }
    fun colors(view: NativeMaterialSwitchView) = (0..2).map { motion(view).color(it) }
    fun bitmap(view: NativeMaterialSwitchView): Bitmap {
        val image = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        try { view.draw(Canvas(image)); return image }
        catch (error: Throwable) { image.recycle(); throw error }
    }
    fun await(label: String, ready: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 5_000L
        while (!main(ready)) {
            check(SystemClock.uptimeMillis() < deadline) { "Cached Recording fixture timed out at $label" }
            awaitNativeParityDraw(instrumentation, current.board)
        }
    }
    try {
        preferences.registerOnSharedPreferenceChangeListener(preferenceListener)
        main {
            // A bounded real viewport puts Recording fully into RV's cache at the Theme row.
            current.board.layoutParams = android.widget.FrameLayout.LayoutParams(-1,
                (240 * host.resources.displayMetrics.density).roundToInt())
        }
        val openSettings = host.getString(R.string.open_settings)
        await("open Settings") { current.actionCount(openSettings) == 1 }
        main { current.click(openSettings) }
        await("hydrated Settings") {
            themeViews(current.board).filterIsInstance<NativeSettingsScreenContent>().any {
                it.inputAllowed && it.owner.autoStartChoice.loaded && !it.owner.autoStartChoice.saving
            }
        }
        val settings = main { themeViews(current.board).filterIsInstance<NativeSettingsScreenContent>().single() }
        await("Settings panel settled") {
            val root = themeViews(current.board).filterIsInstance<NativeMainScreenView>().single()
            val panel = themeField(root, "settingsMotion").get(root) as NativeMainPanelMotion
            panel.panelProgress == 1f && panel.settledProgress == 1f
        }
        val viewport = main { checkNotNull(settings.listViewport) }
        val manager = main { viewport.layoutManager as LinearLayoutManager }
        val adapter = main { checkNotNull(viewport.adapter) }
        fun position(key: SettingsSectionKey) = (0 until adapter.itemCount).single { adapter.getItemViewType(it) == key.ordinal }
        fun preview(mode: AppThemeMode) {
            // The existing harness callback publishes the production environment through Main.bind.
            // It changes no draft field and never activates a switch or a Theme choice.
            current.javaClass.getDeclaredMethod("themeChanged", AppThemeMode::class.java)
                .apply { isAccessible = true }.invoke(current, mode)
        }
        main {
            viewport.setItemViewCacheSize(adapter.itemCount)
            manager.isItemPrefetchEnabled = false
            preview(AppThemeMode.LIGHT)
            manager.scrollToPositionWithOffset(position(SettingsSectionKey.RECORDING), 0)
        }
        await("Recording placement and grain") {
            val holder = viewport.findViewHolderForAdapterPosition(position(SettingsSectionKey.RECORDING))
            holder?.itemView?.isAttachedToWindow == true && !viewport.isLayoutRequested &&
                !viewport.isComputingLayout && !viewport.hasPendingAdapterUpdates() &&
                switches(holder.itemView).size == 4 && switches(holder.itemView).all { !themeState(motion(it)).running } &&
                NoiseTexture.shader(NoiseDrawable.SEED_BACKGROUND) != null
        }
        val retained = main { checkNotNull(viewport.findViewHolderForAdapterPosition(position(SettingsSectionKey.RECORDING))) }
        val controls = main { switches(retained.itemView) }
        val recordingOffset = main {
            val bounds = Rect(0, 0, controls.first().width, controls.first().height)
            (retained.itemView as ViewGroup).offsetDescendantRectToMyCoords(controls.first(), bounds)
            (viewport.height - bounds.height()) / 2 - bounds.top
        }
        main { manager.scrollToPositionWithOffset(position(SettingsSectionKey.RECORDING), recordingOffset) }
        await("visible compression switch") {
            val visible = Rect()
            !viewport.isLayoutRequested && controls.first().getGlobalVisibleRect(visible) &&
                visible.width() == controls.first().width && visible.height() == controls.first().height
        }
        val originalGeometry = main { controls.map(::geometry) }
        val originalState = main { controls.map { themeField(it, "checked").getBoolean(it) to it.isEnabled } }
        val draft = main { settings.owner.state }
        val autoStart = main { settings.owner.autoStartChoice }
        val autoStartRevision = AutoStartAfterRestart.policy.revision()
        val factory = main { themeField(settings, "sections").get(settings) as NativeSettingsSections }
        val recycler = main { themeField(RecyclerView::class.java, "mRecycler").get(viewport) }
        fun isCached() = (themeField(recycler, "mCachedViews").get(recycler) as List<*>).any { it === retained }
        fun checkCleanCache() {
            check(isCached() && !retained.itemView.isAttachedToWindow && !viewport.hasPendingAdapterUpdates())
            check(settings.owner.state == draft)
            for ((method, expected) in listOf("isBound" to true, "isInvalid" to false, "needsUpdate" to false)) {
                check(RecyclerView.ViewHolder::class.java.getDeclaredMethod(method).apply { isAccessible = true }
                    .invoke(retained) == expected)
            }
        }
        fun referenceColors(): List<List<Int>> {
            val reference = factory.create(SettingsSectionKey.RECORDING).also { freshSection = it }
            reference.bind()
            val colors = switches(reference.view).map(::colors)
            reference.close()
            freshSection = null
            return colors
        }
        var previousReference = main { referenceColors() }
        main {
            check(controls.distinct().size == 4 && retained.itemId == SettingsSectionKey.RECORDING.ordinal.toLong())
            for (control in controls) {
                val row = control.parent as NativeToggleRow
                originalCallbacks += row to callbackField.get(row)
                callbackField.set(row, { _: Boolean ->
                    switchActions++
                    error("Theme rebind invoked a Recording row action")
                })
                val colorMotion = motion(control)
                val scale = themeField(colorMotion, "durationScale")
                originalScales += colorMotion to scale.get(colorMotion)
                scale.set(colorMotion, { 1f }) // Fixture-local supplier also works when device animation scale is zero.
            }
        }
        for (mode in listOf(AppThemeMode.DARK, AppThemeMode.LIGHT)) {
            val previousColors = main { controls.map(::colors) }
            main { manager.scrollToPositionWithOffset(position(SettingsSectionKey.THEME), 0) }
            await("Recording cached before $mode") { !retained.itemView.isAttachedToWindow && isCached() }
            val entry = JSONObject().put("theme", mode.name).put("passed", false)
                .put("previous_actual_colors", JSONArray(previousColors))
            rounds.put(entry)
            val fresh = main {
                preview(mode)
                check(settings.owner.state == draft && isCached() && !retained.itemView.isAttachedToWindow)
                check(current.effectiveDark == (mode == AppThemeMode.DARK))
                check(settings.pointerEnvironment.palette == ReverbDesign.appearance.resolveColorScheme(host, current.effectiveDark))
                checkCleanCache()
                factory.create(SettingsSectionKey.RECORDING).also { freshSection = it }
            }
            val drawn = CountDownLatch(1)
            var sampleFailure: Throwable? = null
            var sampled = false
            var attached = 0
            val held = main { controls.map { HeldThemeMotion(motion(it)) } }
            val attachListener = object : RecyclerView.OnChildAttachStateChangeListener {
                override fun onChildViewAttachedToWindow(view: View) {
                    if (view === retained.itemView) {
                        attached++
                        held.forEach { it.stop() }
                    }
                }
                override fun onChildViewDetachedFromWindow(view: View) = Unit
            }
            val drawListener = ViewTreeObserver.OnDrawListener {
                if (attached > 0 && !sampled) {
                    sampled = true
                    try {
                        check(attached == 1 && viewport.findViewHolderForAdapterPosition(position(SettingsSectionKey.RECORDING)) === retained)
                        check(retained.itemView.isShown && retained.itemView.getGlobalVisibleRect(Rect()))
                        val visible = Rect()
                        check(controls.first().getGlobalVisibleRect(visible) && visible.width() == controls.first().width &&
                            visible.height() == controls.first().height) { "First sampled switch is clipped" }
                        check(switches(retained.itemView) == controls)
                        check(controls.map(::geometry) == originalGeometry)
                        check(controls.map { themeField(it, "checked").getBoolean(it) to it.isEnabled } == originalState)
                        val freshControls = switches(fresh.view)
                        check(freshControls.size == 4 && freshControls.map(::geometry) == originalGeometry)
                        check(freshControls.map { themeField(it, "checked").getBoolean(it) to it.isEnabled } == originalState)
                        check(freshControls.map(::colors) != previousReference) { "Fresh Light/Dark endpoints do not distinguish this case" }
                        previousReference = freshControls.map(::colors)
                        val samples = JSONArray()
                        entry.put("switches", samples).put("same_cached_holder", true).put("same_views_state_geometry", true)
                        var equal = true
                        for (index in controls.indices) {
                            val actual = controls[index]
                            val expected = freshControls[index]
                            val actualBitmap = bitmap(actual)
                            try {
                                val expectedBitmap = bitmap(expected)
                                try {
                                    val pixels = compareNativeParityPixels(expectedBitmap, actualBitmap)
                                    val matches = pixels.getLong("different_pixels") == 0L && colors(actual) == colors(expected) &&
                                        !themeState(motion(actual)).running
                                    equal = equal && matches
                                    samples.put(JSONObject().put("row", index).put("class", actual.javaClass.name)
                                        .put("checked", originalState[index].first).put("enabled", actual.isEnabled)
                                        .put("geometry_px", JSONArray(geometry(actual))).put("pixel_comparison", pixels)
                                        .put("actual_colors", JSONArray(colors(actual))).put("fresh_colors", JSONArray(colors(expected)))
                                        .put("transition_running", themeState(motion(actual)).running).put("passed", matches))
                                    if (!matches) {
                                        saveNativeParityBitmap(directory, "cached-$mode-$index.png", actualBitmap)
                                        saveNativeParityBitmap(directory, "fresh-$mode-$index.png", expectedBitmap)
                                    }
                                } finally { expectedBitmap.recycle() }
                            } finally { actualBitmap.recycle() }
                        }
                        entry.put("passed", equal)
                    } catch (error: Throwable) { sampleFailure = error }
                    finally { drawn.countDown() }
                }
            }
            var roundFailure: Throwable? = null
            try {
                main {
                    fresh.bind()
                    fresh.view.layoutDirection = retained.itemView.layoutDirection
                    fresh.view.measure(View.MeasureSpec.makeMeasureSpec(retained.itemView.width, View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
                    fresh.view.layout(0, 0, fresh.view.measuredWidth, fresh.view.measuredHeight)
                    viewport.addOnChildAttachStateChangeListener(attachListener)
                    current.board.viewTreeObserver.addOnDrawListener(drawListener)
                    // Check again in the same main-thread turn as the scroll request.
                    checkCleanCache()
                    manager.scrollToPositionWithOffset(position(SettingsSectionKey.RECORDING), recordingOffset)
                }
                check(drawn.await(5, TimeUnit.SECONDS)) { "No first Recording draw after $mode" }
                sampleFailure?.let { throw it }
                main {
                    check(settings.owner.state == draft && settings.owner.autoStartChoice == autoStart)
                    check(AutoStartAfterRestart.policy.revision() == autoStartRevision && switchActions == 0)
                    check(preferences.all == originalPreferences && preferenceChanges.get() == 0)
                }
            } catch (error: Throwable) { roundFailure = error }
            finally {
                roundFailure = captureNativeParityCleanup(roundFailure) { main {
                    viewport.removeOnChildAttachStateChangeListener(attachListener)
                    if (current.board.viewTreeObserver.isAlive) current.board.viewTreeObserver.removeOnDrawListener(drawListener)
                } }
                roundFailure = captureNativeParityCleanup(roundFailure) { main { fresh.close(); freshSection = null } }
                roundFailure = captureNativeParityCleanup(roundFailure) { main {
                    // Resume ordinary advancement after the first-draw evidence, including a failing old-source round.
                    controls.forEach { motion(it).doFrame(System.nanoTime()) }
                } }
            }
            roundFailure?.let { throw it }
            await("settled $mode before next round") { controls.all { !themeState(motion(it)).running } }
        }
        result.put("retained_row_actions", switchActions).put("observed_preference_changes", preferenceChanges.get())
            .put("passed", (0 until rounds.length()).all { rounds.getJSONObject(it).getBoolean("passed") })
        check(result.getBoolean("passed")) { "Cached Recording first-draw colors differ from fresh controls; see both theme rounds" }
    } catch (error: Throwable) { failure = error }
    finally {
        originalCallbacks.forEach { (row, callback) ->
            failure = captureNativeParityCleanup(failure) { main { callbackField.set(row, callback) } }
        }
        originalScales.forEach { (motion, scale) ->
            failure = captureNativeParityCleanup(failure) { main { themeField(motion, "durationScale").set(motion, scale) } }
        }
        failure = captureNativeParityCleanup(failure) { main { freshSection?.close() } }
        failure = captureNativeParityCleanup(failure) { main { current.close() } }
        failure = captureNativeParityCleanup(failure) { current.awaitClosed() }
        failure = captureNativeParityCleanup(failure) { main {
            preferences.unregisterOnSharedPreferenceChangeListener(preferenceListener)
            check(preferences.all == originalPreferences && preferenceChanges.get() == 0)
            check(processCommittedTheme.state.value == committedTheme && current.forbiddenCalls == 0 && switchActions == 0)
        } }
        if (failure != null) result.put("passed", false).put("failure", failure!!.stackTraceToString())
    }
    failure?.let { throw it }
}

/** Actual Android producer/consumer check; expected RGB never comes from the Reverb producer. */
private fun verifyReverbSurfaceProducer(context: Context, result: JSONObject) {
    val rows = JSONArray()
    result.put("samples", rows)
    var oldTransparentFailures = 0
    fun verifyModal(label: String, source: Int, actual: Int, grain: Float) {
        val amount = .08f
        val alpha = Color.alpha(source)
        // Current modal policy returns the exact background role, without a black lift.
        // Keep the retired blend only as a historical alpha-mutation negative control.
        val expectedRgb = source and 0x00ffffff
        val expected = source
        val old = ColorUtils.blendARGB(source, Color.BLACK, amount)
        val range = NoiseDrawable(actual).apply { setNoiseIntensity(grain) }.paintedTextSurface
        val oldRange = NoiseDrawable(old).apply { setNoiseIntensity(grain) }.paintedTextSurface
        rows.put(JSONObject().put("label", label).put("source_argb", source.toLong() and 0xffffffffL)
            .put("policy", "background_identity").put("old_blend_amount", amount.toDouble())
            .put("grain_intensity", grain.toDouble()).put("expected_rgb", expectedRgb).put("expected_alpha", alpha)
            .put("expected_argb", expected.toLong() and 0xffffffffL)
            .put("actual_argb", actual.toLong() and 0xffffffffL)
            .put("old_blend_argb", old.toLong() and 0xffffffffL)
            .put("known_surface", range != 0L).put("old_known_surface", oldRange != 0L))
        check(actual == expected && Color.alpha(actual) == alpha) { "$label: producer changed RGB or source alpha" }
        if (alpha == 255) {
            check(range != 0L && (range ushr 56).toInt() == 255 && (range.toInt() ushr 24) == 255) {
                "$label: opaque producer became an unknown surface"
            }
        } else check(range == 0L) { "$label: translucent producer claimed an opaque surface" }
        if (alpha in listOf(0, 1, 127)) {
            check(Color.alpha(old) != alpha && old != expected) { "$label: old transparency mutation did not reproduce" }
            oldTransparentFailures++
        }
    }
    for ((name, sdk) in listOf("fallback28" to 28, "device" to Build.VERSION.SDK_INT)) {
        for (dark in listOf(false, true)) {
            // Retain one resource snapshot for both resolvers, avoiding a changing system palette.
            val resources = HashMap<Int, Int>()
            val readColor: (Int) -> Int = { id ->
                check(sdk >= 31 || id == NativeUiR.color.native_default_light_background ||
                    id == NativeUiR.color.native_default_light_foreground || id == NativeUiR.color.native_default_light_popup) {
                    "Fallback palette requested unexpected resource $id"
                }
                resources.getOrPut(id) { context.resources.getColor(id, context.theme) }
            }
            val source = NativeColorScheme.resolve(dark, sdk, readColor)
            val actual = ReverbDesign.appearance.colorScheme.resolve(dark, sdk, readColor)
            val label = "$name-sdk$sdk-${if (dark) "dark" else "light"}"
            val backdrop = if (dark) context.getColor(NativeUiR.color.native_default_amoled_background)
                else readColor(NativeUiR.color.native_default_light_background)
            val expected = if (dark) NativeDarkSurfacePreset.Amoled.colorScheme().resolve(true, sdk, readColor)
                else {
                    val foreground = readColor(NativeUiR.color.native_default_light_foreground)
                    val popup = readColor(NativeUiR.color.native_default_light_popup)
                    source.copy(background = backdrop, surface = backdrop, surfaceDim = backdrop,
                        surfaceBright = popup, surfaceVariant = foreground,
                        surfaceContainerLowest = popup, surfaceContainerLow = foreground,
                        surfaceContainer = foreground, surfaceContainerHigh = foreground,
                        surfaceContainerHighest = foreground,
                        surfacePreset = NativeGrainPreset(backgroundGrain = .22f, foregroundGrain = .20f),
                        popupFillOverride = popup)
                }
            check(actual == expected) { "$label: default appearance palette differs from the shared policy" }
            // The full value equality above already checked the resolved preset; avoid its internal getter.
            val grain = if (dark) NativeDarkSurfacePreset.Amoled.backgroundGrain else .22f
            for ((role, color) in listOf("background" to actual.background, "surface" to actual.surface)) {
                val range = NoiseDrawable(color).apply { setNoiseIntensity(grain) }.paintedTextSurface
                check(color == backdrop && Color.alpha(color) == 255 && range != 0L) {
                    "$label-$role: shared backdrop must be opaque and sampleable"
                }
                rows.put(JSONObject().put("label", "$label-$role")
                    .put("expected_argb", backdrop.toLong() and 0xffffffffL)
                    .put("actual_argb", color.toLong() and 0xffffffffL)
                    .put("grain_intensity", grain.toDouble()).put("known_surface", true))
            }
            for (alpha in listOf(0, 1, 127, 254, 255)) {
                val modalSource = (actual.background and 0x00ffffff) or (alpha shl 24)
                verifyModal("$label-modal-alpha$alpha", modalSource,
                    ReverbDesign.modalSurface(actual.copy(background = modalSource)), grain)
            }
        }
    }
    check(rows.length() == 28 && oldTransparentFailures == 12)
    result.put("old_transparent_failures", oldTransparentFailures)
        .put("passed", true)
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
    val editor = ReverbRetentionInputView(host, light).apply {
        // This isolated geometry fixture measures the editor directly. TextView's
        // later RTL relayout still requires the parameters a real parent supplies.
        layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT)
    }
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

        // The nonlinear tables leave 34sp unchanged between scales 1 and 1.5. A changed
        // font scale must invalidate geometry even on that plateau; it need not grow text.
        val changedScale = ReverbViewEnvironment(Density(dark.textDensity.density, dark.textDensity.fontScale * 1.4f),
            dark.sharedFontResolver, dark.palette)
        bind(changedScale)
        measureAndPlace()
        val changedSpan = lineHeightSpan()
        val changedSize = editor.textSize
        val expectedChangedSize = with(changedScale.textDensity) {
            changedScale.appearance.editorVisuals.largeValueText.fontSizeSp.sp.toPx()
        }
        check(changedSpan !== originalSpan && kotlin.math.abs(changedSize - expectedChangedSize) < .001f) {
            "Changed font scale did not rebind geometry: size=$changedSize expected=$expectedChangedSize"
        }
        // Scale 2 crosses the large-text plateau. Keep a real size-change assertion too.
        val larger = ReverbViewEnvironment(Density(dark.textDensity.density,
            maxOf(2f, changedScale.textDensity.fontScale * 1.4f)), dark.sharedFontResolver, dark.palette)
        bind(larger)
        measureAndPlace()
        val largerSpan = lineHeightSpan()
        check(largerSpan !== changedSpan && editor.textSize > changedSize) {
            "Larger font scale did not replace geometry: size=${editor.textSize} previous=$changedSize"
        }

        editor.layoutDirection = View.LAYOUT_DIRECTION_RTL
        bind(larger)
        measureAndPlace()
        check(editor.layoutDirection == View.LAYOUT_DIRECTION_RTL && lineHeightSpan() !== largerSpan)
        check(editor.selectionStart == 1 && editor.selectionEnd == 3)
        return JSONObject().put("name", "retention_palette_reuses_native_geometry")
            .put("light_ink", originalInk).put("dark_ink", editor.currentTextColor)
            .put("original_text_size_px", originalSize.toDouble()).put("larger_text_size_px", editor.textSize.toDouble())
            .put("original_font_scale", light.textDensity.fontScale.toDouble())
            .put("changed_font_scale", changedScale.textDensity.fontScale.toDouble())
            .put("changed_text_size_px", changedSize.toDouble())
            .put("larger_font_scale", larger.textDensity.fontScale.toDouble())
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
        // The toolbar fill is owned by a retained binding, not a field of type
        // ViewColorTransition on a View. Leaving it on Choreographer mixes different
        // background frames between the underlay, glyph capture and range assertion.
        val toolbarChrome = themeField(ReverbTitledTopBarLayout::class.java, "chrome").get(settings.topBar)
        hold(themeField(NativeBackdropBinding::class.java, "motion").get(toolbarChrome) as ViewColorTransition)
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
    fun surfaceRanges(): LongArray = targets.map(::surface).toLongArray()
    fun checkSurfaceRanges(expected: LongArray) {
        val actual = surfaceRanges()
        check(expected.contentEquals(actual)) {
            "Settings painted surfaces advanced during a deterministic capture: " +
                targets.indices.filter { expected[it] != actual[it] }.joinToString { label(targets[it]) }
        }
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
        val cursor = themeField(smallthingz.ui.components.NativeCaretEditText::class.java, "cursor").get(view)
        if (android.os.Build.VERSION.SDK_INT >= 29) check(view.textCursorDrawable === cursor)
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
