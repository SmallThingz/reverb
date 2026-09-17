package app.smallthingz.reverb

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordingCatalogIdentityPolicyTest {
    @Test
    fun catalogMutation_requiresKnownCurrentPhysicalIdentity() {
        assertTrue(
            recordingCatalogIdentityIsCurrent(
                stableIdentityAvailable = true,
                currentIdentityMatches = true,
            ),
        )
        assertFalse(
            recordingCatalogIdentityIsCurrent(
                stableIdentityAvailable = false,
                currentIdentityMatches = true,
            ),
        )
        assertFalse(
            recordingCatalogIdentityIsCurrent(
                stableIdentityAvailable = true,
                currentIdentityMatches = false,
            ),
        )
    }
}
