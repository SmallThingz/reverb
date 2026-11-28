package app.smallthingz.reverb

import android.app.Activity
import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.view.Choreographer
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.lifecycle.Lifecycle
import androidx.core.graphics.ColorUtils
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import app.smallthingz.reverb.nativeui.*
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import smallthingz.ui.components.NativeDurationWheelView
import smallthingz.ui.components.NativeDarkSurfacePreset
import smallthingz.ui.components.NativeGroupLayout
import smallthingz.ui.components.NativeMaterialButtonView
import smallthingz.ui.components.NativeMaterialSwitchView
import smallthingz.ui.components.NativePairedFieldLayout
import smallthingz.ui.components.NativeToggleRow
import smallthingz.ui.common.DropdownContent
import smallthingz.ui.controls.textSurfaceRange
import smallthingz.ui.graphics.Color as SurfaceColor
import smallthingz.ui.graphics.NoiseDrawable
import smallthingz.ui.graphics.NoiseTexture
import smallthingz.ui.graphics.compositeOver
import smallthingz.ui.graphics.toArgb
import smallthingz.ui.platform.AndroidUiFrameClock
import smallthingz.ui.reverb.ReverbEnvironment
import smallthingz.ui.reverb.ReverbTextSurfaceScope
import smallthingz.ui.reverb.settings.ReverbSettingsActionView
import smallthingz.ui.reverb.settings.ReverbAudioActions
import smallthingz.ui.reverb.settings.ReverbAudioChoices
import smallthingz.ui.reverb.settings.ReverbAudioOptions
import smallthingz.ui.reverb.settings.ReverbRecordingPresentation
import smallthingz.ui.reverb.settings.ReverbRecordingSettingsView
import smallthingz.ui.reverb.settings.ReverbSettingsSection
import smallthingz.ui.reverb.settings.ReverbSettingsSectionFactory
import smallthingz.ui.reverb.settings.ReverbSettingsSectionListView
import smallthingz.ui.text.view.ParagraphView
import java.io.File
import kotlin.coroutines.CoroutineContext
import kotlin.math.roundToInt

/** Actual app section factories/adapters and production recipes, not isolated palette propagation. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, shadows = [SettingsPublicationEnvironmentShadow::class])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class NativeSettingsForegroundCoverageTest {
    @Test fun legacyRecordingConstructorEndsAtNotificationWithoutAnExtraRowOrDivider() = Fixture().use { f ->
        fun option(label: String) = DropdownContent(label, 1, listOf(1, 2)) { it.toString() }
        val choices = ReverbAudioChoices(f.context, f.dark.theme,
            ReverbAudioOptions(option("Channels"), option("Format"), option("Rate"), option("Source"), option("Route")),
            ReverbAudioActions<Int, Int, Int, Int>({}, {}, {}, {}, {}), { f.root }, { true })
        try {
            val initialRows = descendants(choices.view).filter {
                it.layoutParams is NativeGroupLayout.LayoutParams
            }.count()
            val compression = View(f.context)
            val silence = View(f.context)
            val notification = View(f.context)
            // Deliberately use the published pre-merge signature (including its named parameters).
            val legacy = ReverbRecordingSettingsView(context = f.context, choices = choices,
                compression = compression, silenceDetection = silence, saveNotification = notification,
                initial = ReverbRecordingPresentation("Recording", null), theme = f.dark.theme)
            f.mount(legacy)
            val content = notification.parent as ViewGroup
            assertEquals(initialRows + 4, content.childCount) // Three supplied rows and the existing hidden warning.
            assertSame(notification, content.getChildAt(content.childCount - 1))
            assertSame(silence, content.getChildAt(content.childCount - 2))
            assertEquals(1, descendants(legacy).filterIsInstance<NativeGroupLayout>().count())
            assertEquals(1, (0 until content.childCount).count { content.getChildAt(it).visibility == View.GONE })
            legacy.bind(ReverbRecordingPresentation("Recording", "Warning"), f.light.theme)
            f.mount(legacy)
            assertSame(notification, content.getChildAt(content.childCount - 1))
            assertEquals(initialRows + 4, content.childCount)
        } finally { choices.close() }
    }

    @Test fun legacyAutoStartBindKeyRebindsOnlyTheRecordingOwnerWithoutAddingASection() = Fixture().use { f ->
        val binds = mutableMapOf<SettingsSectionKey, Int>()
        val created = mutableListOf<SettingsSectionKey>()
        val factory = ReverbSettingsSectionFactory { key ->
            created += key
            object : ReverbSettingsSection {
                override val view = View(f.context).apply { minimumHeight = f.dp(40) }
                override fun bind() { binds[key] = (binds[key] ?: 0) + 1 }
                override fun close() = Unit
            }
        }
        ReverbSettingsSectionListView(f.context, factory, f.dark, false).use { list ->
            for (restricted in listOf(false, true)) {
                list.bind(restricted, f.dark)
                f.mount(list)
                assertNotNull(list.viewport.findViewHolderForItemId(SettingsSectionKey.RECORDING.ordinal.toLong()))
                binds.clear()
                list.bindSection(SettingsSectionKey.AUTO_START)
                assertEquals(mapOf(SettingsSectionKey.RECORDING to 1), binds)
                binds.clear()
                list.bindSection(SettingsSectionKey.QUICK_TILES)
                assertEquals(mapOf(SettingsSectionKey.QUICK_TILES to 1), binds)
                assertEquals(SettingsSectionKey.visible(restricted).size, list.viewport.adapter!!.itemCount)
            }
            assertFalse(created.contains(SettingsSectionKey.AUTO_START))
        }
    }

    @Test fun movedAutoStartKeepsLoadingPermissionCopyAndRejectsThePreviousActivation() = Fixture().use { f ->
        f.owner.setActive(false)
        f.owner.setActive(true) // Retained values are visible, but fresh hydration owns readiness.
        val section = f.section(SettingsSectionKey.RECORDING)
        val title = descendants(section.view).filterIsInstance<ParagraphView>().single {
            it.getText() == f.context.getString(R.string.auto_start_title)
        }
        val row = ancestors(title).filterIsInstance<NativeToggleRow>().first()
        val toggle = descendants(row).filterIsInstance<NativeMaterialSwitchView>().single()
        assertFalse(row.isEnabled)
        assertFalse(row.performClick())
        assertFalse(f.owner.autoStartPermissionPending)

        f.hydrate()
        section.bind()
        assertFalse(BootResumeActivity.overlayAvailable(f.context))
        assertTrue(row.performClick()) // A real current action owns the permission request, not the global boot policy.
        section.bind()
        assertTrue(row.isEnabled)
        assertEquals(true, field(toggle, "checked"))
        assertTrue(descendants(row).filterIsInstance<ParagraphView>().any {
            it.getText() == f.context.getString(R.string.auto_start_permission_pending)
        })
        assertTrue(row.performClick()) // Cancel the pending enable using the actual OFF callback.
        assertFalse(f.owner.autoStartPermissionPending)
        section.bind()
        val oldActivation = f.owner.state.activation
        f.owner.setActive(false)
        f.owner.setActive(true)
        f.hydrate()
        assertNotSame(oldActivation, f.owner.state.activation)
        assertTrue(f.owner.featureInputReady())
        assertFalse(f.owner.acceptsInput(oldActivation))
        val before = f.owner.autoStartChoice
        val revision = AutoStartAfterRestart.policy.revision()
        assertTrue(row.performClick()) // Actual moved row still carries the old section activation.
        assertEquals(before, f.owner.autoStartChoice)
        assertEquals(revision, AutoStartAfterRestart.policy.revision())

        section.bind()
        assertTrue(row.performClick())
        assertTrue(f.owner.autoStartPermissionPending)
        assertFalse(f.owner.autoStartChoice.saving)
        assertEquals(revision, AutoStartAfterRestart.policy.revision())
        assertSame(row, ancestors(title).filterIsInstance<NativeToggleRow>().first())
    }

    @Test fun recordingFactoryPlacesAutoStartDirectlyAfterNotificationInsideTheSameSurface() = Fixture().use { f ->
        val section = f.section(SettingsSectionKey.RECORDING)
        val notification = descendants(section.view).filterIsInstance<SettingsNotificationSaveView>().single()
        val title = descendants(section.view).filterIsInstance<ParagraphView>().single {
            it.getText() == f.context.getString(R.string.auto_start_title)
        }
        val group = ancestors(notification).filterIsInstance<NativeGroupLayout>().first()
        assertSame(group, ancestors(title).filterIsInstance<NativeGroupLayout>().first())
        val autoRow = ancestors(title).first { it.parent === notification.parent }
        val content = notification.parent as ViewGroup
        assertEquals(content.indexOfChild(notification) + 1, content.indexOfChild(autoRow))
        assertEquals(notification.bottom, autoRow.top)
        assertTrue((autoRow.layoutParams as NativeGroupLayout.LayoutParams).dividerTop)
        assertEquals(1, descendants(section.view).filterIsInstance<NativeGroupLayout>().count())
        assertFalse(SettingsSectionKey.visible(false).contains(SettingsSectionKey.AUTO_START))
        assertFalse(SettingsSectionKey.visible(true).contains(SettingsSectionKey.AUTO_START))
        assertEquals(listOf("theme", "retention", "recording", "quick-save-tiles", "storage"),
            SettingsSectionKey.visible(false).map { it.sourceKey })
        assertEquals(3, SettingsSectionKey.RECORDING.ordinal)
        assertEquals(4, SettingsSectionKey.QUICK_TILES.ordinal)
        assertEquals(5, SettingsSectionKey.STORAGE.ordinal)
        val wheel = descendants(notification).filterIsInstance<NativeDurationWheelView>().single()
        f.rebind(section, f.light)
        f.rebind(section, f.dark)
        assertSame(content, autoRow.parent)
        assertSame(wheel, descendants(notification).filterIsInstance<NativeDurationWheelView>().single())
    }

    @Test fun bothAppRetentionEditorsShareVisibleForegroundGrainAndSurviveThemeAndModeChanges() = Fixture().use { f ->
        val section = f.section(SettingsSectionKey.RETENTION)
        val editors = descendants(section.view).filterIsInstance<SettingsRetentionInputView>().toList()
        assertEquals(2, editors.size)
        val fields = ancestors(editors[0]).filterIsInstance<NativePairedFieldLayout>().first()
        assertSame("Both actual editors belong to the same paired field surface", fields,
            ancestors(editors[1]).filterIsInstance<NativePairedFieldLayout>().first())
        val group = ancestors(fields).filterIsInstance<NativeGroupLayout>().first()
        assertEquals(setOf(f.context.getString(R.string.buffer_one_shot), f.context.getString(R.string.retention_loop_label)),
            editors.map { it.contentDescription.toString() }.toSet())
        val values = editors.map { it.text.toString() }
        val snapshot = f.owner.snapshot
        assertRenderedGrain("retention-time", f, section.view, group, centerStrip(group, f.dp(4), f.dp(8))) { env -> f.rebind(section, env) }
        assertEquals(values, editors.map { it.text.toString() })
        assertEquals(snapshot, f.owner.snapshot)
        val draft = f.owner.state.displayed
        f.owner.draft.edited(draft.copy(snapshot = draft.snapshot.copy(retentionMode = RetentionMode.SIZE)))
        f.rebind(section, f.dark)
        assertTrue(descendants(section.view).filterIsInstance<SettingsRetentionInputView>().zip(editors.asSequence()).all { (a, b) -> a === b })
        assertRenderedGrain("retention-size", f, section.view, group, centerStrip(group, f.dp(4), f.dp(8))) { env -> f.rebind(section, env) }
    }

    @Test fun appConfiguredCalibrationProgressButtonPaintsItsOwnGrainAndRetainsItsVisual() = Fixture().use { f ->
        val section = f.section(SettingsSectionKey.RECORDING)
        val action = descendants(section.view).filterIsInstance<ReverbSettingsActionView>().single {
            it.textView.getText() == f.context.getString(R.string.silence_calibrate)
        }
        val visual = descendants(action).filterIsInstance<NativeMaterialButtonView>().single()
        assertTrue("Fixture exercises the enabled calibration fill", visual.isEnabled)
        val snapshot = f.owner.snapshot
        // Draw this actual configured button over a flat underlay: parent grain cannot hide a missing bind.
        assertRenderedGrain("recording-calibration", f, section.view, visual, centerStrip(visual, f.dp(4), f.dp(8))) { env -> f.rebind(section, env) }
        assertSame(visual, descendants(action).filterIsInstance<NativeMaterialButtonView>().single())
        assertEquals(snapshot, f.owner.snapshot)
        assertFalse(f.owner.calibration.running)
    }

    @Test fun actualCreateTileActionAndExpandedWheelRevealTheRetainedQuickSaveGroupGrain() = Fixture().use { f ->
        // Seed only the storage read boundary. The app's real owner, create callback, morph and editor run below.
        @Suppress("UNCHECKED_CAST")
        val options = field(QuickSaveTileOptions, "mutableOptions") as MutableStateFlow<List<QuickSaveTileOption>?>
        @Suppress("UNCHECKED_CAST")
        val errors = field(QuickSaveTileOptions, "mutableError") as MutableStateFlow<String?>
        val previousOptions = options.value
        val previousError = errors.value
        val inputs = NativeQuickTilesInputs(true, true, true, 7L)
        val owner = NativeQuickSettingsTilesOwner(f.context, f.lifecycle, f.parked,
            QuickSettingsTilesEditorState(), inputs, { 7L }, { 7L }, { 7L }, { 7L })
        // Suppress the unrelated asynchronous disk refresh, not any view or action binding.
        setField(owner, "refreshKey", true to true)
        var view: SettingsQuickTilesView? = null
        try {
            options.value = emptyList()
            errors.value = null
            val tiles = SettingsQuickTilesView(f.context, owner, f.environment, { f.root }, {}, {}).also { view = it }
            f.mount(tiles)
            val group = descendants(tiles).filterIsInstance<NativeGroupLayout>().single()
            val create = descendants(tiles).filterIsInstance<NativeMaterialButtonView>().single {
                it.label.getText() == f.context.getString(R.string.tiles_ui_create)
            }
            fun bind(env: ReverbEnvironment) = f.rebindView(tiles, env) { tiles.bind(inputs, env) }
            assertRenderedGrain("quick-save-create", f, tiles, group, centerStrip(group, f.dp(4), f.dp(8)), ::bind)
            assertSame(create, descendants(tiles).filterIsInstance<NativeMaterialButtonView>().single {
                it.label.getText() == f.context.getString(R.string.tiles_ui_create)
            })
            assertTrue(create.performClick())
            val inlineOwner = checkNotNull(owner.inline)
            val request = checkNotNull(inlineOwner.capacityLoad.begin(7L))
            assertTrue(inlineOwner.capacityLoad.finish(request, 7L, TileCapacities(3600, 600.0, 300.0)))
            bind(f.dark) // Detached bind settles the morph without substituting a copied editor.
            val wheel = descendants(tiles).filterIsInstance<NativeDurationWheelView>().single()
            val wheelBounds = Rect(0, 0, wheel.width, wheel.height).also { group.offsetDescendantRectToMyCoords(wheel, it) }
            // Empty strip at the wheel's left edge: it must show the group's grain, not selector/text pixels.
            val patch = Rect(wheelBounds.left + 1, wheelBounds.centerY() - f.dp(12),
                wheelBounds.left + 4, wheelBounds.centerY() + f.dp(12))
            val target = inlineOwner.target
            val seconds = target.seconds
            assertRenderedGrain("quick-save-expanded", f, tiles, group, patch, ::bind)
            assertSame(inlineOwner, owner.inline)
            assertSame(target, inlineOwner.target)
            assertEquals(seconds, target.seconds)
            assertSame(wheel, descendants(tiles).filterIsInstance<NativeDurationWheelView>().single())
            assertSame(group, ancestors(wheel).filterIsInstance<NativeGroupLayout>().first())
        } finally {
            view?.close() ?: owner.close()
            options.value = previousOptions
            errors.value = previousError
        }
    }

    private fun assertRenderedGrain(name: String, f: Fixture, scene: View, target: View, patch: Rect,
                                    bind: (ReverbEnvironment) -> Unit) {
        awaitTexture()
        val shader = NoiseTexture.shader(NoiseDrawable.SEED_BACKGROUND)
        val grain = grainOwner(target)
        assertNotNull("Production bind created a retained grain layer", grain)
        val before = pixels(target, patch, f.environment.palette.field)
        screenshot(scene, "$name-dark", f.environment.palette.background)
        bind(f.darkFlat)
        val flat = pixels(target, patch, f.environment.palette.field)
        assertTrue("Visible foreground noise must change the actual blank painted patch",
            before.indices.count { before[it] != flat[it] } > before.size / 20)
        assertEquals("Patch excludes text, icons, borders and other textured child surfaces", 1, flat.toSet().size)
        bind(f.light)
        val lightPixels = pixels(target, patch, f.environment.palette.field)
        screenshot(scene, "$name-light", f.environment.palette.background)
        bind(f.lightFlat)
        val lightFlatPixels = pixels(target, patch, f.environment.palette.field)
        assertEquals("Light comparison patch excludes child paint", 1, lightFlatPixels.toSet().size)
        assertTrue("Light foreground grain changes the actual painted patch",
            lightPixels.indices.count { lightPixels[it] != lightFlatPixels[it] } > lightPixels.size / 20)
        bind(f.light)
        assertArrayEquals("Light rebind retains the deterministic texture", lightPixels,
            pixels(target, patch, f.environment.palette.field))
        assertSame("Theme changes reuse the retained grain layer", grain, grainOwner(target))
        if (target is NativeGroupLayout) {
            assertEquals("Light fill role is unchanged", f.environment.palette.field, target.paintedFillColor)
            assertEquals(f.environment.palette.field, lightFlatPixels.first())
            assertTrue(target.paintedTextSurface != textSurfaceRange(target.paintedFillColor))
            descendants(target).filterIsInstance<SettingsRetentionInputView>().forEach {
                assertTrue("Light-mode retention value stays readable",
                    lightPixels.all { pixel -> ColorUtils.calculateContrast(it.currentTextColor, pixel) >= 4.5 })
            }
        } else {
            val colors = f.environment.palette
            val expectedOpaqueFill = SurfaceColor(colors.primary)
                .copy(alpha = f.environment.appearance.calibration.actionOpacity)
                .compositeOver(SurfaceColor(colors.field)).toArgb()
            val actualPaint = NativeMaterialButtonView::class.java.getDeclaredField("paint").let {
                it.isAccessible = true; it.get(target) as Paint
            }
            assertEquals("Calibration binds the exact precomposed light-theme container", expectedOpaqueFill, actualPaint.color)
            assertEquals(255, Color.alpha(actualPaint.color))
            val renderedFill = lightFlatPixels.first()
            assertEquals("Flat calibration pixels match the opaque tinted fill", expectedOpaqueFill, renderedFill)
            assertTrue("Calibration paints a tinted fill distinct from the light group", renderedFill != colors.field)
            val ink = NativeMaterialButtonView::class.java.getDeclaredField("displayedInk").let {
                it.isAccessible = true; it.getInt(target)
            }
            assertEquals(255, Color.alpha(ink))
            assertTrue("Light-mode calibration label stays readable over grain",
                lightPixels.all { ColorUtils.calculateContrast(ink, it) >= 4.5 })
        }
        bind(f.dark)
        val restored = pixels(target, patch, f.environment.palette.field)
        assertArrayEquals("Same retained deterministic texture after theme round trip", before, restored)
        repeat(4) { bind(f.dark) }
        assertSame(grain, grainOwner(target))
        assertSame(shader, NoiseTexture.shader(NoiseDrawable.SEED_BACKGROUND))
        assertTrue(scene.isAttachedToWindow)
    }

    private class Fixture : LifecycleOwner, AutoCloseable {
        private val activity = Robolectric.buildActivity(Activity::class.java).setup().visible()
        val context = activity.get()
        override val lifecycle = LifecycleRegistry(this).apply { currentState = Lifecycle.State.RESUMED }
        val root: FrameLayout = object : FrameLayout(context), ReverbTextSurfaceScope {
            override fun hasWindowFocus() = true
        }.also { context.setContentView(it) }
        val parked = CoroutineScope(object : CoroutineDispatcher() {
            override fun dispatch(context: CoroutineContext, block: Runnable) = Unit
        })
        private val animations = CoroutineScope(Dispatchers.Main.immediate + AndroidUiFrameClock(Choreographer.getInstance()))
        val dark = ReverbEnvironment(context, true).forSettings()
        val darkFlat = dark.withPalette(dark.palette.copy(surfacePreset = NativeDarkSurfacePreset.Amoled.copy(foregroundGrain = 0f)))
        val light = ReverbEnvironment(context, false).forSettings()
        val lightFlat = light.withPalette(light.palette.copy(surfacePreset = null))
        var environment = dark
        val owner = NativeSettingsOwner(context, root, lifecycle, parked, true,
            isPanelActive = { true }, onThemePreview = {}, releaseInputFocus = {},
            launchDirectory = { error("Unexpected directory launch") }, onBack = {})
        private val owned = mutableListOf<NativeSettingsSection>()
        init { hydrate() }
        fun hydrate() {
            runBlocking {
                val values = resolveNativeSettingsRestoredDraft(SettingsUiDraft.normalized(SettingsSnapshot(
                    oneShotRetentionTime = 300, loopingRetentionTime = 600,
                    quickSaveEnabled = true, quickSaveSeconds = 30,
                    silenceGate = encodeSilenceGate(SilenceGateConfig(enabled = true)),
                )), listOf(InputRouteMode.AUTO))
                assertEquals(SettingsDraftHydrationResult.APPLIED, owner.draft.hydrate(
                    readDurable = { SettingsDraftBaseline(values, null) }, restoreFields = { it }))
            }
        }
        fun section(key: SettingsSectionKey): NativeSettingsSection {
            val factory = NativeSettingsSections(context, owner, { environment }, lifecycle, { root },
                animations, { false }, { null }, { _, _ -> }, {})
            return factory.create(key).also { owned += it; it.bind(); mount(it.view) }
        }
        fun rebind(section: NativeSettingsSection, env: ReverbEnvironment) = rebindView(section.view, env, section::bind)
        fun rebindView(view: View, env: ReverbEnvironment, bind: () -> Unit) {
            root.removeAllViews() // Settle color/expansion clocks without changing the retained component owners.
            environment = env
            bind()
            mount(view)
        }
        fun mount(view: View) {
            if (view.parent == null) root.addView(view, FrameLayout.LayoutParams(-1, -2))
            root.measure(exact(dp(420)), exact(dp(1800)))
            root.layout(0, 0, root.measuredWidth, root.measuredHeight)
        }
        fun dp(value: Int) = (value * context.resources.displayMetrics.density).roundToInt()
        override fun close() {
            try { owned.forEach { it.close() }; owner.close() }
            finally { parked.cancel(); animations.cancel(); activity.pause().stop().destroy() }
        }
    }

    companion object {
        private fun exact(size: Int) = View.MeasureSpec.makeMeasureSpec(size, View.MeasureSpec.EXACTLY)
        private fun descendants(view: View): Sequence<View> = sequence {
            yield(view)
            if (view is ViewGroup) for (i in 0 until view.childCount) yieldAll(descendants(view.getChildAt(i)))
        }
        private fun ancestors(view: View): Sequence<View> = generateSequence(view.parent as? View) { it.parent as? View }
        private fun centerStrip(view: View, top: Int, bottom: Int) = Rect(view.width / 2 - 16, top, view.width / 2 + 16, bottom)
        private fun field(owner: Any, name: String): Any? = owner.javaClass.getDeclaredField(name).let {
            it.isAccessible = true; it.get(owner)
        }
        private fun setField(owner: Any, name: String, value: Any) = owner.javaClass.getDeclaredField(name).let {
            it.isAccessible = true; it.set(owner, value)
        }
        private fun grainOwner(view: View): Any? {
            val type = if (view is NativeGroupLayout) NativeGroupLayout::class.java else NativeMaterialButtonView::class.java
            return type.getDeclaredField("grain").let { it.isAccessible = true; it.get(view) }
        }
        private fun awaitTexture() {
            NoiseTexture.prepare()
            val deadline = System.nanoTime() + 5_000_000_000L
            while (NoiseTexture.shader(NoiseDrawable.SEED_BACKGROUND) == null && System.nanoTime() < deadline) Thread.sleep(5)
            assertNotNull("Texture preparation completed", NoiseTexture.shader(NoiseDrawable.SEED_BACKGROUND))
        }
        private fun pixels(view: View, patch: Rect, underlay: Int): IntArray {
            assertTrue(view.width > 0 && view.height > 0)
            assertTrue(Rect(0, 0, view.width, view.height).contains(patch))
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            try {
                val canvas = Canvas(bitmap)
                canvas.drawColor(underlay)
                view.draw(canvas)
                return IntArray(patch.width() * patch.height()).also {
                    bitmap.getPixels(it, 0, patch.width(), patch.left, patch.top, patch.width(), patch.height())
                }
            } finally { bitmap.recycle() }
        }

        /** Optional peer-test captures; these do not substitute for final APK/device screenshots. */
        private fun screenshot(view: View, name: String, underlay: Int) {
            val directory = System.getProperty("reverb.settings.visual.output")?.let(::File) ?: return
            check(directory.isDirectory || directory.mkdirs())
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            try {
                val canvas = Canvas(bitmap)
                canvas.drawColor(underlay)
                view.draw(canvas)
                File(directory, "$name.png").outputStream().use {
                    check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
                }
            } finally { bitmap.recycle() }
        }
    }
}
