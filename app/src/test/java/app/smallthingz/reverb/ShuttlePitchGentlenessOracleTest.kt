package app.smallthingz.reverb

import kotlin.math.log2
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ShuttlePitchGentlenessOracleTest {
    @Test fun pitchStaysAtUnityThroughSubUnitSpeedInEitherDirection() {
        for (speed in listOf(0f, .1f, .5f, .99f, 1f)) {
            assertEquals(1f, shuttleAudibleSpeed(speed), 0f)
            assertEquals(1f, shuttleAudibleSpeed(-speed), 0f)
        }
        assertTrue("Crossing 1x should not produce a perceptible pitch step", cents(1.001f) in 0.0..1.0)
    }

    @Test fun measuredPhysicalSpeedHasGentleFeedbackAndDoesNotRunAwayAtFastScrubRates() {
        // Perceptual acceptance bands, in cents, leave headroom for different gentle curves.
        // The former linear policy is already about 165 cents at 2x and 919 cents at 8x.
        assertTrue("2x should raise pitch by less than 1.4 semitones", cents(2f) in 25.0..140.0)
        assertTrue("8x should raise pitch by less than 4 semitones", cents(8f) in 140.0..400.0)
        assertTrue("64x should remain under 7 semitones", cents(64f) in 250.0..700.0)
        val maxAcceptedRate = sanitizedShuttleSourceRate(Float.MAX_VALUE)
        assertTrue(cents(maxAcceptedRate) <= 1_050.0)
        assertEquals(cents(maxAcceptedRate), cents(sanitizedShuttleSourceRate(-Float.MAX_VALUE)), 0.0)

        var rate = 1f
        var previous = cents(rate)
        while (rate * 2 <= maxAcceptedRate) {
            rate *= 2
            val current = cents(rate)
            assertEquals("Pitch does not depend on transport direction", current, cents(-rate), 0.0)
            assertTrue("Each doubling must be monotone with at most a small audible increase at $rate",
                current - previous in 0.0..145.0)
            previous = current
        }
    }

    private fun cents(rate: Float): Double = 1_200.0 * log2(shuttleAudibleSpeed(rate).toDouble())
}
