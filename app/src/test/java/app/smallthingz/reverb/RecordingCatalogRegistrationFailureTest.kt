package app.smallthingz.reverb

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordingCatalogRegistrationFailureTest {
    @Test
    fun onlyPhysicalIdentityLossRevokesVerifiedSaveSuccess() {
        assertFalse(
            catalogRegistrationFailureAllowsVerifiedSaveSuccess(
                RecordingCatalogIdentityChangedException("output changed"),
            ),
        )
        assertTrue(
            catalogRegistrationFailureAllowsVerifiedSaveSuccess(
                IllegalStateException("database unavailable"),
            ),
        )
    }
}
