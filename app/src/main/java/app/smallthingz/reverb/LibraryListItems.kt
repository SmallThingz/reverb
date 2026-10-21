package app.smallthingz.reverb

import android.content.Context
import java.util.Date

/** Mechanical extraction of FilesScreen's released section model; input order remains authoritative. */
internal sealed class LibraryListItem {
    data class Header(val dateLabel: String) : LibraryListItem()
    data class Recording(val recording: RecordingEntity) : LibraryListItem()
}

internal fun buildLibraryListItems(context: Context, recordings: List<RecordingEntity>): List<LibraryListItem> {
    if (recordings.isEmpty()) return emptyList()
    val dateFormat = android.text.format.DateFormat.getLongDateFormat(context)
    val items = mutableListOf<LibraryListItem>()
    var currentDateHeader = ""
    recordings.forEach { recording ->
        val dateHeader = dateFormat.format(Date(recording.startedAtMillis))
        if (dateHeader != currentDateHeader) {
            currentDateHeader = dateHeader
            items.add(LibraryListItem.Header(dateHeader))
        }
        items.add(LibraryListItem.Recording(recording))
    }
    return items
}

internal fun libraryListItemKey(item: LibraryListItem): String = when (item) {
    is LibraryListItem.Header -> "header:" + item.dateLabel
    is LibraryListItem.Recording -> "recording:" + item.recording.id
}
