package app.smallthingz.reverb

import android.app.Application
import android.app.Instrumentation
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.res.Configuration
import android.os.Process
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowNotificationManager
import org.robolectric.shadows.ShadowProcess

@RunWith(RobolectricTestRunner::class)
@Config(
    sdk = [35],
    application = Application::class,
    qualifiers = "en-port-notnight",
    shadows = [NotificationLocaleScanShadow::class, JournalHostIoShadow::class],
)
@LooperMode(LooperMode.Mode.PAUSED)
class ReverbApplicationNotificationLocaleTest {
    @Test fun startupAndLocaleChangesRefreshDormantChannels() {
        val host = RuntimeEnvironment.getApplication()
        val app = Instrumentation.newApplication(ReverbApplication::class.java, host.baseContext) as ReverbApplication
        val manager = app.getSystemService(NotificationManager::class.java)
        val channel = ReverbNotificationChannel.PLAYBACK
        manager.createNotificationChannel(NotificationChannel(channel.id, "Stale name", NotificationManager.IMPORTANCE_NONE))
        NotificationLocaleScanShadow.scans.set(0)

        // The observation receiver is a process-lifetime child; only startup refreshes need draining.
        val existingTileJobs = jobs(QuickSaveTileOptions, "worker").toSet()
        val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        val previousPid = Process.myPid()
        val previousThreads = Thread.getAllStackTraces().keys
        check(ServiceStateOsShadow.fixtureRoot == null)
        JournalHostIoShadow.reset()
        val hostRoot = checkNotNull(host.noBackupFilesDir.parentFile)
        ServiceStateOsShadow.fixtureRoot = hostRoot.toPath().toAbsolutePath().normalize()
        ShadowProcess.setPid(41_026)
        var failure: Throwable? = null
        fun cleanup(block: () -> Unit) {
            try {
                block()
            } catch (error: Throwable) {
                val primary = failure
                if (primary == null) failure = error else if (primary !== error) primary.addSuppressed(error)
            }
        }
        try {
            app.onCreate()
            awaitRefresh()
            assertEquals(1, NotificationLocaleScanShadow.scans.get())
            assertPlaybackMetadata(manager, "Recording playback", host.getString(channel.descriptionResource))

            fun deliver(qualifiers: String) {
                RuntimeEnvironment.setQualifiers(qualifiers)
                app.onConfigurationChanged(Configuration(app.resources.configuration))
                awaitRefresh()
            }

            deliver("en-port-night")
            assertEquals(1, NotificationLocaleScanShadow.scans.get())
            deliver("en-land-night")
            assertEquals(1, NotificationLocaleScanShadow.scans.get())
            deliver("de-land-night")
            assertEquals(2, NotificationLocaleScanShadow.scans.get())
            assertPlaybackMetadata(manager, "Aufnahmewiedergabe", "Wiedergabestatus und Steuerung für gespeicherte Aufnahmen.")
            deliver("de-port-night")
            assertEquals(2, NotificationLocaleScanShadow.scans.get())
        } catch (error: Throwable) {
            failure = error
        } finally {
            // Let the unrelated startup workers finish while their host file fixture is still owned.
            Thread.getAllStackTraces().keys.filter {
                it !in previousThreads && (it.name == "reverb-save-recovery" || it.name == "reverb-incident-recovery")
            }.forEach { thread ->
                cleanup {
                    thread.join(20_000)
                    assertFalse("${thread.name} did not settle", thread.isAlive)
                }
            }
            cleanup { awaitRefresh() }
            cleanup {
                runBlocking {
                    withTimeout(5_000) {
                        jobs(QuickSaveTileOptions, "worker").filterNot { it in existingTileJobs }.joinAll()
                    }
                }
            }
            cleanup {
                runBlocking {
                    withTimeout(5_000) { jobs(DurableSaveNotifications, "scope").forEach { it.cancelAndJoin() } }
                }
            }
            cleanup { JournalHostIoShadow.finishFixture()?.let { throw it } }
            ServiceStateOsShadow.fixtureRoot = null
            cleanup { ShadowProcess.setPid(previousPid) }
            cleanup { Thread.setDefaultUncaughtExceptionHandler(previousHandler) }
        }
        failure?.let { throw it }
    }

    private fun awaitRefresh() = runBlocking {
        // A launch registers with its parent before returning. Join any pending refreshes
        // so a missing scan is a count assertion rather than a timing-based negative check.
        withTimeout(5_000) { jobs(ReverbNotificationChannels, "scope").joinAll() }
    }

    private fun jobs(owner: Any, fieldName: String): List<Job> {
        val field = owner.javaClass.getDeclaredField(fieldName).apply { isAccessible = true }
        val scope = field.get(owner) as CoroutineScope
        return checkNotNull(scope.coroutineContext[Job]).children.toList()
    }

    private fun assertPlaybackMetadata(manager: NotificationManager, name: String, description: String) {
        val channel = checkNotNull(manager.getNotificationChannel(ReverbNotificationChannel.PLAYBACK.id))
        assertEquals(name, channel.name.toString())
        assertEquals(description, channel.description)
        assertEquals(NotificationManager.IMPORTANCE_NONE, channel.importance)
    }
}

@Implements(NotificationManager::class)
class NotificationLocaleScanShadow : ShadowNotificationManager() {
    @Implementation
    override fun getNotificationChannels(): MutableList<NotificationChannel> {
        scans.incrementAndGet()
        return super.getNotificationChannels()
    }

    companion object {
        val scans = AtomicInteger()
    }
}
