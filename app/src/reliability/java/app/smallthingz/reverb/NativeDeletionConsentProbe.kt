package app.smallthingz.reverb

import android.app.Activity
import android.app.Application
import android.app.Instrumentation
import android.content.Context
import android.content.ContentValues
import android.net.Uri
import android.os.Build
import android.os.Process
import android.os.SystemClock
import android.provider.MediaStore
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.json.JSONArray

/** Exact creator-supplied synthetic rows; real production grouping and Android consent, no discovery. */
internal fun verifyNativeDeletionConsent(
    instrumentation: Instrumentation,
    report: StringBuilder,
    manifestText: String,
    manifestSha256: String,
) {
    val context=instrumentation.targetContext
    check(context.packageName=="app.smallthingz.reverb.uitheme.reliability")
    check(context.applicationContext.javaClass==Application::class.java)
    check(Build.VERSION.SDK_INT>=30) { "This probe qualifies only the API30+ write-consent path" }
    require(manifestText.length<=32768 && manifestSha256.matches(Regex("[0-9a-f]{64}")))
    check(consentProbeSha(manifestText.toByteArray(Charsets.UTF_8))==manifestSha256)
    val manifest=JSONObject(manifestText)
    val schema=manifest.getInt("schema")
    require(schema==1 || schema==2)
    val runId=manifest.getString("run_id")
    require(UUID.fromString(runId).toString()==runId)
    val caseId=manifest.getString("case_id")
    require(caseId=="cancel"||caseId=="approve")
    val mixedOwned=manifest.optBoolean("include_owned",false)
    require(!mixedOwned || schema==2)
    val expectedBytes=consentProbeWave()
    val supplied=if(schema==1)listOf(manifest) else manifest.getJSONArray("targets").let { array ->
        require(array.length() in 2..3) { "Batch qualification requires two or three exact targets" }
        List(array.length()) { array.getJSONObject(it) }
    }
    val targets=supplied.mapIndexed { index, target ->
        val uri=Uri.parse(target.getString("uri"))
        require(uri.toString().matches(Regex("content://media/external_primary/audio/media/[1-9][0-9]*")))
        require(target.getString("relative_path")=="Music/Reverb/")
        val suffix=if(schema==1)"" else "-${index+1}"
        val displayName="Reverb-QA-Consent-$runId-$caseId$suffix.wav"
        require(target.getString("display_name")==displayName)
        require(target.getInt("creator_uid")>=0 && target.getInt("creator_uid")!=Process.myUid())
        require(target.getLong("size_bytes")==expectedBytes.size.toLong())
        require(target.getString("sha256")==consentProbeSha(expectedBytes))
        require(target.has("owner_package_name"))
        val owner=if(target.isNull("owner_package_name"))null else target.getString("owner_package_name")
        require(owner!=context.packageName)
        require(target.getLong("date_modified")>0L && target.getLong("generation_modified")>0L)
        ConsentProbeTarget(uri,displayName,owner,target.getLong("date_modified"),target.getLong("generation_modified"))
    }
    require(!mixedOwned || targets.size==2) { "Mixed case requires exactly two foreign targets" }
    require(targets.map { it.uri }.toSet().size==targets.size) { "Targets must be distinct" }
    val directory=File(context.filesDir,"media-delete-consent-$runId-$caseId")
    check(directory.mkdir()) { "Run/case already exists; do not replay consent" }
    val receipt=JSONObject().put("schema",2).put("run_id",runId).put("case_id",caseId)
        .put("manifest_sha256",manifestSha256).put("target_count",targets.size+if(mixedOwned)1 else 0)
        .put("include_owned",mixedOwned)
        .put("uris",JSONArray(targets.map { it.uri.toString() }))
        .put("api",Build.VERSION.SDK_INT).put("package",context.packageName)
        .put("request_token",JSONObject.NULL).put("granted",JSONObject.NULL).put("prompt_count",0)
        .put("coverage","Production repository, grouped sender factory and OS ActivityResult transport; not normal MainActivity or Library gesture")
    fun phase(value:String) {
        receipt.put("phase",value).put("elapsed_realtime_ms",SystemClock.elapsedRealtime())
        writeQaEvidenceAtomically(File(directory,"phase.json")) {
            it.write(receipt.toString(2).toByteArray(Charsets.UTF_8))
        }
    }
    fun assertEmptyJournal() {
        check(getRecorderPreferences(context).requireDurableStringSet(PrefKey.PENDING_RECORDING_DELETIONS).isEmpty()) {
            "Existing deletion journal must be preserved; probe admission refused"
        }
    }
    fun observe(target:ConsentProbeTarget):ConsentProbeObservation?=consentProbeObserve(context,target.uri)
    fun verifyOriginal(target:ConsentProbeTarget):ConsentProbeObservation {
        val row=checkNotNull(observe(target)) { "Exact supplied row is absent: ${target.uri}" }
        check(row.name==target.name && row.relativePath=="Music/Reverb/" && row.size==expectedBytes.size.toLong())
        check(row.owner==target.owner && row.pending==0)
        check(row.modified==target.modified && row.generation==target.generation) {
            "Original provider revision changed; preserve the row: ${target.uri}"
        }
        context.contentResolver.openInputStream(target.uri).use { stream ->
            checkNotNull(stream)
            check(stream.readBytesBounded(expectedBytes.size+1).contentEquals(expectedBytes)) {
                "Synthetic content proof mismatch: ${target.uri}"
            }
        }
        return row
    }
    fun verifyAllOriginal() { targets.forEach { verifyOriginal(it) } }
    val allTargets=targets.toMutableList()
    var createdOwned:ConsentProbeTarget?=null
    var host:NativeParityActivity?=null
    val registered=mutableListOf<RecordingEntity>()
    var request:RecordingDeletionConsentLaunch?=null
    var submitted=false
    var failure:Throwable?=null
    var completionObserved=false
    try {
        assertEmptyJournal()
        // This isolated invocation must begin with no process-owned request or completion history.
        check(RecordingDeletionConsent.changes.value==0L && RecordingDeletionConsent.completions.value==0L)
        verifyAllOriginal()
        if(mixedOwned) {
            val name="Reverb-QA-Consent-$runId-$caseId-owned.wav"
            val values=ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME,name)
                put(MediaStore.MediaColumns.RELATIVE_PATH,"Music/Reverb/")
                put(MediaStore.MediaColumns.MIME_TYPE,"audio/wav")
                put(MediaStore.MediaColumns.IS_PENDING,1)
            }
            val uri=checkNotNull(context.contentResolver.insert(
                MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),values))
            // Record the freshly allocated exact URI before writing. A failed/partial creation is
            // preserved for the executor; it never authorizes searching by name or broad cleanup.
            receipt.put("created_owned",JSONObject().put("uri",uri.toString()).put("display_name",name)
                .put("expected_sha256",consentProbeSha(expectedBytes)).put("size_bytes",expectedBytes.size))
            phase("owned_inserted")
            context.contentResolver.openOutputStream(uri,"w").use { stream -> checkNotNull(stream).write(expectedBytes) }
            check(context.contentResolver.update(uri,ContentValues().apply {
                put(MediaStore.MediaColumns.IS_PENDING,0)
            },null,null)==1)
            val observed=checkNotNull(consentProbeObserve(context,uri))
            check(observed.owner==context.packageName && observed.name==name && observed.modified>0 && observed.generation>0)
            val owned=ConsentProbeTarget(uri,name,observed.owner,observed.modified,observed.generation)
            verifyOriginal(owned)
            createdOwned=owned
            allTargets.add(1,owned)
            receipt.getJSONObject("created_owned").put("owner_package_name",observed.owner)
                .put("date_modified",observed.modified).put("generation_modified",observed.generation)
                .put("payload_verified",true)
            phase("owned_verified")
        }
        val dao=RecordingDatabase.getInstance(context).recordingDao()
        allTargets.forEach { target ->
            val original=verifyOriginal(target)
            val identity=mediaStoreProviderIdentityFromMetadata(target.uri.toString(),true,original.size,
                true,original.modified,true,original.generation)
            check(identity.isNotBlank())
            runBlocking { check(dao.findById(target.uri.toString())==null) { "Never overwrite an existing catalog row" } }
            val entity=RecordingEntity(id=target.uri.toString(),displayName=target.name,mimeType="audio/wav",
                startedAtMillis=original.modified*1000L,durationMillis=100L,sizeBytes=original.size,
                codecSummary="QA synthetic PCM16 8 kHz mono",storageType=RecordingStorageType.MEDIASTORE,
                directoryId=MEDIA_STORE_DIRECTORY_ID,fileIdentity=identity)
            registered+=runBlocking { RecordingRepository.register(context,entity) }
        }
        phase("admitted")
        val activity=launchCurrentUiSmokeActivity(instrumentation)
        host=activity
        allTargets.forEach {verifyOriginal(it)};assertEmptyJournal()
        submitted=true
        val result=runBlocking { RecordingRepository.deleteBatch(context,registered.toList()) }
        check(result.outcomes==allTargets.map {it===createdOwned} &&
            result.consentIndices==allTargets.indices.filter {allTargets[it]!==createdOwned}.toSet() &&
            !result.cleanupFailed && result.failures.isEmpty()) {
            "Actual nonowner batch did not produce the required consent handoff: $result"
        }
        assertEmptyJournal()
        verifyAllOriginal()
        createdOwned?.let { owned ->
            check(observe(owned)==null) { "Owned fixture did not delete before consent" }
            runBlocking { check(dao.findById(owned.uri.toString())==null) }
            receipt.put("owned_deleted_before_prompt",true)
        }
        // The production owner prepares the sender asynchronously. Capturing the generation before
        // claim avoids missing READY between the main-thread call and the StateFlow subscription.
        val claimed=runBlocking { withTimeout(30_000L) {
            var ready:RecordingDeletionConsentLaunch?=null
            while(ready==null) {
                val version=RecordingDeletionConsent.changes.value
                nativeParityOnMain(instrumentation) { ready=RecordingDeletionConsent.claim(context) }
                if(ready==null)RecordingDeletionConsent.changes.first { it!=version }
            }
            checkNotNull(ready)
        } }
        request=claimed
        check(claimed.targets.size==targets.size) { "API30+ batch was split into per-row prompts" }
        check(claimed.targets.map {it.recording.id}.toSet()==targets.map {it.uri.toString()}.toSet())
        claimed.targets.forEach { target ->
            val original=registered.single {it.id==target.recording.id}
            check(target.recording.fileIdentity==original.fileIdentity)
            check(target.fingerprint.id==original.id && target.fingerprint.byteCount==original.sizeBytes &&
                target.fingerprint.sha256Hex==consentProbeSha(expectedBytes) && !target.fingerprint.assetDeleted)
        }
        receipt.put("request_token",claimed.token).put("fingerprint_sha256",consentProbeSha(expectedBytes))
            .put("group_target_count",claimed.targets.size)
        val completionBefore=RecordingDeletionConsent.completions.value
        val callback=CompletableDeferred<Pair<Int,Boolean>>()
        nativeParityOnMain(instrumentation) {
            check(activity.hasWindowFocus())
            receipt.put("prompt_count",1)
            phase("awaiting_consent")
            activity.launchDeletionConsentProbe(claimed) { code,launchFailed -> callback.complete(code to launchFailed) }
        }
        val (resultCode,launchFailed)=runBlocking { withTimeout(120_000L) { callback.await() } }
        val granted=resultCode==Activity.RESULT_OK && !launchFailed
        receipt.put("result_code",resultCode).put("granted",granted).put("launch_failed",launchFailed)
        phase("callback_received")
        // Always settle the exact claimed token with the real callback, even if the operator chose
        // the unexpected button. Never replace its original fingerprint after the dialog.
        RecordingDeletionConsent.finish(context,claimed.token,granted,launchFailed=launchFailed)
        runBlocking { withTimeout(15_000L) {
            RecordingDeletionConsent.completions.first { it>completionBefore }
        } }
        completionObserved=true
        check(!launchFailed) { "IntentSender launch failed; cancellation was not exercised" }
        check(granted==(caseId=="approve")) { "OS result differs from requested case" }
        check(!RecordingDeletionConsent.owns(claimed.token))
        assertEmptyJournal()
        val targetResults=JSONArray()
        allTargets.forEach { target ->
            val original=registered.single {it.id==target.uri.toString()}
            val expectedDeleted=granted || target===createdOwned
            if(expectedDeleted) {
                check(observe(target)==null) { "Approved exact row still exists: ${target.uri}" }
                runBlocking { check(dao.findById(original.id)==null) }
            } else {
                verifyOriginal(target)
                runBlocking { check(dao.findById(original.id)?.fileIdentity==original.fileIdentity) }
            }
            targetResults.put(JSONObject().put("uri",original.id).put("original_file_identity",original.fileIdentity)
                .put("original_sha256",consentProbeSha(expectedBytes))
                .put("verified_outcome",if(expectedDeleted)"asset_and_catalog_absent" else "original_asset_and_catalog_preserved"))
        }
        receipt.put("target_results",targetResults)
        // One callback must settle the whole logical group, not just its first or last target.
        check(RecordingDeletionConsent.completions.value==completionBefore+1)
        nativeParityOnMain(instrumentation) { check(RecordingDeletionConsent.claim(context)==null) }
        val completed=RecordingDeletionConsent.completions.value
        RecordingDeletionConsent.finish(context,claimed.token,granted)
        check(RecordingDeletionConsent.completions.value==completed && !RecordingDeletionConsent.owns(claimed.token))
        if(!granted)verifyAllOriginal()
        receipt.put("verified_target_count",allTargets.size)
        receipt.put("duplicate_finish_noop",true).put("completion_counter",completed)
        phase("completed")
    } catch(error:Throwable) {
        failure=error
        receipt.put("failure",error.stackTraceToString())
        phase("failed")
    } finally {
        // Foreign cleanup never deletes assets. Only our newly created owned URI may be cleaned,
        // after exact original proof and after any submitted deletion has settled.
        failure=captureNativeParityCleanup(failure) {
            host?.let { activity -> nativeParityOnMain(instrumentation) {
                activity.closeDeletionConsentProbe();activity.finish()
            } }
        }
        failure=captureNativeParityCleanup(failure) {
            request?.let { pending ->
                if(RecordingDeletionConsent.owns(pending.token)) {
                    val before=RecordingDeletionConsent.completions.value
                    RecordingDeletionConsent.finish(context,pending.token,false)
                    runBlocking { withTimeout(15_000L) { RecordingDeletionConsent.completions.first {it>before} } }
                    completionObserved=true
                }
            }
        }
        failure=captureNativeParityCleanup(failure) {
            if(submitted&&!completionObserved)receipt.put("catalog_preserved_unsettled_retry",true)
            else registered.forEach { row -> runBlocking {
                val dao=RecordingDatabase.getInstance(context).recordingDao()
                val current=dao.findById(row.id)
                if(current!=null) {
                    // Removing our own catalog-only fixture requires the still-original asset.
                    verifyOriginal(allTargets.single {it.uri.toString()==row.id})
                    check(current.fileIdentity==row.fileIdentity && current.displayName==row.displayName && current.sizeBytes==row.sizeBytes)
                    dao.deleteById(row.id)
                    receipt.put("own_catalog_cleanup",true)
                }
            } }
        }
        failure=captureNativeParityCleanup(failure) {
            createdOwned?.let { owned ->
                if(submitted&&!completionObserved)receipt.put("owned_preserved_unsettled_retry",true)
                else if(observe(owned)!=null) {
                    verifyOriginal(owned)
                    check(context.contentResolver.delete(owned.uri,null,null)==1)
                    check(observe(owned)==null)
                    receipt.put("created_owned_cleanup",true)
                }
            }
        }
        if(failure!=null)receipt.put("failure",failure!!.stackTraceToString())
        phase(if(failure==null)"completed" else "failed")
        report.appendLine("MEDIA_DELETE_CONSENT_EVIDENCE ${directory.absolutePath}")
    }
    failure?.let {throw it}
    report.appendLine("PASS current_media_delete_consent $caseId targets=${allTargets.size} foreign=${targets.size} mixed=$mixedOwned prompts=1; API${Build.VERSION.SDK_INT} API30+ production repository and OS result")
}

private data class ConsentProbeTarget(val uri:Uri,val name:String,val owner:String?,val modified:Long,val generation:Long)

private data class ConsentProbeObservation(val name:String,val relativePath:String,val size:Long,
    val owner:String?,val modified:Long,val generation:Long,val pending:Int)

private fun consentProbeObserve(context:Context,uri:Uri):ConsentProbeObservation? {
    val columns=arrayOf(MediaStore.MediaColumns.DISPLAY_NAME,MediaStore.MediaColumns.RELATIVE_PATH,
        MediaStore.MediaColumns.SIZE,MediaStore.MediaColumns.OWNER_PACKAGE_NAME,
        MediaStore.MediaColumns.DATE_MODIFIED,MediaStore.MediaColumns.GENERATION_MODIFIED,MediaStore.MediaColumns.IS_PENDING)
    val cursor=checkNotNull(context.contentResolver.query(uri,columns,null,null,null))
    return cursor.use {
        if(!it.moveToFirst())null else {
            check((0..6).filter {index->index!=3}.all {index->!it.isNull(index)})
            val row=ConsentProbeObservation(it.getString(0),it.getString(1),it.getLong(2),
                if(it.isNull(3))null else it.getString(3),it.getLong(4),it.getLong(5),it.getInt(6))
            check(!it.moveToNext()) { "Exact URI returned multiple rows" };row
        }
    }
}

private fun java.io.InputStream.readBytesBounded(limit:Int):ByteArray {
    val bytes=ByteArray(limit)
    var size=0
    while(size<limit) {val count=read(bytes,size,limit-size);if(count<0)break;check(count>0);size+=count}
    return bytes.copyOf(size)
}
private fun consentProbeSha(bytes:ByteArray):String=MessageDigest.getInstance("SHA-256").digest(bytes)
    .joinToString("") {"%02x".format(it.toInt() and 255)}
private fun consentProbeWave():ByteArray=ByteBuffer.allocate(1644).order(ByteOrder.LITTLE_ENDIAN).apply {
    put("RIFF".toByteArray());putInt(1636);put("WAVEfmt ".toByteArray());putInt(16)
    putShort(1);putShort(1);putInt(8000);putInt(16000);putShort(2);putShort(16)
    put("data".toByteArray());putInt(1600)
}.array()
