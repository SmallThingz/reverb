package app.smallthingz.reverb

internal data class ForegroundNotificationContent(val title: String, val text: String)

/** Main-owned proof of the last successful, exclusively retention-owned notification. */
internal class RetentionForegroundPublication(private val dataSyncType: Int) {
    private var publishedRetentionContent: ForegroundNotificationContent? = null

    fun publishRetentionIfNeeded(
        retentionOnly: Boolean,
        foregroundServiceTypes: Int,
        content: ForegroundNotificationContent,
        publication: () -> Unit,
    ) {
        if (retentionOnly && foregroundServiceTypes == dataSyncType && publishedRetentionContent == content) return
        publication()
    }

    fun publish(
        types: Int,
        exporting: Boolean,
        clearing: Boolean,
        retaining: Boolean,
        content: () -> ForegroundNotificationContent,
        publication: (ForegroundNotificationContent) -> Unit,
    ) {
        // A failed or uncertain transition must never reuse the previous owner's proof.
        invalidate()
        val publishedContent = content()
        publication(publishedContent)
        if (types == dataSyncType && retaining && !exporting && !clearing) {
            publishedRetentionContent = publishedContent
        }
    }

    fun invalidate() {
        publishedRetentionContent = null
    }
}
