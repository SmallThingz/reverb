package app.smallthingz.reverb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class DurablePreferenceMutationTest {
    @Test
    fun configuredExportTreeAuthority_requiresCanonicalTreeOnly() {
        assertTrue(configuredExportTreePreferenceIsUsable(null))
        assertTrue(configuredExportTreePreferenceIsUsable("content://docs/tree/root"))
        assertTrue(configuredExportTreePreferenceIsUsable("content://docs/tree/primary%3AMusic%2FReverb"))
        assertFalse(configuredExportTreePreferenceIsUsable(""))
        assertFalse(configuredExportTreePreferenceIsUsable("content://docs/tree/root/document/child"))
        assertFalse(configuredExportTreePreferenceIsUsable("content://docs/tree/root?query=1"))
        assertFalse(configuredExportTreePreferenceIsUsable("content://docs/tree/%72oot"))
    }

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

        val previousValue = processValue
        val committed = commitConfiguredExportTreeUriChange(
            updatedValue = "content://new",
            write = { value ->
                processValue = value
                writes += value
                value != "content://new"
            },
            restoreInMemory = {
                processValue = previousValue
                writes += previousValue
            },
        )

        assertFalse(committed)
        assertEquals("content://old", processValue)
        assertEquals(listOf("content://new", "content://old"), writes)
    }

    @Test
    fun failedExportDirectoryCommit_preservesMalformedPriorProcessValue() {
        var processValue: Any? = 17
        val previous = durablePreferenceValueSnapshot(
            mapOf(PrefKey.EXPORT_DIRECTORY_URI.name to processValue),
            PrefKey.EXPORT_DIRECTORY_URI,
        )

        val committed = commitConfiguredExportTreeUriChange(
            updatedValue = "content://docs/tree/new",
            write = { value ->
                processValue = value
                value != "content://docs/tree/new"
            },
            restoreInMemory = { processValue = previous.value },
        )

        assertFalse(committed)
        assertTrue(previous.present)
        assertEquals(17, processValue)
    }

    @Test
    fun failedExportDirectoryRemoval_restoresPreviousProcessValue() {
        var processValue: String? = "content://old"
        val writes = mutableListOf<String?>()

        val previousValue = processValue
        val committed = commitConfiguredExportTreeUriChange(
            updatedValue = null,
            write = { value ->
                processValue = value
                writes += value
                value != null
            },
            restoreInMemory = {
                processValue = previousValue
                writes += previousValue
            },
        )

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
