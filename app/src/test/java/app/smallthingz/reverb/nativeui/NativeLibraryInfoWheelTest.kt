package app.smallthingz.reverb.nativeui

import android.app.Activity
import android.app.Application
import android.content.pm.PackageManager
import android.content.pm.PermissionInfo
import android.os.Build
import android.os.Looper
import android.os.SystemClock
import android.view.Choreographer
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.activity.OnBackPressedDispatcher
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import app.smallthingz.reverb.NativeLibraryCallbacks
import app.smallthingz.reverb.NativeLibraryInput
import app.smallthingz.reverb.RecordingEntity
import app.smallthingz.reverb.RecordingStorageType
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import smallthingz.ui.MotionDurationScale
import smallthingz.ui.platform.AndroidUiFrameClock
import smallthingz.ui.reverb.ReverbRecordingSpotlight
import smallthingz.ui.scroll.NativeVerticalNestedHost
import smallthingz.ui.scroll.NativeVerticalScrollSource
import smallthingz.ui.text.font.createFontFamilyResolver
import smallthingz.ui.unit.Density
import kotlin.coroutines.CoroutineContext

/** Actual screen/Info listener on minimum and current fixture SDKs; no repository/IME work. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 35], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class NativeLibraryInfoWheelTest {
    @Before fun modelDeclaredSignatureReceiverPermissionOnSdk28() {
        if (Build.VERSION.SDK_INT != 28) return
        val app = RuntimeEnvironment.getApplication()
        val permission = "${app.packageName}.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION"
        val manager = app.packageManager
        val declared = manager.getPermissionInfo(permission, 0)
        assertEquals(app.packageName, declared.packageName)
        assertEquals(PermissionInfo.PROTECTION_SIGNATURE,
            declared.protectionLevel and PermissionInfo.PROTECTION_MASK_BASE)
        val requested = manager.getPackageInfo(app.packageName, PackageManager.GET_PERMISSIONS).requestedPermissions
        assertTrue("Merged manifest must request its receiver permission", requested.orEmpty().contains(permission))
        // Robolectric's SDK28 process does not grant this manifest signature permission by default.
        // Model installation admission; retain the real ContextCompat receiver registration.
        shadowOf(app).grantPermissions(permission)
        assertEquals(PackageManager.PERMISSION_GRANTED, app.checkSelfPermission(permission))
    }

    @Test fun backdropMapsOnceWithRetainedScratchAndConsumesSingularViewport() = Fixture().use { f ->
        val source = f.spotlight
        val viewport = checkNotNull(f.screen.listViewport)
        val list = viewport.parent as View
        val body = f.screen.bodyPointerView
        body.pivotX = 0f; body.pivotY = 0f
        body.scaleX = 1.5f; body.scaleY = .75f
        body.translationX = 9f; body.translationY = 13f
        viewport.translationX = 5f; viewport.translationY = 7f
        source.pivotX = 0f; source.pivotY = 0f
        source.scaleX = .8f; source.scaleY = 1.25f
        source.translationX = 4f; source.translationY = 6f
        val delivered = mutableListOf<FloatArray>()
        viewport.setOnGenericMotionListener { _, event ->
            delivered += floatArrayOf(event.x, event.y, event.getAxisValue(MotionEvent.AXIS_VSCROLL))
            false // Exercise the target's false result; the modal must still consume forwarding.
        }
        fun wheel(x: Float, y: Float): MotionEvent {
            val properties = MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_MOUSE }
            val coordinates = MotionEvent.PointerCoords().apply {
                this.x = x; this.y = y; setAxisValue(MotionEvent.AXIS_VSCROLL, -2f)
            }
            val now = SystemClock.uptimeMillis()
            return MotionEvent.obtain(now, now, MotionEvent.ACTION_SCROLL, 1, arrayOf(properties),
                arrayOf(coordinates), 0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_MOUSE, 0)
        }
        for ((x, y) in listOf(100f to 200f, 160f to 240f)) {
            val event = wheel(x, y)
            val count = delivered.size
            try {
                assertTrue(source.dispatchGenericMotionEvent(event))
                assertEquals(count + 1, delivered.size)
                // Independent affine oracle for this known hierarchy, including safe inset,
                // toolbar, list padding, ancestor scale and each branch's translation.
                val targetX = body.left + 9f + 1.5f * (list.left + viewport.left + 5f)
                val targetY = body.top + 13f + .75f * (list.top + viewport.top + 7f)
                assertArrayEquals(floatArrayOf((source.left + 4f + .8f * x - targetX) / 1.5f,
                    (source.top + 6f + 1.25f * y - targetY) / .75f, -2f), delivered.last(), .01f)
                assertEquals(x, event.x, 0f)
                assertEquals(y, event.y, 0f)
                assertEquals(-2f, event.getAxisValue(MotionEvent.AXIS_VSCROLL), 0f)
                assertEquals(MotionEvent.ACTION_SCROLL, event.actionMasked)
            } finally { event.recycle() }
        }
        viewport.scaleX = 0f
        val singular = wheel(100f, 200f)
        try {
            assertTrue("Modal consumes even when inverse does not exist", source.dispatchGenericMotionEvent(singular))
            assertEquals("Singular mapping must not forward stale coordinates", 2, delivered.size)
            assertEquals(100f, singular.x, 0f)
            assertEquals(200f, singular.y, 0f)
        } finally { singular.recycle() }
        val state = checkNotNull(f.screen.currentState())
        assertEquals(f.record.id, state.infoRecording?.id)
        assertNull(state.renameRecording)
        assertNull(state.menuRecording)
        assertNull(state.list.expandedOwner)
        assertEquals(0, state.selectedCount)
    }

    private class Fixture : AutoCloseable {
        private val activity = Robolectric.buildActivity(Activity::class.java).setup().visible()
        private val context = activity.get()
        private val root = FrameLayout(context)
        private val lifecycleOwner = object : LifecycleOwner {
            override val lifecycle = LifecycleRegistry(this).apply { currentState = Lifecycle.State.STARTED }
        }
        private val frames = AndroidUiFrameClock(Choreographer.getInstance())
        // Controller publication uses its actual Handler. Park coroutine side effects, provider
        // reads and frame jobs; this test targets the real generic-motion listener, not timing.
        private val scope = CoroutineScope(object : CoroutineDispatcher() {
            override fun dispatch(context: CoroutineContext, block: Runnable) = Unit
        } + frames + object : MotionDurationScale { override val scaleFactor = 0f })
        val record = RecordingEntity("/synthetic-info/one.wav", "One", "audio/wav", 1_700_000_000_000L,
            1_000L, 32_044L, "PCM", RecordingStorageType.FILE, "/synthetic-info")
        val screen = ReverbLibraryScreenContent(context,
            ReverbViewEnvironment(Density(1f), createFontFamilyResolver(context), ReverbPalette.fallback(false)),
            NativeLibraryScreenInput(NativeLibraryInput(true, listOf(record), NativeLibraryCallbacks()),
                false, true, LibraryScaffoldInsets(left = 11, right = 13, bottom = 17), {}, {}, {}, {}),
            lifecycleOwner, OnBackPressedDispatcher(), scope, frames, { 0f }, null, root,
            object : NativeVerticalNestedHost {
                override fun preScroll(availableY: Float, source: NativeVerticalScrollSource) = 0f
                override fun postScroll(consumedY: Float, availableY: Float, source: NativeVerticalScrollSource) = 0f
                override suspend fun preFling(availableY: Float) = 0f
                override suspend fun postFling(consumedY: Float, availableY: Float) = 0f
            }, {}, {})
        val spotlight get() = descendants(screen).filterIsInstance<ReverbRecordingSpotlight>().single()
        init {
            root.addView(screen, FrameLayout.LayoutParams(-1, -1))
            context.setContentView(root)
            screen.owner.info(record)
            screen.publishInitialPresentation()
            shadowOf(Looper.getMainLooper()).idle()
            root.measure(View.MeasureSpec.makeMeasureSpec(600, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(800, View.MeasureSpec.EXACTLY))
            root.layout(0, 0, 600, 800)
            check(screen.inlineInfoActive && spotlight.isAttachedToWindow)
            check((screen.listViewport?.width ?: 0) > 0)
        }
        override fun close() {
            try { screen.close() } finally {
                try { scope.cancel() } finally { activity.pause().stop().destroy() }
            }
        }
    }

    companion object {
        private fun descendants(view: View): Sequence<View> = sequence {
            yield(view)
            if (view is ViewGroup) repeat(view.childCount) { yieldAll(descendants(view.getChildAt(it))) }
        }
    }
}
