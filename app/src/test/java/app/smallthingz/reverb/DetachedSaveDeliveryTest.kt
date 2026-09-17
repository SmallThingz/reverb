package app.smallthingz.reverb

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DetachedSaveDeliveryTest {
    @Test
    fun detachedSuccess_notifiesWithoutFallbackWhenNotificationPathWorks() {
        var notified = false
        var fallback = false
        val delivered = deliverDetachedSaveSuccess(
            notificationAvailable = { true },
            notify = { notified = true },
            fallback = { fallback = true },
        )
        assertTrue(delivered)
        assertTrue(notified)
        assertFalse(fallback)
    }

    @Test
    fun detachedSuccess_fallsBackWhenNotificationSetupFails() {
        var fallback = false
        val delivered = deliverDetachedSaveSuccess(
            notificationAvailable = { error("channel setup failed") },
            notify = { error("must not notify") },
            fallback = { fallback = true },
        )
        assertFalse(delivered)
        assertTrue(fallback)
    }

    @Test
    fun detachedSuccess_fallsBackWhenNotifyFails() {
        var fallback = false
        val delivered = deliverDetachedSaveSuccess(
            notificationAvailable = { true },
            notify = { error("notify failed") },
            fallback = { fallback = true },
        )
        assertFalse(delivered)
        assertTrue(fallback)
    }
}
