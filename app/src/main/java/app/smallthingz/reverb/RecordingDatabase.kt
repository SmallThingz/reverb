package app.smallthingz.reverb

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import android.database.Cursor
import android.database.sqlite.SQLiteOpenHelper
import androidx.core.database.sqlite.transaction

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
                "$COLUMN_ID = ? AND $COLUMN_FILE_IDENTITY = ? AND $COLUMN_SIZE_BYTES = ? AND " +
                    "$COLUMN_DURATION_MILLIS = ?",
                arrayOf(
                    recording.id,
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
        internal const val DATABASE_VERSION = 4
        internal const val TABLE_RECORDINGS = "recordings"
        internal const val COLUMN_ID = "id"
        internal const val COLUMN_DISPLAY_NAME = "displayName"
        internal const val COLUMN_MIME_TYPE = "mimeType"
        internal const val COLUMN_STARTED_AT_MILLIS = "startedAtMillis"
        internal const val COLUMN_DURATION_MILLIS = "durationMillis"
        internal const val COLUMN_SIZE_BYTES = "sizeBytes"
        internal const val COLUMN_CODEC_SUMMARY = "codecSummary"
        internal const val COLUMN_STORAGE_TYPE = "storageType"
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
    }
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
            ${RecordingDatabase.COLUMN_STORAGE_TYPE} TEXT NOT NULL,
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
}

private fun RecordingEntity.toContentValues(): ContentValues {
    return ContentValues(15).apply {
        put(RecordingDatabase.COLUMN_ID, id)
        put(RecordingDatabase.COLUMN_DISPLAY_NAME, displayName)
        put(RecordingDatabase.COLUMN_MIME_TYPE, mimeType)
        put(RecordingDatabase.COLUMN_STARTED_AT_MILLIS, startedAtMillis)
        put(RecordingDatabase.COLUMN_DURATION_MILLIS, durationMillis)
        put(RecordingDatabase.COLUMN_SIZE_BYTES, sizeBytes)
        put(RecordingDatabase.COLUMN_CODEC_SUMMARY, codecSummary)
        put(RecordingDatabase.COLUMN_STORAGE_TYPE, storageType)
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
        result.add(
            RecordingEntity(
                id = cursor.getString(idIndex),
                displayName = cursor.getString(displayNameIndex),
                mimeType = cursor.getString(mimeTypeIndex),
                startedAtMillis = cursor.getLong(startedAtMillisIndex),
                durationMillis = cursor.getLong(durationMillisIndex),
                sizeBytes = cursor.getLong(sizeBytesIndex),
                codecSummary = cursor.getString(codecSummaryIndex),
                storageType = cursor.getString(storageTypeIndex),
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
