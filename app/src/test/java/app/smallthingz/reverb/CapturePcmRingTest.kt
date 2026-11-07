package app.smallthingz.reverb

import java.nio.ByteBuffer
import org.junit.Assert.*
import org.junit.Test

class CapturePcmRingTest {
    private fun identity(destination: Byte = 0) = CaptureReadIdentity(1, 2, 3, destination, 44_100, 1, 2, 2, 0)
    private fun ring() = CapturePcmRing(CapturePcmBudget(2, 8, 32, 8))
    private fun append(q: CapturePcmRing, bytes: ByteArray, requested: Int = bytes.size): CapturePcmRing.Lease {
        val lease = requireNotNull(q.reserve(identity(), requested))
        assertTrue(q.complete(lease, ByteBuffer.wrap(bytes), bytes.size, 4, null, 5))
        return lease
    }
    private fun consume(q: CapturePcmRing): ByteArray {
        val packet = requireNotNull(q.poll())
        return ByteArray(packet.byteCount).also { q.copyPacket(packet, it); q.completePacket(packet) }
    }

    @Test fun writerOwnedPrefixAndWrappedPacketsKeepTheirBytes() {
        val q = ring()
        append(q, ByteArray(8) { 1 }); append(q, ByteArray(8) { 2 })
        val first = requireNotNull(q.poll())
        append(q, ByteArray(8) { 3 }); append(q, ByteArray(8) { 4 })
        val firstBytes = ByteArray(8); q.copyPacket(first, firstBytes)
        assertArrayEquals(ByteArray(8) { 1 }, firstBytes)
        q.completePacket(first)
        append(q, ByteArray(8) { 5 }) // Wraps to physical offset zero after the writer releases it.
        for (value in 2..5) assertArrayEquals(ByteArray(8) { value.toByte() }, consume(q))
        assertTrue(q.isDrained())
    }

    @Test fun packetStraddlingEndOfRingCopiesExactly() {
        val q = ring()
        append(q, ByteArray(6) { 1 }); append(q, ByteArray(8) { 2 })
        assertArrayEquals(ByteArray(6) { 1 }, consume(q))
        append(q, ByteArray(8) { 3 }); append(q, ByteArray(8) { 4 })
        assertArrayEquals(ByteArray(8) { 2 }, consume(q))
        append(q, byteArrayOf(5, 6, 7, 8, 9, 10, 11, 12))
        assertArrayEquals(ByteArray(8) { 3 }, consume(q))
        assertArrayEquals(ByteArray(8) { 4 }, consume(q))
        assertArrayEquals(byteArrayOf(5, 6, 7, 8, 9, 10, 11, 12), consume(q))
    }

    @Test fun shortNativeReadReturnsUnusedTailWithoutLeavingFalseBackpressure() {
        val q = ring()
        repeat(4) { append(q, byteArrayOf(1, 2), requested = 8) }
        assertEquals(8, q.status().occupiedBytes)
        repeat(3) { append(q, ByteArray(8) { 3 }) }
        assertEquals(32, q.status().occupiedBytes)
        assertNull(q.reserve(identity(), 2))
        assertEquals(CapturePcmRing.Terminal.EXHAUSTED, q.status().terminal)
        repeat(4) { assertArrayEquals(byteArrayOf(1, 2), consume(q)) }
        repeat(3) { assertArrayEquals(ByteArray(8) { 3 }, consume(q)) }
        assertTrue(q.isDrained())
    }

    @Test fun twoHundredForegroundReadsUseActualPayloadBudgetNotTwoHundredSecondSizedSlots() {
        val budget = CapturePcmBudget.forFormat(44_100, 1, 2)
        val q = CapturePcmRing(budget)
        val bytes = ByteArray(704) { 7 }
        repeat(200) { append(q, bytes) }
        assertEquals(140_800, q.status().occupiedBytes)
        assertEquals(529_200L, budget.totalPcmBytes)
        assertNull(q.status().terminal)
        repeat(200) { assertArrayEquals(bytes, consume(q)) }
    }

    @Test fun foregroundToBackgroundCadenceKeepsOneFifoWithoutAllocationOrFormatRestart() {
        val q = CapturePcmRing(CapturePcmBudget.forFormat(44_100, 1, 2))
        append(q, ByteArray(704) { 1 })
        append(q, ByteArray(88_200) { 2 })
        append(q, ByteArray(3528) { 3 })
        assertArrayEquals(ByteArray(704) { 1 }, consume(q))
        assertArrayEquals(ByteArray(88_200) { 2 }, consume(q))
        assertArrayEquals(ByteArray(3528) { 3 }, consume(q))
    }

    @Test fun fullBytePoolStillHasIndependentOrderedControlCapacity() {
        val q = ring()
        repeat(4) { append(q, ByteArray(8) { it.toByte() }) }
        assertNull(q.reserve(identity(), 2))
        val fences = List(66) { q.reserveFence(pauseAdmission = it % 2 == 0) }
        fences.forEach { assertFalse(q.prefixDrained(it)) }
        repeat(4) { consume(q) }
        assertNull(q.poll())
        fences.forEach { fence ->
            assertTrue(q.prefixDrained(fence)); assertFalse(q.fenceMayExecute(fence)); q.completeFence(fence)
        }
        assertFalse(q.status().fence)
        assertTrue(q.isDrained())
    }

    @Test fun fenceIncludesInflightReservationAndSeparatesSubsequentNonpausingPacket() {
        val q = ring()
        val first = requireNotNull(q.reserve(identity(1), 8))
        val fence = q.reserveFence(pauseAdmission = false)
        assertEquals(first.sequence, fence.afterSequence)
        assertFalse(q.prefixDrained(fence))
        assertTrue(q.complete(first, ByteBuffer.wrap(ByteArray(8) { 1 }), 8, 4, 3, 5))
        append(q, ByteArray(8) { 2 })
        assertArrayEquals(ByteArray(8) { 1 }, consume(q))
        assertTrue(q.prefixDrained(fence)); assertTrue(q.fenceMayExecute(fence))
        append(q, ByteArray(8) { 3 })
        q.completeFence(fence)
        assertArrayEquals(ByteArray(8) { 2 }, consume(q))
        assertArrayEquals(ByteArray(8) { 3 }, consume(q))
    }

    @Test fun writerFailureDiscardsLaterPacketsButNeverReusesNativeOrWriterReservationEarly() {
        val q = ring()
        append(q, ByteArray(8) { 1 })
        val writer = requireNotNull(q.poll())
        append(q, ByteArray(8) { 2 })
        val reader = requireNotNull(q.reserve(identity(), 8))
        val fence = q.reserveFence(true)
        q.writerFailed(); q.writerFailed()
        assertEquals(2, q.status().occupiedPackets)
        assertFalse(q.prefixDrained(fence))
        q.completePacket(writer)
        assertFalse(q.prefixDrained(fence))
        assertFalse(q.complete(reader, ByteBuffer.allocate(8), 8, 4, null, 5))
        assertNull(q.poll())
        assertTrue(q.prefixDrained(fence)); assertFalse(q.fenceMayExecute(fence)); q.completeFence(fence)
        assertTrue(q.isDrained())
    }

    @Test fun multipleAcceptedFencesRetainTheirOwnPrefixes() {
        val q = ring()
        val first = append(q, ByteArray(8) { 1 })
        val beforeSecond = q.reserveFence(false)
        val second = append(q, ByteArray(8) { 2 })
        val beforeThird = q.reserveFence(false)
        append(q, ByteArray(8) { 3 })
        assertEquals(first.sequence, beforeSecond.afterSequence)
        assertEquals(second.sequence, beforeThird.afterSequence)
        assertFalse(q.prefixDrained(beforeSecond))
        assertFalse(q.prefixDrained(beforeThird))
        assertArrayEquals(ByteArray(8) { 1 }, consume(q))
        assertTrue(q.prefixDrained(beforeSecond))
        assertFalse(q.prefixDrained(beforeThird))
        q.completeFence(beforeSecond)
        assertArrayEquals(ByteArray(8) { 2 }, consume(q))
        assertTrue(q.prefixDrained(beforeThird))
        q.completeFence(beforeThird)
        assertArrayEquals(ByteArray(8) { 3 }, consume(q))
    }

    @Test fun pausingFenceBlocksNewAdmissionUntilEveryPausingOwnerCompletes() {
        val q = ring()
        val native = requireNotNull(q.reserve(identity(), 8))
        val first = q.reserveFence(true)
        val second = q.reserveFence(true)
        val nonpausing = q.reserveFence(false)
        assertNull(q.reserve(identity(), 8))
        assertTrue(q.complete(native, ByteBuffer.allocate(8), 8, 4, null, 5))
        consume(q)
        assertTrue(q.prefixDrained(first)); assertTrue(q.prefixDrained(second))
        q.completeFence(first)
        assertNull(q.reserve(identity(), 8))
        q.completeFence(second)
        val next = requireNotNull(q.reserve(identity(), 8))
        assertTrue(q.prefixDrained(nonpausing))
        q.completeFence(nonpausing)
        assertTrue(q.complete(next, ByteBuffer.allocate(8), 8, 4, null, 5))
        consume(q)
        assertFalse(q.status().fence)
    }

    @Test fun zeroNativeAcknowledgementResolvesAcceptedPrefixWithoutInventingPacket() {
        val q = ring()
        val native = requireNotNull(q.reserve(identity(), 8))
        val fence = q.reserveFence(true)
        assertFalse(q.prefixDrained(fence))
        assertFalse(q.complete(native, ByteBuffer.allocate(0), 0, 4, null, 5))
        assertTrue(q.prefixDrained(fence))
        assertNull(q.poll())
        q.completeFence(fence)
        assertNotNull(q.reserve(identity(), 8))
    }

    @Test fun shortReadDescriptorExhaustionIsExplicitEvenWithFreePcmBytes() {
        val q = CapturePcmRing(CapturePcmBudget(2, 8, 32, 2))
        append(q, byteArrayOf(1, 2), requested = 8)
        append(q, byteArrayOf(3, 4), requested = 8)
        assertEquals(4, q.status().occupiedBytes)
        assertNull(q.reserve(identity(), 2))
        assertEquals(CapturePcmRing.Terminal.EXHAUSTED, q.status().terminal)
        assertArrayEquals(byteArrayOf(1, 2), consume(q))
        assertArrayEquals(byteArrayOf(3, 4), consume(q))
        assertTrue(q.isDrained())
    }

    @Test fun stopAndGenericTeardownHaveDifferentInflightDisposition() {
        for (preserve in listOf(false, true)) {
            val q = ring()
            val reader = requireNotNull(q.reserve(identity(), 8))
            q.stop(preserve)
            assertEquals(preserve, q.complete(reader, ByteBuffer.allocate(8), 8, 4, null, 5))
            if (preserve) consume(q)
            assertTrue(q.isDrained())
        }
    }

    @Test fun invalidReturnRetainsLeaseUntilExplicitZeroAcknowledgement() {
        val q = ring(); val reader = requireNotNull(q.reserve(identity(), 8))
        try { q.complete(reader, ByteBuffer.allocate(8), 7, 0, null, 0); fail() }
        catch (_: IllegalArgumentException) { }
        assertTrue(q.status().reading)
        q.inputFailed()
        assertFalse(q.complete(reader, ByteBuffer.allocate(0), 0, 0, null, 0))
        assertTrue(q.isDrained())
    }

    @Test fun supportedFormatsFitExplicitPcmAndDescriptorCeilings() {
        val rates = listOf(96_000, 88_200, 64_000, 48_000, 44_100, 32_000, 24_000, 22_050,
            16_000, 12_000, 11_025, 8_000, 7_350)
        for (rate in rates) for (channels in 1..2) for (sample in listOf(1, 2, 4)) {
            val b = CapturePcmBudget.forFormat(rate, channels, sample)
            assertTrue(b.totalPcmBytes <= 4_608_000)
            assertTrue(b.packetCount <= 509)
            assertEquals(0, b.ringBytes % (channels * sample))
        }
        assertEquals(4_608_000L, CapturePcmBudget.forFormat(96_000, 2, 4).totalPcmBytes)
    }

    @Test fun malformedBudgetCannotOverflowIntoSmallOrNegativeAllocation() {
        try { CapturePcmBudget(2, 1_073_741_824, Int.MIN_VALUE, 3); fail() }
        catch (_: IllegalArgumentException) { }
        try { CapturePcmBudget.forFormat(Int.MAX_VALUE, 2, 4); fail() }
        catch (_: IllegalArgumentException) { }
    }
}
