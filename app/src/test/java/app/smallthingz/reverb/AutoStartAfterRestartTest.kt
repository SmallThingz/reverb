package app.smallthingz.reverb

import java.io.IOException
import org.junit.Assert.*
import org.junit.Test

class AutoStartAfterRestartTest {
    @Test fun missingPreferenceAndUnloadedStateNeverAdmitAutomaticLaunch() {
        val policy = AutoStartPolicy()
        assertNull(policy.admission())
        policy.load(policy.revision(), false)
        assertTrue(policy.state.value.loaded)
        assertFalse(policy.state.value.enabled)
        assertNull(policy.admission())
    }

    @Test fun permissionGrantRequiresCurrentExplicitEnableRequest() {
        val policy = AutoStartPolicy()
        policy.load(0L, false)
        assertNull(policy.permissionReturned(0L, true))
        val request = policy.requestPermission()
        assertNull(policy.admission())
        val write = requireNotNull(policy.permissionReturned(request, true))
        assertTrue(write.enabled)
        assertNull(policy.admission()) // A grant is not a completed durable write.
        policy.complete(write, true)
        assertNotNull(policy.admission())
        assertNull(policy.permissionReturned(request, true))
    }

    @Test fun deniedCancelledOrRetiredPermissionReturnCannotOptIn() {
        val policy = AutoStartPolicy()
        policy.load(0L, false)
        val denied = policy.requestPermission()
        assertNull(policy.permissionReturned(denied, false))
        assertFalse(policy.state.value.enabled)
        val cancelled = policy.requestPermission()
        policy.cancelPermission(cancelled)
        assertNull(policy.permissionReturned(cancelled, true))
        val stale = policy.requestPermission()
        val off = policy.request(false)
        assertNull(policy.permissionReturned(stale, true))
        policy.complete(off, false)
        assertNull(policy.admission())
    }

    @Test fun offImmediatelyRevokesBootLaunchAndStaleOnCompletionCannotReviveIt() {
        val policy = AutoStartPolicy()
        policy.load(0L, true)
        val boot = requireNotNull(policy.admission())
        val on = policy.request(true)
        val off = policy.request(false)
        assertFalse(policy.currentAdmission(boot))
        assertFalse(policy.current(on))
        policy.complete(on, true)
        assertFalse(policy.state.value.enabled)
        assertNull(policy.admission())
        policy.complete(off, false)
        policy.load(on.revision, true) // A delayed refresh has no new authority either.
        assertFalse(policy.state.value.enabled)
    }

    @Test fun failedOffRestoresAndReportsActualDurableOnWithoutRevivingOldLaunch() {
        val policy = AutoStartPolicy()
        policy.load(0L, true)
        val boot = requireNotNull(policy.admission())
        val off = policy.request(false)
        var persisted = true
        val result = writeAutoStartChoice(previousEnabled = true, enabled = false,
            commit = { persisted = false; false }, // SharedPreferences already changed its map.
            restore = { persisted = true; true })
        policy.complete(off, result.storedEnabled)
        assertNotNull(result.failure)
        assertTrue(persisted)
        assertEquals(true, result.storedEnabled)
        assertTrue(policy.state.value.enabled)
        assertFalse(policy.currentAdmission(boot))
    }

    @Test fun uncertainRollbackCannotBecomeOptInThroughPlainRefresh() {
        val policy = AutoStartPolicy()
        policy.load(0L, true)
        val off = policy.request(false)
        val primary = IOException("write")
        val result = writeAutoStartChoice(true, false, commit = { throw primary }, restore = { false })
        assertSame(primary, result.failure)
        assertEquals(1, primary.suppressed.size)
        assertNull(result.storedEnabled)
        policy.complete(off, result.storedEnabled)
        policy.load(policy.revision(), true)
        assertTrue(policy.state.value.unavailable)
        assertNull(policy.admission())
        val repair = policy.request(false)
        val repaired = writeAutoStartChoice(true, false, commit = { true }, restore = { error("No rollback after success") })
        policy.complete(repair, repaired.storedEnabled)
        assertFalse(policy.state.value.unavailable)
        assertFalse(policy.state.value.enabled)
    }

    @Test fun staleHydrationCannotBlessAnUncommittedEnable() {
        val policy = AutoStartPolicy()
        val read = policy.revision()
        val enable = policy.request(true)
        policy.load(read, false)
        assertTrue(policy.state.value.saving)
        assertNull(policy.admission())
        policy.complete(enable, true)
        assertNotNull(policy.admission())
    }
}
