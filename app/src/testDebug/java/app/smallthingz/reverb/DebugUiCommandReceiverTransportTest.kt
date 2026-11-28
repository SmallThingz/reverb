package app.smallthingz.reverb

import android.app.Activity
import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.os.Looper
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

/** Transport tests: call the actual receiver, never dispatchDebugUiCommand directly. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@LooperMode(LooperMode.Mode.PAUSED)
class DebugUiCommandReceiverTransportTest {
    @Test fun orderedColdSnapshotDeliversTerminalCanceledResultAndBoundedJson() {
        val result = ordered(Intent(DebugUiCommandReceiver.SNAPSHOT))
        assertEquals(Activity.RESULT_CANCELED, result.code)
        assertEquals("NO_ACTIVITY", result.json.getString("status"))
        assertEquals("NO_ACTIVITY", result.json.getString("readiness"))
        assertTrue(result.json.isNull("generation"))
        assertTrue(result.json.isNull("navigation"))
        val controls = result.json.getJSONArray("controls")
        assertEquals(8, controls.length())
        assertEquals(DebugUiControl.entries.map { it.id }.toSet(),
            (0 until controls.length()).map { controls.getJSONObject(it).getString("id") }.toSet())
        repeat(controls.length()) { index ->
            assertEquals("NO_ACTIVITY", controls.getJSONObject(index).getString("status"))
        }
        assertNoLaunch()
    }

    @Test fun orderedInvalidRequestDeliversConstantRejectionWithoutLiveObservation() {
        val result = ordered(Intent(DebugUiCommandReceiver.SNAPSHOT).putExtra("unexpected", true))
        assertEquals(Activity.RESULT_CANCELED, result.code)
        assertEquals("INVALID_REQUEST", result.json.getString("status"))
        assertEquals("INVALID_REQUEST", result.json.getString("readiness"))
        assertTrue(result.json.isNull("generation"))
        assertTrue(result.json.isNull("navigation"))
        assertEquals(0, result.json.getJSONArray("controls").length())
        assertNoLaunch()
    }

    @Test fun orderedActivationWithExpiredObservationReturnsStaleWithoutLaunchingActivity() {
        val result = ordered(activation("retired-process:1"))
        assertEquals(Activity.RESULT_CANCELED, result.code)
        assertEquals("STALE_OBSERVATION", result.json.getString("status"))
        assertEquals("NO_ACTIVITY", result.json.getString("readiness"))
        assertTrue(result.json.isNull("generation"))
        assertNoLaunch()
    }

    @Test fun unorderedActivationReturnsBeforeResolvingApplicationOrDispatching() {
        var applicationReads = 0
        val context = object : ContextWrapper(application) {
            override fun getApplicationContext(): Context {
                applicationReads++
                error("Unordered delivery must not resolve the runtime or dispatch a command")
            }
        }
        val receiver = DebugUiCommandReceiver()
        // An ordinary direct delivery has no ordered PendingResult. Do not manufacture one.
        assertFalse(receiver.isOrderedBroadcast)
        receiver.onReceive(context, activation("unused-observation:1"))
        assertEquals(0, applicationReads)
        assertNoLaunch()
    }

    private val application: Application
        get() = RuntimeEnvironment.getApplication()

    private fun activation(generation: String) = Intent(DebugUiCommandReceiver.ACTIVATE)
        .putExtra(DebugUiCommandReceiver.CONTROL_ID, DebugUiControl.SETTINGS.id)
        .putExtra(DebugUiCommandReceiver.GENERATION, generation)

    private data class Delivered(val code: Int, val json: JSONObject)

    private fun ordered(intent: Intent): Delivered {
        val app = application
        val receiver = DebugUiCommandReceiver()
        var callbacks = 0
        var delivered: Delivered? = null
        val terminal = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                assertTrue("Ordered terminal callback must run on main", Looper.myLooper() == Looper.getMainLooper())
                callbacks++
                // setResult must replace all three sender-provided sentinel values.
                assertNull(getResultExtras(false))
                delivered = Delivered(resultCode, JSONObject(checkNotNull(resultData)))
            }
        }
        // Register the actual class to let Robolectric supply an ordered PendingResult. This
        // deliberately tests onReceive/result propagation, not manifest resolution or permission.
        app.registerReceiver(receiver, IntentFilter(checkNotNull(intent.action)), Context.RECEIVER_NOT_EXPORTED)
        try {
            app.sendOrderedBroadcast(Intent(intent).setPackage(app.packageName), null, terminal, null,
                741, "sender-result-must-be-replaced", Bundle().apply { putBoolean("sender-extra", true) })
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals("Require actual terminal delivery, not elapsed time without an error", 1, callbacks)
            return checkNotNull(delivered)
        } finally {
            app.unregisterReceiver(receiver)
        }
    }

    private fun assertNoLaunch() {
        assertNull(shadowOf(application).nextStartedActivity)
        assertNull(shadowOf(application).nextStartedService)
    }
}
