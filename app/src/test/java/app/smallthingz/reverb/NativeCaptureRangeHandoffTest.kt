package app.smallthingz.reverb

import android.app.Activity
import android.app.Application
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.activity.BackEventCompat
import androidx.activity.OnBackPressedDispatcher
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import app.smallthingz.reverb.ReverbService.BufferSlot
import app.smallthingz.reverb.nativeui.NativeLibraryPredictiveBack
import app.smallthingz.reverb.nativeui.NativeMaterialButtonHost
import app.smallthingz.reverb.nativeui.NativeWindowTextAccessibilityOwner
import app.smallthingz.reverb.nativeui.ReverbHomeBodyOwner
import app.smallthingz.reverb.nativeui.ReverbRangeExportContent
import app.smallthingz.reverb.nativeui.ReverbViewEnvironment
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.suspendCancellableCoroutine
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import smallthingz.ui.MotionDurationScale
import smallthingz.ui.components.blob.AudioReactiveBlobView
import smallthingz.ui.geometry.Offset
import smallthingz.ui.geometry.Rect
import smallthingz.ui.geometry.Size
import smallthingz.ui.input.phase.NativeViewPointerHitLayout
import smallthingz.ui.input.phase.NativeWindowPointerCoordinates
import smallthingz.ui.runtime.MonotonicFrameClock
import smallthingz.ui.text.view.NativePlainTextViewGeometry
import smallthingz.ui.text.view.NativeSourceAccessibilityNode
import smallthingz.ui.text.view.NativeTextSemanticsClip
import smallthingz.ui.text.view.NativeTextSemanticsClipPolicy

/** Production capture View handoff and Back registration with a synthetic PCM lease. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, shadows = [ServiceStateOsShadow::class])
@LooperMode(LooperMode.Mode.PAUSED)
class NativeCaptureRangeHandoffTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun rangeHandoffReleasesOnlyTheVisualizationLeaseAndHomeReacquiresItOnce() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().visible()
        try {
            Fixture(activity.get()).use { fixture ->
                activity.windowFocusChanged(true)
                fixture.verifyVisualizationLease()
            }
        } finally {
            activity.pause().stop().destroy()
        }
    }

    @Test fun snapshotArrivalAndPredictiveCloseKeepThePreDetachSource() {
        val directory = temporary.newFolder()
        val previousRoot = ServiceStateOsShadow.fixtureRoot
        ServiceStateOsShadow.fixtureRoot = directory.toPath().toAbsolutePath().normalize()
        try {
            PersistentAudioChunkStore(directory, overwriteOldest = false, directorySync = {}).use { store ->
                store.configure(RetentionMode.SIZE, 1_048_576L, 8_000, 1, PcmSampleFormat.PCM_16)
                val pcm = ByteArray(8_000 * 4 * 2)
                assertEquals(pcm.size, store.append(pcm, 0, pcm.size))
                store.sealActiveChunk()
                ReverbService.TimelineSnapshot(checkNotNull(store.acquireRange(0.0, 4.0))).use { snapshot ->
                    val activity = Robolectric.buildActivity(Activity::class.java).setup().visible()
                    try {
                        Fixture(activity.get()).use { fixture -> fixture.verify(snapshot) }
                    } finally {
                        activity.pause().stop().destroy()
                    }
                }
            }
        } finally {
            ServiceStateOsShadow.fixtureRoot = previousRoot
        }
    }

    private class Fixture(activity: Activity) : AutoCloseable, LifecycleOwner {
        override val lifecycle = LifecycleRegistry(this).apply { currentState = Lifecycle.State.RESUMED }
        private val root = FrameLayout(activity)
        private val clock = FrameClock()
        private val scope = CoroutineScope(Dispatchers.Unconfined + Job() + clock + object : MotionDurationScale {
            override val scaleFactor = 1f
        })
        private val back = OnBackPressedDispatcher { error("Range must own Back") }
        private var environment = ReverbViewEnvironment(activity, false)
        private val clips = NativeTextSemanticsClipPolicy { NativeTextSemanticsClip.Unclipped }
        private val geometry = NativePlainTextViewGeometry(root, clips)
        private val text = NativeWindowTextAccessibilityOwner(root, clips)
        private val coordinates = NativeWindowPointerCoordinates { root }
        // Construction is inert. The test delivers UI snapshots at the production bind boundary.
        private val owner = NativeCaptureUiOwner(activity, this, root, scope,
            accessibilityManager = null, isSaveFeedbackVisible = { true },
            onRecordingSaved = {}, onOpenLibrary = {}, onOpenBufferSettings = {})
        private val host = NativeMaterialButtonHost(environment, root, { false }, text.textWindow,
            geometry, { view, _ -> NativeSourceAccessibilityNode.fromView(view) }, { AutoCloseable {} },
            { view, nodes, children, clip -> object : NativeViewPointerHitLayout {
                override val placed get() = view.isAttachedToWindow
                override val size get() = Size(view.width.toFloat(), view.height.toFloat())
                override val minimumTouchTarget = Size.Zero
                override val nodes = nodes
                override val childrenInHitOrder get() = children()
                override fun screenToLocal(screen: Offset) = coordinates.coordinates(view).local(screen)
                override fun withinLayerBounds(local: Offset) = clip(local)
            } })
        private val content = NativeCaptureContentView(activity, environment, owner, this, back, scope,
            root, root, coordinates, text, geometry, NativeSourceAccessibilityNode::fromView, { host }, {})
        private val home = owner.state.value.let { initial ->
            initial.copy(body = initial.body.copy(activeBuffer = BufferSlot.ONE_SHOT,
                listening = true, serviceConnected = true, serviceStateHydrated = true,
                oneShotMetrics = BufferMetrics(4f, 64_000L)))
        }
        private var width = 720
        private var height = 1280

        init {
            try {
                root.addView(content, FrameLayout.LayoutParams(-1, -1))
                activity.setContentView(root)
                layout()
                assertTrue("Real Home must be attached before handoff", content.isAttachedToWindow)
                content.bind(home, environment)
                layout()
            } catch (failure: Throwable) {
                try { close() } catch (cleanup: Throwable) { failure.addSuppressed(cleanup) }
                throw failure
            }
        }

        fun verifyVisualizationLease() {
            // Use the real Service registries, with no service startup, recorder, or audio work.
            val recorder = Robolectric.buildService(ReverbService::class.java).get()
            var audioPosts = 0
            write(recorder, "audioHandler", object : Handler(Looper.getMainLooper()) {
                override fun sendMessageAtTime(msg: Message, uptimeMillis: Long): Boolean {
                    audioPosts++
                    return true
                }
            })
            write(recorder, "state", ReverbService.STATE_LISTENING)
            write(recorder, "durableListeningIntentEnabled", true)
            assertTrue(root.hasWindowFocus())
            write(owner, "attached", true)
            NativeCaptureUiOwner::class.java.getDeclaredMethod("replaceService", ReverbService::class.java)
                .apply { isAccessible = true }.invoke(owner, recorder)

            val mailbox = read<IdentityFrameMailbox<ReverbService.VisualizationCallback,
                ReverbService.VisualizationFrame>>(recorder, "visualizationCallbacks")
            val foreground = read<UiForegroundOwnerTracker>(recorder, "appUiForegroundOwners")
            val homeRegistration = checkNotNull(mailbox.currentRegistration())
            val initialPosts = audioPosts
            val normal = read<ReverbHomeBodyOwner>(content, "normal")
            val blob = normal.view.blobInputView
            val renderer = (blob as ViewGroup).getChildAt(0) as AudioReactiveBlobView
            write(renderer, "currentLife", .62f)
            write(renderer, "currentActivity", .75f)
            val bounds = checkNotNull(geometry.capture(blob, Offset.Zero,
                Rect(0f, 0f, blob.width.toFloat(), blob.height.toFloat()))).visibleBoundsInRoot
            val diameter = minOf(bounds.width, bounds.height) * 2f * renderer.currentBaseRadiusFraction()
            val pending = ReverbService.VisualizationFrame(.9f, ReverbService.VisualizationFrame.EMPTY.bins,
                bufferSlot = BufferSlot.ONE_SHOT)
            assertTrue(mailbox.publish(homeRegistration, pending))
            assertEquals(1, mailbox.size())
            assertEquals(1, foreground.size())

            val preparing = home.copy(rangeSnapshotBuffer = BufferSlot.ONE_SHOT,
                body = home.body.copy(preparingRange = true))
            content.bind(preparing, environment)
            val source = read<RangeMorphSourceGeometry>(content, "sourceGeometry")
            assertEquals("Sample the renderer before lease retirement clears its live frame", diameter,
                source.bodyDiameterPx, .001f)
            assertNull(home.body.oneShotController.currentBaseRadiusFraction())
            assertNull(mailbox.currentRegistration())
            assertEquals(0, mailbox.size())
            assertEquals(initialPosts + 1, audioPosts)
            mailbox.dispatchPending { _, _ -> fail("A frame queued for the retired Home was delivered") }
            assertFalse(mailbox.publish(homeRegistration, pending))
            assertTrue("Range Back remains enabled", back.hasEnabledCallbacks())
            assertTrue(read<Boolean>(read<NativeRangeExportOwner>(content, "rangeOwner"), "visualizerVisible"))
            repeat(3) { content.bind(preparing, environment) }
            assertNull(mailbox.currentRegistration())
            assertEquals(initialPosts + 1, audioPosts)
            assertEquals(1, foreground.size())

            content.bind(home, environment)
            val returnedRegistration = checkNotNull(mailbox.currentRegistration())
            assertNotSame(homeRegistration, returnedRegistration)
            assertEquals(1, mailbox.size())
            assertEquals(initialPosts + 2, audioPosts)
            repeat(3) { content.bind(home, environment) }
            assertSame(returnedRegistration, mailbox.currentRegistration())
            assertEquals(initialPosts + 2, audioPosts)
            assertEquals(1, foreground.size())
            assertEquals(ReverbService.STATE_LISTENING, read<Int>(recorder, "state"))
            assertTrue(read<Boolean>(recorder, "durableListeningIntentEnabled"))
        }

        fun verify(snapshot: ReverbService.TimelineSnapshot) {
            val normal = read<ReverbHomeBodyOwner>(content, "normal")
            val blob = normal.view.blobInputView
            val bounds = checkNotNull(geometry.capture(blob, Offset.Zero,
                Rect(0f, 0f, blob.width.toFloat(), blob.height.toFloat()))).visibleBoundsInRoot
            val controller = home.body.oneShotController
            // Hold a valid intermediate pose in the real renderer, independent of host animator settings.
            val renderer = (blob as ViewGroup).getChildAt(0) as AudioReactiveBlobView
            AudioReactiveBlobView::class.java.getDeclaredField("currentLife").apply {
                isAccessible = true
                setFloat(renderer, .62f)
            }
            AudioReactiveBlobView::class.java.getDeclaredField("currentActivity").apply {
                isAccessible = true
                setFloat(renderer, .25f)
            }
            val radius = checkNotNull(controller.currentBaseRadiusFraction())
            assertEquals(renderer.currentBaseRadiusFraction(), radius, 0f)
            assertTrue("Fixture must distinguish renderer radius from detached active fallback",
                kotlin.math.abs(radius - rangeBlobBaseRadiusFraction(true, true, 0f)) > .01f)
            val expectedDiameter = minOf(bounds.width, bounds.height) * 2f * radius
            var atDetach: RangeMorphSourceGeometry? = null
            normal.view.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(view: View) = Unit
                override fun onViewDetachedFromWindow(view: View) {
                    atDetach = read(content, "sourceGeometry")
                }
            })

            val preparing = home.copy(rangeSnapshotBuffer = BufferSlot.ONE_SHOT,
                body = home.body.copy(preparingRange = true))
            content.bind(preparing, environment)
            val frozen = checkNotNull(atDetach) { "Source must already be captured in Home's detach callback" }
            assertEquals(expectedDiameter, frozen.bodyDiameterPx, .001f)
            assertEquals(bounds.center.x, frozen.centerXInRootPx, .001f)
            assertEquals(bounds.center.y, frozen.centerYInRootPx, .001f)
            assertNull("Home close retires the actual blob attachment", controller.currentBaseRadiusFraction())
            val rangeOwner = read<NativeRangeExportOwner>(content, "rangeOwner")
            val rangeView = read<ReverbRangeExportContent>(content, "rangeView")
            layout()
            clock.at(0)
            assertPose(rangeView, frozen, 0f)
            clock.at(200)
            assertTrue(frame(rangeView).waveformFrame.morphProgress in .01f..0.95f)

            val arrived = preparing.copy(rangeSnapshot = snapshot,
                body = preparing.body.copy(preparingRange = false))
            content.bind(arrived, environment)
            clock.at(216)
            assertSame("Snapshot delivery retains the editor owner", rangeOwner, read(content, "rangeOwner"))
            assertSame(rangeView, read(content, "rangeView"))
            assertSame(snapshot, rangeOwner.state.snapshot)
            assertPose(rangeView, frozen)

            val editor = rangeOwner.state
            editor.setTarget(RangeEditTarget.START, 1f)
            editor.setTarget(RangeEditTarget.END, 3f)
            editor.selectTarget(RangeEditTarget.START)
            clock.at(232)
            val selectedStart = editor.startSeconds
            val selectedEnd = editor.endSeconds
            val selectedTarget = editor.lastTarget
            environment = environment.withPalette(ReverbViewEnvironment(root.context, true).palette)
            content.bind(arrived, environment)
            layout()
            clock.at(248)
            assertPose(rangeView, frozen)

            back.dispatchOnBackStarted(event(0f))
            back.dispatchOnBackProgressed(event(.9f))
            clock.at(264)
            assertEquals(.9f, frame(rangeView).backProgress, 0f)
            assertPose(rangeView, frozen, 0f)
            back.dispatchOnBackCancelled()
            clock.at(280)
            assertEquals(0f, frame(rangeView).backProgress, 0f)
            assertTrue(frame(rangeView).waveformFrame.morphProgress > 0f)
            assertPose(rangeView, frozen)
            assertSame(rangeOwner, read(content, "rangeOwner"))
            assertSame(editor, rangeView.state)
            assertSame(snapshot, editor.snapshot)
            assertEquals(selectedStart, editor.startSeconds, 0f)
            assertEquals(selectedEnd, editor.endSeconds, 0f)
            assertEquals(selectedTarget, editor.lastTarget)
            assertFalse(editor.isPlaying || editor.isScrubbing || editor.selectionDurationEditing)

            back.dispatchOnBackStarted(event(0f))
            back.dispatchOnBackProgressed(event(1f))
            clock.at(296)
            assertEquals(1f, frame(rangeView).backProgress, 0f)
            assertPose(rangeView, frozen, 0f)
            assertSame(snapshot, editor.snapshot)
            assertEquals(selectedStart, editor.startSeconds, 0f)
            assertEquals(selectedEnd, editor.endSeconds, 0f)
            back.onBackPressed()
            assertTrue(read<NativeLibraryPredictiveBack>(content, "rangeBack").commitCompleted)

            // Route retirement and a new Home layout must permit a fresh renderer handoff.
            content.bind(home, environment)
            width = 600
            height = 1000
            layout()
            val reopenedHome = read<ReverbHomeBodyOwner>(content, "normal")
            val reopenedBlob = reopenedHome.view.blobInputView
            val nextDiameter = minOf(reopenedBlob.width, reopenedBlob.height) * 2f *
                checkNotNull(controller.currentBaseRadiusFraction())
            content.bind(arrived, environment)
            val nextSource = read<RangeMorphSourceGeometry>(content, "sourceGeometry")
            assertNotSame(frozen, nextSource)
            assertEquals(nextDiameter, nextSource.bodyDiameterPx, .001f)
            assertNotSame(rangeOwner, read(content, "rangeOwner"))
            assertSame(snapshot, read<NativeRangeExportOwner>(content, "rangeOwner").state.snapshot)
        }

        private fun layout() {
            root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
            root.layout(0, 0, width, height)
        }

        private fun assertPose(view: ReverbRangeExportContent, source: RangeMorphSourceGeometry,
            expectedMorph: Float? = null) {
            val frame = frame(view)
            assertSame("Published morph must keep the source sampled before detach", source, frame.sourceGeometry)
            assertTrue(frame.transitionStarted)
            val progress = frame.waveformFrame.morphProgress
            expectedMorph?.let { assertEquals(it, progress, 0f) }
            val target = checkNotNull(frame.waveformTargetBoundsInRoot)
            val waveform = view.timeline.waveform
            assertEquals(source.bodyDiameterPx + (target.width - source.bodyDiameterPx) * progress,
                waveform.width * waveform.scaleX, .002f)
            assertEquals(source.bodyDiameterPx + (target.height - source.bodyDiameterPx) * progress,
                waveform.height * waveform.scaleY, .002f)
            assertEquals((source.centerXInRootPx - target.center.x) * (1f - progress), waveform.translationX, .002f)
            assertEquals((source.centerYInRootPx - target.center.y) * (1f - progress), waveform.translationY, .002f)
        }

        override fun close() {
            try { content.close() }
            finally {
                try { owner.close() }
                finally {
                    scope.cancel()
                    root.removeAllViews()
                    lifecycle.currentState = Lifecycle.State.DESTROYED
                }
            }
        }
    }

    private class FrameClock : MonotonicFrameClock {
        private val pending = mutableListOf<(Long) -> Unit>()
        override suspend fun <R> withFrameNanos(onFrame: (Long) -> R): R = suspendCancellableCoroutine { continuation ->
            val callback: (Long) -> Unit = { time ->
                if (continuation.isActive) continuation.resumeWith(runCatching { onFrame(time) })
            }
            pending.add(callback)
            continuation.invokeOnCancellation { pending.remove(callback) }
        }
        fun at(millis: Long) {
            val batch = pending.toList()
            pending.clear()
            batch.forEach { it(millis * 1_000_000L) }
        }
    }

    companion object {
        private fun event(progress: Float) = BackEventCompat(0f, 0f, progress, BackEventCompat.EDGE_LEFT)
        private fun write(owner: Any, name: String, value: Any) =
            owner.javaClass.getDeclaredField(name).run { isAccessible = true; set(owner, value) }
        @Suppress("UNCHECKED_CAST")
        private fun <T> read(owner: Any, name: String): T = owner.javaClass.getDeclaredField(name).run {
            isAccessible = true
            get(owner) as T
        }
        private fun frame(view: ReverbRangeExportContent): NativeRangeExportPresentation =
            smallthingz.ui.reverb.ReverbRangeExportContent::class.java.getDeclaredField("presentation").run {
                isAccessible = true
                get(view) as NativeRangeExportPresentation
            }
    }
}
