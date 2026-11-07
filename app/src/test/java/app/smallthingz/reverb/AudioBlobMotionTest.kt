package app.smallthingz.reverb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs

class AudioBlobMotionTest {
    @Test fun silenceCalmsMotionAndDigitalZeroHasNoNewMotion() {
        assertEquals(0f, audioBlobMotionEnergy(0f, 0f, 0f, 0f), 0f)
        val audible = audioBlobMotionEnergy(.5f, .7f, .2f, 0f)
        val suppressed = audioBlobMotionEnergy(.5f, .7f, .2f, 1f)
        assertEquals(audible * .08f, suppressed, .00001f)
        assertTrue(audioBlobMotionEnergy(.04f, 0f, 0f, 0f) > .15f)
    }

    @Test fun audibleMaterialEvolvesAndStaysInsideExistingFootprint() {
        var largestChange = 0f
        for (low in listOf(0f, .2f, 1f)) for (high in listOf(0f, .2f, 1f)) {
            val energy = audioBlobMotionEnergy(.5f, low, high, 0f)
            for (step in 0..360) {
                val angle = step * PI.toFloat() / 180f
                val a = audioBlobFold(angle, 0f, energy, low, high)
                val b = audioBlobFold(angle, 1.3f, energy, low, high)
                assertTrue(abs(a) <= .09601f && abs(b) <= .09601f)
                // Largest base radius + bounded fold + idle + glow stays within the .495 clip.
                assertTrue(.348f + abs(a) + .008f + .024f < .495f)
                largestChange = maxOf(largestChange, abs(a - b))
            }
        }
        assertTrue(largestChange > .03f)
    }
}
