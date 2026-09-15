package app.smallthingz.reverb

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import android.database.Cursor
import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteOpenHelper
import androidx.core.database.sqlite.transaction
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest

data class RecordingEntity(
    val id: String,
    val displayName: String,
    val mimeType: String,
    val startedAtMillis: Long,
    val durationMillis: Long,
    val sizeBytes: Long,
    val codecSummary: String,
    val storageType: String,
    val directoryId: String,
    val fileIdentity: String = "",
    val waveformData: String = "",
    val waveformRevision: String = "",
    val createdAtMillis: Long = System.currentTimeMillis(),
    // Last successful observation/import of this asset. Used to keep rows stable
    // across short provider/file-system visibility gaps.
    val lastSeenAtMillis: Long = createdAtMillis,
    // First time the asset was observed missing. Null while the asset is present.
    val missingSinceMillis: Long? = null,
)

interface RecordingDao {
    suspend fun listAll(): List<RecordingEntity>

    suspend fun findById(id: String): RecordingEntity?

    suspend fun listByDirectory(directoryId: String): List<RecordingEntity>

    suspend fun upsert(recording: RecordingEntity)

    suspend fun updateWaveformCache(
        recording: RecordingEntity,
        waveformData: String,
        waveformRevision: String,
    ): Boolean

    suspend fun deleteById(id: String)

    suspend fun applyChanges(
        upserts: List<RecordingEntity>,
        deleteIds: List<String>,
    )
}

class RecordingDatabase private constructor(context: Context) : SQLiteOpenHelper(
    context.applicationContext,
    DATABASE_NAME,
    null,
    DATABASE_VERSION,
    PreservingRecordingDatabaseErrorHandler(context.applicationContext, DATABASE_NAME),
) {
    private val dao = DaoImpl()

    override fun onCreate(db: SQLiteDatabase) {
        db.createSchema()
    }

    override fun onUpgrade(
        db: SQLiteDatabase,
        oldVersion: Int,
        newVersion: Int,
    ) {
        for (step in recordingDatabaseMigrationSteps(oldVersion, newVersion)) {
            recordingDatabaseMigrationSql(step).forEach(db::execSQL)
        }
    }

    override fun onDowngrade(
        db: SQLiteDatabase,
        oldVersion: Int,
        newVersion: Int,
    ) {
        throw SQLiteException(
            "Refusing destructive recording database downgrade from $oldVersion to $newVersion",
        )
    }

    override fun onOpen(db: SQLiteDatabase) {
        super.onOpen(db)
        if (!db.isReadOnly) db.createIndexes()
    }

    fun recordingDao(): RecordingDao = dao

    private inner class DaoImpl : RecordingDao {
        override suspend fun listAll(): List<RecordingEntity> {
            return readableDatabase.query(
                TABLE_RECORDINGS,
                null,
                null,
                null,
                null,
                null,
                "$COLUMN_STARTED_AT_MILLIS DESC, $COLUMN_CREATED_AT_MILLIS DESC",
            ).use(::readRecordings)
        }

        override suspend fun findById(id: String): RecordingEntity? {
            return readableDatabase.query(
                TABLE_RECORDINGS,
                null,
                "$COLUMN_ID = ?",
                arrayOf(id),
                null,
                null,
                null,
                "1",
            ).use(::readRecordings).firstOrNull()
        }

        override suspend fun listByDirectory(directoryId: String): List<RecordingEntity> {
            return readableDatabase.query(
                TABLE_RECORDINGS,
                null,
                "$COLUMN_DIRECTORY_ID = ?",
                arrayOf(directoryId),
                null,
                null,
                "$COLUMN_STARTED_AT_MILLIS DESC, $COLUMN_CREATED_AT_MILLIS DESC",
            ).use(::readRecordings)
        }

        override suspend fun upsert(recording: RecordingEntity) {
            writableDatabase.upsertRecording(recording)
        }

        override suspend fun updateWaveformCache(
            recording: RecordingEntity,
            waveformData: String,
            waveformRevision: String,
        ): Boolean {
            val values = ContentValues(2).apply {
                put(COLUMN_WAVEFORM_DATA, waveformData)
                put(COLUMN_WAVEFORM_REVISION, waveformRevision)
            }
            return writableDatabase.update(
                TABLE_RECORDINGS,
                values,
                "$COLUMN_ID = ? AND $COLUMN_STORAGE_TYPE_CODE = ? AND $COLUMN_FILE_IDENTITY = ? AND " +
                    "$COLUMN_SIZE_BYTES = ? AND $COLUMN_DURATION_MILLIS = ? AND " +
                    "$COLUMN_MISSING_SINCE_MILLIS IS NULL",
                arrayOf(
                    recording.id,
                    (resolveRecordingStorageType(recording)?.storageCode?.toInt() ?: return false).toString(),
                    recording.fileIdentity,
                    recording.sizeBytes.toString(),
                    recording.durationMillis.toString(),
                ),
            ) == 1
        }

        override suspend fun deleteById(id: String) {
            writableDatabase.delete(TABLE_RECORDINGS, "$COLUMN_ID = ?", arrayOf(id))
        }

        override suspend fun applyChanges(
            upserts: List<RecordingEntity>,
            deleteIds: List<String>,
        ) {
            if (upserts.isEmpty() && deleteIds.isEmpty()) return
            val db = writableDatabase
            db.transaction {
                upserts.forEach { recording ->
                    db.upsertRecording(recording)
                }
                db.deleteIds(deleteIds)
            }
        }
    }

    companion object {
        private const val DATABASE_NAME = ReverbConfig.DATABASE_FILE_NAME
        internal const val DATABASE_VERSION = 5
        internal const val TABLE_RECORDINGS = "recordings"
        internal const val COLUMN_ID = "id"
        internal const val COLUMN_DISPLAY_NAME = "displayName"
        internal const val COLUMN_MIME_TYPE = "mimeType"
        internal const val COLUMN_STARTED_AT_MILLIS = "startedAtMillis"
        internal const val COLUMN_DURATION_MILLIS = "durationMillis"
        internal const val COLUMN_SIZE_BYTES = "sizeBytes"
        internal const val COLUMN_CODEC_SUMMARY = "codecSummary"
        internal const val COLUMN_STORAGE_TYPE = "storageType" // Legacy name column; never canonical for new writes.
        internal const val COLUMN_STORAGE_TYPE_CODE = "storageTypeCode"
        internal const val COLUMN_DIRECTORY_ID = "directoryId"
        internal const val COLUMN_FILE_IDENTITY = "fileIdentity"
        internal const val COLUMN_WAVEFORM_DATA = "waveformData"
        internal const val COLUMN_WAVEFORM_REVISION = "waveformRevision"
        internal const val COLUMN_CREATED_AT_MILLIS = "createdAtMillis"
        internal const val COLUMN_LAST_SEEN_AT_MILLIS = "lastSeenAtMillis"
        internal const val COLUMN_MISSING_SINCE_MILLIS = "missingSinceMillis"

        @Volatile
        private var instance: RecordingDatabase? = null

        fun getInstance(context: Context): RecordingDatabase {
            return instance ?: synchronized(this) {
                instance ?: RecordingDatabase(context).also { instance = it }
            }
        }

        internal fun resetAfterCorruption() {
            synchronized(this) {
                runCatching { instance?.close() }
                instance = null
            }
        }
    }
}


private class PreservingRecordingDatabaseErrorHandler(
    private val context: Context,
    private val databaseName: String,
) : DatabaseErrorHandler {
    override fun onCorruption(dbObj: SQLiteDatabase) {
        val databaseFile = context.getDatabasePath(databaseName)
        val recoveryRoot = File(context.noBackupFilesDir, "recording-database-recovery")
        val closed = runCatching {
            if (dbObj.isOpen) dbObj.close()
            !dbObj.isOpen
        }.getOrDefault(false)
        if (!closed) {
            throw SQLiteException("Recording database is corrupt and could not be frozen for preservation")
        }
        val preserved = preserveCorruptRecordingDatabase(databaseFile, recoveryRoot)
            ?: throw SQLiteException("Recording database is corrupt and could not be preserved")

        SQLiteDatabase.deleteDatabase(databaseFile)
        if (!recordingDatabaseSidecarsConfirmedMissing(databaseFile)) {
            throw SQLiteException("Recording database was preserved at ${preserved.name} but could not be reset")
        }
        val databaseParent = databaseFile.parentFile
            ?: throw SQLiteException("Recording database path has no parent directory")
        forceRecordingDatabaseDirectoryDurable(databaseParent)
    }
}

internal fun recordingDatabaseSidecars(databaseFile: File): List<File> = listOf(
    databaseFile,
    File(databaseFile.path + "-wal"),
    File(databaseFile.path + "-shm"),
    File(databaseFile.path + "-journal"),
)

internal fun recordingDatabaseFilesForPreservation(
    databaseFile: File,
    observe: (File) -> StoragePathObservation = ::observeStoragePath,
): List<File>? {
    val present = mutableListOf<File>()
    for (candidate in recordingDatabaseSidecars(databaseFile)) {
        val observation = observe(candidate)
        when (observation.state) {
            StoragePathState.MISSING -> Unit
            StoragePathState.UNAVAILABLE -> return null
            StoragePathState.PRESENT -> {
                if (!observation.isRegularFile) return null
                present += candidate
            }
        }
    }
    return present
}

internal fun recordingDatabaseSidecarsConfirmedMissing(
    databaseFile: File,
    observe: (File) -> StoragePathObservation = ::observeStoragePath,
): Boolean = recordingDatabaseSidecars(databaseFile).all { candidate ->
    observe(candidate).state == StoragePathState.MISSING
}

internal interface RecordingDatabaseRecoveryIo {
    fun sha256(file: File): ByteArray

    fun copyAndSync(source: File, target: File)

    fun forceDirectory(directory: File)

    fun atomicMove(source: File, target: File)
}

internal object DefaultRecordingDatabaseRecoveryIo : RecordingDatabaseRecoveryIo {
    override fun sha256(file: File): ByteArray = sha256DatabaseFile(file)

    override fun copyAndSync(source: File, target: File) {
        FileInputStream(source).use { input ->
            FileOutputStream(target).use { output ->
                input.copyTo(output)
                output.fd.sync()
            }
        }
    }

    override fun forceDirectory(directory: File) {
        FileChannel.open(directory.toPath(), StandardOpenOption.READ).use { it.force(true) }
    }

    override fun atomicMove(source: File, target: File) {
        Files.move(source.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
    }
}

internal fun preserveCorruptRecordingDatabase(
    databaseFile: File,
    recoveryRoot: File,
    recoveryId: String = "${System.currentTimeMillis()}-${java.util.UUID.randomUUID()}",
    io: RecordingDatabaseRecoveryIo = DefaultRecordingDatabaseRecoveryIo,
): File? {
    val sources = recordingDatabaseFilesForPreservation(databaseFile) ?: return null
    if (sources.isEmpty()) return null
    return runCatching {
        val rootExisted = recoveryRoot.exists()
        if (!rootExisted && !recoveryRoot.mkdirs() && !recoveryRoot.isDirectory) {
            throw IOException("Unable to create recording database recovery directory")
        }
        if (!rootExisted) {
            val recoveryParent = recoveryRoot.parentFile
                ?: throw IOException("Recording database recovery root has no parent")
            io.forceDirectory(recoveryParent)
        }

        var suffix = 0
        var destination: File
        do {
            destination = File(recoveryRoot, if (suffix == 0) recoveryId else "$recoveryId-$suffix")
            suffix++
        } while (destination.exists() || File(recoveryRoot, destination.name + ".partial").exists())
        val staging = File(recoveryRoot, destination.name + ".partial")
        if (!staging.mkdir()) throw IOException("Unable to create recording database recovery snapshot")
        io.forceDirectory(recoveryRoot)

        val sourceDigests = LinkedHashMap<String, ByteArray>(sources.size)
        sources.forEach { source ->
            sourceDigests[source.name] = io.sha256(source)
        }
        sources.forEach { source ->
            val target = File(staging, source.name)
            io.copyAndSync(source, target)
            val expectedDigest = sourceDigests.getValue(source.name)
            if (!expectedDigest.contentEquals(io.sha256(target))) {
                throw IOException("Recording database recovery copy mismatch: ${source.name}")
            }
        }

        val finalSources = recordingDatabaseFilesForPreservation(databaseFile)
            ?: throw IOException("Recording database sidecar state became unavailable during recovery")
        if (finalSources.map(File::getName) != sources.map(File::getName)) {
            throw IOException("Recording database sidecar set changed during recovery")
        }
        finalSources.forEach { source ->
            val expectedDigest = sourceDigests.getValue(source.name)
            if (!expectedDigest.contentEquals(io.sha256(source))) {
                throw IOException("Recording database source changed during recovery: ${source.name}")
            }
        }

        io.forceDirectory(staging)
        try {
            io.atomicMove(staging, destination)
        } catch (error: AtomicMoveNotSupportedException) {
            throw IOException("Atomic recording database recovery publication is unavailable", error)
        }
        io.forceDirectory(recoveryRoot)
        destination
    }.getOrNull()
}

private fun sha256DatabaseFile(file: File): ByteArray {
    val digest = MessageDigest.getInstance("SHA-256")
    FileInputStream(file).use { input ->
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            if (count > 0) digest.update(buffer, 0, count)
        }
    }
    return digest.digest()
}

private fun forceRecordingDatabaseDirectoryDurable(directory: File) {
    DefaultRecordingDatabaseRecoveryIo.forceDirectory(directory)
}

private fun SQLiteDatabase.upsertRecording(recording: RecordingEntity) {
    val result = insertWithOnConflict(
        RecordingDatabase.TABLE_RECORDINGS,
        null,
        recording.toContentValues(),
        SQLiteDatabase.CONFLICT_REPLACE,
    )
    check(result != -1L) { "Unable to persist recording ${recording.id}" }
}

private fun SQLiteDatabase.deleteIds(ids: List<String>) {
    ids.chunked(500).forEach { batch ->
        val placeholders = batch.joinToString(",") { "?" }
        delete(
            RecordingDatabase.TABLE_RECORDINGS,
            "${RecordingDatabase.COLUMN_ID} IN ($placeholders)",
            batch.toTypedArray(),
        )
    }
}

private fun SQLiteDatabase.createSchema() {
    execSQL(
        """
        CREATE TABLE IF NOT EXISTS ${RecordingDatabase.TABLE_RECORDINGS} (
            ${RecordingDatabase.COLUMN_ID} TEXT PRIMARY KEY NOT NULL,
            ${RecordingDatabase.COLUMN_DISPLAY_NAME} TEXT NOT NULL,
            ${RecordingDatabase.COLUMN_MIME_TYPE} TEXT NOT NULL,
            ${RecordingDatabase.COLUMN_STARTED_AT_MILLIS} INTEGER NOT NULL,
            ${RecordingDatabase.COLUMN_DURATION_MILLIS} INTEGER NOT NULL,
            ${RecordingDatabase.COLUMN_SIZE_BYTES} INTEGER NOT NULL,
            ${RecordingDatabase.COLUMN_CODEC_SUMMARY} TEXT NOT NULL,
            ${RecordingDatabase.COLUMN_STORAGE_TYPE} TEXT NOT NULL DEFAULT '',
            ${RecordingDatabase.COLUMN_STORAGE_TYPE_CODE} INTEGER NOT NULL,
            ${RecordingDatabase.COLUMN_DIRECTORY_ID} TEXT NOT NULL,
            ${RecordingDatabase.COLUMN_FILE_IDENTITY} TEXT NOT NULL,
            ${RecordingDatabase.COLUMN_WAVEFORM_DATA} TEXT NOT NULL,
            ${RecordingDatabase.COLUMN_WAVEFORM_REVISION} TEXT NOT NULL,
            ${RecordingDatabase.COLUMN_CREATED_AT_MILLIS} INTEGER NOT NULL,
            ${RecordingDatabase.COLUMN_LAST_SEEN_AT_MILLIS} INTEGER NOT NULL,
            ${RecordingDatabase.COLUMN_MISSING_SINCE_MILLIS} INTEGER
        )
        """.trimIndent(),
    )
    createIndexes()
}

private fun SQLiteDatabase.createIndexes() {
    execSQL(
        "CREATE INDEX IF NOT EXISTS recordings_started_created_idx ON " +
            "${RecordingDatabase.TABLE_RECORDINGS} (" +
            "${RecordingDatabase.COLUMN_STARTED_AT_MILLIS} DESC, " +
            "${RecordingDatabase.COLUMN_CREATED_AT_MILLIS} DESC)",
    )
    execSQL(
        "CREATE INDEX IF NOT EXISTS recordings_directory_started_created_idx ON " +
            "${RecordingDatabase.TABLE_RECORDINGS} (" +
            "${RecordingDatabase.COLUMN_DIRECTORY_ID}, " +
            "${RecordingDatabase.COLUMN_STARTED_AT_MILLIS} DESC, " +
            "${RecordingDatabase.COLUMN_CREATED_AT_MILLIS} DESC)",
    )
}

internal enum class RecordingDatabaseMigrationStep {
    ADD_LAST_SEEN,
    ADD_MISSING_SINCE,
    ADD_FILE_IDENTITY,
    ADD_WAVEFORM_CACHE,
    ADD_STORAGE_TYPE_CODE,
}

internal fun recordingDatabaseMigrationSteps(
    oldVersion: Int,
    newVersion: Int,
): List<RecordingDatabaseMigrationStep> {
    require(oldVersion > 0) { "Invalid recording database version: $oldVersion" }
    require(newVersion >= oldVersion) { "Database downgrade is not a migration" }
    require(newVersion <= RecordingDatabase.DATABASE_VERSION) {
        "Unsupported future recording database version: $newVersion"
    }
    if (oldVersion == newVersion) return emptyList()
    return buildList {
        if (oldVersion < 2 && newVersion >= 2) {
            add(RecordingDatabaseMigrationStep.ADD_LAST_SEEN)
            add(RecordingDatabaseMigrationStep.ADD_MISSING_SINCE)
        }
        if (oldVersion < 3 && newVersion >= 3) {
            add(RecordingDatabaseMigrationStep.ADD_FILE_IDENTITY)
        }
        if (oldVersion < 4 && newVersion >= 4) {
            add(RecordingDatabaseMigrationStep.ADD_WAVEFORM_CACHE)
        }
        if (oldVersion < 5 && newVersion >= 5) {
            add(RecordingDatabaseMigrationStep.ADD_STORAGE_TYPE_CODE)
        }
    }
}

internal fun recordingDatabaseMigrationSql(
    step: RecordingDatabaseMigrationStep,
): List<String> = when (step) {
    RecordingDatabaseMigrationStep.ADD_LAST_SEEN -> listOf(
        "ALTER TABLE ${RecordingDatabase.TABLE_RECORDINGS} " +
            "ADD COLUMN ${RecordingDatabase.COLUMN_LAST_SEEN_AT_MILLIS} INTEGER NOT NULL DEFAULT 0",
        "UPDATE ${RecordingDatabase.TABLE_RECORDINGS} " +
            "SET ${RecordingDatabase.COLUMN_LAST_SEEN_AT_MILLIS} = ${RecordingDatabase.COLUMN_CREATED_AT_MILLIS}",
    )
    RecordingDatabaseMigrationStep.ADD_MISSING_SINCE -> listOf(
        "ALTER TABLE ${RecordingDatabase.TABLE_RECORDINGS} " +
            "ADD COLUMN ${RecordingDatabase.COLUMN_MISSING_SINCE_MILLIS} INTEGER",
    )
    RecordingDatabaseMigrationStep.ADD_FILE_IDENTITY -> listOf(
        "ALTER TABLE ${RecordingDatabase.TABLE_RECORDINGS} " +
            "ADD COLUMN ${RecordingDatabase.COLUMN_FILE_IDENTITY} TEXT NOT NULL DEFAULT ''",
    )
    RecordingDatabaseMigrationStep.ADD_WAVEFORM_CACHE -> listOf(
        "ALTER TABLE ${RecordingDatabase.TABLE_RECORDINGS} " +
            "ADD COLUMN ${RecordingDatabase.COLUMN_WAVEFORM_DATA} TEXT NOT NULL DEFAULT ''",
        "ALTER TABLE ${RecordingDatabase.TABLE_RECORDINGS} " +
            "ADD COLUMN ${RecordingDatabase.COLUMN_WAVEFORM_REVISION} TEXT NOT NULL DEFAULT ''",
    )
    RecordingDatabaseMigrationStep.ADD_STORAGE_TYPE_CODE -> listOf(
        "ALTER TABLE ${RecordingDatabase.TABLE_RECORDINGS} " +
            "ADD COLUMN ${RecordingDatabase.COLUMN_STORAGE_TYPE_CODE} INTEGER NOT NULL DEFAULT 0",
        "UPDATE ${RecordingDatabase.TABLE_RECORDINGS} SET ${RecordingDatabase.COLUMN_STORAGE_TYPE_CODE} = " +
            "CASE ${RecordingDatabase.COLUMN_STORAGE_TYPE} " +
            "WHEN 'FILE' THEN ${RecordingStorageType.FILE.storageCode.toInt()} " +
            "WHEN 'DOCUMENT' THEN ${RecordingStorageType.DOCUMENT.storageCode.toInt()} " +
            "WHEN 'MEDIASTORE' THEN ${RecordingStorageType.MEDIASTORE.storageCode.toInt()} " +
            "ELSE CAST(${RecordingDatabase.COLUMN_STORAGE_TYPE} AS INTEGER) END",
    )
}

private fun RecordingEntity.toContentValues(): ContentValues {
    val storage = resolveRecordingStorageType(this)
        ?: throw SQLiteException("Unknown recording storage type: $storageType")
    return ContentValues(16).apply {
        put(RecordingDatabase.COLUMN_ID, id)
        put(RecordingDatabase.COLUMN_DISPLAY_NAME, displayName)
        put(RecordingDatabase.COLUMN_MIME_TYPE, mimeType)
        put(RecordingDatabase.COLUMN_STARTED_AT_MILLIS, startedAtMillis)
        put(RecordingDatabase.COLUMN_DURATION_MILLIS, durationMillis)
        put(RecordingDatabase.COLUMN_SIZE_BYTES, sizeBytes)
        put(RecordingDatabase.COLUMN_CODEC_SUMMARY, codecSummary)
        put(RecordingDatabase.COLUMN_STORAGE_TYPE, "")
        put(RecordingDatabase.COLUMN_STORAGE_TYPE_CODE, storage.storageCode.toInt())
        put(RecordingDatabase.COLUMN_DIRECTORY_ID, directoryId)
        put(RecordingDatabase.COLUMN_FILE_IDENTITY, fileIdentity)
        put(RecordingDatabase.COLUMN_WAVEFORM_DATA, waveformData)
        put(RecordingDatabase.COLUMN_WAVEFORM_REVISION, waveformRevision)
        put(RecordingDatabase.COLUMN_CREATED_AT_MILLIS, createdAtMillis)
        put(RecordingDatabase.COLUMN_LAST_SEEN_AT_MILLIS, lastSeenAtMillis)
        put(RecordingDatabase.COLUMN_MISSING_SINCE_MILLIS, missingSinceMillis)
    }
}

private fun readRecordings(cursor: Cursor): List<RecordingEntity> {
    val idIndex = cursor.getColumnIndexOrThrow(RecordingDatabase.COLUMN_ID)
    val displayNameIndex = cursor.getColumnIndexOrThrow(RecordingDatabase.COLUMN_DISPLAY_NAME)
    val mimeTypeIndex = cursor.getColumnIndexOrThrow(RecordingDatabase.COLUMN_MIME_TYPE)
    val startedAtMillisIndex = cursor.getColumnIndexOrThrow(RecordingDatabase.COLUMN_STARTED_AT_MILLIS)
    val durationMillisIndex = cursor.getColumnIndexOrThrow(RecordingDatabase.COLUMN_DURATION_MILLIS)
    val sizeBytesIndex = cursor.getColumnIndexOrThrow(RecordingDatabase.COLUMN_SIZE_BYTES)
    val codecSummaryIndex = cursor.getColumnIndexOrThrow(RecordingDatabase.COLUMN_CODEC_SUMMARY)
    val storageTypeIndex = cursor.getColumnIndexOrThrow(RecordingDatabase.COLUMN_STORAGE_TYPE)
    val storageTypeCodeIndex = cursor.getColumnIndexOrThrow(RecordingDatabase.COLUMN_STORAGE_TYPE_CODE)
    val directoryIdIndex = cursor.getColumnIndexOrThrow(RecordingDatabase.COLUMN_DIRECTORY_ID)
    val fileIdentityIndex = cursor.getColumnIndexOrThrow(RecordingDatabase.COLUMN_FILE_IDENTITY)
    val waveformDataIndex = cursor.getColumnIndexOrThrow(RecordingDatabase.COLUMN_WAVEFORM_DATA)
    val waveformRevisionIndex = cursor.getColumnIndexOrThrow(RecordingDatabase.COLUMN_WAVEFORM_REVISION)
    val createdAtMillisIndex = cursor.getColumnIndexOrThrow(RecordingDatabase.COLUMN_CREATED_AT_MILLIS)
    val lastSeenAtMillisIndex = cursor.getColumnIndexOrThrow(RecordingDatabase.COLUMN_LAST_SEEN_AT_MILLIS)
    val missingSinceMillisIndex = cursor.getColumnIndexOrThrow(RecordingDatabase.COLUMN_MISSING_SINCE_MILLIS)
    val count = cursor.count.coerceAtLeast(0)
    val result = ArrayList<RecordingEntity>(count)
    while (cursor.moveToNext()) {
        val storage = RecordingStorageType.fromStorageCode(cursor.getInt(storageTypeCodeIndex))
            ?: RecordingStorageType.fromLegacyName(cursor.getString(storageTypeIndex))
            ?: throw SQLiteException("Unknown recording storage type in catalog")
        result.add(
            RecordingEntity(
                id = cursor.getString(idIndex),
                displayName = cursor.getString(displayNameIndex),
                mimeType = cursor.getString(mimeTypeIndex),
                startedAtMillis = cursor.getLong(startedAtMillisIndex),
                durationMillis = cursor.getLong(durationMillisIndex),
                sizeBytes = cursor.getLong(sizeBytesIndex),
                codecSummary = cursor.getString(codecSummaryIndex),
                storageType = storage.name,
                directoryId = cursor.getString(directoryIdIndex),
                fileIdentity = cursor.getString(fileIdentityIndex),
                waveformData = cursor.getString(waveformDataIndex),
                waveformRevision = cursor.getString(waveformRevisionIndex),
                createdAtMillis = cursor.getLong(createdAtMillisIndex),
                lastSeenAtMillis = cursor.getLong(lastSeenAtMillisIndex),
                missingSinceMillis =
                    if (cursor.isNull(missingSinceMillisIndex)) null else cursor.getLong(missingSinceMillisIndex),
            ),
        )
    }
    return result
}
