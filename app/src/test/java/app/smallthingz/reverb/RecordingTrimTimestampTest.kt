package app.smallthingz.reverb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class RecordingTrimTimestampTest {
    @Test
    fun trimTimestampAddsNormalOffsetExactly() {
        assertEquals(
            12_345L,
            recordingTimestampWithOffset(
                startedAtMillis = 10_000L,
                offsetMillis = 2_345L,
            ),
        )
    }

    @Test
    fun trimTimestampSaturatesInsteadOfWrappingNegative() {
        assertEquals(
            Long.MAX_VALUE,
            recordingTimestampWithOffset(
                startedAtMillis = Long.MAX_VALUE - 5L,
                offsetMillis = 10L,
            ),
        )
    }

    @Test
    fun trimTimestampRejectsNegativeInputs() {
        assertThrows(IllegalArgumentException::class.java) {
            recordingTimestampWithOffset(-1L, 0L)
        }
        assertThrows(IllegalArgumentException::class.java) {
            recordingTimestampWithOffset(0L, -1L)
        }
    }
}
