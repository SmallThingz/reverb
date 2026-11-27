package app.smallthingz.reverb

import android.content.IntentSender
import androidx.lifecycle.ViewModel
import java.util.Collections
import java.util.IdentityHashMap
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/** The original, positively unchanged target; never reconstructed from a later URI occupant. */
internal class RecordingRenameConsentRequired(
    val recording: RecordingEntity,
    val displayName: String,
    val before: MediaStoreRenameObservation,
    failure: SecurityException,
    val legacySender: IntentSender?,
) : SecurityException("Write permission is required to rename this recording", failure)

internal fun renameConsentObservationMatches(
    expected: MediaStoreRenameObservation,
    actual: MediaStoreRenameObservation?,
): Boolean = actual != null && expected.displayName == actual.displayName &&
    providerRecordingIdentityMatches(expected.fingerprint.providerIdentity.orEmpty(),
        actual.fingerprint.providerIdentity.orEmpty()) &&
    expected.fingerprint.fileKey == actual.fingerprint.fileKey &&
    copyDigestMatches(expected.fingerprint.digest, actual.fingerprint.digest)

/** One identity per Activity ViewModelStore, retained across configuration recreation. */
internal class RecordingRenameConsentHostViewModel : ViewModel() {
    init { RecordingDeletionConsent.attachRenameHost(this) }
    override fun onCleared() { RecordingDeletionConsent.detachRenameHost(this) }
}

/** Accessed only under the shared consent coordinator monitor. */
internal class RecordingRenameConsentHost {
    private val owners = Collections.newSetFromMap(IdentityHashMap<Any, Boolean>())
    var generation: Long = 0L
        private set
    fun attach(value: Any) { owners.add(value) }
    fun accepts(expectedGeneration: Long): Boolean = owners.isNotEmpty() && generation == expectedGeneration
    fun detach(value: Any): Boolean {
        if (!owners.remove(value) || owners.isNotEmpty()) return false
        generation += 1
        return true
    }
}

/** A cancelled caller must not release its recording reservation ahead of an approved retry. */
internal suspend fun <T> awaitCommittedRenameConsent(result: Deferred<T>): T =
    withContext(NonCancellable) { result.await() }

/** Only positively unchanged rejection or permission-flow termination may keep the editor open. */
internal class RecordingRenameDefiniteFailure(
    message: String,
    cause: Throwable,
    val expectedConsentRejection: RecordingRenameConsentRejection? = null,
) : IOException(message, cause)

internal enum class RecordingRenameConsentRejection { DENIED, HOST_RETIRED }

/** Explicit user/lifecycle outcomes are expected; failure to launch consent is still an error. */
internal fun recordingRenameConsentFailure(
    proof: Throwable,
    rejection: RecordingRenameConsentRejection,
    launchFailed: Boolean = false,
    launchFailure: Throwable? = null,
): RecordingRenameDefiniteFailure {
    val message = if (rejection == RecordingRenameConsentRejection.HOST_RETIRED)
        "Rename permission request was cancelled" else "Write permission was not granted for this rename"
    return RecordingRenameDefiniteFailure(message, launchFailure ?: SecurityException(message, proof),
        expectedConsentRejection = rejection.takeUnless { launchFailed || launchFailure != null })
}

/** Called only after unchanged-source proof, before any permission prompt or approved retry. */
internal inline fun <T> prepareRecordingRenameConsentSender(factory: () -> T): T = try {
    factory()
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (failure: Exception) {
    throw RecordingRenameDefiniteFailure("Unable to request rename permission", failure)
}

internal fun routeRecordingRenameFailure(
    failure: Throwable,
    onDefinite: (Throwable) -> Unit,
    onReconcile: (Throwable) -> Unit,
) {
    if (failure is RecordingRenameDefiniteFailure) onDefinite(failure) else onReconcile(failure)
}
