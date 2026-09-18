package app.smallthingz.reverb

import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test

class RecordingDatabaseResetTest {
    @Test
    fun singletonReset_keepsOwnerWhenCloseFails() {
        val expected = IOException("close failed")
        val events = mutableListOf<String>()

        val thrown = assertThrows(IOException::class.java) {
            closeBeforeClearingDatabaseSingleton(
                close = {
                    events += "close"
                    throw expected
                },
                clear = { events += "clear" },
            )
        }

        assertSame(expected, thrown)
        assertEquals(listOf("close"), events)
    }

    @Test
    fun singletonReset_clearsOwnerOnlyAfterSuccessfulClose() {
        val events = mutableListOf<String>()

        closeBeforeClearingDatabaseSingleton(
            close = { events += "close" },
            clear = { events += "clear" },
        )

        assertEquals(listOf("close", "clear"), events)
    }
}
