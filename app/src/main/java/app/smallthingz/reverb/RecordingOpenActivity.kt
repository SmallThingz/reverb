package app.smallthingz.reverb

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.lifecycleScope
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class RecordingOpenActivity : ComponentActivity() {
    private lateinit var resultDelivery: RecordingOpenResultDelivery<Intent>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val sourceIntent = intent
        resultDelivery = RecordingOpenResultDelivery(
            navigationAllowed = ::navigationAllowed,
            deliver = { openIntent ->
                if (openIntent != null) {
                    try {
                        startActivity(openIntent)
                    } catch (error: ActivityNotFoundException) {
                        Log.w("RecordingOpen", "No compatible recording viewer", error)
                        returnToReverbWithFailure(getString(R.string.recording_open_no_handler))
                    } catch (error: RuntimeException) {
                        Log.w("RecordingOpen", "Unable to launch recording viewer", error)
                        returnToReverbWithFailure(actionableErrorMessage(this,
                            getString(R.string.recording_open_failed), error))
                    }
                } else {
                    returnToReverbWithFailure(verificationFailureMessage(null))
                }
            },
            reportUndeliveredFailure = {
                AppFeedbackCenter.post(verificationFailureMessage(null), FeedbackTone.ERROR)
            },
            finish = ::finish,
            deliverFailure = { failure -> returnToReverbWithFailure(verificationFailureMessage(failure)) },
            reportUndeliveredFailureWithCause = { failure ->
                AppFeedbackCenter.post(verificationFailureMessage(failure), FeedbackTone.ERROR)
            },
        )
        lifecycle.addObserver(LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> resultDelivery.foregroundChanged()
                Lifecycle.Event.ON_PAUSE, Lifecycle.Event.ON_DESTROY -> resultDelivery.retire()
                else -> Unit
            }
        })
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { buildVerifiedOpenIntent(applicationContext, sourceIntent) }
            }
            result.exceptionOrNull()?.let { Log.w("RecordingOpen", "Unable to verify saved recording", it) }
            resultDelivery.complete(result.getOrNull(), result.exceptionOrNull())
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && ::resultDelivery.isInitialized) resultDelivery.foregroundChanged()
    }

    private fun navigationAllowed(): Boolean = !isFinishing && !isDestroyed &&
        lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) && window.decorView.hasWindowFocus()

    private fun verificationFailureMessage(failure: Throwable?): String =
        if (failure == null) getString(R.string.recording_open_unverified)
        else actionableErrorMessage(this, getString(R.string.recording_open_unverified), failure)

    private fun returnToReverbWithFailure(message: String) {
        AppFeedbackCenter.post(message, FeedbackTone.ERROR)
        // This activity is only a notification trampoline and has no feedback host of its own.
        // Return only while the original foreground request still owns navigation.
        if (!navigationAllowed()) return
        runCatching {
            startActivity(
                Intent(this, MainActivity::class.java).apply {
                    addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK or
                            Intent.FLAG_ACTIVITY_CLEAR_TOP or
                            Intent.FLAG_ACTIVITY_SINGLE_TOP,
                    )
                },
            )
        }.onFailure { Log.w("RecordingOpen", "Unable to return to Reverb", it) }
    }

    companion object {
        private const val EXTRA_ID = "recording_id"
        private const val EXTRA_STORAGE_TYPE = "recording_storage_type"
        private const val EXTRA_MIME_TYPE = "recording_mime_type"
        private const val EXTRA_FILE_IDENTITY = "recording_file_identity"
        private const val EXTRA_DISPLAY_NAME = "recording_display_name"
        private const val EXTRA_SIZE_BYTES = "recording_size_bytes"

        fun intentFor(context: Context, recording: RecordingEntity): Intent =
            Intent(context, RecordingOpenActivity::class.java).apply {
                putExtra(EXTRA_ID, recording.id)
                putExtra(EXTRA_STORAGE_TYPE, recording.storageType.storageCode)
                putExtra(EXTRA_MIME_TYPE, recording.mimeType)
                putExtra(EXTRA_FILE_IDENTITY, recording.fileIdentity)
                putExtra(EXTRA_DISPLAY_NAME, recording.displayName)
                putExtra(EXTRA_SIZE_BYTES, recording.sizeBytes)
            }
    }
}

internal fun buildVerifiedOpenIntent(context: Context, source: Intent): Intent? {
    val id = source.getStringExtra("recording_id")?.takeIf { it.isNotBlank() } ?: return null
    val encodedStorage = runCatching {
        source.getByteExtra("recording_storage_type", Byte.MIN_VALUE)
    }.getOrDefault(Byte.MIN_VALUE)
    val storage = if (encodedStorage != Byte.MIN_VALUE) {
        RecordingStorageType.fromStorageCode(encodedStorage.toInt())
    } else {
        runCatching { source.getStringExtra("recording_storage_type") }.getOrNull()
            ?.let(RecordingStorageType::fromLegacyName)
    } ?: return null
    val mimeType = source.getStringExtra("recording_mime_type")
        ?.takeIf { it.isNotBlank() }
        ?: FALLBACK_MIME_TYPE_AUDIO
    val uri = when (storage) {
        RecordingStorageType.FILE -> {
            val expectedIdentity = source.getStringExtra("recording_file_identity").orEmpty()
            val file = File(id)
            if (!fileIdentityMatches(expectedIdentity, resolveFileIdentity(file))) return null
            buildVerifiedFileProviderUri(context, file, expectedIdentity)
        }
        RecordingStorageType.DOCUMENT,
        RecordingStorageType.MEDIASTORE,
        -> {
            val expectedIdentity = source.getStringExtra("recording_file_identity").orEmpty()
            if (expectedIdentity.isBlank()) return null
            val displayName = source.getStringExtra("recording_display_name")
                ?.takeIf { it.isNotBlank() } ?: "recording"
            val sizeBytes = source.getLongExtra("recording_size_bytes", 0L).coerceAtLeast(0L)
            buildVerifiedProviderUri(
                context,
                VerifiedProviderRequest(
                    storageType = storage,
                    sourceId = id,
                    expectedIdentity = expectedIdentity,
                    mimeType = mimeType,
                    displayName = displayName,
                    sizeBytes = sizeBytes,
                ),
            )
        }
    }
    return Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(uri, mimeType)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
}
