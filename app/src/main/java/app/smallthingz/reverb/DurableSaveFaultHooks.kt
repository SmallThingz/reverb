package app.smallthingz.reverb

import android.content.Context
import java.io.File

internal fun durableSaveJournalRoot(context: Context): File = File(context.noBackupFilesDir, "durable-saves")

/** Isolated reliability fixtures install bounded barriers; production leaves this null. */
internal object DurableSaveFaultHooks {
    @Volatile var onBoundary: ((jobId: String, boundary: String) -> Unit)? = null
    fun reached(jobId: String, boundary: String) { onBoundary?.invoke(jobId, boundary) }
}

internal data class DurableSaveHandle(val id: String, val publication: DurableSavePublicationReceipt?)
