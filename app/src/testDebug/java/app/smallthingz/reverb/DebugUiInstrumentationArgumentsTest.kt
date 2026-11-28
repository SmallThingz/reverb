package app.smallthingz.reverb

import android.app.Application
import android.os.Bundle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class DebugUiInstrumentationArgumentsTest {
    @Test
    fun omittedPlanDefaultsToSnapshot() {
        val expected = listOf(DebugUiCommand.Snapshot)
        assertEquals(expected, parseDebugUiPlanArgument(null))
        assertEquals(expected, parseDebugUiPlanArgument(Bundle()))
    }

    @Test
    fun presentNonStringOrNullPlanIsRejected() {
        val integer = Bundle().apply { putInt("plan", 1) }
        val explicitNull = Bundle().apply { putString("plan", null) }
        for (arguments in listOf(integer, explicitNull)) {
            val error = assertThrows(IllegalArgumentException::class.java) {
                parseDebugUiPlanArgument(arguments)
            }
            assertEquals("plan must be a String", error.message)
        }
    }

    @Test
    fun presentStringUsesStrictCommandParser() {
        val valid = Bundle().apply { putString("plan", "activate:navigation.settings") }
        assertEquals(listOf(DebugUiCommand.Activate(DebugUiControl.SETTINGS)), parseDebugUiPlanArgument(valid))
        val empty = Bundle().apply { putString("plan", "") }
        assertThrows(IllegalArgumentException::class.java) { parseDebugUiPlanArgument(empty) }
    }
}
