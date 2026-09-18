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
