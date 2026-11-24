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
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import smallthingz.ui.reverb.ReverbInlineRecordingInfo

/** One expansion lifetime, with metadata/labels cached across row binds and theme-only changes. */
internal class NativeLibraryInfoLease(context: Context, environment: ReverbViewEnvironment, parent: CoroutineScope) : LibraryInlineExpansionLease {
    private val context = context
    private val scope = CoroutineScope(parent.coroutineContext + Job(parent.coroutineContext[Job]))
    override val view = ReverbInlineRecordingInfo(context, environment)
    private var key: List<Any?>? = null
    private var load: Job? = null
    private var closed = false

    override fun bind(recording: RecordingEntity, owner: InlinePlaybackRowOwner?, expanded: Boolean,
        screenActive: Boolean, trimRequested: Boolean) {
        if (closed || !expanded) return
        val next = listOf(recording.id, recording.displayName, recording.directoryId, recording.storageType,
            recording.codecSummary, recording.mimeType, context.resources.configuration.locales.toLanguageTags())
        if (key == next) return
        key = next
        load?.cancel()
        val labels = listOf(R.string.recording_details_codec, R.string.recording_details_mime, R.string.recording_details_location)
            .map(context::getString)
        fun rows(location: String) = listOf(labels[0] to recording.codecSummary,
            labels[1] to recording.mimeType, labels[2] to location)
        view.bind(rows(context.getString(R.string.recording_details_loading)))
        load = scope.launch {
            val location = try {
                // FILE formatting reaches getSavedRecordingsDirectory; never run that in bind/layout.
                withContext(Dispatchers.IO) { describeRecordingLocation(context.applicationContext, recording) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                Log.w("RecordingInfo", "Could not describe recording location", failure)
                recording.id // The known URI/path remains truthful; no provider probing on main.
            }
            if (!closed && key == next) view.bind(rows(location))
        }
    }
    override fun setEnvironment(environment: ReverbViewEnvironment) = view.bindEnvironment(environment)
    override fun setPresentationAttached(attached: Boolean) = Unit
    override fun closePresentation() { closed = true; scope.cancel() }
}
