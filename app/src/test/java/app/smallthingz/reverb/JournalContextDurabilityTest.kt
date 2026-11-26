package app.smallthingz.reverb

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.os.Process
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowProcess

@RunWith(org.robolectric.RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, shadows = [JournalHostIoShadow::class])
class JournalContextDurabilityTest {
    private val retentionBytes = "4e52545201000000010000003930000000000000b268de3a0000000031d4000000000000b0327976000000005d0a1d61"
        .chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    private val retention = RetentionConfiguration(RetentionMode.TIME, 12345L, 987654322L, 54321L, 1987654320L)

    @Test fun actualKnownStopPublishesOnlyDisarmedSameSessionAfterDirectoryBarrier() = fixture { context, root ->
        val bytes = currentSession()
        val session = File(root, "recording-session.bin").apply { writeBytes(bytes) }
        val token = RecordingIncidentStore.beginKnownCaptureStop(context)
        assertTrue(token.armed)
        assertEquals(DeferredKnownCaptureStopResult.DURABLE,
            RecordingIncidentStore.completeKnownCaptureStop(context, token))
        val disarmed = bytes.copyOf().also { it[5] = 0 }
        assertArrayEquals(disarmed, session.readBytes())
        assertTrue(JournalHostIoShadow.directorySyncAttempts > 0)
        assertEquals(setOf("recording-session.bin"), root.list()!!.toSet())
        assertEquals(0, JournalHostIoShadow.outstandingOwners)
    }

    @Test fun failedDisarmDirectoryBarrierUsesDurableAbsenceFallback() = fixture { context, root ->
        File(root, "recording-session.bin").writeBytes(currentSession())
        val token = RecordingIncidentStore.beginKnownCaptureStop(context)
        JournalHostIoShadow.failDirectorySyncs = 1
        assertEquals(DeferredKnownCaptureStopResult.DURABLE,
            RecordingIncidentStore.completeKnownCaptureStop(context, token))
        assertTrue(root.list()!!.isEmpty())
        assertTrue(JournalHostIoShadow.directorySyncAttempts >= 2)
        assertEquals(0, JournalHostIoShadow.outstandingOwners)
    }

    @Test fun actualRetentionWriteAndReadBindPublishedBytesAndCloseEveryOwner() = fixture { context, root ->
        assertTrue(writeRetentionRecoveryConfiguration(context, retention))
        assertArrayEquals(retentionBytes, File(root, "retention-config.v1").readBytes())
        assertEquals(RetentionRecoveryRead(RetentionRecoveryReadState.VALID, retention), readRetentionRecovery(context))
        assertTrue(JournalHostIoShadow.directorySyncAttempts > 0)
        assertEquals(setOf("retention-config.v1"), root.list()!!.toSet())
        assertEquals(0, JournalHostIoShadow.outstandingOwners)
    }

    @Test fun retentionPublicationRejectsAnEqualByteReplacement() = fixture { context, root ->
        var published: RetentionRecoveryPublication? = null
        assertTrue(writeRetentionRecoveryConfiguration(context, retention) { published = it })
        val publication = requireNotNull(published)
        assertTrue(readRetentionRecovery(context, publication).matchesPublication)

        val base = File(root, "retention-config.v1")
        val replacement = File(root, "replacement").apply { writeBytes(retentionBytes) }
        Files.move(replacement.toPath(), base.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)

        assertEquals(RetentionRecoveryRead(RetentionRecoveryReadState.VALID, retention),
            readRetentionRecovery(context, publication))
        assertArrayEquals(retentionBytes, base.readBytes())
        assertEquals(1, JournalHostIoShadow.directorySyncAttempts)
        assertEquals(0, JournalHostIoShadow.outstandingOwners)
    }

    @Test fun retentionPublicationRejectsPendingAtomicFileStateEvenWhenBaseStillMatches() = fixture { context, root ->
        var published: RetentionRecoveryPublication? = null
        assertTrue(writeRetentionRecoveryConfiguration(context, retention) { published = it })
        val publication = requireNotNull(published)
        assertTrue(readRetentionRecovery(context, publication).matchesPublication)
        File(root, "retention-config.v1.new").writeBytes(retentionBytes)

        assertFalse(readRetentionRecovery(context, publication).matchesPublication)
        assertEquals(1, JournalHostIoShadow.directorySyncAttempts)
    }

    @Test fun settingsSaveBaselineRequiresEveryRetentionFieldBeforeOfferingReuse() = fixture { context, _ ->
        val configuration = defaultRetentionConfiguration()
        val preferences = getRecorderPreferences(context)
        assertTrue(restoreRetentionConfigurationToPreferences(preferences, configuration))
        assertTrue(writeRetentionRecoveryConfiguration(context, configuration))
        assertEquals(configuration, readRetentionSettingsBaseline(context).matchingConfiguration)

        assertTrue(preferences.edit().remove(PrefKey.ONE_SHOT_RETENTION_SECONDS).commit())
        val baseline = readRetentionSettingsBaseline(context)
        assertEquals(configuration, baseline.rollbackConfiguration)
        assertNull(baseline.matchingConfiguration)
    }

    @Test fun settingsSaveBaselineRejectsTwoValidButConflictingRetentionCopies() = fixture { context, _ ->
        val configuration = defaultRetentionConfiguration()
        assertTrue(restoreRetentionConfigurationToPreferences(getRecorderPreferences(context), configuration))
        assertTrue(writeRetentionRecoveryConfiguration(context, configuration.copy(oneShotSeconds = 0L)))

        val baseline = readRetentionSettingsBaseline(context)
        assertEquals(configuration, baseline.rollbackConfiguration)
        assertNull(baseline.matchingConfiguration)
    }

    @Test fun postPublicationRetentionBarrierFailureReportsFalseAndPreservesPublishedNewBytes() = fixture { context, root ->
        val file = File(root, "retention-config.v1").apply { writeBytes(retentionBytes) }
        JournalHostIoShadow.failDirectorySyncs = 1
        val changed = retention.copy(oneShotSeconds = 0)
        var published: RetentionRecoveryPublication? = null
        assertFalse(writeRetentionRecoveryConfiguration(context, changed) { published = it })
        assertNull(published)
        assertEquals(RetentionRecoveryRead(RetentionRecoveryReadState.VALID, changed), readRetentionRecovery(context))
        val expectedNewBytes = "4e52545201000000010000000000000000000000b268de3a0000000031d4000000000000b032797600000000e919cabd"
            .chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        assertArrayEquals(expectedNewBytes, file.readBytes())
        assertEquals(setOf("retention-config.v1"), root.list()!!.toSet())
        assertEquals(0, JournalHostIoShadow.outstandingOwners)
    }

    @Test fun actualOnboardingFalseCommitRestoresRawPreferencesAndPublishedRecovery() = fixture { context, root ->
        val isolated = context as IsolatedContext
        seedMalformedPreferences(isolated)
        File(root, "retention-config.v1").writeBytes(retentionBytes)
        val before = isolated.preferences.all.toMap()
        isolated.preferences.failNextCommit = true
        assertFalse(finishOnboarding(context, false, true,
            OnboardingAudioSelection(false, false), OnboardingAudioSelection(false, false)))
        assertEquals(true, isolated.preferences.failedCommitValues!![PrefKey.ONBOARDING_SHOWN.name])
        assertEquals(before, isolated.preferences.all)
        assertArrayEquals(retentionBytes, File(root, "retention-config.v1").readBytes())
        assertEquals(0, JournalHostIoShadow.outstandingOwners)
    }

    @Test fun actualOnboardingThrownCommitKeepsPrimaryAndRestoresBothDurableSides() = fixture { context, root ->
        val isolated = context as IsolatedContext
        seedMalformedPreferences(isolated)
        File(root, "retention-config.v1").writeBytes(retentionBytes)
        val before = isolated.preferences.all.toMap()
        val primary = IOException("preferences transport failed after process map update")
        isolated.preferences.throwNextCommit = primary
        val thrown = assertThrows(IOException::class.java) {
            finishOnboarding(context, false, true,
                OnboardingAudioSelection(false, false), OnboardingAudioSelection(false, false))
        }
        assertSame(primary, thrown)
        assertEquals(true, isolated.preferences.failedCommitValues!![PrefKey.ONBOARDING_SHOWN.name])
        assertEquals(before, isolated.preferences.all)
        assertArrayEquals(retentionBytes, File(root, "retention-config.v1").readBytes())
        assertEquals(0, JournalHostIoShadow.outstandingOwners)
    }

    private fun seedMalformedPreferences(context: IsolatedContext) {
        check(context.preferences.edit()
            .putString(PrefKey.ONBOARDING_SHOWN.name, "malformed-boolean")
            .putString(PrefKey.RETENTION_MODE.name, "malformed-mode")
            .putInt(PrefKey.RETENTION_CONFIG_DIGEST.name, 7)
            .putString("unrelated-key", "retained")
            .commit())
    }

    private fun currentSession(): ByteArray =
        "5249533103010000002a000000000000006400000000000000c80000000000000096000000000000000700000009"
            .chunked(2).map { it.toInt(16).toByte() }.toByteArray().also { bytes ->
                ByteBuffer.wrap(bytes).apply {
                    putInt(6, Process.myPid())
                    putLong(10, Process.getStartElapsedRealtime())
                }
            }

    private class CommitFaultPreferences(private val actual: SharedPreferences) : SharedPreferences by actual {
        var failNextCommit = false
        var throwNextCommit: IOException? = null
        var failedCommitValues: Map<String, *>? = null
        override fun edit(): SharedPreferences.Editor {
            val target = actual.edit()
            return object : SharedPreferences.Editor by target {
                override fun putString(key: String, value: String?): SharedPreferences.Editor { target.putString(key, value); return this }
                override fun putStringSet(key: String, value: MutableSet<String>?): SharedPreferences.Editor { target.putStringSet(key, value); return this }
                override fun putInt(key: String, value: Int): SharedPreferences.Editor { target.putInt(key, value); return this }
                override fun putLong(key: String, value: Long): SharedPreferences.Editor { target.putLong(key, value); return this }
                override fun putFloat(key: String, value: Float): SharedPreferences.Editor { target.putFloat(key, value); return this }
                override fun putBoolean(key: String, value: Boolean): SharedPreferences.Editor { target.putBoolean(key, value); return this }
                override fun remove(key: String): SharedPreferences.Editor { target.remove(key); return this }
                override fun clear(): SharedPreferences.Editor { target.clear(); return this }
                override fun commit(): Boolean {
                    val result = target.commit()
                    check(result)
                    val thrown = throwNextCommit
                    if (failNextCommit || thrown != null) {
                        failNextCommit = false
                        throwNextCommit = null
                        failedCommitValues = actual.all.toMap()
                        if (thrown != null) throw thrown
                        return false
                    }
                    return result
                }
            }
        }
    }

    private class IsolatedContext(base: Context, private val root: File, val preferences: CommitFaultPreferences) : ContextWrapper(base) {
        override fun getApplicationContext(): Context = this
        override fun getNoBackupFilesDir(): File = root
        override fun getFilesDir(): File = root
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences = preferences
    }

    private fun fixture(test: (Context, File) -> Unit) {
        val root = Files.createTempDirectory("journal-context-durability-").toFile()
        val base = RuntimeEnvironment.getApplication() as Context
        val preferenceName = "journal-fixture-${root.name}"
        val preferences = CommitFaultPreferences(base.getSharedPreferences(preferenceName, Context.MODE_PRIVATE))
        val previousPid = Process.myPid()
        val previousRoot = ServiceStateOsShadow.fixtureRoot
        var failure: Throwable? = null
        var ownsFixture = false
        fun record(error: Throwable) {
            val first = failure
            if (first == null) failure = error else if (first !== error) first.addSuppressed(error)
        }
        fun cleanup(action: () -> Unit) {
            try { action() } catch (error: Throwable) { record(error) }
        }
        try {
            check(previousRoot == null)
            JournalHostIoShadow.reset()
            ownsFixture = true
            ShadowProcess.setPid(4242)
            ServiceStateOsShadow.fixtureRoot = root.toPath()
            test(IsolatedContext(base, root, preferences), root)
        } catch (error: Throwable) { failure = error }
        finally {
            if (ownsFixture) cleanup { JournalHostIoShadow.finishFixture()?.let(::record) }
            cleanup { ServiceStateOsShadow.fixtureRoot = previousRoot }
            cleanup { ShadowProcess.setPid(previousPid) }
            if (failure == null) {
                cleanup { check(base.deleteSharedPreferences(preferenceName)) }
                cleanup { check(root.deleteRecursively()) }
            }
        }
        failure?.let { throw it }
    }
}
