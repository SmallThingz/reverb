package app.smallthingz.reverb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class DurablePreferenceMutationTest {
    @Test
    fun failedDurableCommit_restoresPriorProcessStateBeforeReturningFailure() {
        val events = mutableListOf<String>()

        val committed = commitDurablePreferenceOrRestoreInMemory(
            commit = { events += "commit"; false },
            restoreInMemory = { events += "restore" },
        )

        assertFalse(committed)
        assertEquals(listOf("commit", "restore"), events)
    }

    @Test
    fun successfulDurableCommit_doesNotRollBackProcessState() {
        val events = mutableListOf<String>()

        val committed = commitDurablePreferenceOrRestoreInMemory(
            commit = { events += "commit"; true },
            restoreInMemory = { events += "restore" },
        )

        assertTrue(committed)
        assertEquals(listOf("commit"), events)
    }

    @Test
    fun failedExportDirectoryCommit_restoresPreviousProcessValue() {
        var processValue: String? = "content://old"
        val writes = mutableListOf<String?>()

        val committed = commitConfiguredExportTreeUriChange(
            previousValue = processValue,
            updatedValue = "content://new",
        ) { value ->
            processValue = value
            writes += value
            value != "content://new"
        }

        assertFalse(committed)
        assertEquals("content://old", processValue)
        assertEquals(listOf("content://new", "content://old"), writes)
    }

    @Test
    fun failedExportDirectoryRemoval_restoresPreviousProcessValue() {
        var processValue: String? = "content://old"
        val writes = mutableListOf<String?>()

        val committed = commitConfiguredExportTreeUriChange(
            previousValue = processValue,
            updatedValue = null,
        ) { value ->
            processValue = value
            writes += value
            value != null
        }

        assertFalse(committed)
        assertEquals("content://old", processValue)
        assertEquals(listOf(null, "content://old"), writes)
    }

    @Test
    fun throwingDurableCommit_restoresStateAndPreservesPrimaryFailure() {
        val primary = IllegalStateException("commit failed")
        val rollback = IllegalArgumentException("restore failed")
        var observed: Throwable? = null

        try {
            commitDurablePreferenceOrRestoreInMemory(
                commit = { throw primary },
                restoreInMemory = { throw rollback },
            )
        } catch (error: Throwable) {
            observed = error
        }

        assertSame(primary, observed)
        assertEquals(listOf(rollback), primary.suppressed.toList())
    }
}
