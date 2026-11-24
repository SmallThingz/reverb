package app.smallthingz.reverb.nativeui

import android.view.View
import app.smallthingz.reverb.R
import app.smallthingz.reverb.RecordingEntity
import app.smallthingz.reverb.describeRecordingLocation
import app.smallthingz.reverb.formatSavedRecordingDuration
import app.smallthingz.reverb.formatShortFileSize
import android.content.Context
import java.util.Date
import smallthingz.ui.reverb.ReverbRecordingInfoSheet

/** One source RecordingInfoDialogContent lifetime; no recording read/write, player or operation owner. */
internal class NativeRecordingInfoSheet(
    anchor:View,
    environment:ReverbViewEnvironment,
    recording:RecordingEntity,
    onDismiss:()->Unit,
):AutoCloseable {
    private val context = anchor.context
    private val sheet = ReverbRecordingInfoSheet(anchor, environment.theme,
        context.getString(R.string.recording_info), context.getString(R.string.close),
        recording.displayName, recording.infoRows(context), onDismiss)

    fun show() = sheet.show()
    fun bindEnvironment(next: ReverbViewEnvironment) = sheet.bindEnvironment(next.theme)
    fun bind(recording: RecordingEntity, onDismiss: () -> Unit) = sheet.bind(
        context.getString(R.string.recording_info), context.getString(R.string.close),
        recording.displayName, recording.infoRows(context), onDismiss,
    )
    override fun close() = sheet.close()
}

private fun RecordingEntity.infoRows(context: Context): List<Pair<String, String>> {
    val started = Date(startedAtMillis)
    val date = android.text.format.DateFormat.getMediumDateFormat(context)
    val time = android.text.format.DateFormat.getTimeFormat(context)
    return listOf(
        context.getString(R.string.recording_details_started) to "${date.format(started)} ${time.format(started)}",
        context.getString(R.string.recording_details_duration) to formatSavedRecordingDuration(context, durationMillis),
        context.getString(R.string.recording_details_size) to formatShortFileSize(sizeBytes),
        context.getString(R.string.recording_details_codec) to codecSummary,
        context.getString(R.string.recording_details_mime) to mimeType,
        context.getString(R.string.recording_details_storage) to storageType.name,
        context.getString(R.string.recording_details_location) to describeRecordingLocation(context, this),
    )
}
