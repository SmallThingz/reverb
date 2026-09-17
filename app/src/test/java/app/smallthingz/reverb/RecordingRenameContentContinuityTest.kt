package app.smallthingz.reverb

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordingRenameContentContinuityTest {
    @Test
    fun renameRebind_requiresSameObjectAndExactBytes() {
        val before = CopyDigest(4L, byteArrayOf(1, 2, 3, 4))
        val same = CopyDigest(4L, byteArrayOf(1, 2, 3, 4))
        val changed = CopyDigest(4L, byteArrayOf(4, 3, 2, 1))

        assertTrue(renameContentContinuityIsSafe(true, before, same))
        assertFalse(renameContentContinuityIsSafe(false, before, same))
        assertFalse(renameContentContinuityIsSafe(true, before, changed))
        assertFalse(renameContentContinuityIsSafe(true, before, null))
        assertFalse(renameContentContinuityIsSafe(true, null, same))
    }
}
