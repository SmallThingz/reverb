package app.smallthingz.reverb

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat

class DebugCommandReceiver : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        val forwardedIntent =
            Intent(context, ReverbService::class.java).apply {
                action = intent.action
                intent.extras?.let { putExtras(it) }
                setPackage(context.packageName)
            }
        // Stopped diagnostic commands do not enter foreground mode. Requesting an FGS
        // for them leaves Android's promotion deadline armed while the UI remains bound.
        val started = if (debugCommandRunsWithoutListening(intent.action, debuggable = true)) {
            context.startService(forwardedIntent)
        } else {
            ContextCompat.startForegroundService(context, forwardedIntent)
        }
        checkNotNull(started) { "Unable to start Reverb debug command" }
    }
}
