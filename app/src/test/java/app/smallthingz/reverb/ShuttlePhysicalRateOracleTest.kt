package app.smallthingz.reverb

import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ShuttlePhysicalRateOracleTest {
    @Test fun equalPhysicalMotionOnShortAndLongTimelinesCommandsTheSamePitch() {
        val shortDuration = 12f
        val longDuration = 1_200f
        val verticalPull = -.8f
        val frameSeconds = listOf(.008f, .016f, .017f, .024f, .033f, .049f)
        for (speed in listOf(.5f, 1f, 2f, 3f)) {
            for (direction in listOf(-1, 1)) {
                val signedSpeed = speed * direction
                val shortPull = solveVisualPull(signedSpeed, shortDuration, verticalPull)
                val longPull = solveVisualPull(signedSpeed, longDuration, verticalPull)
                assertTrue("Same physical speed should require different pulls", abs(shortPull - longPull) > .05f)
                val pitches = listOf(shortDuration to shortPull, longDuration to longPull).map { (duration, pull) ->
                    val command = rangeFineTuneShuttleRate(pull, verticalPull, duration)
                    val distanceSeconds = frameSeconds.sumOf { displayDelta(pull, verticalPull, duration, it).toDouble() }
                    val wallSeconds = frameSeconds.sumOf { it.toDouble() }
                    assertEquals("command for ${duration}s", signedSpeed, command, .0003f)
                    assertEquals("source seconds per wall second for ${duration}s",
                        distanceSeconds / wallSeconds, command.toDouble(), .0003)
                    shuttleAudibleSpeed(command)
                }
                assertEquals("pitch for matched physical rate $signedSpeed", pitches[0], pitches[1], .00004f)
            }
        }
    }

    @Test fun suddenlyChangingPullPublishesTheNewPhysicalSpeedInTheSameFrame() {
        val duration = 300f
        val frames = listOf(
            Triple(.22f, -.7f, .016f),
            Triple(.77f, -.7f, .008f),
            Triple(.0f, .0f, .033f),
            Triple(-.52f, -.3f, .020f),
            Triple(-.11f, .48f, .048f),
        )
        val commands = frames.map { (pull, vertical, dt) ->
            val actualMotion = displayDelta(pull, vertical, duration, dt)
            val command = rangeFineTuneShuttleRate(pull, vertical, duration)
            assertEquals("instantaneous physical rate at pull $pull", actualMotion / dt, command, .0002f)
            command
        }
        assertTrue(commands[0] > 0f)
        assertTrue(commands[1] > commands[0] * 4f)
        assertEquals(0f, commands[2], 0f)
        assertTrue(commands[3] < commands[4] && commands[4] < 0f)
    }

    private fun displayDelta(pull: Float, vertical: Float, duration: Float, dt: Float): Float =
        rangeFineTuneDeltaSeconds(rangeFineTuneSeekPull(pull), vertical, duration, dt)

    private fun solveVisualPull(speed: Float, duration: Float, vertical: Float): Float {
        // Invert the visible displacement over a frame rather than solving against the audio API.
        val dt = .02f
        var lower = 0f
        var upper = 1f
        repeat(25) {
            val candidate = (lower + upper) * .5f
            if (displayDelta(candidate, vertical, duration, dt) / dt < abs(speed)) lower = candidate
            else upper = candidate
        }
        val pull = (lower + upper) * .5f
        return if (speed < 0f) -pull else pull
    }
}
