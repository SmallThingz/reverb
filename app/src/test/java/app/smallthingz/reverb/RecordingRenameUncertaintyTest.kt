package app.smallthingz.reverb

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordingRenameUncertaintyTest {
    @Test
    fun providerRename_onlyBecomesUncertainAfterAcceptedMutationLosesProof() {
        assertFalse(providerRenameStateIsUncertain(mutationAccepted = false, contentContinuityVerified = false))
        assertFalse(providerRenameStateIsUncertain(mutationAccepted = true, contentContinuityVerified = true))
        assertTrue(providerRenameStateIsUncertain(mutationAccepted = true, contentContinuityVerified = false))
    }

    @Test
    fun uncertainRenameSignal_isAnIoFailureForRepositoryUiPropagation() {
        val error: Throwable = RecordingRenameStateUncertainException("rename state uncertain")
        assertTrue(error is java.io.IOException)
    }
}
