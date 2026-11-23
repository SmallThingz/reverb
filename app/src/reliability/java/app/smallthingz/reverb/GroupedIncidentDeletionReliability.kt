package app.smallthingz.reverb

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.os.SystemClock
import java.io.File

/** Exercises AtomicFile-backed mutations inside the QA app without opening its real history. */
internal fun verifyGroupedIncidentDeletion(base: Context, report: StringBuilder) {
    check(base.packageName == "app.smallthingz.reverb.nativeparity.reliability" ||
        base.packageName == "app.smallthingz.reverb.uitheme.reliability")
    check(base.applicationContext.javaClass == Application::class.java)
    val evidenceDir = File(base.filesDir, "native-incidents-store-${System.currentTimeMillis()}")
    check(evidenceDir.mkdir())
    val storage = File(evidenceDir, "grouped-history-store").also { check(it.mkdir()) }
    val context = object : ContextWrapper(base) {
        override fun getApplicationContext(): Context = this
        override fun getNoBackupFilesDir(): File = storage
    }
    val store = RecordingIncidentStore
    val startElapsed = SystemClock.elapsedRealtime()
    val duration = CaptureObservationDuration(startElapsed)
    val reason = CaptureInputObservationReason.POLICY_SILENCED
    val duplicate = IllegalStateException("Shared QA error")
    fun appendError() = store.recordUnexpectedError(context, "Grouped incident fixture", duplicate)
    fun read() = store.readIncidents(context)
    fun await(condition: () -> Boolean) {
        val end = SystemClock.elapsedRealtime() + 10_000L
        while (SystemClock.elapsedRealtime() < end) {
            if (condition()) return
            SystemClock.sleep(25L)
        }
        check(condition()) { "Grouped history did not converge" }
    }
    try {
        appendError()
        appendError()
        check(store.recordCaptureInputObservation(context, System.currentTimeMillis(), reason, duration))
        await { read().any { it.description == reason.description && it.ongoingPolicyDuration === duration } }
        val snapshot = read()
        val oldErrors = snapshot.filter { it.kind == RecordingIncidentKind.UNEXPECTED_ERROR }
        check(oldErrors.size == 2 && oldErrors.map { it.description }.distinct().size == 1)
        val oldObservation = snapshot.single { it.kind == RecordingIncidentKind.CAPTURE_INPUT_OBSERVATION }
        synchronized(store) {
            store.deleteIncidentsInBackground(context, snapshot)
            // The delete worker must acquire this monitor after the newly published occurrence.
            appendError()
        }
        await { read().none { row -> snapshot.any { recordingIncidentReferenceMatches(row, it) } } }
        val survivor = read().single()
        check(survivor.description == oldErrors.first().description)
        check(snapshot.none { recordingIncidentReferenceMatches(survivor, it) })

        // The original live process owner still receives the end; a separate newer dropout
        // provides durable observation traffic after that end, without reviving the deleted one.
        duration.finish(startElapsed + 1_100L)
        val admission = store.javaClass.getDeclaredField("observationAdmission").apply { isAccessible = true }
            .get(store) as CaptureObservationAdmission
        await { admission.incidents(storage.absoluteFile).none { it.observationId == oldObservation.observationId } }
        check(read() == listOf(survivor))
        val nextDuration = CaptureObservationDuration(startElapsed + 2_000L)
        nextDuration.finish(startElapsed + 3_200L)
        check(store.recordCaptureInputObservation(context, System.currentTimeMillis(), reason, nextDuration))
        await { read().any { it.kind == RecordingIncidentKind.CAPTURE_INPUT_OBSERVATION } }
        val current = read()
        val nextObservation = current.single { it.kind == RecordingIncidentKind.CAPTURE_INPUT_OBSERVATION }
        check(nextObservation.observationId > oldObservation.observationId)
        check(recordingIncidentDowntimeMillis(nextObservation) == 1_200L)
        check(current.single { it.kind == RecordingIncidentKind.UNEXPECTED_ERROR } == survivor)
        val evidence = File(evidenceDir, "grouped-deletion.txt")
        evidence.writeText(
            "batch_deleted=${snapshot.size}\nconcurrent_same_message_preserved=true\n" +
                "completed_deleted_observation_hidden=true\nnew_observation_duration_millis=1200\n",
        )
        report.appendLine("PASS native_incidents_store: exact snapshot deletion preserved a concurrent arrival and retired the deleted live observation")
        report.appendLine("STORE_EVIDENCE ${evidenceDir.name}/${evidence.name}")
    } finally {
        duration.finish(startElapsed + 1_100L)
    }
}
