package app.smallthingz.reverb.nativeui

import android.content.Context
import android.util.Log
import app.smallthingz.reverb.InlinePlaybackRowOwner
import app.smallthingz.reverb.R
import app.smallthingz.reverb.RecordingEntity
import app.smallthingz.reverb.describeRecordingLocation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import smallthingz.ui.reverb.ReverbInlineRecordingInfo

/** One expansion lifetime, with metadata/labels cached across row binds and theme-only changes. */
internal class NativeLibraryInfoLease(
    context: Context,
    environment: ReverbViewEnvironment,
    parent: CoroutineScope,
    private val describeLocation: suspend (RecordingEntity) -> String = { recording ->
        // FILE can resolve the app's storage directory here. Never run this in bind/layout.
        withContext(Dispatchers.IO) { describeRecordingLocation(context.applicationContext, recording) }
    },
) : LibraryInlineExpansionLease {
    private val context = context
    private val scope = CoroutineScope(parent.coroutineContext + Job(parent.coroutineContext[Job]))
    override val view = ReverbInlineRecordingInfo(context, environment)
    private var key: List<Any?>? = null
    private var load: Job? = null
    private var locationResolved = false
    private var closed = false

    override fun bind(recording: RecordingEntity, owner: InlinePlaybackRowOwner?, expanded: Boolean,
        screenActive: Boolean, trimRequested: Boolean) {
        if (closed) return
        if (!expanded) {
            cancelLocationLoad()
            return
        }
        val next = listOf(recording.id, recording.displayName, recording.directoryId, recording.storageType,
            recording.codecSummary, recording.mimeType, context.resources.configuration.locales.toLanguageTags())
        val changed = key != next
        if (changed) {
            key = next
            locationResolved = false
        }
        if (changed || !screenActive) cancelLocationLoad()
        if (!changed && (!screenActive || locationResolved || load != null)) return
        val labels = listOf(R.string.recording_details_codec, R.string.recording_details_mime, R.string.recording_details_location)
            .map(context::getString)
        fun rows(location: String) = listOf(labels[0] to recording.codecSummary,
            labels[1] to recording.mimeType, labels[2] to location)
        if (changed) view.bind(rows(context.getString(R.string.recording_details_loading)))
        if (!screenActive) return
        load = scope.launch {
            val location = try {
                describeLocation(recording)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                Log.w("RecordingInfo", "Could not describe recording location", failure)
                recording.id // The known URI/path remains truthful; no provider probing on main.
            }
            // A platform lookup may return after cancellation and the same key may already be
            // active again. Only the current, still-admitted coroutine may update the row.
            ensureActive()
            if (!closed && key == next) {
                locationResolved = true
                view.bind(rows(location))
            }
        }
    }

    private fun cancelLocationLoad() {
        val pending = load
        load = null
        pending?.cancel()
    }

    override fun setEnvironment(environment: ReverbViewEnvironment) = view.bindEnvironment(environment)

    override fun setPresentationAttached(attached: Boolean) {
        if (!attached) cancelLocationLoad()
    }

    override fun closePresentation() {
        closed = true
        cancelLocationLoad()
        scope.cancel()
    }
}
