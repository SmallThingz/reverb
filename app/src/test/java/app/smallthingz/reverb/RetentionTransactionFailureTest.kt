package app.smallthingz.reverb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class RetentionTransactionFailureTest {
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

}
