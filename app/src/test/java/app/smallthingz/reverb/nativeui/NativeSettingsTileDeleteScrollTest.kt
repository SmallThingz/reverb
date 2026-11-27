package app.smallthingz.reverb.nativeui

import android.app.Activity
import android.app.Application
import android.graphics.Rect
import android.os.Looper
import android.view.View
import android.widget.FrameLayout
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import app.smallthingz.reverb.*
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowViewRootImpl
import org.robolectric.util.ReflectionHelpers
import smallthingz.ui.components.NativeActionSheet
import smallthingz.ui.components.NativeColorScheme
import smallthingz.ui.components.NativeMaterialButtonView
import smallthingz.ui.input.phase.NativePointerLayerShape
import smallthingz.ui.reverb.ReverbEnvironment
import smallthingz.ui.scroll.NativeVerticalPhaseBinding
import smallthingz.ui.scroll.NativeVerticalViewport
import smallthingz.ui.text.font.createFontFamilyResolver
import smallthingz.ui.unit.Density
import java.time.Duration
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.coroutines.CoroutineContext

/** Real app caller wiring: no copied sheet construction or mutation admission policy. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, shadows = [SettingsPublicationEnvironmentShadow::class])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class NativeSettingsTileDeleteScrollTest {
    @Test fun actualTileDeleteCallerScrollsNaturalActionsAndExportsItsRoundedHitShape() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup().visible()
        val activity = controller.get()
        val lifecycleOwner = object : LifecycleOwner {
            override val lifecycle = LifecycleRegistry(this).apply { currentState = Lifecycle.State.RESUMED }
        }
        val root = FrameLayout(activity).apply { setViewTreeLifecycleOwner(lifecycleOwner) }
        activity.setContentView(root)
        val environment = ReverbEnvironment(Density(1f, 2f), createFontFamilyResolver(activity), NativeColorScheme.fallback(false))
        val parked = CoroutineScope(object : CoroutineDispatcher() {
            override fun dispatch(context: CoroutineContext, block: Runnable) = Unit
        })
        val target = QuickSettingsTileDeleteTarget(QuickSaveTileOption(0, "a".repeat(32)), 7L)
        val editor = QuickSettingsTilesEditorState().apply { deleting = target }
        val owner = NativeQuickSettingsTilesOwner(activity, lifecycleOwner.lifecycle, parked, editor,
            NativeQuickTilesInputs(true, true, true, 7L), { 7L }, { 7L }, { 7L }, { 7L })
        val caller = NativeSettingsTileDeleteSheet(root) { environment }
        try {
            caller.bind(owner, target)
            val dialog = field(caller, "dialog") as NativeActionSheet
            val binding = field(dialog, "contentScroll") as? NativeVerticalPhaseBinding
            assertNotNull("App caller must install the existing outer scroll owner", binding)
            val viewport = checkNotNull(binding).dragNode.view as NativeVerticalViewport
            val content = field(caller, "content") as QuickTileDeleteContent
            val actions = content.actions
            fun layoutViewport() {
                viewport.measure(exact(200), exact(120))
                viewport.layout(0, 0, 200, 120)
            }
            layoutViewport()
            assertEquals(120, viewport.height)
            assertTrue(viewport.state.maxValue > 0)
            val surface = actions.actionSurface
            val buttons = descendants(actions).filterIsInstance<NativeMaterialButtonView>()
            assertEquals(2, buttons.size)
            buttons.forEach { assertTrue(it.height >= 48) }
            assertEquals(viewport.getChildAt(0).height - viewport.height, viewport.state.maxValue)
            viewport.state.scrollBy(viewport.state.maxValue.toFloat())
            viewport.requestLayout()
            layoutViewport()
            val last = Rect(0, 0, buttons.last().width, buttons.last().height)
            viewport.offsetDescendantRectToMyCoords(buttons.last(), last)
            assertTrue("Final Delete target is reachable", last.top >= 0 && last.bottom <= viewport.height)
            // NativeSettingsTileDeleteSheet.hit(view) uses this exact interface while recursing.
            val shape: NativePointerLayerShape = actions
            for (direction in listOf(View.LAYOUT_DIRECTION_LTR, View.LAYOUT_DIRECTION_RTL)) {
                actions.layoutDirection = direction
                actions.measure(exact(700), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
                actions.layout(0, 0, 700, actions.measuredHeight)
                assertEquals(if (direction == View.LAYOUT_DIRECTION_RTL) 0 else 700 - surface.width, surface.left)
                assertFalse(shape.containsPointerLayer(surface.left + .5f, surface.top + .5f))
                assertTrue(shape.containsPointerLayer(surface.left + surface.width / 2f, surface.top + surface.height / 2f))
                assertFalse(shape.containsPointerLayer(350f, actions.height + 1f))
            }
            assertSame(target, editor.deleting) // Geometry never accepts the destructive operation.
        } finally {
            caller.close()
            owner.close()
            parked.cancel()
            controller.pause().stop().destroy()
        }
    }

    @Test fun deleteFollowsItsOwnWindowFocusWithoutAnOwnerRebind() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup().visible()
        val activity = controller.get()
        val lifecycleOwner = object : LifecycleOwner {
            override val lifecycle = LifecycleRegistry(this).apply { currentState = Lifecycle.State.RESUMED }
        }
        val root = FrameLayout(activity).apply { setViewTreeLifecycleOwner(lifecycleOwner) }
        activity.setContentView(root)
        val environment = ReverbEnvironment(Density(1f, 1f), createFontFamilyResolver(activity), NativeColorScheme.fallback(false))
        val continuations = LinkedBlockingQueue<Runnable>()
        val scope = CoroutineScope(object : CoroutineDispatcher() {
            override fun dispatch(context: CoroutineContext, block: Runnable) { continuations.add(block) }
        })
        val option = QuickSaveTileOption(0, "b".repeat(32))
        val target = QuickSettingsTileDeleteTarget(option, 7L)
        val editor = QuickSettingsTilesEditorState().apply { deleting = target }
        val owner = NativeQuickSettingsTilesOwner(activity, lifecycleOwner.lifecycle, scope, editor,
            NativeQuickTilesInputs(true, true, true, 7L), { 7L }, { 7L }, { 7L }, { 7L })
        val caller = NativeSettingsTileDeleteSheet(root) { environment }
        try {
            val prefs = getRecorderPreferences(activity)
            assertTrue(prefs.edit().putString(PrefKey.QUICK_SAVE_TILE_OPTIONS, encodeQuickSaveTileOptions(listOf(option))).commit())
            assertEquals(listOf(option), QuickSaveTileOptions.load(activity))

            // Only the dialog focus events can rebind Delete after this initial bind.
            caller.bind(owner, target)
            val dialog = field(caller, "dialog") as NativeActionSheet
            val content = field(caller, "content") as QuickTileDeleteContent
            val delete = descendants(content.actions).filterIsInstance<NativeMaterialButtonView>().single {
                it.label.getText() == activity.getString(R.string.delete_recording)
            }
            @Suppress("UNCHECKED_CAST")
            val queuedConfirm = field(delete, "action") as () -> Unit
            val main = shadowOf(Looper.getMainLooper())
            main.idleFor(Duration.ofMillis(500))
            val decor = checkNotNull(dialog.window).decorView
            assertTrue(decor.isAttachedToWindow)
            val viewRoot = checkNotNull(ReflectionHelpers.callInstanceMethod<Any>(decor, "getViewRootImpl"))
            val sheetWindow: ShadowViewRootImpl = Shadow.extract(viewRoot)
            fun focus(focused: Boolean) {
                sheetWindow.callWindowFocusChanged(focused)
                main.idle()
                assertEquals(focused, dialog.hasCurrentWindowFocus)
                assertEquals(focused, delete.isEnabled)
                assertEquals(Lifecycle.State.RESUMED, lifecycleOwner.lifecycle.currentState)
            }

            focus(false)
            focus(true)
            focus(false)
            queuedConfirm() // A queued action must recheck focus even after button admission.
            assertFalse(editor.deletingBusy)
            assertSame(target, editor.deleting)
            assertEquals(listOf(option), QuickSaveTileOptions.options.value)

            focus(true)
            assertTrue(delete.performClick())
            assertTrue(editor.deletingBusy)
            queuedConfirm()
            val ownerJob = checkNotNull(scope.coroutineContext[Job]).children.single()
            assertEquals(1, ownerJob.children.count())
            checkNotNull(continuations.poll(5, TimeUnit.SECONDS)).run()
            assertEquals(emptyList<QuickSaveTileOption>(), QuickSaveTileOptions.options.value)
            assertFalse(editor.deletingBusy)
            assertNull(editor.deleting)
            assertTrue(continuations.isEmpty())
        } finally {
            caller.close()
            owner.close()
            scope.cancel()
            controller.pause().stop().destroy()
        }
    }

    @Test fun retainedDismissAndCancelOnlyRetireTheirLiveExactTarget() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup().visible()
        val activity = controller.get()
        val lifecycleOwner = object : LifecycleOwner {
            override val lifecycle = LifecycleRegistry(this).apply { currentState = Lifecycle.State.RESUMED }
        }
        val root = FrameLayout(activity).apply { setViewTreeLifecycleOwner(lifecycleOwner) }
        activity.setContentView(root)
        val environment = ReverbEnvironment(Density(1f, 1f), createFontFamilyResolver(activity), NativeColorScheme.fallback(false))
        val parked = CoroutineScope(object : CoroutineDispatcher() {
            override fun dispatch(context: CoroutineContext, block: Runnable) = Unit
        })
        val option = QuickSaveTileOption(0, "c".repeat(32))
        val first = QuickSettingsTileDeleteTarget(option, 7L)
        val reopened = QuickSettingsTileDeleteTarget(option, 7L)
        assertNotSame(first, reopened) // Equal payload, separate confirmation lifetime.
        val editor = QuickSettingsTilesEditorState().apply { deleting = first }
        fun newOwner() = NativeQuickSettingsTilesOwner(activity, lifecycleOwner.lifecycle, parked, editor,
            NativeQuickTilesInputs(true, true, true, 7L), { 7L }, { 7L }, { 7L }, { 7L })
        val owner = newOwner()
        var successor: NativeQuickSettingsTilesOwner? = null
        val caller = NativeSettingsTileDeleteSheet(root) { environment }
        // Retain actual installed actions, as a queued modal completion/input dispatch can do.
        fun callbacks(): Pair<() -> Unit, () -> Unit> {
            val dialog = field(caller, "dialog") as NativeActionSheet
            val content = field(caller, "content") as QuickTileDeleteContent
            val cancel = descendants(content.actions).filterIsInstance<NativeMaterialButtonView>().single {
                it.label.getText() == activity.getString(R.string.cancel)
            }
            @Suppress("UNCHECKED_CAST")
            val dismiss = field(dialog, "onOwnerDismiss") as () -> Unit
            @Suppress("UNCHECKED_CAST")
            val action = field(cancel, "action") as () -> Unit
            return dismiss to action
        }
        try {
            caller.bind(owner, first)
            val old = callbacks()
            editor.deleting = reopened
            // Also cover the gap before the renderer replaces the old sheet.
            old.first()
            assertSame(reopened, editor.deleting)
            old.second()
            assertSame(reopened, editor.deleting)
            caller.bind(owner, reopened)
            val current = callbacks()
            old.first()
            old.second()
            assertSame(reopened, editor.deleting)

            editor.deletingBusy = true
            current.first()
            current.second()
            assertSame(reopened, editor.deleting)
            assertTrue(editor.deletingBusy)
            editor.deletingBusy = false
            current.first()
            assertNull(editor.deleting)
            editor.deleting = reopened
            current.second()
            assertNull(editor.deleting)

            editor.deleting = reopened
            owner.close()
            val nextOwner = newOwner().also { successor = it }
            caller.bind(nextOwner, reopened)
            current.first()
            current.second()
            assertSame("A retired owner cannot dismiss a confirmation handed to its successor", reopened, editor.deleting)
            val next = callbacks()
            next.first()
            assertNull(editor.deleting)
            editor.deleting = reopened
            next.second()
            assertNull(editor.deleting)
            assertFalse(editor.deletingBusy)
        } finally {
            caller.close()
            successor?.close()
            owner.close()
            parked.cancel()
            controller.pause().stop().destroy()
        }
    }

    private fun field(instance: Any, name: String): Any? = instance.javaClass.getDeclaredField(name)
        .apply { isAccessible = true }.get(instance)
    private fun exact(size: Int) = View.MeasureSpec.makeMeasureSpec(size, View.MeasureSpec.EXACTLY)
    private fun descendants(view: View): List<View> = buildList {
        add(view)
        if (view is android.view.ViewGroup) for (i in 0 until view.childCount) addAll(descendants(view.getChildAt(i)))
    }
}
