package app.smallthingz.reverb

import android.content.Context
import android.system.ErrnoException
import android.system.OsConstants
import java.io.FileNotFoundException
import java.nio.file.AccessDeniedException
import java.nio.file.NoSuchFileException
import java.util.Collections
import java.util.IdentityHashMap

internal enum class AppFailureReason { PERMISSION, MISSING, SPACE, READ_ONLY, BUSY, CANCELLED, UNKNOWN }
internal data class AppFailureDetail(val reason: AppFailureReason, val type: String?, val code: Int? = null, val reported: String? = null)

/** Inspect bounded cause chains without interpreting arbitrary provider text as a factual cause. */
internal fun appFailureDetail(failure: Throwable?): AppFailureDetail {
    val visited = Collections.newSetFromMap(IdentityHashMap<Throwable, Boolean>())
    var current = failure
    var best = AppFailureDetail(AppFailureReason.UNKNOWN, failure?.javaClass?.simpleName?.take(64))
    repeat(16) {
        val error = current ?: return best
        if (!visited.add(error)) return best
        val reason = when (error) {
            is java.util.concurrent.CancellationException -> AppFailureReason.CANCELLED
            is AccessDeniedException, is SecurityException -> AppFailureReason.PERMISSION
            is NoSuchFileException -> AppFailureReason.MISSING
            // FileNotFoundException also means access denied on Android; do not invent missing-file certainty.
            is FileNotFoundException -> AppFailureReason.UNKNOWN
            is ErrnoException -> when (error.errno) {
                OsConstants.ENOSPC -> AppFailureReason.SPACE
                OsConstants.EACCES, OsConstants.EPERM -> AppFailureReason.PERMISSION
                OsConstants.ENOENT -> AppFailureReason.MISSING
                OsConstants.EROFS -> AppFailureReason.READ_ONLY
                OsConstants.EBUSY -> AppFailureReason.BUSY
                else -> AppFailureReason.UNKNOWN
            }
            else -> AppFailureReason.UNKNOWN
        }
        val detail = AppFailureDetail(reason, error.javaClass.simpleName.take(64), (error as? ErrnoException)?.errno, safeReportedErrorDetail(error.message) ?: best.reported)
        if (reason != AppFailureReason.UNKNOWN) return detail
        best = detail
        current = error.cause
    }
    return best
}

/** Shows bounded redacted reported wording; original exceptions remain available to diagnostics. */
internal fun actionableErrorMessage(context: Context, operation: String, failure: Throwable?): String {
    val detail = appFailureDetail(failure)
    val reason = context.getString(when (detail.reason) {
        AppFailureReason.PERMISSION -> R.string.failure_reason_permission
        AppFailureReason.MISSING -> R.string.failure_reason_missing
        AppFailureReason.SPACE -> R.string.failure_reason_space
        AppFailureReason.READ_ONLY -> R.string.failure_reason_read_only
        AppFailureReason.BUSY -> R.string.failure_reason_busy
        AppFailureReason.CANCELLED -> R.string.failure_reason_cancelled
        AppFailureReason.UNKNOWN -> R.string.failure_reason_unknown
    })
    val diagnostic = when {
        detail.code != null -> context.getString(R.string.failure_code, detail.type.orEmpty(), detail.code)
        detail.type != null -> context.getString(R.string.failure_type, detail.type)
        else -> ""
    }
    return listOf(operation.trim(), reason, diagnostic, detail.reported?.let { context.getString(R.string.failure_reported_detail, it) }.orEmpty()).filter { it.isNotBlank() }.joinToString(" ")
}

private val errorControls = Regex("""[\p{Cc}\p{Cf}]""")
private val errorUri = Regex("""(?i)\b[a-z][a-z0-9+.-]*://[^\s]+""")
private val errorAuthorization = Regex("""(?i)\bauthorization\s*[:=]\s*(?:"[^"]*"|'[^']*'|(?:bearer\s+|basic\s+)?[^\s,;]+)""")
private val errorBearer = Regex("""(?i)\bBearer\s+[^\s,;]+""")
private val errorCredentials = Regex("""(?i)\b(?:password|passwd|token|secret|authorization|api[_-]?key)\s*[:=]\s*(?:"[^"]*"|'[^']*'|[^\s,;]+)""")
private val errorQuotedPath = Regex("""(["'])(?:[A-Za-z]:[\\/]|/)[^"'\r\n]*\1""")
private val errorPath = Regex("""(?:[A-Za-z]:[\\/]|/)[^,;\r\n]*""")
private val errorEmail = Regex("""[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}""")
private val errorLongToken = Regex("""[A-Za-z0-9_+=/-]{48,}""")
private val errorWhitespace = Regex("""\s+""")
private val uninformativeErrors = setOf("error", "unknown", "null", "an error occurred", "operation failed")

/** Provider wording is a report, never evidence for typed classification. Bound before regex work. */
internal fun safeReportedErrorDetail(message: String?, maximumLength: Int = 160): String? {
    require(maximumLength in 1..512)
    val prefix = message?.take(2048) ?: return null
    if (prefix.isBlank()) return null
    var text = prefix.substringBefore("\n\tat ")
    text = text.replace(errorUri, "[location]")
    // Remove bearer value first: otherwise Authorization's replacement could leave its token behind.
    text = text.replace(errorAuthorization, "[redacted]")
    text = text.replace(errorBearer, "[redacted]")
    text = text.replace(errorCredentials, "[redacted]")
    text = text.replace(errorQuotedPath, "[path]")
    text = text.replace(errorPath, "[path]")
    text = text.replace(errorControls, " ")
    text = text.replace(errorEmail, "[address]")
    text = text.replace(errorLongToken, "[redacted]")
    text = text.replace(errorWhitespace, " ").trim().take(maximumLength)
    if (text.isBlank() || text.lowercase() in uninformativeErrors) return null
    return text
}
