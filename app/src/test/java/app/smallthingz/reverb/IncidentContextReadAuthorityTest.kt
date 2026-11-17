package app.smallthingz.reverb

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.os.Process
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.file.Files
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowProcess

@RunWith(org.robolectric.RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, shadows = [ServiceStateOsShadow::class])
class IncidentContextReadAuthorityTest {
    @Test fun actualPublishedMarkerIsReservedWithoutMutatingDurableEvidence() = fixture { context, root ->
        val bytes = currentSession()
        val file = File(root, "recording-session.bin").apply { writeBytes(bytes) }
        val inode = Files.getAttribute(file.toPath(), "unix:ino")
        val token = RecordingIncidentStore.beginKnownCaptureStop(context)
        assertTrue(token.armed)
        assertEquals(root.absoluteFile, token.root)
        assertEquals(Process.myPid(), token.marker!!.pid)
        assertEquals(Process.getStartElapsedRealtime(), token.marker!!.processStartElapsedRealtimeMillis)
        assertArrayEquals(bytes, file.readBytes())
        assertEquals(inode, Files.getAttribute(file.toPath(), "unix:ino"))
        assertEquals(setOf("recording-session.bin"), root.list()!!.toSet())
    }

    @Test fun absentSessionReservesOnlyAbsenceWithoutCreatingArtifacts() = fixture { context, root ->
        val token = RecordingIncidentStore.beginKnownCaptureStop(context)
        assertFalse(token.armed)
        assertEquals(null, token.marker)
        assertTrue(root.list()!!.isEmpty())
    }

    @Test fun stopTokenCannotCrossAnActualContextStorageRoot() = fixture { context, root ->
        val bytes = currentSession()
        val original = File(root, "recording-session.bin").apply { writeBytes(bytes) }
        val token = RecordingIncidentStore.beginKnownCaptureStop(context)
        val second = File(root, "other-context").apply { check(mkdir()) }
        val otherMarker = File(second, "recording-session.bin").apply { writeBytes(bytes) }
        val otherContext = IsolatedContext(context, second)
        assertEquals(DeferredKnownCaptureStopResult.SUPERSEDED,
            RecordingIncidentStore.completeKnownCaptureStop(otherContext, token))
        assertArrayEquals(bytes, original.readBytes())
        assertArrayEquals(bytes, otherMarker.readBytes())
        assertEquals(setOf("recording-session.bin"), second.list()!!.toSet())
        assertFalse(token.retirementAttempted)
    }

    @Test fun nonregularBackingNeverBecomesCleanAbsenceOrConsumesTheRegularMarker() = fixture { context, root ->
        val bytes = currentSession()
        val file = File(root, "recording-session.bin").apply { writeBytes(bytes) }
        for (suffix in listOf(".bak", ".new")) {
            val backing = File(file.path + suffix).apply { check(mkdir()) }
            assertThrows(IOException::class.java) { RecordingIncidentStore.beginKnownCaptureStop(context) }
            assertArrayEquals(bytes, file.readBytes())
            assertTrue(backing.isDirectory)
            check(backing.delete())
        }
    }

    @Test fun corruptActualMarkerRemainsPreservedAndCannotBeReservedAsKnownStop() = fixture { context, root ->
        val file = File(root, "recording-session.bin")
        val bytes = currentSession()
        for (invalid in listOf(bytes.copyOf(17), bytes + byteArrayOf(0), bytes.copyOf().also { it[0] = 0 })) {
            file.writeBytes(invalid)
            assertThrows(IOException::class.java) { RecordingIncidentStore.beginKnownCaptureStop(context) }
            assertArrayEquals(invalid, file.readBytes())
            assertEquals(setOf("recording-session.bin"), root.list()!!.toSet())
        }
    }

    private fun currentSession(): ByteArray =
        "5249533103010000002a000000000000006400000000000000c80000000000000096000000000000000700000009"
            .chunked(2).map { it.toInt(16).toByte() }.toByteArray().also { bytes ->
                ByteBuffer.wrap(bytes).apply {
                    putInt(6, Process.myPid())
                    putLong(10, Process.getStartElapsedRealtime())
                }
            }

    private class IsolatedContext(base: Context, private val root: File) : ContextWrapper(base) {
        override fun getApplicationContext(): Context = this
        override fun getNoBackupFilesDir(): File = root
    }

    private fun fixture(test: (Context, File) -> Unit) {
        val root = Files.createTempDirectory("incident-context-authority-").toFile()
        val previous = ServiceStateOsShadow.fixtureRoot
        val previousPid = Process.myPid()
        var failure: Throwable? = null
        fun cleanup(action: () -> Unit) {
            try { action() } catch (error: Throwable) {
                val first = failure
                if (first == null) failure = error else if (first !== error) first.addSuppressed(error)
            }
        }
        try {
            check(previous == null) { "Another fixture owns the Os shadow" }
            ShadowProcess.setPid(4242)
            ServiceStateOsShadow.fixtureRoot = root.toPath()
            test(IsolatedContext(RuntimeEnvironment.getApplication(), root), root)
        } catch (error: Throwable) { failure = error }
        finally {
            cleanup { ServiceStateOsShadow.fixtureRoot = previous }
            cleanup { ShadowProcess.setPid(previousPid) }
            if (failure == null) cleanup { check(root.deleteRecursively()) }
        }
        failure?.let { throw it }
    }
}
