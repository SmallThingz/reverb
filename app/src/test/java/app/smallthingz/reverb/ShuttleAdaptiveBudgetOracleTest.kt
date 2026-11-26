package app.smallthingz.reverb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ShuttleAdaptiveBudgetOracleTest {
    @Test fun alignmentDegradesOnlyAfterRepeatedMeasuredDeadlinePressureWithALowQueue() {
        for (sampleRate in listOf(48_000, 96_000)) {
            val budget = ShuttleRenderBudget(sampleRate)
            val scarce = frames(sampleRate, 3).toLong()
            val buffered = frames(sampleRate, 6).toLong()

            for (atMs in 16L..160L step 16L) {
                assertFalse(budget.observeGrainRender(ns(atMs), ns(5), scarce, ns(15)))
                assertFalse(budget.observeGrainRender(ns(atMs + 1), ns(5), buffered, ns(30)))
            }
            assertTrue("Neither available headroom nor cheap renders warrant lower quality", budget.phaseAlignmentEnabled)
            assertFalse(budget.observeGrainRender(ns(180), ns(5), scarce, ns(18)))
            assertFalse(budget.observeGrainRender(ns(550), ns(5), scarce, ns(18)))
            assertFalse(budget.observeGrainRender(ns(570), ns(60), scarce, ns(32)))
            assertFalse(budget.observeGrainRender(ns(580), ns(5), scarce, ns(18)))
            assertTrue("A stale command and separated spikes must not combine", budget.phaseAlignmentEnabled)

            assertTrue("Two fresh missed deadlines with little queued PCM must reduce alignment work",
                budget.observeGrainRender(ns(610), ns(5), scarce, ns(16)))
            assertFalse(budget.phaseAlignmentEnabled)
            assertEquals("Grain pressure must not expand the unity queue", 0, budget.extraUnityLookaheadFrames)
            assertFalse("An already-adapted epoch must not adapt again",
                budget.observeGrainRender(ns(640), ns(5), 0L, ns(25)))
            assertTrue("A fresh track must start with full-quality alignment", ShuttleRenderBudget(sampleRate).phaseAlignmentEnabled)
        }
    }

    @Test fun unityLookaheadRequiresNewUnderrunsDuringFreshLowQueueMotion() {
        for (sampleRate in listOf(48_000, 96_000)) {
            val budget = ShuttleRenderBudget(sampleRate)
            val scarce = frames(sampleRate, 2).toLong()
            val buffered = frames(sampleRate, 6).toLong()
            assertFalse(budget.observeUnity(ns(100), ns(5), scarce, 7)) // Baseline, not seven new underruns.
            assertFalse(budget.observeUnity(ns(120), ns(5), scarce, 7))
            assertFalse(budget.observeUnity(ns(140), ns(5), buffered, 9))
            assertFalse(budget.observeUnity(ns(160), ns(50), scarce, 10))
            assertFalse(budget.observeUnity(ns(180), ns(5), scarce, 10))
            assertFalse(budget.observeUnity(ns(200), ns(5), scarce, 11))
            budget.discardUnityPressure()
            assertFalse(budget.observeUnity(ns(220), ns(5), scarce, 15))
            assertFalse(budget.observeUnity(ns(240), ns(5), scarce, 16))
            assertEquals(0, budget.extraUnityLookaheadFrames)

            assertTrue(budget.observeUnity(ns(270), ns(5), scarce, 17))
            assertEquals(frames(sampleRate, 8), budget.extraUnityLookaheadFrames)
            assertTrue("Unity needs headroom without degrading the grain filter", budget.phaseAlignmentEnabled)
            assertFalse(budget.observeUnity(ns(310), ns(5), scarce, 200))
            assertEquals("Render pressure cannot cause unbounded growth", frames(sampleRate, 8), budget.extraUnityLookaheadFrames)
            assertEquals(0, ShuttleRenderBudget(sampleRate).extraUnityLookaheadFrames)
        }
    }

    @Test fun measuredUnityAdaptationAddsBoundedHeadroomWithoutDuplicatingOrReversingFrames() {
        for (sampleRate in listOf(48_000, 96_000)) for (direction in listOf(1, -1)) {
            val budget = ShuttleRenderBudget(sampleRate)
            assertFalse(budget.observeUnity(ns(100), ns(4), 0L, 0))
            assertFalse(budget.observeUnity(ns(135), ns(4), 0L, 1))
            assertTrue(budget.observeUnity(ns(170), ns(4), 0L, 2))
            val origin = 2.0
            val target = origin + direction * .020

            fun drain(extraFrames: Int): Long {
                val cursor = ShuttleUnityCursor(sampleRate, reverse = direction < 0)
                val prime = requireNotNull(cursor.next(origin, 0.0, 5.0, extraFrames))
                var edge = if (direction > 0) prime.endFrame else prime.firstFrame
                for (attempt in 0 until 4) {
                    val next = cursor.next(target, 0.0, 5.0, extraFrames) ?: break
                    assertFalse(next.fadeIn)
                    assertEquals(edge, if (direction > 0) next.firstFrame else next.endFrame)
                    assertEquals(direction < 0, next.reverse)
                    edge = if (direction > 0) next.endFrame else next.firstFrame
                }
                assertNull(cursor.next(target, 0.0, 5.0, extraFrames))
                return ((edge - origin * sampleRate) * direction).toLong()
            }

            val healthy = drain(0)
            val adapted = drain(budget.extraUnityLookaheadFrames)
            assertTrue("Adaptation should keep one more packet available for this arrival phase", adapted > healthy)
            assertTrue("Adaptive source read-ahead exceeded the 64ms acceptance bound",
                adapted <= frames(sampleRate, 64))
        }
    }

    private fun ns(ms: Long): Long = ms * 1_000_000
    private fun frames(sampleRate: Int, ms: Int): Int = sampleRate * ms / 1000
}
