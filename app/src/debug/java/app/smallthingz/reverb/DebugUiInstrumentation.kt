package app.smallthingz.reverb

import android.app.Activity
import android.app.Application
import android.app.Instrumentation
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import org.json.JSONArray
import org.json.JSONObject

internal sealed interface DebugUiCommand {
    data object Snapshot : DebugUiCommand
    data class Activate(val control: DebugUiControl) : DebugUiCommand
    data class Await(val control: DebugUiControl) : DebugUiCommand
}

internal fun parseDebugUiCommands(plan: String): List<DebugUiCommand> {
    require(plan.length <= 1024) { "plan is limited to 1024 characters" }
    val tokens = plan.split(',')
    require(tokens.size in 1..16) { "plan requires 1..16 commands" }
    return tokens.map { token ->
        if (token == "snapshot") DebugUiCommand.Snapshot else {
            val parts = token.split(':')
            require(parts.size == 2) { "Expected snapshot, activate:<control-id>, or await:<control-id>" }
            val control = requireNotNull(DebugUiControl.fromId(parts[1])) { "Unknown control ID" }
            when (parts[0]) {
                "activate" -> DebugUiCommand.Activate(control)
                "await" -> DebugUiCommand.Await(control)
                else -> error("Unknown UI command")
            }
        }
    }
}

@Suppress("DEPRECATION") // Bundle.get distinguishes a present wrong type from an omitted key.
internal fun parseDebugUiPlanArgument(arguments: Bundle?): List<DebugUiCommand> {
    if (arguments == null || !arguments.containsKey("plan")) return listOf(DebugUiCommand.Snapshot)
    val plan = requireNotNull(arguments.get("plan") as? String) { "plan must be a String" }
    return parseDebugUiCommands(plan)
}

/** Debug-source-only entry point; runs against the real MainActivity, not a QA/demo UI. */
class DebugUiInstrumentation : Instrumentation() {
    private var planArguments: Bundle? = null
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        planArguments = arguments
        start()
    }

    override fun onStart() {
        val results = JSONArray()
        var app: Application? = null
        var activities: DebugUiActivities? = null
        var successful = false
        try {
            val commands = parseDebugUiPlanArgument(planArguments) // Reject unsupported input before launching anything.
            val application = targetContext.applicationContext as Application
            val registry = DebugUiActivities()
            app = application; activities = registry
            runOnMainSync { application.registerActivityLifecycleCallbacks(registry) }
            startActivitySync(Intent(targetContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            val controls = DebugUiControls(registry)
            var last = onMain { controls.snapshot() }
            val deadline = SystemClock.elapsedRealtime() + 30_000L
            successful = true
            for (command in commands) {
                val result = when (command) {
                    DebugUiCommand.Snapshot -> onMain { controls.snapshot() }.let { DebugUiResult(it.status, it) }
                    is DebugUiCommand.Activate -> last.generation?.let { generation ->
                        onMain { controls.activate(command.control, generation) }
                    } ?: DebugUiResult(DebugUiStatus.NO_ACTIVITY, onMain { controls.snapshot() })
                    is DebugUiCommand.Await -> {
                        // Observe actual readiness, never force completion or change animation settings.
                        val until = minOf(deadline, SystemClock.elapsedRealtime() + 3_000L)
                        var snapshot = onMain { controls.snapshot() }
                        while (snapshot.controls.first { it.id == command.control.id }.status != DebugUiStatus.READY &&
                            SystemClock.elapsedRealtime() < until) {
                            Thread.sleep(16L)
                            snapshot = onMain { controls.snapshot() }
                        }
                        DebugUiResult(snapshot.controls.first { it.id == command.control.id }.status, snapshot)
                    }
                }
                last = result.snapshot
                val json = result.toJson().put("command", when (command) {
                    DebugUiCommand.Snapshot -> "snapshot"
                    is DebugUiCommand.Activate -> "activate:${command.control.id}"
                    is DebugUiCommand.Await -> "await:${command.control.id}"
                })
                results.put(json)
                sendStatus(0, Bundle().apply { putString("ui_result", json.toString()) })
                if (command != DebugUiCommand.Snapshot && result.status !in setOf(DebugUiStatus.READY, DebugUiStatus.DISPATCHED)) {
                    successful = false
                    break
                }
                if (SystemClock.elapsedRealtime() >= deadline) { successful = false; break }
            }
        } catch (error: Exception) {
            successful = false
            results.put(JSONObject().put("status", "ERROR").put("message", error.message ?: error.javaClass.simpleName))
        } finally {
            val application = app
            val registry = activities
            if (application != null && registry != null) runOnMainSync { application.unregisterActivityLifecycleCallbacks(registry) }
            finish(if (successful) Activity.RESULT_OK else Activity.RESULT_CANCELED,
                Bundle().apply { putString("ui_results", results.toString()) })
        }
    }

    private fun <T> onMain(block: () -> T): T {
        var value: Result<T>? = null
        runOnMainSync { value = runCatching(block) }
        return checkNotNull(value).getOrThrow()
    }
}

internal fun DebugUiResult.toJson(): JSONObject = JSONObject().apply {
    put("status", status.name)
    put("generation", snapshot.generation ?: JSONObject.NULL)
    put("readiness", snapshot.status.name)
    put("navigation", snapshot.navigation?.let { state -> JSONObject().apply {
        put("settings", state.showSettings); put("incidents", state.showIncidents)
        put("library", state.showLibrary); put("about", state.showAboutDialog)
    } } ?: JSONObject.NULL)
    put("incidentsSelecting", snapshot.incidentsSelecting ?: JSONObject.NULL)
    put("libraryScrollOffset", snapshot.libraryScrollOffset ?: JSONObject.NULL)
    put("controls", JSONArray().apply { snapshot.controls.forEach { control ->
        put(JSONObject().put("id", control.id).put("status", control.status.name))
    } })
}
