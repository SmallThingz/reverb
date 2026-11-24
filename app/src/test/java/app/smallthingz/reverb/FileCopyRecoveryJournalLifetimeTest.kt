package app.smallthingz.reverb

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import java.io.File
import java.io.IOException
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Real journal transitions, with only the SharedPreferences durability result fault-injected. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class FileCopyRecoveryJournalLifetimeTest {
    private class JournalContext(base: Context) : ContextWrapper(base) {
        private val namespace = UUID.randomUUID().toString()
        var rejectRetirement = false
        override fun getApplicationContext(): Context = this
        override fun getExternalFilesDir(type: String?): File = File(baseContext.cacheDir, namespace)
        override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences {
            val preferences = super.getSharedPreferences("$name-$namespace", mode)
            return object : SharedPreferences by preferences {
                override fun edit(): SharedPreferences.Editor {
                    val editor = preferences.edit()
                    var retiring = false
                    return object : SharedPreferences.Editor by editor {
                        override fun remove(key: String?): SharedPreferences.Editor {
                            if (key == PrefKey.FILE_COPY_PUBLICATIONS.name) retiring = true
                            editor.remove(key)
                            return this
                        }
                        override fun commit(): Boolean {
                            val committed = editor.commit()
                            return committed && !(retiring && rejectRetirement)
                        }
                    }
                }
            }
        }
    }

    private fun retainedOwners(): Int {
        val field = FileCopyPublications::class.java.getDeclaredField("recoveryIncidents").apply { isAccessible = true }
        return (field.get(FileCopyPublications) as FileCopyRecoveryIncidentOwners).size
    }

    private fun fixture(test: (JournalContext, FileCopyPublication) -> Unit) {
        val context = JournalContext(RuntimeEnvironment.getApplication())
        val directory = getSavedRecordingsDirectory(context)
        val record = FileCopyPublication(UUID.randomUUID().toString(), FileCopyPurpose.RENAME,
            File(directory, "source.wav").path, "renamed.wav", File(directory, "renamed.wav").path,
            "stat:1:2:3:4:0", 48L, "0".repeat(64))
        val before = retainedOwners()
        FileCopyPublications.replace(context, null, record)
        try {
            assertEquals(before + 1, retainedOwners())
            test(context, record)
        } finally {
            context.rejectRetirement = false
            FileCopyPublications.snapshot(context).records.forEach { FileCopyPublications.replace(context, it, null) }
            assertEquals(before, retainedOwners())
        }
    }

    @Test fun failedDurabilityRetainsDeduplicationAndSuccessfulTerminalReleasesIt() = fixture { context, record ->
        val owner = checkNotNull(record.recoveryIncidentOwner)
        assertTrue(owner.claim())
        val snapshot = FileCopyPublications.snapshot(context).records.single()
        assertSame(owner, snapshot.recoveryIncidentOwner)
        context.rejectRetirement = true
        assertThrows(IOException::class.java) { FileCopyPublications.replace(context, record, null) }
        assertEquals(record, FileCopyPublications.snapshot(context).records.single())
        assertFalse(owner.claim())
        context.rejectRetirement = false
        FileCopyPublications.replace(context, record, null)
        assertTrue(FileCopyPublications.snapshot(context).records.isEmpty())
        assertFalse(checkNotNull(snapshot.recoveryIncidentOwner).claim())
    }

    @Test fun incompletePreservationMarksExactFailureOnlyAfterDurableTerminal() = fixture { context, record ->
        val owner = checkNotNull(record.recoveryIncidentOwner)
        assertTrue(owner.claim()) // An earlier recovery already owns this token's one diagnostic.
        val failure = FileCopyRecoveryRequired("incomplete preserved")
        val fingerprint = StableOutputFingerprint(CopyDigest(16L, ByteArray(32)), "stat:1:5:6:7:0", null)
        val store = ContextFileCopyPublicationStore(context)
        context.rejectRetirement = true
        assertThrows(IOException::class.java) { store.preserveIncomplete(record, fingerprint, failure) }
        assertFalse(failure.incidentOwnedByRecovery)
        assertEquals(record, FileCopyPublications.snapshot(context).records.single())
        assertSame(owner, FileCopyPublications.snapshot(context).records.single().recoveryIncidentOwner)
        context.rejectRetirement = false
        store.preserveIncomplete(record, fingerprint, failure)
        assertTrue(failure.incidentOwnedByRecovery)
        assertTrue(FileCopyPublications.snapshot(context).records.isEmpty())
        assertFalse(owner.claim())
        val suppression = getRecorderPreferences(context).requireDurableStringSet(PrefKey.PENDING_OUTPUT_CLEANUP)
            .mapNotNull(::decodePendingOutputCleanupRecord).single()
        assertEquals(record.targetId, suppression.id)
        assertNull(suppression.fileKey)
        assertNull(suppression.providerIdentity)
    }
}
