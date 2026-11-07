package app.smallthingz.reverb.nativeui

import org.junit.Assert.assertEquals
import org.junit.Test

class PendingSaveProgressTest {
    @Test fun staleUnknownAndInvalidSamplesDoNotRegressAcceptedProgress() {
        for (sample in listOf(null, Float.NaN, Float.POSITIVE_INFINITY, -.2f, .1f)) {
            assertEquals(.7f, pendingSaveProgressTarget(.7f, sample, false), 0f)
        }
    }

    @Test fun pendingWorkCannotClaimCompletionEvenWithTerminalOrOversizedSample() {
        for (sample in listOf(.99f, 1f, 3f)) {
            assertEquals(.99f, pendingSaveProgressTarget(.7f, sample, false), 0f)
        }
    }

    @Test fun failureClearsFillAndRetryCanBeginAgain() {
        val failed = pendingSaveProgressTarget(.9f, .95f, true)
        assertEquals(0f, failed, 0f)
        assertEquals(.05f, pendingSaveProgressTarget(failed, .05f, false), 0f)
    }
}
