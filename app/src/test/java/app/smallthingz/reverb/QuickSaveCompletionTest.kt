package app.smallthingz.reverb

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QuickSaveCompletionTest {
    @Test fun shortenedSaveUsesFinalDurationAndToleratesOnlyMillisecondQuantization() {
        assertFalse(quickSaveWasShortened(120, 120_000L))
        assertFalse(quickSaveWasShortened(120, 119_999L))
        assertTrue(quickSaveWasShortened(120, 119_998L))
        assertTrue(quickSaveWasShortened(120, 32_500L))
        assertTrue(quickSaveWasShortened(86_400, 0L))
        assertFalse(quickSaveWasShortened(120, Long.MAX_VALUE))
    }

    @Test fun ordinaryExportsAndInvalidRequestedDurationsDoNotClaimAQuickSaveShortfall() {
        assertFalse(quickSaveWasShortened(null, 10_000L))
        for (requested in listOf(-1, 0, 86_401, Int.MAX_VALUE)) {
            assertFalse(quickSaveWasShortened(requested, 10_000L))
        }
        assertFalse(quickSaveWasShortened(120, -1L))
    }
}
