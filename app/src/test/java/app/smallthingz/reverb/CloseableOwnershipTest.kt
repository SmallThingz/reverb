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

}
