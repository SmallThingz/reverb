package app.smallthingz.reverb.nativeui

import android.app.Activity
import android.app.Application
import android.os.Looper
import android.os.SystemClock
import android.view.Choreographer
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import java.time.Duration
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import smallthingz.ui.components.NativeMenuOrigin
import smallthingz.ui.platform.AndroidUiFrameClock
import smallthingz.ui.reverb.ReverbEnvironment
import smallthingz.ui.text.font.createFontFamilyResolver
import smallthingz.ui.components.NativeColorScheme
import smallthingz.ui.unit.Density

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LibraryMenuPointerOriginTest {
    @Test fun bodyPointerKeepsItsOriginButLeadingAndSubsequentKeyOrSemanticLongClicksDoNot() {
        Fixture().use { f ->
            val body = f.body
            body.pivotX = 0f; body.pivotY = 0f
            body.scaleX = 1.25f; body.translationX = 9f; body.translationY = 7f
            var offsetX = 0f; var offsetY = 0f
            var current: View = body
            while (current !== f.row) {
                val parent = current.parent as View
                offsetX += current.left - parent.scrollX
                offsetY += current.top - parent.scrollY
                current = parent
            }
            f.touch(body, MotionEvent.ACTION_DOWN, 140f, 30f)
            // Every test event is recycled before the long-press timer runs.
            f.longPressTimeout()
            assertEquals(listOf("recording" to NativeMenuOrigin(offsetX + 184f, offsetY + 37f)), f.origins)
            f.touch(body, MotionEvent.ACTION_UP, 140f, 30f)
            assertEquals(0, f.activations)
            assertEquals(0, f.semanticLongs)
            val key = KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_CENTER)
            assertTrue(body.onKeyDown(key.keyCode, key))
            f.longPressTimeout()
            assertTrue(body.onKeyUp(key.keyCode, KeyEvent.changeAction(key, KeyEvent.ACTION_UP)))
            assertEquals(1, f.semanticLongs)
            assertTrue(body.performLongClick())
            assertEquals(2, f.semanticLongs)
            assertEquals(1, f.origins.size)
            f.touch(f.leading, MotionEvent.ACTION_DOWN, 30f, 30f)
            f.longPressTimeout()
            f.touch(f.leading, MotionEvent.ACTION_UP, 30f, 30f)
            assertEquals(1, f.leadingLongs)
            assertEquals(1, f.origins.size)
            assertEquals(0, f.activations)
        }
    }

    @Test fun canceledOrRecycledPointerCannotPublishAnOriginForTheNextRecording() {
        Fixture().use { f ->
            f.touch(f.body, MotionEvent.ACTION_DOWN, 140f, 30f)
            f.touch(f.body, MotionEvent.ACTION_CANCEL, 140f, 30f)
            f.longPressTimeout()
            assertTrue(f.origins.isEmpty())
            f.touch(f.body, MotionEvent.ACTION_DOWN, 140f, 30f)
            f.row.bind(f.model.copy(recordingId = "replacement"), f.actions)
            f.longPressTimeout()
            assertTrue(f.origins.isEmpty())
            f.touch(f.body, MotionEvent.ACTION_DOWN, 180f, 35f)
            f.longPressTimeout()
            f.touch(f.body, MotionEvent.ACTION_UP, 180f, 35f)
            assertEquals("replacement", f.origins.single().first)
            assertEquals(0, f.activations)
        }
    }

    private class Fixture : AutoCloseable {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().visible()
        private val context = activity.get()
        private val scope = CoroutineScope(Dispatchers.Main.immediate + AndroidUiFrameClock(Choreographer.getInstance()))
        val row = ReverbLibraryRecordingRow(context,
            ReverbEnvironment(Density(1f), createFontFamilyResolver(context), NativeColorScheme.fallback(false)),
            scope, context.window.decorView)
        var activations = 0
        var semanticLongs = 0
        var leadingLongs = 0
        val origins = mutableListOf<Pair<String, NativeMenuOrigin>>()
        val model = LibraryRecordingRow("recording", "Recording", "One second", null,
            selected = false, selectionActive = false, interactionsEnabled = true, edges = null, expanded = true)
        val actions = LibraryRecordingRowActions(
            activate = { activations++ }, longClick = { semanticLongs++ },
            leadingLongClick = { leadingLongs++ }, pointerLongClick = { id, point -> origins += id to point })
        val body: View
        val leading: View
        init {
            row.bind(model, actions)
            context.setContentView(row)
            row.measure(View.MeasureSpec.makeMeasureSpec(400, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(600, View.MeasureSpec.AT_MOST))
            row.layout(0, 0, row.measuredWidth, row.measuredHeight)
            val targets = descendants(row).filter { it is NativeLibrarySummaryInput }.toList()
            body = targets[0]
            leading = targets[1]
            assertTrue(body.isAttachedToWindow)
        }
        fun touch(target: View, action: Int, x: Float, y: Float) {
            val now = SystemClock.uptimeMillis()
            val event = MotionEvent.obtain(now, now, action, x, y, 0)
            try { assertTrue(target.onTouchEvent(event)) } finally { event.recycle() }
        }
        fun longPressTimeout() = shadowOf(Looper.getMainLooper()).idleFor(
            Duration.ofMillis(ViewConfiguration.getLongPressTimeout().toLong() + 1))
        override fun close() {
            try { row.unbind() } finally {
                try { scope.cancel() } finally { activity.pause().stop().destroy() }
            }
        }
        private fun descendants(view: View): Sequence<View> = sequence {
            yield(view)
            if (view is ViewGroup) for (i in 0 until view.childCount) yieldAll(descendants(view.getChildAt(i)))
        }
    }
}
