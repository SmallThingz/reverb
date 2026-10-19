package app.smallthingz.reverb

import androidx.compose.runtime.MonotonicFrameClock
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.runtime.snapshots.SnapshotStateObserver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class IncidentsPanelMotionTest {
    @Test
    fun overlayLifetime_doesNotInvalidateCompositionForEveryClosingPosition() {
        val scope = CoroutineScope(SupervisorJob())
        val motion = IncidentsPanelMotionState(false, scope, 220)
        val requestedOpen = mutableStateOf(false)
        val baselineScope = Any()
        val visibilityScope = Any()
        val layerScope = Any()
        val scopes = listOf(baselineScope, visibilityScope, layerScope)
        val notifications = mutableMapOf<Any, Int>()
        val onChanged: (Any) -> Unit = { notifications[it] = (notifications[it] ?: 0) + 1 }
        val observer = SnapshotStateObserver { it() }
        var baselineVisible = false
        var visible = false
        var layerProgress = 0f

        fun observe() {
            observer.observeReads(baselineScope, onChanged) {
                baselineVisible = requestedOpen.value || motion.progress > 0f
            }
            observer.observeReads(visibilityScope, onChanged) {
                visible = requestedOpen.value || motion.hasVisibleProgress
            }
            // The graphics layer must still receive every distinct position.
            observer.observeReads(layerScope, onChanged) { layerProgress = motion.progress }
        }

        fun step(
            label: String,
            expectedVisible: Boolean,
            expectedProgress: Float,
            expectedNotifications: List<Int>,
            change: () -> Unit,
        ) {
            notifications.clear()
            Snapshot.withMutableSnapshot { change() }
            Snapshot.sendApplyNotifications()
            assertEquals(label, expectedNotifications, scopes.map { notifications[it] ?: 0 })
            observe()
            assertEquals(label, expectedVisible, baselineVisible)
            assertEquals(label, baselineVisible, visible)
            assertEquals(label, expectedProgress, layerProgress, 0f)
        }

        observer.start()
        try {
            observe()
            assertEquals(false, visible)
            step("mount before entry starts", true, 0f, listOf(1, 1, 0)) { requestedOpen.value = true }
            step("first entry position", true, 0.25f, listOf(0, 0, 1)) { motion.snapTo(0.25f) }
            step("later entry position", true, 0.75f, listOf(0, 0, 1)) { motion.snapTo(0.75f) }
            step("retain panel when closing starts", true, 0.75f, listOf(1, 1, 0)) {
                requestedOpen.value = false
            }
            step("first closing position", true, 0.5f, listOf(1, 0, 1)) { motion.snapTo(0.5f) }
            step("later closing position", true, 0.2f, listOf(1, 0, 1)) { motion.snapTo(0.2f) }
            step("unchanged position", true, 0.2f, listOf(0, 0, 0)) { motion.snapTo(0.2f) }
            step("remove only at zero", false, 0f, listOf(1, 1, 1)) { motion.snapTo(0f) }
            step("reopen before entry starts", true, 0f, listOf(1, 1, 0)) { requestedOpen.value = true }
            step("partial reopening", true, 0.4f, listOf(0, 0, 1)) { motion.snapTo(0.4f) }
            step("Back captures the current pose", true, 0.4f, listOf(0, 0, 0)) {
                assertEquals(0.4f, motion.beginBack(null), 0f)
            }
            step("Back interrupts a return", true, 0.16f, listOf(0, 0, 1)) {
                assertEquals(0.16f, motion.beginBack(0.16f), 0f)
            }
            step("close the partial panel", true, 0.16f, listOf(1, 1, 0)) { requestedOpen.value = false }
            step("continue partial close", true, 0.08f, listOf(1, 0, 1)) { motion.snapTo(0.08f) }
            step("reopen during closing", true, 0.08f, listOf(1, 1, 0)) { requestedOpen.value = true }
            step("entry continues after reversal", true, 0.2f, listOf(0, 0, 1)) { motion.snapTo(0.2f) }
            step("committed Back removes the panel", false, 0f, listOf(1, 1, 1)) {
                motion.snapTo(0f)
                requestedOpen.value = false
            }
        } finally {
            observer.stop()
            observer.clear()
            scope.cancel()
        }
    }

    private fun visiblePanelWidth(viewportWidth: Float, openProgress: Float): Float {
        val left = incidentsPanelTranslationX(viewportWidth, openProgress)
        val right = left + viewportWidth
        return (minOf(viewportWidth, right) - maxOf(0f, left)).coerceAtLeast(0f)
    }

    @Test
    fun closedEndpoint_hasNoVisibleRemainderBeforeRemoval() {
        // The old 8% translation left 92% of the panel visible at the removal boundary.
        for (width in listOf(320f, 1080f, 2400f)) {
            assertEquals(0f, visiblePanelWidth(width, 0f), 0f)
            assertEquals(0f, visiblePanelWidth(width, -0.2f), 0f)
        }
    }

    @Test
    fun openEndpoint_coversTheViewportWithoutOffset() {
        for (width in listOf(320f, 1080f, 2400f)) {
            assertEquals(0f, incidentsPanelTranslationX(width, 1f), 0f)
            assertEquals(width, visiblePanelWidth(width, 1f), 0f)
            assertEquals(width, visiblePanelWidth(width, 1.2f), 0f)
        }
    }

    @Test
    fun revealAndReverse_traverseTheFullViewportContinuously() {
        val width = 1080f
        val progress = listOf(0f, 0.25f, 0.5f, 0.75f, 1f)
        val offsets = progress.map { incidentsPanelTranslationX(width, it) }

        assertEquals(width, offsets.first() - offsets.last(), 0f)
        assertTrue(offsets.zipWithNext().all { (before, after) -> before > after })
        assertEquals(width / 2f, visiblePanelWidth(width, 0.5f), 0f)
        assertEquals(offsets.reversed(), progress.reversed().map { incidentsPanelTranslationX(width, it) })
    }

    @Test
    fun cancelledReverse_returnsFromCurrentPositionToFullyOpen() {
        val width = 1080f
        val cancelledReturn = listOf(0.4f, 0.2f, 0f).map { backProgress ->
            incidentsPanelTranslationX(width, predictiveBackPanelOpenProgress(
                settledProgress = 1f,
                gestureActive = true,
                gestureProgress = backProgress,
                commitCompleted = false,
                surfaceVisible = true,
            ))
        }

        assertTrue(cancelledReturn.first() in 0f..width)
        assertTrue(cancelledReturn.zipWithNext().all { (before, after) -> before > after })
        assertEquals(0f, cancelledReturn.last(), 0f)
    }

    @Test
    fun committedReverse_staysOffscreenWhenNavigationDropsThePanel() {
        val width = 1080f
        val terminalProgress = predictiveBackPanelOpenProgress(
            settledProgress = 1f,
            gestureActive = true,
            gestureProgress = 1f,
            commitCompleted = false,
            surfaceVisible = true,
        )
        val afterNavigation = predictiveBackPanelOpenProgress(
            settledProgress = 1f,
            gestureActive = false,
            gestureProgress = 1f,
            commitCompleted = true,
            surfaceVisible = false,
        )

        assertEquals(0f, visiblePanelWidth(width, terminalProgress), 0f)
        assertEquals(0f, visiblePanelWidth(width, afterNavigation), 0f)
    }

    @Test
    fun backDuringOpening_startsAtTheActualPoseAndStopsForwardMotion() = runBlocking {
        withMotionClock { motion, frames, _ ->
            motion.settleTo(true)
            frames.send(0L); yield()
            frames.send(80_000_000L); yield()
            val visible = motion.progress
            assertTrue(visible > 0f && visible < 1f)

            val start = motion.beginBack(null)
            assertEquals(visible, start, 0f)
            assertEquals(visible, incidentsPanelOpenProgress(
                settledProgress = motion.progress,
                gestureActive = true,
                gestureProgress = 0f,
                commitCompleted = false,
                surfaceVisible = true,
                gestureStartOpenProgress = start,
            ), 0f)
            frames.send(160_000_000L); yield()
            assertEquals(visible, motion.progress, 0f)
        }
    }

    @Test
    fun cancelledBackDuringOpening_resumesFromCapturedPoseWithoutJump() = runBlocking {
        withMotionClock { motion, frames, owner ->
            motion.snapTo(0.4f)
            val start = motion.beginBack(null)
            val returnedProgress = incidentsPanelOpenProgress(
                settledProgress = motion.progress,
                gestureActive = true,
                gestureProgress = 0f,
                commitCompleted = false,
                surfaceVisible = true,
                gestureStartOpenProgress = start,
            )

            motion.settleTo(true)
            assertEquals(returnedProgress, motion.progress, 0f)
            repeat(30) { frames.send(it * 16_000_000L); yield() }
            owner.children.toList().joinAll()
            assertEquals(1f, motion.progress, 0f)
        }
    }

    @Test
    fun newBackDuringCancelledReturn_ownsTheCurrentPose() = runBlocking {
        withMotionClock { motion, frames, _ ->
            motion.snapTo(0.4f)
            motion.settleTo(true)
            frames.send(0L); yield()
            frames.send(80_000_000L); yield()
            assertTrue(motion.progress > 0.4f)

            val interruptedVisibleProgress = 0.3f
            val newStart = motion.beginBack(interruptedVisibleProgress)
            assertEquals(interruptedVisibleProgress, newStart, 0f)
            frames.send(160_000_000L); yield()
            assertEquals(interruptedVisibleProgress, motion.progress, 0f)
        }
    }

    @Test
    fun disabledBack_resumesClosingFromTheVisiblePose() = runBlocking {
        withMotionClock { motion, frames, owner ->
            motion.snapTo(0.7f)
            motion.beginBack(null)
            motion.resumeAfterBack(interruptedOpenProgress = 0.35f, open = false)
            assertEquals(0.35f, motion.progress, 0f)

            repeat(30) { frames.send(it * 16_000_000L); yield() }
            owner.children.toList().joinAll()
            assertEquals(0f, visiblePanelWidth(1080f, motion.progress), 0f)
        }
    }

    private suspend fun CoroutineScope.withMotionClock(
        block: suspend (IncidentsPanelMotionState, Channel<Long>, Job) -> Unit,
    ) {
        val frames = Channel<Long>(Channel.UNLIMITED)
        val clock = object : MonotonicFrameClock {
            override suspend fun <R> withFrameNanos(onFrame: (Long) -> R): R = onFrame(frames.receive())
        }
        val owner = SupervisorJob(coroutineContext[Job])
        val scope = CoroutineScope(coroutineContext + owner + clock)
        try {
            block(IncidentsPanelMotionState(false, scope, 220), frames, owner)
        } finally {
            scope.cancel()
            frames.close()
        }
    }
}
