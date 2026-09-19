package app.smallthingz.reverb

import java.io.Closeable
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class CloseableOwnershipTest {
    @Test
    fun openFailure_closesOwnerAndPreservesPrimaryFailure() {
        val owner = TestCloseable()
        val primary = IOException("open failed")

        val thrown = org.junit.Assert.assertThrows(IOException::class.java) {
            openChildOrCloseOwner(owner) { throw primary }
        }

        assertSame(primary, thrown)
        assertTrue(owner.closed)
    }

    @Test
    fun closeFailure_isSuppressedOnOpenFailure() {
        val closeFailure = IOException("close failed")
        val owner = TestCloseable(closeFailure)
        val primary = IOException("open failed")

        val thrown = org.junit.Assert.assertThrows(IOException::class.java) {
            openChildOrCloseOwner(owner) { throw primary }
        }

        assertSame(primary, thrown)
        assertEquals(listOf(closeFailure), thrown.suppressed.toList())
        assertTrue(owner.closed)
    }

    @Test
    fun successfulOpen_keepsOwnerOpenForChildLifetime() {
        val owner = TestCloseable()

        val child = openChildOrCloseOwner(owner) { "child" }

        assertEquals("child", child)
        assertFalse(owner.closed)
    }

    @Test
    fun configurationFailure_releasesNonCloseableOwner() {
        val owner = TestOwnedResource()
        val primary = IOException("configure failed")

        val thrown = org.junit.Assert.assertThrows(IOException::class.java) {
            configureOwnedResourceOrRelease(
                owner = owner,
                configure = { throw primary },
                release = { it.release() },
            )
        }

        assertSame(primary, thrown)
        assertTrue(owner.released)
    }

    @Test
    fun releaseFailure_isSuppressedOnConfigurationFailure() {
        val releaseFailure = IOException("release failed")
        val owner = TestOwnedResource(releaseFailure)
        val primary = IOException("configure failed")

        val thrown = org.junit.Assert.assertThrows(IOException::class.java) {
            configureOwnedResourceOrRelease(
                owner = owner,
                configure = { throw primary },
                release = { it.release() },
            )
        }

        assertSame(primary, thrown)
        assertEquals(listOf(releaseFailure), thrown.suppressed.toList())
        assertTrue(owner.released)
    }

    @Test
    fun successfulConfiguration_transfersOwnerWithoutRelease() {
        val owner = TestOwnedResource()

        val configured = configureOwnedResourceOrRelease(
            owner = owner,
            configure = {},
            release = { it.release() },
        )

        assertSame(owner, configured)
        assertFalse(owner.released)
    }

    @Test
    fun ownedUse_successReleasesOwnerAndReturnsResult() {
        val owner = TestOwnedResource()

        val result = withOwnedResource(
            owner = owner,
            release = { it.release() },
        ) { "result" }

        assertEquals("result", result)
        assertTrue(owner.released)
    }

    @Test
    fun ownedUse_failureReleasesOwnerAndPreservesPrimary() {
        val owner = TestOwnedResource()
        val primary = IOException("probe failed")

        val thrown = org.junit.Assert.assertThrows(IOException::class.java) {
            withOwnedResource(
                owner = owner,
                release = { it.release() },
            ) { throw primary }
        }

        assertSame(primary, thrown)
        assertTrue(owner.released)
    }

    @Test
    fun ownedUse_releaseFailureIsSuppressedOnPrimary() {
        val releaseFailure = IOException("release failed")
        val owner = TestOwnedResource(releaseFailure)
        val primary = IOException("probe failed")

        val thrown = org.junit.Assert.assertThrows(IOException::class.java) {
            withOwnedResource(
                owner = owner,
                release = { it.release() },
            ) { throw primary }
        }

        assertSame(primary, thrown)
        assertEquals(listOf(releaseFailure), thrown.suppressed.toList())
        assertTrue(owner.released)
    }

    @Test
    fun ownedUse_releaseFailureAfterSuccessBecomesTerminalFailure() {
        val releaseFailure = IOException("release failed")
        val owner = TestOwnedResource(releaseFailure)

        val thrown = org.junit.Assert.assertThrows(IOException::class.java) {
            withOwnedResource(
                owner = owner,
                release = { it.release() },
            ) { "result" }
        }

        assertSame(releaseFailure, thrown)
        assertTrue(owner.released)
    }

    private class TestOwnedResource(
        private val releaseFailure: IOException? = null,
    ) {
        var released = false
            private set

        fun release() {
            released = true
            releaseFailure?.let { throw it }
        }
    }

    private class TestCloseable(
        private val closeFailure: IOException? = null,
    ) : Closeable {
        var closed = false
            private set

        override fun close() {
            closed = true
            closeFailure?.let { throw it }
        }
    }

    @Test
    fun closeFailure_becomesPrimaryWhenThereWasNoEarlierFailure() {
        val closeFailure = IOException("close failed")

        val observed = closePreservingPrimaryFailure(null) { throw closeFailure }

        assertSame(closeFailure, observed)
    }

    @Test
    fun closeFailure_isSuppressedOnExistingPrimaryFailure() {
        val primary = IOException("write failed")
        val closeFailure = IOException("close failed")

        val observed = closePreservingPrimaryFailure(primary) { throw closeFailure }

        assertSame(primary, observed)
        assertEquals(listOf(closeFailure), primary.suppressed.toList())
    }

    @Test
    fun successfulClose_preservesExistingPrimaryFailure() {
        val primary = IOException("write failed")

        val observed = closePreservingPrimaryFailure(primary) {}

        assertSame(primary, observed)
    }

    @Test
    fun rejectedOwner_closeFailureIsSuppressedOnPrimaryRejection() {
        val primary = IOException("identity changed")
        val closeFailure = IOException("descriptor close failed")

        val thrown = org.junit.Assert.assertThrows(IOException::class.java) {
            throwAfterClosePreservingPrimary(primary) { throw closeFailure }
        }

        assertSame(primary, thrown)
        assertEquals(listOf(closeFailure), primary.suppressed.toList())
    }

    @Test
    fun rejectedOwnerWithoutPrimary_surfacesCloseFailure() {
        val closeFailure = IOException("descriptor close failed")

        val thrown = org.junit.Assert.assertThrows(IOException::class.java) {
            closeRejectedOwnerOrThrow { throw closeFailure }
        }

        assertSame(closeFailure, thrown)
    }

    @Test
    fun rejectedOwnerWithoutPrimary_acceptsSuccessfulClose() {
        var closed = false

        closeRejectedOwnerOrThrow { closed = true }

        assertTrue(closed)
    }

}
