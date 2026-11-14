package app.smallthingz.reverb

import smallthingz.ui.components.NativeModalWindowRuntime

import android.app.Application
import android.app.Instrumentation
import android.content.res.Configuration
import android.graphics.Rect
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.FrameLayout
import androidx.compose.ui.unit.Density
import app.smallthingz.reverb.nativeui.*
import smallthingz.ui.text.view.NativeMergedRowAccessibilityHost
import smallthingz.ui.text.view.NativePlainTextViewGeometry
import smallthingz.ui.text.view.NativeSourceAccessibilityNode
import smallthingz.ui.text.view.NativeTextSemanticsClip
import smallthingz.ui.text.view.NativeTextSemanticsClipPolicy
import smallthingz.ui.text.view.nativeMergedRoleBoundsInScreen
import smallthingz.ui.geometry.Offset
import smallthingz.ui.geometry.Rect as ComposeRect
import java.io.File
import java.util.Locale
import kotlin.math.roundToInt
import org.json.JSONArray
import org.json.JSONObject

/** Current rendered permission rows and Library selection actions; all callbacks are forbidden. */
internal fun verifyNativeUiConsistencyCurrent(instrumentation: Instrumentation, report: StringBuilder) {
    val context = instrumentation.targetContext
    check(context.packageName == "app.smallthingz.reverb.uitheme.reliability")
    check(context.applicationContext.javaClass == Application::class.java)
    val preferences = getRecorderPreferences(context).all.toMap()
    val directory = File(context.filesDir, "native-ui-consistency-current-${System.currentTimeMillis()}")
    check(directory.mkdir())
    val scenes = JSONArray()
    val result = JSONObject().put("selector", "native_ui_consistency_current")
        .put("passed", false).put("historical_parity_claim", false).put("scenes", scenes)
        .put("unqualified", JSONArray(listOf("physical touch", "TalkBack delivery", "system permission dialogs",
            "compact Save-tile/notification wheel; owner initialization reads capacity preferences")))
    writeQaEvidenceAtomically(File(directory, "result.json")) { it.write(result.toString(2).toByteArray()) }
    val host = launchCurrentUiSmokeActivity(instrumentation)
    val runtime = NativeModalWindowRuntime(host).apply { setStarted(true) }
    var failure: Throwable? = null
    fun <T> main(action: () -> T): T = nativeParityOnMain(instrumentation, action)
    fun descendants(root: View): Sequence<View> = sequence {
        yield(root)
        if (root is ViewGroup) for (index in 0 until root.childCount) yieldAll(descendants(root.getChildAt(index)))
    }
    fun capture(name: String, root: View, dark: Boolean) {
        awaitNativeParityDraw(instrumentation, root)
        val bitmap = captureNativeParityContent(instrumentation, host) { root }
        try { saveNativeParityBitmap(directory, "$name.png", bitmap) } finally { bitmap.recycle() }
        scenes.put(JSONObject().put("name", name).put("png", "$name.png")
            .put("dark", dark).put("width_dp", 320).put("font_scale", 1.3))
    }
    try {
        awaitNativeParityDraw(instrumentation, host.fixtureRoot)
        val densityPx = context.resources.displayMetrics.density
        val width = (320 * densityPx).toInt()
        check(width <= main { host.fixtureRoot.width }) { "Native parity host is narrower than 320dp" }
        val storageMeasurements = JSONArray()
        result.put("storage_measurements", storageMeasurements)
        for (widthDp in listOf(220, 320)) for (scale in listOf(1f, 1.3f, 2f)) for (rtl in listOf(false, true)) {
            main {
                val config = Configuration(context.resources.configuration).apply {
                    setLocale(Locale.ENGLISH)
                    fontScale = scale
                }
                val themed = context.createConfigurationContext(config)
                val density = Density(themed.resources.displayMetrics.density, scale)
                val palette = ReverbPalette.resolve(themed, false)
                val environment = nativeParityEnvironment(themed, density, palette)
                val forbidden = { error("Storage measurement invoked an application action") }
                val section = SettingsStorageSection(themed, SettingsStorageState(
                    pathText = "/recordings", hasCustomDirectory = false, canMove = false,
                    settingsPersisting = false, allFilesAvailable = true, allFilesAllowed = false,
                    audioReadAvailable = true, audioReadAllowed = false), environment,
                    { host.fixtureRoot }, forbidden, forbidden, forbidden, forbidden, forbidden)
                try {
                    val expectedDirection = if (rtl) View.LAYOUT_DIRECTION_RTL else View.LAYOUT_DIRECTION_LTR
                    section.layoutDirection = expectedDirection
                    host.fixtureRoot.addView(section, FrameLayout.LayoutParams((widthDp * density.density).toInt(), -2))
                    section.measure(View.MeasureSpec.makeMeasureSpec((widthDp * density.density).toInt(), View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
                    section.layout(0, 0, section.measuredWidth, section.measuredHeight)
                    for (label in listOf(R.string.settings_audio_read_missing, R.string.settings_all_files_optional)) {
                        val title = descendants(section).filterIsInstance<smallthingz.ui.text.view.ParagraphView>()
                            .single { it.getText().toString() == themed.getString(label) }
                        val row = title.parent as ViewGroup
                        val action = (0 until row.childCount).map(row::getChildAt)
                            .filterIsInstance<SettingsCalibrationActionView>().single()
                        // Independently measure the inline candidate with the same text/typography.
                        // Final title geometry may already be stacked and cannot establish inline fit.
                        fun px(dp: Int) = (dp * density.density).roundToInt()
                        val icon = row.getChildAt(0)
                        val available = (row.width - 2 * px(15)).coerceAtLeast(0)
                        val firstGap = px(12).coerceAtMost((available - icon.width).coerceAtLeast(0))
                        val secondGap = px(12).coerceAtMost((available - icon.width - firstGap - action.width).coerceAtLeast(0))
                        val inlineWidth = (available - icon.width - firstGap - action.width - secondGap).coerceAtLeast(0)
                        val inlineTitle = environment.paragraph(themed, title.getText().toString(), ReverbTextRole.BODY_MEDIUM)
                        inlineTitle.layoutDirection = expectedDirection
                        inlineTitle.measure(View.MeasureSpec.makeMeasureSpec(inlineWidth, View.MeasureSpec.EXACTLY),
                            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
                        val inlineLines = requireNotNull(inlineTitle.currentParagraph).lineCount
                        val inlineHeight = inlineTitle.measuredHeight
                        val inlineFits = inlineWidth > 0 && (inlineLines <= 2 || inlineHeight <= action.height)
                        val entry = JSONObject().put("width_dp", widthDp).put("font_scale", scale)
                            .put("rtl", rtl).put("label", title.getText().toString())
                            .put("title_width_px", title.width).put("title_height_px", title.height)
                            .put("title_intrinsic_width_px", title.maxIntrinsicTextWidth())
                            .put("action_width_px", action.width).put("action_height_px", action.height)
                            .put("row_direction", row.layoutDirection).put("title_direction", title.layoutDirection)
                            .put("action_direction", action.layoutDirection).put("action_label_direction", action.textView.layoutDirection)
                            .put("stacked", action.top >= title.bottom)
                            .put("inline_width_px", inlineWidth).put("inline_height_px", inlineHeight)
                            .put("inline_line_count", inlineLines).put("inline_fits", inlineFits)
                        storageMeasurements.put(entry)
                        check(section.isAttachedToWindow && row.isAttachedToWindow && title.isAttachedToWindow && action.isAttachedToWindow) {
                            "Storage measurement must use the attached native hierarchy: $entry"
                        }
                        check(listOf(section, row, title, action, action.textView).all { it.layoutDirection == expectedDirection }) {
                            "Permission descendants did not resolve the requested direction: $entry"
                        }
                        check(title.width > 0 && title.height > 0) { "Missing permission title: $entry" }
                        check(title.left >= 0 && title.right <= row.width && title.top >= 0 && title.bottom <= row.height) {
                            "Permission title outside its row: $entry"
                        }
                        check(action.width >= 48 * density.density - 1 && action.height >= 48 * density.density - 1) {
                            "Permission action below 48dp: $entry"
                        }
                        check(action.left >= 0 && action.right <= row.width && action.top >= 0 && action.bottom <= row.height) {
                            "Permission action outside its row: $entry"
                        }
                        check(action.top >= title.bottom || action.right <= title.left || title.right <= action.left) {
                            "Permission title and action overlap: $entry"
                        }
                        check((action.top < title.bottom) == inlineFits) {
                            "Permission row disagrees with independently measured inline fit: $entry"
                        }
                    }
                } finally {
                    try { section.close() } finally { host.fixtureRoot.removeView(section) }
                }
            }
        }
        for (dark in listOf(false, true)) {
            val config = Configuration(context.resources.configuration).apply {
                uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
                    if (dark) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
                fontScale = 1.3f
            }
            val themed = context.createConfigurationContext(config)
            val density = Density(themed.resources.displayMetrics.density, config.fontScale)
            val palette = ReverbPalette.resolve(themed, dark)
            val environment = nativeParityEnvironment(themed, density, palette)
            val forbidden = { error("Current UI fixture invoked a forbidden application action") }

            val permissionState = OnboardingPageContentState(
                permissions = OnboardingPermissionStatus(
                    microphoneAllowed = true, storageRequired = true, storageAllowed = false,
                    recoveryRequired = true, recoveryAllowed = false,
                    notificationPermissionRequired = true, notificationsAllowed = true,
                    batteryOptimizationAllowed = true, interactionEnabled = true,
                    allFilesAvailable = true, allFilesAllowed = false,
                ),
                oneShotEnabled = true, loopingEnabled = true, outputCompression = true,
                skipSilence = false, compressionAvailable = true, silenceValid = true,
                controlsEnabled = true, interactionEnabled = true,
            )
            lateinit var permissionScreen: NativeOnboardingScreenView
            main {
                host.fixtureRoot.setBackgroundColor(palette.background)
                permissionScreen = NativeOnboardingScreenView.create(themed, environment, permissionState,
                    host, OnboardingPageActions(
                        requestPermission = { forbidden() }, oneShotChanged = { forbidden() },
                        loopingChanged = { forbidden() }, compressionChanged = { forbidden() },
                        skipSilenceChanged = { forbidden() }, pageChanged = {},
                        compressionLevelChanged = { forbidden() }), forbidden, runtime.scope,
                    host.onBackPressedDispatcher,
                    restored = NativeOnboardingViewportSavedState(page = 0), observeWindowInsets = false)
                host.fixtureRoot.addView(permissionScreen, FrameLayout.LayoutParams(width, -1))
            }
            awaitNativeParityDraw(instrumentation, permissionScreen)
            val placement = JSONObject().put("dark", dark)
            val placements = result.optJSONArray("permission_viewports") ?: JSONArray().also {
                result.put("permission_viewports", it)
            }
            placements.put(placement)
            val targetLabels = listOf(R.string.storage_settings_title,
                R.string.settings_audio_read_missing, R.string.settings_all_files_optional).map { themed.getString(it) }
            val viewport = main {
                descendants(permissionScreen).filterIsInstance<OnboardingPageContentView>()
                    .single { it.page == 0 && it.isAttachedToWindow }.sourceVerticalViewport
            }
            val targets = main {
                val paragraphs = descendants(viewport).filterIsInstance<smallthingz.ui.text.view.ParagraphView>().toList()
                targetLabels.map { text -> paragraphs.single { it.getText().toString() == text } }
            }
            fun bounds(rect: Rect) = JSONArray(listOf(rect.left, rect.top, rect.right, rect.bottom))
            main {
                val rectangles = targets.map { title -> Rect(0, 0, title.width, title.height).also {
                    viewport.offsetDescendantRectToMyCoords(title, it)
                } }
                val top = rectangles.minOf { it.top }
                val bottom = rectangles.maxOf { it.bottom }
                val beforeScroll = viewport.state.value
                val requested = (beforeScroll + (top + bottom - viewport.height) / 2)
                    .coerceIn(0, viewport.state.maxValue)
                placement.put("page", 0).put("viewport_width", viewport.width).put("viewport_height", viewport.height)
                    .put("scroll_before", beforeScroll).put("scroll_max", viewport.state.maxValue)
                    .put("target_union_top_before", top).put("target_union_bottom_before", bottom)
                    .put("target_union_fits", viewport.width > 0 && viewport.height > 0 &&
                        targets.all { it.width > 0 && it.height > 0 } && bottom - top <= viewport.height)
                    .put("requested_scroll", requested)
                // Rendering fixture placement, not accessibility-scroll or touch qualification.
                viewport.state.scrollBy((requested - beforeScroll).toFloat())
            }
            awaitNativeParityDraw(instrumentation, permissionScreen)
            val permissionLabels = main {
                val clip = Rect()
                val viewportVisible = viewport.getGlobalVisibleRect(clip)
                placement.put("scroll_after", viewport.state.value).put("viewport_visible", viewportVisible)
                    .put("viewport_global_bounds", bounds(clip))
                val rows = JSONArray().also { placement.put("titles", it) }
                targets.mapNotNull { title ->
                    val global = Rect()
                    val visible = title.isShown && title.getGlobalVisibleRect(global)
                    val clipped = Rect(global)
                    val clippedVisible = visible && viewportVisible && clipped.intersect(clip)
                    if (!clippedVisible) clipped.setEmpty()
                    rows.put(JSONObject().put("text", title.getText().toString())
                        .put("attached", title.isAttachedToWindow).put("shown", title.isShown)
                        .put("width", title.width).put("height", title.height)
                        .put("global_visible", visible).put("global_bounds", bounds(global))
                        .put("clipped_visible", clippedVisible).put("clipped_bounds", bounds(clipped)))
                    title.getText().toString().takeIf { title.isAttachedToWindow && clippedVisible }
                }.toSet()
            }
            // Retain the actual frame even when a subsequent visibility assertion fails.
            capture("${if (dark) "dark" else "light"}-missing-permissions", permissionScreen, dark)
            check(placement.getBoolean("target_union_fits")) { "Permission titles do not fit the measured viewport: $placement" }
            check(themed.getString(R.string.storage_settings_title) in permissionLabels) { "Missing Storage: $placement" }
            check(themed.getString(R.string.settings_audio_read_missing) in permissionLabels) { "Missing audio access: $placement" }
            check(themed.getString(R.string.settings_all_files_optional) in permissionLabels) { "Missing all-files access: $placement" }
            for ((allFilesAllowed, overlayAllowed) in listOf(false to false, true to false, true to true,
                false to true, false to false)) {
                main { permissionScreen.bind(permissionState.copy(permissions = permissionState.permissions.copy(
                    allFilesAllowed = allFilesAllowed, overlayAllowed = overlayAllowed))) }
                awaitNativeParityDraw(instrumentation, permissionScreen)
                main {
                    val labels = descendants(permissionScreen).filterIsInstance<smallthingz.ui.text.view.ParagraphView>()
                        .filter { it.isShown }.map { it.getText().toString() }.toSet()
                    check((themed.getString(R.string.settings_all_files_optional) in labels) == !allFilesAllowed)
                    check((themed.getString(R.string.onboarding_restart_resume_title) in labels) == !overlayAllowed)
                }
            }
            scenes.put(JSONObject().put("name", "${if (dark) "dark" else "light"}-onboarding-grant-revoke")
                .put("all_files_and_overlay_absent_iff_granted", true).put("permission_activity_launched", false))
            main { permissionScreen.close(); host.fixtureRoot.removeView(permissionScreen) }

            val storageState = SettingsStorageState(
                pathText = "/recordings", hasCustomDirectory = false, canMove = false,
                settingsPersisting = false, allFilesAvailable = true, allFilesAllowed = false,
                audioReadAvailable = true, audioReadAllowed = false)
            val storageSection = main {
                SettingsStorageSection(themed, storageState, environment,
                    { host.fixtureRoot }, forbidden, forbidden, forbidden, forbidden, forbidden)
            }
            val storageFrame = main {
                FrameLayout(themed).apply {
                    setBackgroundColor(palette.background)
                    addView(storageSection, FrameLayout.LayoutParams(width, -2))
                    host.fixtureRoot.addView(this, FrameLayout.LayoutParams(width, -2))
                }
            }
            awaitNativeParityDraw(instrumentation, storageFrame)
            val storageLabels = main {
                descendants(storageFrame).filterIsInstance<smallthingz.ui.text.view.ParagraphView>()
                    .map { it.getText().toString() }.toSet()
            }
            check(themed.getString(R.string.settings_audio_read_missing) in storageLabels)
            check(themed.getString(R.string.settings_all_files_optional) in storageLabels)
            capture("${if (dark) "dark" else "light"}-settings-storage-missing", storageFrame, dark)
            for (granted in listOf(true, false, true)) {
                main { storageSection.bind(storageState.copy(allFilesAllowed = granted), environment) }
                awaitNativeParityDraw(instrumentation, storageFrame)
                main {
                    val title = descendants(storageSection).filterIsInstance<smallthingz.ui.text.view.ParagraphView>()
                        .single { it.getText().toString() == themed.getString(R.string.settings_all_files_optional) }
                    check(title.isShown == !granted)
                    check(descendants(storageSection).any {
                        it.contentDescription == themed.getString(R.string.choose_folder) && it.isShown
                    })
                }
            }
            capture("${if (dark) "dark" else "light"}-settings-storage-granted", storageFrame, dark)
            main { storageSection.close(); host.fixtureRoot.removeView(storageFrame) }

            val toolbar = main {
                ReverbLibraryToolbarView(themed, environment, host.fixtureRoot).apply {
                    bind(LibraryToolbarState(selectedCount = 3, selectionHasBusyMutation = false,
                        acceptsDeletions = true, hasIncidents = false),
                        LibraryToolbarActions(forbidden, forbidden, forbidden, forbidden,
                            forbidden, forbidden, forbidden))
                }.also { host.fixtureRoot.addView(it, FrameLayout.LayoutParams(width,
                    (66 * density.density).toInt())) }
            }
            awaitNativeParityDraw(instrumentation, toolbar)
            val textOwner = main {
                NativeWindowTextAccessibilityOwner(host.fixtureRoot,
                    NativeTextSemanticsClipPolicy { NativeTextSemanticsClip.Unclipped })
            }
            val clipPolicy = NativeTextSemanticsClipPolicy { NativeTextSemanticsClip.Unclipped }
            val geometry = main { textOwner.contentGeometry(toolbar, clipPolicy) }
            fun sourceNode(view: View): NativeSourceAccessibilityNode {
                if (!view.isAttachedToWindow || !view.isShown || !view.isLaidOut) return NativeSourceAccessibilityNode.absent()
                val mapped = geometry.capture(view, Offset.Zero,
                    ComposeRect(0f, 0f, view.width.toFloat(), view.height.toFloat()))
                    ?: return NativeSourceAccessibilityNode.absent()
                return NativeSourceAccessibilityNode(true, false, true,
                    nativeMergedRoleBoundsInScreen(mapped, view.isLaidOut))
            }
            val toolbarAccessibility = main {
                descendants(toolbar).filterIsInstance<ReverbTopBarIconAction>()
                    .map { it.bindAccessibility(textOwner.textWindow, geometry, ::sourceNode) }.toList()
            }
            val expectedActionLabels = setOf(themed.getString(R.string.share_recording),
                themed.getString(R.string.delete_recording))
            val parentEvidence = JSONArray()
            val actionNodes = main {
                descendants(toolbar).mapNotNull { view ->
                    if (view !is NativeMergedRowAccessibilityHost || view !is ViewGroup) return@mapNotNull null
                    val parent = JSONObject().put("view_class", view.javaClass.name)
                    parentEvidence.put(parent)
                    val info = view.createAccessibilityNodeInfo()
                    parent.put("present", info != null)
                    if (info == null) return@mapNotNull null
                    try {
                        // The clickable parent advertises the Icon's description as a real child.
                        val advertised = ArrayList<View>().also(view::addChildrenForAccessibility)
                        val children = advertised.mapNotNull childNode@ { child ->
                            val childInfo = child.createAccessibilityNodeInfo() ?: return@childNode null
                            try {
                                val childBounds = Rect().also(childInfo::getBoundsInScreen)
                                JSONObject().put("label", (childInfo.contentDescription ?: childInfo.text)?.toString().orEmpty())
                                    .put("visible", childInfo.isVisibleToUser)
                                    .put("bounds", childBounds.toShortString())
                                    .put("width_px", childBounds.width()).put("height_px", childBounds.height())
                            } finally { @Suppress("DEPRECATION") childInfo.recycle() }
                        }
                        val labels = children.filter { it.getString("label") in expectedActionLabels }
                        val bounds = Rect().also(info::getBoundsInScreen)
                        parent.put("parent_label", (info.contentDescription ?: info.text)?.toString().orEmpty())
                            .put("children", JSONArray(children)).put("matching_label_count", labels.size)
                            .put("clickable", info.isClickable).put("enabled", info.isEnabled)
                            .put("visible", info.isVisibleToUser).put("bounds", bounds.toShortString())
                            .put("width_px", bounds.width()).put("height_px", bounds.height())
                            .put("width_dp", bounds.width() / density.density)
                            .put("height_dp", bounds.height() / density.density)
                        val label = labels.singleOrNull() ?: return@mapNotNull null
                        parent.put("label", label.getString("label"))
                            .put("label_visible", label.getBoolean("visible"))
                            .put("label_width_px", label.getInt("width_px"))
                            .put("label_height_px", label.getInt("height_px"))
                    } finally { @Suppress("DEPRECATION") info.recycle() }
                }.toList()
            }
            val minimumActionPixels = (48f * density.density).roundToInt()
            scenes.put(JSONObject().put("name", "${if (dark) "dark" else "light"}-grouped-selection")
                .put("png", "${if (dark) "dark" else "light"}-grouped-selection.png")
                .put("dark", dark).put("width_dp", 320).put("font_scale", 1.3)
                .put("minimum_action_pixels", minimumActionPixels)
                .put("observed_parent_nodes", parentEvidence)
                .put("separate_action_nodes", JSONArray(actionNodes)))
            // Capture the actual toolbar even if exposure or hit-geometry assertions fail.
            val bitmap = captureNativeParityContent(instrumentation, host) { toolbar }
            try { saveNativeParityBitmap(directory, "${if (dark) "dark" else "light"}-grouped-selection.png", bitmap) }
            finally { bitmap.recycle() }
            check(actionNodes.size == 2 && actionNodes.map { it.getString("label") }.toSet() == expectedActionLabels) {
                "Expected separately exposed Share and Delete parents with description children: $parentEvidence"
            }
            for (node in actionNodes) {
                check(node.getBoolean("clickable") && node.getBoolean("enabled") && node.getBoolean("visible"))
                check(node.getBoolean("label_visible") && node.getInt("label_width_px") > 0 && node.getInt("label_height_px") > 0)
                check(node.getInt("width_px") >= minimumActionPixels && node.getInt("height_px") >= minimumActionPixels) {
                    "Selection action below rounded 48dp minimum $minimumActionPixels px: $node"
                }
            }
            main {
                toolbarAccessibility.asReversed().forEach(AutoCloseable::close)
                toolbar.bind(LibraryToolbarState(0, false, true, false),
                    LibraryToolbarActions(forbidden, forbidden, forbidden, forbidden, forbidden, forbidden, forbidden))
                host.fixtureRoot.removeView(toolbar)
            }
        }
        check(getRecorderPreferences(context).all == preferences) { "Probe changed QA preferences" }
        result.put("passed", true)
    } catch (error: Throwable) {
        failure = error
        result.put("error", android.util.Log.getStackTraceString(error))
    } finally {
        try { nativeParityOnMain(instrumentation) { runtime.close(); host.finish() } }
        catch (cleanup: Throwable) { if (failure == null) failure = cleanup else failure?.addSuppressed(cleanup) }
        result.put("passed", result.getBoolean("passed") && failure == null)
            .put("failure", failure?.stackTraceToString() ?: JSONObject.NULL)
        writeQaEvidenceAtomically(File(directory, "result.json")) { it.write(result.toString(2).toByteArray()) }
    }
    failure?.let { throw it }
    report.appendLine("PASS native_ui_consistency_current isolated rendered UI; evidence ${directory.absolutePath}")
}
