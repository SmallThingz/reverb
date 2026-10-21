package app.smallthingz.reverb.nativeui

import androidx.compose.runtime.MonotonicFrameClock
import app.smallthingz.reverb.R
import kotlinx.coroutines.CoroutineScope

/** IncidentsScreen446–467: Copy always; Delete only for a currently mutable history snapshot. */
internal class NativeIncidentMenuHost(
    private var environment: ReverbViewEnvironment,
    frames: MonotonicFrameClock,
    scope: CoroutineScope,
) : IncidentCardMenuHost, AutoCloseable {
    private val host = ReverbActionMenuHost(environment, frames, scope)
    private val requests = java.util.IdentityHashMap<IncidentCardMenuIdentity, IncidentCardMenuRequest>()
    private fun actions(request: IncidentCardMenuRequest): List<ReverbMenuAction> = buildList {
        val context = request.anchor.context
        val palette = environment.palette
        add(ReverbMenuAction(context.getString(R.string.incident_copy), ReverbIcon.Copy,
            palette.onSurface, palette.onSurfaceVariant, onClick = request.copy))
        if (request.canDelete) add(ReverbMenuAction(context.getString(R.string.delete_recording),
            ReverbIcon.Delete, palette.error, palette.error, onClick = request.delete))
    }
    override fun show(request: IncidentCardMenuRequest) {
        requests[request.identity] = request
        host.show(request.identity, request.anchor, actions(request), environment.palette.surfaceContainerHigh)
    }
    override fun update(request: IncidentCardMenuRequest) {
        requests[request.identity] = request
        host.update(request.identity, request.anchor, actions(request), environment.palette.surfaceContainerHigh)
    }
    fun bindPalette(palette: ReverbPalette) {
        environment = ReverbViewEnvironment(environment.textDensity, environment.sharedFontResolver, palette)
        host.bindPalette(palette)
        requests.values.toList().forEach(::update)
    }
    override fun release(identity: IncidentCardMenuIdentity) { requests.remove(identity); host.release(identity) }
    override fun close() { requests.clear(); host.close() }
}
