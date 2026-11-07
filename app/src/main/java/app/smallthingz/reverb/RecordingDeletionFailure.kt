package app.smallthingz.reverb

internal enum class RecordingDeletionFailureReason {
    BUSY, CATALOG_UNAVAILABLE, IDENTITY_CHANGED, IDENTITY_UNVERIFIED, VERIFICATION_FAILED, JOURNAL_FAILED,
    STORAGE_UNAVAILABLE, PERMISSION_REQUIRED, CONSENT_UNAVAILABLE, PROVIDER_REJECTED, METADATA_CLEANUP, UNEXPECTED,
}

internal data class RecordingDeletionFailure(
    val reason: RecordingDeletionFailureReason,
    val cause: Throwable? = null,
)

internal fun recordingDeletionFailureAfterException(
    previous: RecordingDeletionFailure,
    error: Exception,
): RecordingDeletionFailure = if (
    previous.reason == RecordingDeletionFailureReason.JOURNAL_FAILED && previous.cause === error
) previous else RecordingDeletionFailure(when (error) {
    is SecurityException -> RecordingDeletionFailureReason.PERMISSION_REQUIRED
    is java.io.IOException -> RecordingDeletionFailureReason.STORAGE_UNAVAILABLE
    else -> RecordingDeletionFailureReason.UNEXPECTED
}, error)

internal data class RecordingDeletionSummary(
    val deleted: Int,
    val failed: Int,
    val awaitingConsent: Int,
    val names: List<String>,
    val additionalNames: Int,
    val reasons: Set<RecordingDeletionFailureReason>,
)

internal fun recordingDeletionSummary(names: List<String>, result: RecordingDeletionBatchResult): RecordingDeletionSummary {
    val failed = names.indices.filter { result.outcomes.getOrNull(it) != true && it !in result.consentIndices }
    val reasons = failed.mapTo(linkedSetOf()) {
        result.failures[it]?.reason ?: RecordingDeletionFailureReason.CATALOG_UNAVAILABLE
    }
    if (result.cleanupFailed) reasons += RecordingDeletionFailureReason.METADATA_CLEANUP
    return RecordingDeletionSummary(
        deleted = result.outcomes.take(names.size).count { it },
        failed = failed.size,
        awaitingConsent = names.indices.count { it in result.consentIndices },
        names = failed.take(2).map { names[it].replace('\n', ' ').replace('\r', ' ').let { name ->
            if (name.length > 48) name.take(45) + "..." else name
        } },
        additionalNames = (failed.size - 2).coerceAtLeast(0),
        reasons = reasons,
    )
}
