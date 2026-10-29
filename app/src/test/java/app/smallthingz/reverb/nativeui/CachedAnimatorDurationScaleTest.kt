package app.smallthingz.reverb.nativeui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CachedAnimatorDurationScaleTest {
    @Test fun framesReadMemoryUntilSettingsObserverRefreshes() {
        var setting = 0.5f
        var reads = 0
        val cache = CachedAnimatorDurationScale { reads++; setting }
        setting = 2f
        repeat(120) { assertEquals(0.5f, cache.value, 0f) }
        assertEquals(1, reads)
        cache.refresh()
        repeat(120) { assertEquals(2f, cache.value, 0f) }
        assertEquals(2, reads)
    }

    @Test fun refreshPreservesDisabledFractionalAndSlowAnimationScales() {
        var setting = 1f
        val cache = CachedAnimatorDurationScale { setting }
        for (scale in listOf(0f, 0.5f, 2f, 10f, 1f)) {
            setting = scale
            cache.refresh()
            assertEquals(scale, cache.value, 0f)
        }
    }

    @Test fun failedRefreshDoesNotPublishAnInventedScale() {
        var failure = false
        val cache = CachedAnimatorDurationScale {
            if (failure) throw IllegalStateException("unavailable")
            0f
        }
        failure = true
        assertEquals(1000L, cache.refresh())
        assertEquals(0f, cache.value, 0f)
    }

    @Test fun unavailableProviderRetriesWithBoundedBackoffAndRecovers() {
        var failure = false
        var setting = 0.5f
        val cache = CachedAnimatorDurationScale {
            if (failure) throw IllegalStateException("unavailable")
            setting
        }
        failure = true
        for (delay in listOf(1000L, 2000L, 4000L, 8000L, 16000L, 30000L, 30000L)) {
            assertEquals(delay, cache.refresh())
            assertEquals(0.5f, cache.value, 0f)
        }
        failure = false
        setting = 0f
        assertNull(cache.refresh())
        assertEquals(0f, cache.value, 0f)
        failure = true
        assertEquals(1000L, cache.refresh())
    }
}
