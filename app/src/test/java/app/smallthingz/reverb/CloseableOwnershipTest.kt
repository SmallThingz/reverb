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
}
