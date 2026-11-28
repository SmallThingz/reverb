package app.smallthingz.reverb

import android.app.Activity
import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Explicit-component, permission-protected, one-command transport. Never starts/focuses an Activity. */
class DebugUiCommandReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        // Unordered sends cannot observe their result and must never perform a UI action.
        if (!isOrderedBroadcast) return
        val controls = DebugUiRuntime.controls(context.applicationContext as Application)
        val result = dispatchDebugUiCommand(controls, intent)
        setResult(if (result.status in setOf(DebugUiStatus.READY, DebugUiStatus.DISPATCHED)) Activity.RESULT_OK
            else Activity.RESULT_CANCELED, result.toJson().toString(), null)
    }

    companion object {
        const val SNAPSHOT = "app.smallthingz.reverb.debug.ui.SNAPSHOT"
        const val ACTIVATE = "app.smallthingz.reverb.debug.ui.ACTIVATE"
        const val CONTROL_ID = "control_id"
        const val GENERATION = "generation"
    }
}

/** Parsing and invocation share the receiver's single main-thread turn; no queue or delayed job. */
internal fun dispatchDebugUiCommand(controls: DebugUiControls, intent: Intent): DebugUiResult {
    fun invalid() = invalidDebugUiRequest
    return when (intent.action) {
        DebugUiCommandReceiver.SNAPSHOT -> {
            if (!intent.extras?.keySet().isNullOrEmpty()) invalid()
            else controls.snapshot().let { DebugUiResult(it.status, it) }
        }
        DebugUiCommandReceiver.ACTIVATE -> {
            val extras = intent.extras ?: return invalid()
            if (extras.keySet() != setOf(DebugUiCommandReceiver.CONTROL_ID, DebugUiCommandReceiver.GENERATION)) return invalid()
            val id = extras.get(DebugUiCommandReceiver.CONTROL_ID) as? String ?: return invalid()
            val generation = extras.get(DebugUiCommandReceiver.GENERATION) as? String ?: return invalid()
            if (id.length > 64 || generation.isEmpty() || generation.length > 128) return invalid()
            val control = DebugUiControl.fromId(id) ?: return invalid()
            controls.activate(control, generation)
        }
        else -> invalid()
    }
}

// Parse failures carry no live observation and must not discover views or revise the registry.
private val invalidDebugUiRequest = DebugUiResult(DebugUiStatus.INVALID_REQUEST,
    DebugUiSnapshot(null, DebugUiStatus.INVALID_REQUEST, null, emptyList()))
