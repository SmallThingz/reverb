package app.smallthingz.reverb.nativeui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.SystemClock
import app.smallthingz.reverb.AppFeedbackCenter
import app.smallthingz.reverb.FeedbackTone
import app.smallthingz.reverb.IncidentHistoryController
import app.smallthingz.reverb.IncidentPresentation
import app.smallthingz.reverb.R
import app.smallthingz.reverb.summaryAt
import smallthingz.ui.reverb.ReverbIncidentActions
import smallthingz.ui.reverb.ReverbIncidentTexts

internal fun incidentTexts(context: Context): ReverbIncidentTexts = ReverbIncidentTexts(
    title = context.getString(R.string.incidents_title),
    back = context.getString(R.string.back),
    clearSelection = context.getString(R.string.clear_selection),
    selectionCount = { context.resources.getQuantityString(R.plurals.incidents_selected, it, it) },
    multiSelect = context.getString(R.string.multi_select),
    selectAll = context.getString(R.string.incidents_select_all),
    deselectAll = context.getString(R.string.incidents_deselect_all),
    deleteSelected = context.getString(R.string.incidents_delete_selected),
    clearAll = context.getString(R.string.incidents_clear_all),
    clearConfirmation = context.getString(R.string.incidents_clear_all_detail),
    cancel = context.getString(R.string.cancel),
    empty = context.getString(R.string.incidents_empty),
    emptyDetail = context.getString(R.string.incidents_empty_detail),
    loading = context.getString(R.string.incidents_loading),
    unavailable = context.getString(R.string.incidents_unavailable),
    stale = context.getString(R.string.incidents_stale),
    select = context.getString(R.string.incident_select),
    deselect = context.getString(R.string.incident_deselect),
    markChecked = context.getString(R.string.incident_mark_checked),
    markUnchecked = context.getString(R.string.incident_mark_unchecked),
    openOptions = context.getString(R.string.open_options),
    copy = context.getString(R.string.incident_copy),
    delete = context.getString(R.string.delete_recording),
)

internal fun incidentContentActions(context: Context, controller: IncidentHistoryController): ReverbIncidentActions =
    ReverbIncidentActions(
        toggleAcknowledged = { controller.toggleAcknowledged((it as IncidentPresentation).incident) },
        delete = { controller.delete((it as IncidentPresentation).incident) },
        deleteMany = { targets -> controller.deleteOccurrences(targets.map { (it as IncidentPresentation).incident }) },
        copy = { copyIncident(context, it as IncidentPresentation) },
    )

private fun copyIncident(context: Context, row: IncidentPresentation) {
    val text = buildList {
        add(row.date)
        add(row.summaryAt(context, SystemClock.elapsedRealtime()))
        row.ended?.let { add(context.getString(R.string.incident_occurrence_end, it)) }
        if (row.cause.isNotEmpty()) add(row.cause)
        if (row.runtime.isNotEmpty()) add(row.runtime)
        if (row.age.isNotEmpty()) add(row.age)
        row.description?.let(::add)
    }.joinToString("\n")
    context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(
        ClipData.newPlainText(context.getString(R.string.incident_copy_label), text))
    AppFeedbackCenter.post(context.getString(R.string.incident_copied), FeedbackTone.SUCCESS)
}
