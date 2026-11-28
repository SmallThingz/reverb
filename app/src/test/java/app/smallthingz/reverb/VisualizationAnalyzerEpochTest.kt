package app.smallthingz.reverb

import android.app.Application
import android.os.Handler
import android.os.Looper
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@LooperMode(LooperMode.Mode.PAUSED)
class VisualizationAnalyzerEpochTest {
    @Test fun firstMatchingReadBeforeQueuedResetStartsWithFreshAnalyzerHistory() {
        exerciseReadBeforeReset(previousFault = false)
    }

    @Test fun firstMatchingReadClearsPreviousFaultButQueuedResetPreservesNewFault() {
        exerciseReadBeforeReset(previousFault = true)
    }

    private fun exerciseReadBeforeReset(previousFault: Boolean) {
        val service = Robolectric.buildService(ReverbService::class.java).get()
        val looper = Looper.getMainLooper()
        val audio = Handler(looper)
        fun field(name: String) = ReverbService::class.java.getDeclaredField(name).apply { isAccessible = true }
        field("audioHandler").set(service, audio)
        val faulted = field("visualizationFaulted")
        @Suppress("UNCHECKED_CAST")
        val mailbox = field("visualizationCallbacks").get(service) as
            IdentityFrameMailbox<ReverbService.VisualizationCallback, ReverbService.VisualizationFrame>
        val publish = ReverbService::class.java.getDeclaredMethod(
            "publishVisualization", ByteArray::class.java, Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType, ReverbService.BufferSlot::class.java, CaptureReadIdentity::class.java,
        ).apply { isAccessible = true }
        fun admitRead() = CaptureReadIdentity(0, 0, 0, 0, 48_000, 1, 2, 2, 0,
            visualizationEpoch = checkNotNull(mailbox.currentEpoch()))
        fun consume(read: CaptureReadIdentity, pcm: ByteArray) {
            publish.invoke(service, pcm, 0, pcm.size, ReverbService.BufferSlot.LOOPING, read)
        }

        val oldFrames = mutableListOf<ReverbService.VisualizationFrame>()
        val newFrames = mutableListOf<ReverbService.VisualizationFrame>()
        val old = ReverbService.VisualizationCallback { oldFrames += it }
        val replacement = ReverbService.VisualizationCallback { newFrames += it }
        val loud = ByteBuffer.allocate(960).order(ByteOrder.LITTLE_ENDIAN).apply {
            repeat(480) { putShort(if (it % 48 < 24) 16_000.toShort() else (-16_000).toShort()) }
        }.array()
        val silent = ByteArray(loud.size)
        try {
            service.setVisualizationCallback(old)
            val oldRead = admitRead()
            audio.post { consume(oldRead, loud) }
            shadowOf(looper).idle()
            assertTrue(oldFrames.single().activity > 0f)
            faulted.setBoolean(service, previousFault)

            lateinit var replacementRead: CaptureReadIdentity
            // The writer wakeup is already queued when UI registration posts its reset.
            // It consumes a read admitted after registration, before that reset runs.
            audio.post {
                consume(oldRead, loud)
                assertFalse(mailbox.hasPending())
                assertEquals(previousFault, faulted.getBoolean(service))
                consume(replacementRead, silent)
                assertTrue("First matching read must publish before the queued reset", mailbox.hasPending())
                if (previousFault) {
                    // Simulate a subsequent analyzer failure owned by the replacement epoch.
                    faulted.setBoolean(service, true)
                }
            }
            service.setVisualizationCallback(replacement)
            replacementRead = admitRead()
            shadowOf(looper).idle()
            assertEquals("Silent first frame must not inherit prior smoothing", 0f, newFrames.single().activity, 0f)
            assertEquals("Queued reset must preserve a fault owned by this epoch",
                previousFault, faulted.getBoolean(service))
        } finally {
            service.clearVisualizationCallback(replacement)
            service.clearVisualizationCallback(old)
            shadowOf(looper).idle()
        }
    }
}
