package app.smallthingz.reverb

import android.app.Activity
import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.FrameLayout
import app.smallthingz.reverb.nativeui.LibraryPendingSaveRowPresentation
import app.smallthingz.reverb.nativeui.ReverbPalette
import app.smallthingz.reverb.nativeui.ReverbViewEnvironment
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import smallthingz.ui.components.NativeCircularProgress
import smallthingz.ui.components.NativeDeterminateLinearProgress
import smallthingz.ui.components.NativeIndeterminateLinearProgress
import smallthingz.ui.components.NativeLinearProgressView
import smallthingz.ui.components.NativeUiAppearance
import smallthingz.ui.runtime.MonotonicFrameClock
import smallthingz.ui.text.font.createFontFamilyResolver
import smallthingz.ui.text.view.NativePlainTextViewGeometry
import smallthingz.ui.text.view.NativeTextSemanticsClip
import smallthingz.ui.text.view.NativeTextSemanticsClipPolicy
import smallthingz.ui.unit.Density

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class NativeProgressPresetsTest {
    @Test fun pendingSaveUsesAppearanceOverlayWithoutChangingAcceptedProgress() {
        val context = RuntimeEnvironment.getApplication()
        val clock = object : MonotonicFrameClock {
            override suspend fun <R> withFrameNanos(onFrame: (Long) -> R): R =
                error("Detached pending rows must not schedule animation frames")
        }
        val scope = CoroutineScope(Dispatchers.Unconfined + clock)
        val palette = ReverbPalette.fallback(false).copy(primary = Color.WHITE, surfaceContainerLow = Color.BLACK)
        val layout = NativeUiAppearance.Default.listDecorations
        val alternate = NativeUiAppearance.Default.copy(listDecorations = layout.copy(
            pendingSave = layout.pendingSave.copy(spinnerStrokeDp = 4f, overlayAlphaByte = 0x80),
        ))
        val save = PendingSave("pending", "Local recording", 0L, 20_000L, SavePhase.SAVING, .5f)
        try {
            for ((appearance, overlay) in listOf(NativeUiAppearance.Default to 0xff222222.toInt(),
                alternate to 0xff808080.toInt())) {
                val environment = ReverbViewEnvironment(Density(1f), createFontFamilyResolver(context), palette,
                    appearance = appearance)
                LibraryPendingSaveRowPresentation(context, environment, scope, FrameLayout(context)).use { presentation ->
                    val row = presentation.row
                    row.layoutDirection = View.LAYOUT_DIRECTION_LTR
                    fun colorAt(x: Int): Int {
                        row.measure(View.MeasureSpec.makeMeasureSpec(360, View.MeasureSpec.EXACTLY),
                            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
                        row.layout(0, 0, row.measuredWidth, row.measuredHeight)
                        val image = Bitmap.createBitmap(row.width, row.height, Bitmap.Config.ARGB_8888)
                        try {
                            row.draw(Canvas(image))
                            return image.getPixel(x, 6)
                        } finally { image.recycle() }
                    }
                    presentation.bind(save, environment)
                    check(colorAt(120) == overlay && colorAt(240) == Color.BLACK)
                    presentation.bind(save.copy(progress = .25f), environment)
                    check(colorAt(120) == overlay)
                    presentation.bind(save.copy(phase = SavePhase.FAILED), environment)
                    check(colorAt(120) == Color.BLACK)
                }
            }
        } finally { scope.cancel() }
    }

    @Test
    fun mountedPresetsHonorFrameOwnershipAndLinearAccessibilityLeases() {
        val owner = Robolectric.buildActivity(Activity::class.java).setup().visible()
        val host = owner.get()
        val root = FrameLayout(host)
        host.setContentView(root)
        val decor = host.window.decorView
        decor.measure(View.MeasureSpec.makeMeasureSpec(360, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(640, View.MeasureSpec.EXACTLY))
        decor.layout(0, 0, 360, 640)
        val times = Channel<Long>()
        var waitingFrames = 0
        val clock = object : MonotonicFrameClock {
            override suspend fun <R> withFrameNanos(onFrame: (Long) -> R): R {
                waitingFrames++
                try { return onFrame(times.receive()) } finally { waitingFrames-- }
            }
        }
        val scope = CoroutineScope(Dispatchers.Unconfined)
        var scale = 1f
        val circular = NativeCircularProgress(host, Color.BLUE, clock, scope, { scale })
        val determinate = NativeDeterminateLinearProgress(host, Color.BLUE, Color.GRAY, 1f)
        val indeterminate = NativeIndeterminateLinearProgress(host, Color.BLUE, Color.GRAY, 1f, clock, scope, { scale })
        val clips = NativeTextSemanticsClipPolicy { NativeTextSemanticsClip.Unclipped }
        fun pose() = Bitmap.createBitmap(48, 48, Bitmap.Config.ARGB_8888).also { circular.draw(Canvas(it)) }
        fun verifyLinear(view: NativeLinearProgressView, expectedProgress: Float?, verifyPaint: () -> Unit) {
            val params = FrameLayout.LayoutParams(120, 12).apply { leftMargin = 16; topMargin = 16 }
            root.addView(view, params)
            // Lay out exactly with the placement used by the host, before its next traversal.
            view.measure(View.MeasureSpec.makeMeasureSpec(120, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(12, View.MeasureSpec.EXACTLY))
            view.layout(16, 16, 136, 28)
            val location = IntArray(2).also(root::getLocationOnScreen)
            val padded = Rect(16, 6, 136, 38).apply { offset(location[0], location[1]) }
            val clipped = Rect(16, 12, 80, 32).apply { offset(location[0], location[1]) }
            val obsolete = view.bindAccessibility(NativePlainTextViewGeometry(root, clips)) { true }
            val bounds = Rect()
            checkNotNull(view.createAccessibilityNodeInfo()).getBoundsInScreen(bounds)
            check(bounds == padded) { "Expected enlarged progress bounds $padded, got $bounds" }
            verifyPaint()
            var exposed = true
            val geometry = NativePlainTextViewGeometry(root, clips,
                rootContentClip = { smallthingz.ui.geometry.Rect(0f, 12f, 80f, 32f) })
            view.bindAccessibility(geometry) { exposed }.use {
                obsolete.close()
                val node = checkNotNull(view.createAccessibilityNodeInfo())
                node.getBoundsInScreen(bounds)
                check(bounds == clipped) { "Expected clipped progress bounds $clipped, got $bounds" }
                check(node.className == "android.widget.ProgressBar" && node.isVisibleToUser)
                val expectedDescription = if (expectedProgress == null) {
                    host.getString(smallthingz.ui.R.string.in_progress)
                } else {
                    host.getString(smallthingz.ui.R.string.template_percent, 37)
                }
                check(AccessibilityNodeInfoCompat.wrap(node).stateDescription == expectedDescription)
                check(node.rangeInfo?.current == expectedProgress)
                node.rangeInfo?.let { range -> check(range.min == 0f && range.max == 1f) }
                check(node.actionList.map { it.id } == listOf(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS))
                check(!view.performAccessibilityAction(AccessibilityNodeInfo.ACTION_CLICK, null))
                exposed = false
                val hidden = checkNotNull(view.createAccessibilityNodeInfo())
                check(!hidden.isVisibleToUser && !hidden.isImportantForAccessibility)
                root.removeView(view)
                check(view.createAccessibilityNodeInfo() == null)
            }
        }
        try {
            root.addView(circular, FrameLayout.LayoutParams(48, 48))
            circular.layout(0, 0, 48, 48)
            check(waitingFrames == 1)
            circular.visibility = View.INVISIBLE
            check(waitingFrames == 0)
            circular.visibility = View.VISIBLE
            check(waitingFrames == 1)
            check(times.trySend(1_000_000L).isSuccess)
            val firstPose = pose()
            try {
                check(times.trySend(201_000_000L).isSuccess)
                val nextPose = pose()
                try { check(!firstPose.sameAs(nextPose)) } finally { nextPose.recycle() }
            } finally { firstPose.recycle() }
            val startingPose = pose()
            try {
                circular.setPresentationActive(false)
                scale = 0f
                circular.motionScaleChanged()
                val retainedPose = pose()
                try { check(waitingFrames == 0 && startingPose.sameAs(retainedPose)) }
                finally { retainedPose.recycle() }
                circular.setPresentationActive(true)
                val zeroScalePose = pose()
                try { check(waitingFrames == 0 && !startingPose.sameAs(zeroScalePose)) }
                finally { zeroScalePose.recycle() }
            } finally { startingPose.recycle() }
            scale = 1f
            circular.motionScaleChanged()
            check(waitingFrames == 1)
            root.removeView(circular)
            check(waitingFrames == 0)
            root.addView(circular, FrameLayout.LayoutParams(48, 48))
            check(waitingFrames == 1)
            root.removeView(circular)
            check(waitingFrames == 0)
            determinate.bind(.37f, Color.BLUE, Color.GRAY, 1f, operationId = 1L, terminal = false)
            verifyLinear(determinate, .37f) {
                determinate.layoutDirection = View.LAYOUT_DIRECTION_LTR
                assertCenterPixels(determinate, 20 to Color.BLUE, 56 to Color.GRAY, 90 to Color.GRAY)
                determinate.layoutDirection = View.LAYOUT_DIRECTION_RTL
                assertCenterPixels(determinate, 29 to Color.GRAY, 99 to Color.BLUE)
                determinate.layoutDirection = View.LAYOUT_DIRECTION_LTR
                // At twice the density the gap grows by 4px; sample inside the resulting gap.
                determinate.bind(.37f, Color.BLUE, Color.GRAY, 2f, operationId = 1L, terminal = true)
                assertCenterPixels(determinate, 20 to Color.BLUE, 56 to Color.TRANSPARENT, 90 to Color.GRAY)
                determinate.bind(.37f, Color.BLUE, Color.GRAY, 1f, operationId = 1L, terminal = true)
            }
            verifyLinear(indeterminate, null) {
                indeterminate.layoutDirection = View.LAYOUT_DIRECTION_LTR
                check(waitingFrames == 1)
                check(times.trySend(1_000_000_000L).isSuccess)
                assertCenterPixels(indeterminate, 35 to Color.GRAY, 95 to Color.GRAY)
                check(times.trySend(1_750_000_000L).isSuccess)
                assertCenterPixels(indeterminate, 35 to Color.BLUE, 95 to Color.GRAY)
                scale = 0f
                check(times.trySend(1_800_000_000L).isSuccess)
                assertCenterPixels(indeterminate, 35 to Color.GRAY, 116 to Color.GRAY)
                check(waitingFrames == 0)
                scale = 1f
                indeterminate.motionScaleChanged()
                check(waitingFrames == 1)
                check(times.trySend(1_900_000_000L).isSuccess)
                assertCenterPixels(indeterminate, 35 to Color.GRAY, 116 to Color.GRAY)
            }
            check(waitingFrames == 0)
        } finally {
            root.removeView(circular)
            root.removeView(determinate)
            root.removeView(indeterminate)
            scope.cancel()
            times.close()
            owner.pause().stop().destroy()
        }
    }

    private fun assertCenterPixels(view: View, vararg samples: Pair<Int, Int>) {
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        try {
            view.draw(Canvas(bitmap))
            for ((x, expected) in samples) {
                val actual = bitmap.getPixel(x, view.height / 2)
                check(actual == expected) {
                    "${view.javaClass.simpleName} center row x=$x expected $expected, got $actual"
                }
            }
        } finally {
            bitmap.recycle()
        }
    }
}
