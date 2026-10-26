package app.smallthingz.reverb

import app.smallthingz.reverb.nativeui.NativeLibraryListUpdates
import app.smallthingz.reverb.nativeui.NativeLibraryRowState
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class StateRowAllocationRegressionTest {
    @Test
    fun tileWheelOwnerTracksBothFullRangeRevisionsAndInitialZero() {
        val target = QuickSettingsTileEditorTarget()
        var previous: Pair<Long, Long>? = null
        var expectedOwner = 0L
        val revisions = listOf(
            0L to 0L, 0L to 0L, Long.MIN_VALUE to 0L, Long.MIN_VALUE to Long.MIN_VALUE,
            Long.MIN_VALUE to Long.MIN_VALUE, Long.MAX_VALUE to Long.MIN_VALUE,
            Long.MAX_VALUE to Long.MAX_VALUE, 0L to Long.MAX_VALUE, 0L to 0L,
        )
        for ((parent, edit) in revisions) {
            target.editRevision = edit
            val pair = parent to edit
            if (previous != pair) { previous = pair; expectedOwner++ }
            assertEquals(expectedOwner, target.wheelOwner(parent))
        }
    }

    @Test
    fun moveRequestDeduplicationUsesValuesAndHideStartsANewGeneration() = runBlocking {
        val scans = mutableListOf<String>()
        val owner = SettingsMoveAvailabilityOwner(this, scan = { target, mayStart ->
            assertTrue(mayStart())
            scans += target
            true
        }, publish = {})
        try {
            val first = "destination-a"
            val equalButDistinct = String(first.toCharArray())
            assertNotSame(first, equalButDistinct)
            owner.request(first, 0)
            owner.request(equalButDistinct, 0)
            yield()
            assertEquals(listOf(first), scans)
            owner.request(first, Int.MIN_VALUE)
            owner.request(first, Int.MAX_VALUE)
            owner.request("destination-b", Int.MAX_VALUE)
            yield()
            assertEquals(listOf(first, first, first, "destination-b"), scans)
            owner.hide()
            owner.request("destination-b", Int.MAX_VALUE)
            yield()
            assertEquals(listOf(first, first, first, "destination-b", "destination-b"), scans)
        } finally {
            owner.close()
        }
    }

    @Test
    fun dirtyDraftShortCircuitRetainsEqualSnapshotValidationForBothModes() {
        for (mode in RetentionMode.entries) {
            val baseline = SettingsSnapshot(retentionMode = mode)
            val normalized = SettingsUiDraft.normalized(baseline)
            for (raw in listOf("", "0", "1", "0:", "-1", "NaN", "Infinity", "12,5", "999999999999999999999")) {
                val draft = normalized.copy(
                    oneShotTimeText = raw, loopingTimeText = raw,
                    oneShotSizeText = raw, loopingSizeText = raw,
                )
                val invalid = when (mode) {
                    RetentionMode.TIME -> parseRetentionTimeSeconds(raw) == null
                    RetentionMode.SIZE -> parseRetentionSizeMib(raw)?.takeIf { it >= 0.0 } == null
                }
                assertEquals("$mode/$raw", invalid, draft.hasUnsavedChanges(baseline))
                val changed = draft.copy(snapshot = baseline.copy(wakeLockEnabled = !baseline.wakeLockEnabled))
                assertTrue("$mode/$raw", changed.hasUnsavedChanges(baseline))
            }
            val inactiveInvalid = if (mode == RetentionMode.TIME) normalized.copy(oneShotSizeText = "-")
                else normalized.copy(oneShotTimeText = "0:")
            assertFalse(inactiveInvalid.hasUnsavedChanges(baseline))
        }
    }

    @Test
    fun unchangedMembershipReusesOnlyOwnedSnapshotsAcrossOtherRowChanges() {
        val updates = NativeLibraryListUpdates<Any>()
        val catalog = Any()
        val selected = mutableSetOf("A", "B", "C")
        val blocked = mutableSetOf("D", "E")
        val initial = NativeLibraryRowState<Any>(selected, true, blocked, null, null, null, true)
        updates.submit(catalog, initial)
        val owned = requireNotNull(updates.current)
        assertNotSame(selected, owned.selectedIds)
        assertNotSame(blocked, owned.blockedIds)

        val equalSelected = selected.toMutableSet()
        val equalBlocked = blocked.toMutableSet()
        val next = initial.copy(selectedIds = equalSelected, blockedIds = equalBlocked,
            expandedRecordingId = "A", expandedOwner = Any(), screenActive = false)
        val update = requireNotNull(updates.submit(Any(), next))
        assertTrue(update.catalogChanged)
        assertSame(owned.selectedIds, updates.current!!.selectedIds)
        assertSame(owned.blockedIds, updates.current!!.blockedIds)
        equalSelected.remove("B")
        equalBlocked.add("F")
        selected.clear()
        blocked.clear()
        assertEquals(setOf("A", "B", "C"), updates.current!!.selectedIds)
        assertEquals(setOf("D", "E"), updates.current!!.blockedIds)

        val changed = requireNotNull(updates.submit(catalog, next))
        assertEquals(setOf("B", "F"), changed.recordingIds)
        assertNotSame(owned.selectedIds, updates.current!!.selectedIds)
        assertNotSame(owned.blockedIds, updates.current!!.blockedIds)
        equalSelected.clear()
        equalBlocked.clear()
        assertEquals(setOf("A", "C"), updates.current!!.selectedIds)
        assertEquals(setOf("D", "E", "F"), updates.current!!.blockedIds)
        assertEquals(setOf("A", "B", "C"), owned.selectedIds)
        assertEquals(setOf("D", "E"), owned.blockedIds)
    }
}
