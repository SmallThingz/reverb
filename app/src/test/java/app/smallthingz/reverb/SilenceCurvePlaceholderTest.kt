package app.smallthingz.reverb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SilenceCurvePlaceholderTest {
    @Test fun uncalibratedGraphHasTheCalibratedKnotsAcrossInputRates() {
        val placeholder = uncalibratedSilenceCurve.validate()
        assertEquals(11, placeholder.points.size)
        assertTrue(placeholder.points.all { it.db == 0 })
        for (maximumHz in listOf(3_500, 8_000, 20_000)) {
            val calibrated = sampledSilenceCurve(List(SILENCE_GRAPH_BINS) { -60f }, maximumHz)
            assertEquals(calibrated.map { it.hz }, placeholder.points.map { it.hz })
        }
    }

    @Test fun placeholderCannotEnableSkippingOrReplaceThePersistedDefault() {
        assertFalse(uncalibratedSilenceCurve.enabled)
        val persistedDefault = decodeSilenceGate(null)
        assertFalse(persistedDefault.enabled)
        assertTrue(persistedDefault.points.all { it.db == -50 })
        assertEquals(persistedDefault, decodeSilenceGate(encodeSilenceGate(persistedDefault)))
    }
}
