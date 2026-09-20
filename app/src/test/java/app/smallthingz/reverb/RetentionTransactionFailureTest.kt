package app.smallthingz.reverb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class RetentionTransactionFailureTest {
    @Test
    fun persistedHistoryProbe_treatsSymlinkedChunkDirectoryAsPossibleHistory() {
        val root = Files.createTempDirectory("reverb-retention-history-root").toFile()
        val target = Files.createTempDirectory("reverb-retention-history-target")
        val chunks = root.toPath().resolve(BUFFER_CHUNKS_FOLDER_NAME)
        try {
            Files.createSymbolicLink(chunks, target)

            assertTrue(persistedBufferHistoryRootMayContainData(root))
        } finally {
            Files.deleteIfExists(chunks)
            Files.deleteIfExists(target)
            Files.deleteIfExists(root.toPath())
        }
    }

    @Test
    fun atomicWriteFailure_successfulRollbackKeepsBooleanFailureContract() {
        val events = mutableListOf<String>()

        val result = atomicWriteFailureResult(
            primary = IllegalStateException("write failed"),
            rollback = { events += "rollback" },
        )

        assertFalse(result)
        assertEquals(listOf("rollback"), events)
    }

    @Test
    fun atomicWriteFailure_failedRollbackKeepsWriteFailurePrimary() {
        val primary = IllegalStateException("write failed")
        val rollback = IllegalArgumentException("rollback failed")
        var observed: Throwable? = null

        try {
            atomicWriteFailureResult(primary) { throw rollback }
        } catch (error: Throwable) {
            observed = error
        }

        assertSame(primary, observed)
        assertEquals(listOf(rollback), primary.suppressed.toList())
    }

    @Test
    fun recoveryWriteException_restoresRecoveryAndPreservesPrimaryFailure() {
        val primary = IllegalStateException("recovery write failed")
        val events = mutableListOf<String>()
        var observed: Throwable? = null

        try {
            persistRetentionTransaction(
                writeNewRecovery = {
                    events += "recovery:new"
                    throw primary
                },
                commitNewPreferences = {
                    events += "preferences:new"
                    true
                },
                restoreRecovery = {
                    events += "recovery:old"
                    true
                },
                restorePreferences = {
                    events += "preferences:old"
                    true
                },
            )
        } catch (error: Throwable) {
            observed = error
        }

        assertSame(primary, observed)
        assertEquals(listOf("recovery:new", "recovery:old"), events)
    }

    @Test
    fun preferenceCommitException_restoresBothTransactionSides() {
        val primary = IllegalStateException("preference commit failed")
        val events = mutableListOf<String>()
        var observed: Throwable? = null

        try {
            persistRetentionTransaction(
                writeNewRecovery = {
                    events += "recovery:new"
                    true
                },
                commitNewPreferences = {
                    events += "preferences:new"
                    throw primary
                },
                restoreRecovery = {
                    events += "recovery:old"
                    true
                },
                restorePreferences = {
                    events += "preferences:old"
                    true
                },
            )
        } catch (error: Throwable) {
            observed = error
        }

        assertSame(primary, observed)
        assertEquals(
            listOf("recovery:new", "preferences:new", "recovery:old", "preferences:old"),
            events,
        )
    }

    @Test
    fun preferenceCommitException_attemptsEveryRollbackAndSuppressesRollbackFailures() {
        val primary = IllegalStateException("preference commit failed")
        val recoveryRollback = IllegalArgumentException("recovery rollback failed")
        val preferenceRollback = IllegalArgumentException("preference rollback failed")
        val events = mutableListOf<String>()
        var observed: Throwable? = null

        try {
            persistRetentionTransaction(
                writeNewRecovery = { true },
                commitNewPreferences = { throw primary },
                restoreRecovery = {
                    events += "recovery:old"
                    throw recoveryRollback
                },
                restorePreferences = {
                    events += "preferences:old"
                    throw preferenceRollback
                },
            )
        } catch (error: Throwable) {
            observed = error
        }

        assertSame(primary, observed)
        assertEquals(listOf("recovery:old", "preferences:old"), events)
        assertEquals(listOf(recoveryRollback, preferenceRollback), primary.suppressed.toList())
    }

    @Test
    fun falsePreferenceCommit_attemptsPreferencesRollbackEvenWhenRecoveryRollbackThrows() {
        val recoveryRollback = IllegalArgumentException("recovery rollback failed")
        val events = mutableListOf<String>()
        var observed: Throwable? = null

        try {
            persistRetentionTransaction(
                writeNewRecovery = { true },
                commitNewPreferences = { false },
                restoreRecovery = {
                    events += "recovery:old"
                    throw recoveryRollback
                },
                restorePreferences = {
                    events += "preferences:old"
                    true
                },
            )
        } catch (error: Throwable) {
            observed = error
        }

        assertSame(recoveryRollback, observed)
        assertEquals(listOf("recovery:old", "preferences:old"), events)
    }

    @Test
    fun falseRecoveryRollbackIsFailureRatherThanSuccessfulRollback() {
        var observed: Throwable? = null

        try {
            persistRetentionTransaction(
                writeNewRecovery = { false },
                commitNewPreferences = { true },
                restoreRecovery = { false },
                restorePreferences = { true },
            )
        } catch (error: Throwable) {
            observed = error
        }

        assertEquals("Retention recovery rollback returned false", observed?.message)
    }

    @Test
    fun falsePreferenceCommitAttemptsBothFalseRollbacksAndPreservesBothFailures() {
        var observed: Throwable? = null
        val events = mutableListOf<String>()

        try {
            persistRetentionTransaction(
                writeNewRecovery = { true },
                commitNewPreferences = { false },
                restoreRecovery = {
                    events += "recovery"
                    false
                },
                restorePreferences = {
                    events += "preferences"
                    false
                },
            )
        } catch (error: Throwable) {
            observed = error
        }

        assertEquals(listOf("recovery", "preferences"), events)
        assertEquals("Retention recovery rollback returned false", observed?.message)
        assertEquals(
            listOf("Retention preferences rollback returned false"),
            observed?.suppressed?.map { it.message },
        )
    }

    @Test
    fun primaryCommitExceptionKeepsFalseRollbackFailuresSuppressed() {
        val primary = IllegalStateException("primary")
        var observed: Throwable? = null

        try {
            persistRetentionTransaction(
                writeNewRecovery = { true },
                commitNewPreferences = { throw primary },
                restoreRecovery = { false },
                restorePreferences = { false },
            )
        } catch (error: Throwable) {
            observed = error
        }

        assertSame(primary, observed)
        assertEquals(
            listOf(
                "Retention recovery rollback returned false",
                "Retention preferences rollback returned false",
            ),
            primary.suppressed.map { it.message },
        )
    }

    @Test
    fun durabilityBarrier_requiresBothFsyncAndDescriptorClose() {
        val events = mutableListOf<String>()
        assertEquals(
            true,
            durableBarrierAndCloseSucceeded(
                barrier = { events += "fsync" },
                close = { events += "close" },
            ),
        )
        assertEquals(listOf("fsync", "close"), events)

        events.clear()
        assertEquals(
            false,
            durableBarrierAndCloseSucceeded(
                barrier = { events += "fsync" },
                close = { events += "close"; throw IllegalStateException("close failed") },
            ),
        )
        assertEquals(listOf("fsync", "close"), events)
    }

    @Test
    fun durabilityBarrier_attemptsCloseAfterFsyncFailure() {
        val events = mutableListOf<String>()
        assertEquals(
            false,
            durableBarrierAndCloseSucceeded(
                barrier = { events += "fsync"; throw IllegalStateException("fsync failed") },
                close = { events += "close" },
            ),
        )
        assertEquals(listOf("fsync", "close"), events)
    }

}
