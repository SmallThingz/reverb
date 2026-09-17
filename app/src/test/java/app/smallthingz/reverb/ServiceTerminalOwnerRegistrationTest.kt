package app.smallthingz.reverb

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ServiceTerminalOwnerRegistrationTest {
    @Test
    fun terminalService_rejectsNewOwners_butAllowsOwnerRelease() {
        assertTrue(serviceOwnerRegistrationMayApply(serviceDestroying = false, registering = true))
        assertFalse(serviceOwnerRegistrationMayApply(serviceDestroying = true, registering = true))
        assertTrue(serviceOwnerRegistrationMayApply(serviceDestroying = true, registering = false))
    }

    @Test
    fun ownerRegistries_canRevokeAStaleLateRegistrationWithoutDroppingFallback() {
        val registry = IdentityOwnerRegistry<Any>()
        val first = Any()
        val stale = Any()
        registry.register(first)
        registry.register(stale)
        assertTrue(registry.unregister(stale))
        assertTrue(registry.current() === first)
    }
}
