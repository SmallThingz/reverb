package app.smallthingz.reverb

import android.content.Context
import android.content.Intent
import android.os.Bundle
import java.util.UUID

/** A notification selects one revision, never whichever bytes later reuse its location. */
internal data class RecordingPlaybackRequest(
    val token: String,
    val recordingId: String,
    val storageType: RecordingStorageType,
    val fileIdentity: String,
) {
    fun matches(recording: RecordingEntity): Boolean = recording.id == recordingId &&
        recording.storageType == storageType && fileIdentity.isNotBlank() &&
        (if (storageType == RecordingStorageType.FILE) fileIdentityMatches(fileIdentity, recording.fileIdentity)
        else fileIdentity == recording.fileIdentity)

    fun toBundle() = Bundle().apply {
        putString("token", token)
        putString("id", recordingId)
        putInt("storage", storageType.storageCode.toInt())
        putString("identity", fileIdentity)
    }

    companion object {
        private const val EXTRA = "reverb_saved_recording_playback"

        fun intentFor(context: Context, recording: RecordingEntity): Intent =
            Intent(context, MainActivity::class.java).apply {
                action = recordingSavedPendingIntentAction(context.packageName, recording.id, recording.fileIdentity)
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                putExtra(EXTRA, RecordingPlaybackRequest(UUID.randomUUID().toString(), recording.id,
                    recording.storageType, recording.fileIdentity).toBundle())
            }

        fun consume(intent: Intent): RecordingPlaybackRequest? {
            val value = runCatching { intent.getBundleExtra(EXTRA) }.getOrNull()
            intent.removeExtra(EXTRA)
            return fromBundle(value)
        }

        fun fromBundle(bundle: Bundle?): RecordingPlaybackRequest? = runCatching {
            bundle ?: return null
            val token = bundle.getString("token")?.takeIf { it.isNotBlank() && it.length <= 128 } ?: return null
            val id = bundle.getString("id")?.takeIf { it.isNotBlank() && it.length <= 16384 } ?: return null
            val storage = RecordingStorageType.fromStorageCode(bundle.getInt("storage", -1)) ?: return null
            val identity = bundle.getString("identity")?.takeIf { it.isNotBlank() && it.length <= 16384 } ?: return null
            RecordingPlaybackRequest(token, id, storage, identity)
        }.getOrNull()
    }
}

/** Latest-only routing authority; an old scan or lifecycle proof cannot start a newer request. */
internal class RecordingPlaybackRoute {
    var request: RecordingPlaybackRequest? = null
        private set
    private var verifiedToken: String? = null
    var proofGeneration: Long = 0
        private set

    fun submit(value: RecordingPlaybackRequest): Boolean {
        if (request?.token == value.token) return false
        request = value
        invalidateProof()
        return true
    }

    fun verified(token: String?, generation: Long) {
        if (generation == proofGeneration && token != null && request?.token == token) verifiedToken = token
    }

    fun invalidateProof() { verifiedToken = null; proofGeneration++ }

    fun takeIfReady(foreground: Boolean): RecordingPlaybackRequest? {
        val current = request ?: return null
        if (!foreground || verifiedToken != current.token) return null
        clear()
        return current
    }

    fun clear() { request = null; invalidateProof() }
}
