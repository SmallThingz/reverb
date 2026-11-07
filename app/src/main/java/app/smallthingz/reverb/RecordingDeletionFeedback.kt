package app.smallthingz.reverb

import android.content.Context

internal fun recordingDeletionSupplementalReasons(result: RecordingDeletionBatchResult): Set<AppFailureReason> {
    val causes = result.failures.values.mapNotNull { it.cause } + listOfNotNull(result.cleanupFailure)
    // A missing object may be journal/catalog metadata. Only the domain reason can establish
    // that the selected recording changed; generic exception taxonomy cannot add that diagnosis.
    return causes.map { appFailureDetail(it).reason }.filterTo(linkedSetOf()) {
        it == AppFailureReason.SPACE || it == AppFailureReason.READ_ONLY ||
            it == AppFailureReason.PERMISSION || it == AppFailureReason.BUSY
    }
}

internal fun recordingDeletionFailureMessage(
    context: Context,
    name: String,
    failure: RecordingDeletionFailure,
): String {
    val resource = when (failure.reason) {
        RecordingDeletionFailureReason.BUSY -> R.string.delete_error_busy
        RecordingDeletionFailureReason.CATALOG_UNAVAILABLE -> R.string.delete_error_catalog
        RecordingDeletionFailureReason.IDENTITY_CHANGED -> R.string.delete_error_changed
        RecordingDeletionFailureReason.IDENTITY_UNVERIFIED -> R.string.delete_error_identity_unverified
        RecordingDeletionFailureReason.VERIFICATION_FAILED -> R.string.delete_error_verification
        RecordingDeletionFailureReason.JOURNAL_FAILED -> R.string.delete_error_journal
        RecordingDeletionFailureReason.STORAGE_UNAVAILABLE -> R.string.delete_error_storage
        RecordingDeletionFailureReason.PERMISSION_REQUIRED -> R.string.delete_error_permission
        RecordingDeletionFailureReason.CONSENT_UNAVAILABLE -> R.string.delete_error_consent
        RecordingDeletionFailureReason.PROVIDER_REJECTED -> R.string.delete_error_provider
        RecordingDeletionFailureReason.METADATA_CLEANUP -> R.string.delete_error_metadata
        RecordingDeletionFailureReason.UNEXPECTED -> R.string.delete_error_unexpected
    }
    val explanation = context.getString(resource)
    val message = if (failure.reason == RecordingDeletionFailureReason.METADATA_CLEANUP) explanation
        else context.getString(R.string.delete_error_target, name, explanation)
    return failure.cause?.let { actionableErrorMessage(context, message, it) } ?: message
}

internal fun recordingDeletionBatchFailureMessage(
    context: Context,
    targets: List<RecordingEntity>,
    result: RecordingDeletionBatchResult,
): String {
    if (targets.size > 1) {
        val summary = recordingDeletionSummary(targets.map { it.displayName }, result)
        val parts = mutableListOf(context.getString(R.string.delete_batch_counts,
            summary.deleted, summary.failed, summary.awaitingConsent))
        if (summary.names.isNotEmpty()) {
            parts += summary.names.joinToString(", ") + if (summary.additionalNames > 0)
                context.getString(R.string.delete_batch_more, summary.additionalNames) else ""
        }
        val reasons = summary.reasons.map { reason -> context.getString(when (reason) {
            RecordingDeletionFailureReason.BUSY -> R.string.delete_short_busy
            RecordingDeletionFailureReason.CATALOG_UNAVAILABLE -> R.string.delete_short_catalog
            RecordingDeletionFailureReason.IDENTITY_CHANGED -> R.string.delete_short_changed
            RecordingDeletionFailureReason.IDENTITY_UNVERIFIED -> R.string.delete_short_identity_unverified
            RecordingDeletionFailureReason.VERIFICATION_FAILED -> R.string.delete_short_verification
            RecordingDeletionFailureReason.JOURNAL_FAILED -> R.string.delete_short_journal
            RecordingDeletionFailureReason.STORAGE_UNAVAILABLE -> R.string.delete_short_storage
            RecordingDeletionFailureReason.PERMISSION_REQUIRED -> R.string.delete_short_permission
            RecordingDeletionFailureReason.CONSENT_UNAVAILABLE -> R.string.delete_short_consent
            RecordingDeletionFailureReason.PROVIDER_REJECTED -> R.string.delete_short_provider
            RecordingDeletionFailureReason.METADATA_CLEANUP -> R.string.delete_short_metadata
            RecordingDeletionFailureReason.UNEXPECTED -> R.string.delete_short_unknown
        }) }.toMutableSet()
        recordingDeletionSupplementalReasons(result).forEach { reason ->
            val resource = when (reason) {
                AppFailureReason.SPACE -> R.string.delete_short_space
                AppFailureReason.READ_ONLY -> R.string.delete_short_readonly
                AppFailureReason.PERMISSION -> R.string.delete_short_permission
                AppFailureReason.BUSY -> R.string.delete_short_busy
                AppFailureReason.MISSING, AppFailureReason.CANCELLED, AppFailureReason.UNKNOWN -> null
            }
            if (resource != null) reasons += context.getString(resource)
        }
        parts += reasons.joinToString("; ")
        return parts.filter { it.isNotBlank() }.joinToString("\n")
    }
    val messages = targets.indices.mapNotNull { index ->
        if (result.outcomes.getOrNull(index) == true || index in result.consentIndices) null
        else recordingDeletionFailureMessage(context, targets.getOrNull(index)?.displayName.orEmpty(),
            result.failures[index] ?: RecordingDeletionFailure(RecordingDeletionFailureReason.CATALOG_UNAVAILABLE))
    }.toMutableList()
    if (result.cleanupFailed) messages += recordingDeletionFailureMessage(context, "",
        RecordingDeletionFailure(RecordingDeletionFailureReason.METADATA_CLEANUP, result.cleanupFailure))
    return messages.distinct().joinToString("\n")
        .ifBlank { context.getString(R.string.delete_error_catalog) }
}
