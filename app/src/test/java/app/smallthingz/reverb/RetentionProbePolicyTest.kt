package app.smallthingz.reverb

import org.junit.Assert.assertEquals
import org.junit.Test

class RetentionProbePolicyTest {
    @Test fun skippedHistoryProbeCannotChangeOperationalResolution() {
        val a = RetentionConfiguration(RetentionMode.SIZE, 60L, 65536L, 120L, 131072L)
        val b = a.copy(loopingSizeBytes = 262144L)
        for (primary in listOf(null, a, b)) for (recovery in listOf(null, a, b)) {
            val probe = retentionResolutionNeedsHistoryProbe(primary, recovery)
            for (history in listOf(false, true)) {
                assertEquals(resolveRetentionConfiguration(primary, recovery, history),
                    resolveRetentionConfiguration(primary, recovery, probe && history))
            }
        }
    }
}
