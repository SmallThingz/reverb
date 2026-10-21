package app.smallthingz.reverb.nativeui

import org.junit.Assert.*
import org.junit.Test

class ReverbTopBarHitTestTest {
    @Test fun lowerZDirectBrandBeatsIncidentExpandedEdge() {
        val targets = listOf(ReverbTopBarHitBounds(0f, 0f, 46f, 46f, 15f),
            ReverbTopBarHitBounds(45f, 0f, 46f, 46f, 23f))
        assertEquals(1, reverbTopBarHitIndex(targets, 46.5f, 23f, true, 48f))
    }
    @Test fun circleCornerIsNotAnAdmittedDirectHit() {
        val targets = listOf(ReverbTopBarHitBounds(0f, 0f, 46f, 46f, 23f),
            ReverbTopBarHitBounds(0f, 0f, 46f, 46f, 0f))
        assertEquals(1, reverbTopBarHitIndex(targets, 1f, 1f, true, 48f))
        assertEquals(1, reverbTopBarHitIndex(targets, 1f, 1f, false, 48f))
    }
    @Test fun closestNearHitBeatsEarlierFartherNearHit() {
        val targets = listOf(ReverbTopBarHitBounds(0f, 0f, 46f, 46f, 0f),
            ReverbTopBarHitBounds(47f, 0f, 46f, 46f, 0f))
        assertEquals(1, reverbTopBarHitIndex(targets, 46.75f, 23f, true, 48f))
    }
    @Test fun equalNearDistanceKeepsHighestZSibling() {
        val targets = listOf(ReverbTopBarHitBounds(0f, 0f, 46f, 46f, 0f),
            ReverbTopBarHitBounds(47f, 0f, 46f, 46f, 0f))
        assertEquals(0, reverbTopBarHitIndex(targets, 46.5f, 23f, true, 48f))
    }
    @Test fun mouseDoesNotUseTouchExpansionOrClippedCorners() {
        val targets = listOf(ReverbTopBarHitBounds(0f, 0f, 46f, 46f, 23f))
        assertNull(reverbTopBarHitIndex(targets, 46.5f, 23f, false, 48f))
        assertNull(reverbTopBarHitIndex(targets, 1f, 1f, false, 48f))
    }
}
