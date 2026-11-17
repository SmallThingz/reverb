package app.smallthingz.reverb

import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.io.IOException
import java.nio.ByteBuffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class IncidentMarkerWireBoundaryTest {
    // Published RIS1/v3, armed, PID 42, process elapsed 100, armed wall 200,
    // armed elapsed 150, package update 7, boot count 9. Big-endian DataOutput format.
    private val session = "5249533103010000002a000000000000006400000000000000c80000000000000096000000000000000700000009"
        .chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    @Test fun independentPublishedSessionRetainsExactOwnershipFields() {
        val marker = decode(session)
        assertEquals(true, marker.armed)
        assertEquals(42, marker.pid)
        assertEquals(100L, marker.processStartElapsedRealtimeMillis)
        assertEquals(200L, marker.armedAtMillis)
        assertEquals(150L, marker.armedElapsedRealtimeMillis)
        assertEquals(7L, marker.packageLastUpdateTimeMillis)
        assertEquals(9, marker.bootCount)
    }

    @Test fun eachTruncatedSessionAndTrailingByteRejectsRatherThanInventingOwner() {
        for (length in 0 until session.size) assertThrows("length $length", IOException::class.java) {
            decode(session.copyOf(length))
        }
        assertThrows(IOException::class.java) { decode(session + byteArrayOf(0)) }
    }

    @Test fun malformedOwnershipFieldsCannotDisarmOrRetargetCapture() {
        val corruptions: List<ByteBuffer.() -> Unit> = listOf(
            { putInt(0, 0) }, { put(4, 0.toByte()) }, { put(4, 1.toByte()) },
            { putInt(6, 0) }, { putInt(6, -1) },
            { putLong(10, -1L) }, { putLong(18, 0L) }, { putLong(18, -1L) },
            { putLong(26, -1L) }, { putInt(42, -2) },
        )
        for ((index, corrupt) in corruptions.withIndex()) {
            val bytes = session.copyOf()
            ByteBuffer.wrap(bytes).corrupt()
            assertThrows("corruption $index", IOException::class.java) { decode(bytes) }
        }
    }

    @Test fun zeroElapsedAndUnknownBootCountRemainLegalLegacyCompatibleOwnership() {
        val bytes = session.copyOf()
        ByteBuffer.wrap(bytes).apply {
            putLong(10, 0L)
            putLong(26, 0L)
            putInt(42, -1)
        }
        val marker = decode(bytes)
        assertEquals(42, marker.pid)
        assertEquals(200L, marker.armedAtMillis)
        assertEquals(0L, marker.processStartElapsedRealtimeMillis)
        assertEquals(0L, marker.armedElapsedRealtimeMillis)
        assertEquals(-1, marker.bootCount)
    }

    private fun decode(bytes: ByteArray): ActiveRecordingSessionMarker =
        DataInputStream(ByteArrayInputStream(bytes)).use {
            readIncidentPayloadExact(it, "recording session", RecordingIncidentStore::readSessionPayload)
        }
}
