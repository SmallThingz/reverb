package app.smallthingz.reverb

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptureScreenDetachedExportTest {
    @Test
    fun detachedSaveSuccessFallsBackWhenNotificationCannotBeShown() {
        assertTrue(detachedSaveSuccessNeedsInAppFallback(false, true, false, true))
        assertTrue(detachedSaveSuccessNeedsInAppFallback(true, false, false, true))
        assertTrue(detachedSaveSuccessNeedsInAppFallback(true, true, true, false))
        assertFalse(detachedSaveSuccessNeedsInAppFallback(true, true, true, true))
        assertFalse(detachedSaveSuccessNeedsInAppFallback(true, true, false, false))
    }
}
