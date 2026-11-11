package app.smallthingz.reverb

import java.io.IOException
import java.io.InterruptedIOException
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicReference
import java.util.zip.CRC32
import org.junit.Assert.*
import org.junit.Test

class PcmConversionAdmissionTest {
    private class Revoked : RuntimeException()

    @Test fun revokedEntryDoesNotStartAndPreservesTheControlException() {
        val revoked = Revoked()
        var entered = false
        val actual = assertThrows(Revoked::class.java) {
            withPcmConversionAdmission({ throw revoked }) { entered = true }
        }
        assertSame(revoked, actual)
        assertFalse(entered)
        checkPcmConversionNotInterrupted()
    }

    @Test fun converterStopsRevokedWorkAtItsExistingCheckpoint() {
        val revoked = Revoked()
        var admitted = true
        var produced = 0
        var published = false
        val actual = assertThrows(Revoked::class.java) {
            withPcmConversionAdmission({ if (!admitted) throw revoked }) {
                val converter = PcmRateConverter(1024, 0.5) { frame, _ ->
                    admitted = false
                    frame.toFloat()
                }
                repeat(1024) {
                    converter.sample(it * 0.5, 0)
                    produced++
                }
                published = true
            }
        }
        assertSame(revoked, actual)
        assertEquals(256, produced)
        assertFalse(published)
    }

    @Test fun revocationInTheLastPartialTileCannotPublishAResult() {
        val revoked = Revoked()
        var admitted = true
        var published = false
        val actual = assertThrows(Revoked::class.java) {
            val result = withPcmConversionAdmission({ if (!admitted) throw revoked }) {
                val converter = PcmRateConverter(2, 1.0) { _, _ ->
                    admitted = false
                    1f
                }
                converter.sample(0.0, 0)
            }
            published = result == 1f
        }
        assertSame(revoked, actual)
        assertFalse(published)
    }

    @Test fun nestedFailureRestoresParentAndOuterFailureClearsWorkerScope() {
        val parent = Revoked()
        val child = Revoked()
        var parentActive = true
        val actual = assertThrows(Revoked::class.java) {
            withPcmConversionAdmission({ if (!parentActive) throw parent }) {
                assertSame(child, assertThrows(Revoked::class.java) {
                    withPcmConversionAdmission({ throw child }) { fail("child entered") }
                })
                checkPcmConversionNotInterrupted()
                parentActive = false
                checkPcmConversionNotInterrupted()
            }
        }
        assertSame(parent, actual)
        // A reused serial worker must not retain either revoked owner.
        withPcmConversionAdmission({}) {
            assertEquals(7f, PcmRateConverter(1, 1.0) { _, _ -> 7f }.sample(0.0, 0), 0f)
        }
        checkPcmConversionNotInterrupted()
    }

    @Test fun nestedAdmissionCannotShadowRevokedParent() {
        val revoked = Revoked()
        var admitted = true
        assertSame(revoked, assertThrows(Revoked::class.java) {
            withPcmConversionAdmission({ if (!admitted) throw revoked }) {
                withPcmConversionAdmission({}) {
                    admitted = false
                    checkPcmConversionNotInterrupted()
                }
            }
        })
    }

    @Test fun scopeDoesNotPropagateToAnotherWorker() {
        val revoked = Revoked()
        val failure = AtomicReference<Throwable?>()
        var admitted = true
        assertSame(revoked, assertThrows(Revoked::class.java) {
            withPcmConversionAdmission({ if (!admitted) throw revoked }) {
                admitted = false
                val worker = Thread {
                    try { checkPcmConversionNotInterrupted() }
                    catch (error: Throwable) { failure.set(error) }
                }
                worker.start()
                worker.join(5000)
                assertFalse("test worker did not drain", worker.isAlive)
                assertNull(failure.get())
                checkPcmConversionNotInterrupted()
            }
        })
    }

    @Test fun interruptionRetainsItsOriginalTypeAndFlag() {
        var admissionCalls = 0
        Thread.currentThread().interrupt()
        try {
            assertThrows(InterruptedIOException::class.java) {
                withPcmConversionAdmission({ admissionCalls++ }) { fail("entered interrupted work") }
            }
            assertTrue(Thread.currentThread().isInterrupted)
            assertEquals(0, admissionCalls)
        } finally { Thread.interrupted() }
        checkPcmConversionNotInterrupted()
    }

    @Test fun admittedMonoAndStereoOutputsKeepExactFloatBits() {
        fun convert(step: Double, stereo: Boolean): IntArray {
            val converter = PcmRateConverter(97, step) { frame, channel ->
                ((frame * 73 + channel * 91) % 503 - 251).toFloat()
            }
            val pair = FloatArray(2)
            return IntArray(514) { sample ->
                val position = (sample / 2) * 96.0 / 256.0
                if (stereo) {
                    if (sample % 2 == 0) converter.sampleStereo(position, { frame, target ->
                        target.left = ((frame * 73) % 503 - 251).toFloat()
                        target.right = ((frame * 73 + 91) % 503 - 251).toFloat()
                    }, pair)
                    pair[sample % 2].toRawBits()
                } else converter.sample(position, sample % 2).toRawBits()
            }
        }
        for (step in listOf(0.5, 1.0, 1.1, 3.0)) for (stereo in listOf(false, true)) {
            val reference = convert(step, stereo)
            assertArrayEquals(reference, withPcmConversionAdmission({}) { convert(step, stereo) })
        }
    }

    @Test fun cancelledCrcReadKeepsSourceAndNeverPublishesPartialBytes() {
        val file = Files.createTempFile("reverb-cancel-read", ".pcm").toFile()
        val payload = ByteArray(131_073) { (it * 37 + it / 257).toByte() }
        val crc = CRC32().apply { update(payload) }.value.toInt()
        val revoked = Revoked()
        try {
            file.writeBytes(payload)
            var checks = 0
            var published = false
            val actual = assertThrows(Revoked::class.java) {
                withPcmConversionAdmission({ if (++checks == 4) throw revoked }) {
                    // Entry, read entry, first batch, then cancellation before the second CRC batch.
                    PcmNormalizer.readVerifiedSegment(file, 0, payload.size.toLong(), crc, 70_000, 1)
                }
                published = true
            }
            assertSame(revoked, actual)
            assertFalse(published)
            assertEquals(4, checks)
            assertArrayEquals(payload, file.readBytes())
            // The source remains usable and the next read still verifies the complete payload.
            assertArrayEquals(byteArrayOf(payload[70_000]),
                PcmNormalizer.readVerifiedSegment(file, 0, payload.size.toLong(), crc, 70_000, 1))
            file.writeBytes(payload.copyOf().apply { this[0] = (this[0].toInt() xor 1).toByte() })
            val failure = assertThrows(IOException::class.java) {
                PcmNormalizer.readVerifiedSegment(file, 0, payload.size.toLong(), crc, 70_000, 1)
            }
            assertTrue(failure.message.orEmpty().startsWith("PCM chunk checksum mismatch:"))
        } finally { file.delete() }
    }
}
