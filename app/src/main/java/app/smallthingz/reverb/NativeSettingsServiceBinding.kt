package app.smallthingz.reverb

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver

/** The released Settings read-style service lease. It never starts capture or creates a Service. */
internal class NativeSettingsServiceBinding(
    private val context: Context,
    private val lifecycle: Lifecycle,
) : AutoCloseable {
    var service: ReverbService? = null
        private set
    private var generation = 0L
    private var owned = false
    private var closed = false
    private var boundConnection: ServiceConnection? = null
    private val observer = LifecycleEventObserver { _, event ->
        when (event) {
            Lifecycle.Event.ON_START -> bindIfNeeded()
            Lifecycle.Event.ON_STOP -> unbindCurrentConnection()
            else -> Unit
        }
    }

    init { lifecycle.addObserver(observer) }

    fun bind(active: Boolean, persisting: Boolean) {
        val next = settingsShouldOwnServiceBinding(active, persisting)
        if (closed || next == owned) return
        // DisposableEffect(shouldOwnServiceBinding) releases the old connection first.
        unbindCurrentConnection()
        owned = next
        bindIfNeeded()
    }

    private fun unbindCurrentConnection() {
        val current = boundConnection ?: return
        boundConnection = null
        generation++
        service = null
        runCatching { context.unbindService(current) }
    }

    private fun bindIfNeeded() {
        if (closed || !owned || boundConnection != null ||
            !lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) return
        val bindingGeneration = ++generation
        val candidate = object : ServiceConnection {
            private fun isCurrent(): Boolean = settingsServiceBindingCallbackIsCurrent(
                callbackGeneration = bindingGeneration,
                currentGeneration = generation,
                bindingOwned = boundConnection === this,
            )
            override fun onServiceConnected(className: ComponentName, binder: IBinder) {
                if (!isCurrent()) return
                service = (binder as? ReverbService.BackgroundRecorderBinder)?.service
            }
            override fun onServiceDisconnected(name: ComponentName) {
                if (!isCurrent()) return
                service = null
            }
        }
        boundConnection = candidate
        val bound = runCatching {
            context.bindService(Intent(context, ReverbService::class.java), candidate, 0)
        }.getOrDefault(false)
        if (!bound && boundConnection === candidate) {
            boundConnection = null
            generation++
            service = null
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        lifecycle.removeObserver(observer)
        unbindCurrentConnection()
    }
}
