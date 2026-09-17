package app.smallthingz.reverb

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordingRegistrationPolicyTest {
    @Test
    fun catalogRegistration_requiresKnownCurrentPhysicalIdentity() {
        assertTrue(
            recordingRegistrationIdentityIsCurrent(
                stableIdentityAvailable = true,
                currentIdentityMatches = true,
            ),
        )
        assertFalse(
            recordingRegistrationIdentityIsCurrent(
                stableIdentityAvailable = false,
                currentIdentityMatches = true,
            ),
        )
        assertFalse(
            recordingRegistrationIdentityIsCurrent(
                stableIdentityAvailable = true,
                currentIdentityMatches = false,
            ),
        )
    }
}
