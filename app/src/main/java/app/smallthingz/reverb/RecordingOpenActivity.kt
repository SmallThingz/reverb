package app.smallthingz.reverb

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import androidx.lifecycle.lifecycleScope
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class RecordingOpenActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val sourceIntent = intent
        lifecycleScope.launch {
            val openIntent = withContext(Dispatchers.IO) {
                runCatching { buildVerifiedOpenIntent(this@RecordingOpenActivity, sourceIntent) }.getOrNull()
            }
            if (openIntent != null) {
                try {
                    startActivity(openIntent)
                } catch (_: ActivityNotFoundException) {
                    AppFeedbackCenter.post(getString(R.string.no_app_available), FeedbackTone.ERROR)
                } catch (_: RuntimeException) {
                    AppFeedbackCenter.post(getString(R.string.recording_unavailable), FeedbackTone.ERROR)
                }
            } else {
                AppFeedbackCenter.post(getString(R.string.recording_unavailable), FeedbackTone.ERROR)
            }
            finish()
        }
    }

    companion object {
        private const val EXTRA_ID = "recording_id"
        private const val EXTRA_STORAGE_TYPE = "recording_storage_type"
        private const val EXTRA_MIME_TYPE = "recording_mime_type"
        private const val EXTRA_FILE_IDENTITY = "recording_file_identity"

        fun intentFor(context: Context, recording: RecordingEntity): Intent =
            Intent(context, RecordingOpenActivity::class.java)
                .putExtra(EXTRA_ID, recording.id)
                .putExtra(EXTRA_STORAGE_TYPE, recording.storageType)
                .putExtra(EXTRA_MIME_TYPE, recording.mimeType)
                .putExtra(EXTRA_FILE_IDENTITY, recording.fileIdentity)
    }
}

internal fun buildVerifiedOpenIntent(context: Context, source: Intent): Intent? {
    val id = source.getStringExtra("recording_id")?.takeIf { it.isNotBlank() } ?: return null
    val storage = source.getStringExtra("recording_storage_type")
        ?.let { stored -> RecordingStorageType.entries.firstOrNull { it.name == stored } }
        ?: return null
    val mimeType = source.getStringExtra("recording_mime_type")
        ?.takeIf { it.isNotBlank() }
        ?: ReverbConfig.FALLBACK_MIME_TYPE_AUDIO
    val uri = when (storage) {
        RecordingStorageType.FILE -> {
            val expectedIdentity = source.getStringExtra("recording_file_identity").orEmpty()
            val file = File(id)
            if (!fileIdentityMatches(expectedIdentity, resolveFileIdentity(file))) return null
            FileProvider.getUriForFile(context, "${context.packageName}.provider", file)
        }
        RecordingStorageType.DOCUMENT,
        RecordingStorageType.MEDIASTORE,
        -> id.toUri().also { candidate ->
            val expectedIdentity = source.getStringExtra("recording_file_identity").orEmpty()
            if (expectedIdentity.isNotBlank()) {
                val currentIdentity = resolveProviderRecordingIdentity(context, storage, candidate)
                if (currentIdentity.isBlank() || currentIdentity != expectedIdentity) return null
            }
            val readable = runCatching {
                context.contentResolver.openFileDescriptor(candidate, "r")?.use { true } ?: false
            }.getOrDefault(false)
            if (!readable) return null
        }
    }
    return Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(uri, mimeType)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
}
