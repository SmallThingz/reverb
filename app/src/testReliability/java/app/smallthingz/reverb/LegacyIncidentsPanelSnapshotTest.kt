package app.smallthingz.reverb

import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.runtime.snapshots.SnapshotStateObserver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.Assert.assertEquals
import org.junit.Test

/** Frozen Compose-source subscription check; never used by native production Views. */
class LegacyIncidentsPanelSnapshotTest {
    @Test
    fun overlayLifetime_doesNotInvalidateCompositionForEveryClosingPosition() {
        val scope = CoroutineScope(SupervisorJob())
        val motion = LegacyIncidentsPanelMotionState(false, scope, 220)
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

}
