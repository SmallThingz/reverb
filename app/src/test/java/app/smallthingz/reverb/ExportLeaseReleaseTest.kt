package app.smallthingz.reverb

import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class ExportLeaseReleaseTest {
    @Test
    fun releaseFailure_isCapturedAndDoesNotRetryOwnedLease() {
        val released = AtomicBoolean(false)
        val expected = IOException("release failed")
        var attempts = 0

        val first = releaseExportLeaseOnceBestEffort(released) {
            attempts++
            throw expected
        }
        val second = releaseExportLeaseOnceBestEffort(released) {
            attempts++
        }

        assertSame(expected, first)
        assertNull(second)
        assertEquals(1, attempts)
    }

    @Test
    fun successfulRelease_runsOnlyOnce() {
        val released = AtomicBoolean(false)
        var attempts = 0

        assertNull(releaseExportLeaseOnceBestEffort(released) { attempts++ })
        assertNull(releaseExportLeaseOnceBestEffort(released) { attempts++ })

        assertEquals(1, attempts)
    }
}
