package app.smallthingz.reverb

import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ShuttleTransportDeadlineOracleTest {
    @Test fun stalledDisplayCannotBuildAnUnboundedUnityPcmQueueEvenWithImmediateWrites() {
        val commandNanos = 2_000_000_000L
        val atSeconds = 4.0
        for (sampleRate in listOf(48_000, 96_000)) for (direction in listOf(1, -1)) {
            val rate = direction.toFloat()
            val cursor = ShuttleUnityCursor(sampleRate, reverse = direction < 0)
            assertEquals(atSeconds, shuttleUnityTargetSeconds(atSeconds, rate, commandNanos, commandNanos - 1), 0.0)
            val fresh = requireNotNull(cursor.next(atSeconds, 0.0, 9.0))
            assertEquals(sampleRate * 24L / 1000, fresh.endFrame - fresh.firstFrame)
            assertTrue(fresh.fadeIn)
            assertNull(cursor.next(atSeconds, 0.0, 9.0))

            for (elapsedMs in listOf(8L, 16L, 24L, 40L, 100L, 500L, 2_000L)) {
                val projected = shuttleUnityTargetSeconds(atSeconds, rate, commandNanos,
                    commandNanos + elapsedMs * 1_000_000)
                if (elapsedMs <= 24) assertEquals(atSeconds + direction * elapsedMs / 1000.0, projected, 1e-9)
                else assertTrue(abs(projected - atSeconds) <= .025)

                // Drain with no simulated native backpressure and then query again. This
                // exposes the production scheduler's bound in source frames, independent
                // of a physical device's sink latency or its buffer capacity.
                for (attempt in 0 until 4) {
                    val next = cursor.next(projected, 0.0, 9.0) ?: break
                    assertFalse("A stale command must not re-anchor", next.fadeIn)
                }
                assertNull("The last display sample cannot drive open-loop audio", cursor.next(projected, 0.0, 9.0))
                val horizon = (requireNotNull(cursor.nextSeconds) - atSeconds) * direction
                assertTrue("Queued source exceeds the 60ms acceptance bound: $horizon", horizon in 0.0..0.060)
            }
        }
    }

    @Test fun irregularCommandReplayNeverTurnsSourceCatchUpIntoAnUnboundedBacklog() {
        for (direction in listOf(1, -1)) {
            var target = 20.0
            var position = target
            for ((speed, elapsedMs) in listOf(1f to 16, 1f to 8, 1f to 8, 12f to 16,
                12f to 8, 1f to 16, 1f to 100, .4f to 10, .4f to 40)) {
                val signedRate = direction * speed
                target += signedRate * elapsedMs / 1_000.0
                val lag = (target - position) * direction
                val next = nextShuttleSourceAnchorSeconds(position, target, signedRate)
                if (next == null) {
                    assertTrue("Held after a full source hop: $lag seconds", lag < .016)
                    continue
                }
                val advance = (next - position) * direction
                assertTrue("Head moved against the requested direction or overshot", advance > 0.0 && advance <= lag + 1e-9)
                if (lag > .064 + 1e-9) {
                    // Above four ordinary hops there is no stale-source catch-up queue.
                    assertEquals(target, next, 1e-9)
                } else {
                    assertTrue("Skipped ahead beyond one transport hop", advance <= .016 * maxOf(1f, speed) + 1e-9)
                }
                position = next
            }
            assertTrue(abs(target - position) < .016)
        }
    }

    @Test fun reversalThroughZeroRequiresNewQueueBeforeTheFirstOppositeRead() {
        for (direction in listOf(1, -1)) {
            val initial = ShuttlePlaybackAdmission(1.0, 4.0, 0).updated(1.0, 4.0, direction.toFloat())
            val faster = initial.updated(1.0, 4.0, direction * 12f)
            assertFalse(faster.requiresRetirementOf(initial))
            val parked = faster.updated(1.0, 4.0, 0f)
            assertFalse(parked.requiresRetirementOf(faster))
            assertNull(nextShuttleSourceAnchorSeconds(2.0, 2.0, 0f))
            val reversed = parked.updated(1.0, 4.0, -direction.toFloat())
            assertTrue(reversed.requiresRetirementOf(parked))
            val requestedAnchor = if (direction > 0) 2.375 else 1.625
            assertEquals(requestedAnchor, requireNotNull(nextShuttleSourceAnchorSeconds(null, requestedAnchor,
                -direction.toFloat())), 0.0)
        }
    }
}
