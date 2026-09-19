package app.smallthingz.reverb

import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordingCatalogRegistrationFailureTest {
    @Test
    fun catalogFailure_rechecksPhysicalIdentityBeforeAllowingSaveSuccess() {
        val databaseFailure = IllegalStateException("database unavailable")

        assertSame(
            databaseFailure,
            catalogRegistrationFailureAfterIdentityRecheck(
                error = databaseFailure,
                stableIdentityAvailable = true,
                currentIdentityMatches = true,
            ),
        )

        val identityFailure = catalogRegistrationFailureAfterIdentityRecheck(
            error = databaseFailure,
            stableIdentityAvailable = true,
            currentIdentityMatches = false,
        )
        assertTrue(identityFailure is RecordingCatalogIdentityChangedException)
        assertSame(databaseFailure, identityFailure.cause)
        assertFalse(catalogRegistrationFailureAllowsVerifiedSaveSuccess(identityFailure))

        assertTrue(
            catalogRegistrationFailureAfterIdentityRecheck(
                error = databaseFailure,
                stableIdentityAvailable = false,
                currentIdentityMatches = true,
            ) is RecordingCatalogIdentityChangedException,
        )
        assertTrue(
            catalogRegistrationFailureAfterIdentityRecheck(
                error = databaseFailure,
                stableIdentityAvailable = true,
                currentIdentityMatches = true,
                currentDisplayNameMatches = false,
            ) is RecordingCatalogIdentityChangedException,
        )
    }

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
