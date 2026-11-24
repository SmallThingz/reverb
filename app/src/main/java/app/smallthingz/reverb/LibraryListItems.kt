package app.smallthingz.reverb

import android.content.Context
import java.text.DateFormat
import java.util.Calendar
import java.util.Date

/** Mechanical extraction of FilesScreen's released section model; input order remains authoritative. */
internal sealed class LibraryListItem {
    data class Header(val dateLabel: String) : LibraryListItem()
    data class Recording(val recording: RecordingEntity) : LibraryListItem()
    data class Pending(val save: PendingSave) : LibraryListItem()
    data class SaveRecoveryStatus(val message: String, val catalog: Boolean = false) : LibraryListItem()
    object RetryFailedSaves : LibraryListItem()
}

internal fun buildLibraryListItems(context: Context, recordings: List<RecordingEntity>): List<LibraryListItem> =
    if (recordings.isEmpty()) emptyList()
    else buildLibraryListItems(recordings, android.text.format.DateFormat.getLongDateFormat(context))

internal fun buildLibraryListItems(recordings: List<RecordingEntity>, dateFormat: DateFormat): List<LibraryListItem> {
    if (recordings.isEmpty()) return emptyList()
    val items = mutableListOf<LibraryListItem>()
    val calendar = dateFormat.calendar.clone() as Calendar
    val date = Date()
    var previousEra = -1
    var previousYear = -1
    var previousDay = -1
    var dateHeader = ""
    var currentDateHeader = ""
    recordings.forEach { recording ->
        calendar.timeInMillis = recording.startedAtMillis
        val era = calendar.get(Calendar.ERA)
        val year = calendar.get(Calendar.YEAR)
        val day = calendar.get(Calendar.DAY_OF_YEAR)
        if (era != previousEra || year != previousYear || day != previousDay) {
            previousEra = era
            previousYear = year
            previousDay = day
            date.time = recording.startedAtMillis
            dateHeader = dateFormat.format(date)
        }
        if (dateHeader != currentDateHeader) {
            currentDateHeader = dateHeader
            items.add(LibraryListItem.Header(dateHeader))
        }
        items.add(LibraryListItem.Recording(recording))
    }
    return items
}

internal fun buildPendingSaveItems(pending: List<PendingSave>, finishedRecordings: Map<String, String>): List<LibraryListItem.Pending> =
    pending.filterNot { it.matchesPublishedRecording(finishedRecordings) }.map { LibraryListItem.Pending(it) }

/** A published catalog row remains owned by its pending job until source/journal retirement. */
internal fun pendingSaveOutputIdentities(pending: List<PendingSave>): Map<String, Set<String>> =
    pending.filter { !it.terminallyRetired && !it.publishedRecordingId.isNullOrBlank() &&
        !it.publishedRecordingIdentity.isNullOrBlank() }
        .groupBy { checkNotNull(it.publishedRecordingId) }
        .mapValues { (_, jobs) -> jobs.mapTo(HashSet()) { checkNotNull(it.publishedRecordingIdentity) } }

internal fun recordingOwnedByPendingSave(recording: RecordingEntity, outputs: Map<String, Set<String>>): Boolean =
    recording.fileIdentity.isNotBlank() && outputs[recording.id]?.any {
        fileIdentityMatches(it, recording.fileIdentity)
    } == true

internal fun buildPendingSaveSection(
    pending: List<PendingSave>,
    finishedRecordings: Map<String, String>,
    readiness: DurableSaveJobs.Readiness = DurableSaveJobs.Readiness(loaded = true),
    catalogFailureMessage: String? = null,
): List<LibraryListItem> {
    val rows = buildPendingSaveItems(pending, finishedRecordings)
    // A truly empty catalog failure keeps the existing full-body error and Retry.
    val inlineCatalogFailure = catalogFailureMessage != null &&
        (rows.isNotEmpty() || finishedRecordings.isNotEmpty() ||
            !readiness.loaded || readiness.recoveryFailure != null)
    val retry = readiness.recoveryFailure != null || inlineCatalogFailure ||
        rows.any { it.save.phase == SavePhase.FAILED }
    return listOfNotNull(
        if (inlineCatalogFailure) LibraryListItem.SaveRecoveryStatus(checkNotNull(catalogFailureMessage), catalog = true) else null,
        LibraryListItem.RetryFailedSaves.takeIf { retry },
    ) + rows
}

internal fun performLibraryFailedSaveRetry(
    canInteract: Boolean,
    pending: List<PendingSave>,
    readiness: DurableSaveJobs.Readiness = DurableSaveJobs.Readiness(loaded = true),
    catalogFailed: Boolean = false,
    retry: () -> Unit,
): Boolean {
    if (!canInteract || (readiness.recoveryFailure == null && !catalogFailed &&
            pending.none { it.phase == SavePhase.FAILED })) return false
    retry()
    return true
}

internal fun libraryListItemKey(item: LibraryListItem): String = when (item) {
    is LibraryListItem.Header -> "header:" + item.dateLabel
    is LibraryListItem.Recording -> "recording:" + item.recording.id
    is LibraryListItem.Pending -> "save-job:" + item.save.id
    is LibraryListItem.SaveRecoveryStatus -> if (item.catalog) "library-catalog-recovery" else "save-job-recovery"
    LibraryListItem.RetryFailedSaves -> "save-jobs-retry"
}
