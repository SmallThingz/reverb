package app.smallthingz.reverb

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.Cursor
import android.database.sqlite.SQLiteOpenHelper

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

    suspend fun upsertAll(recordings: List<RecordingEntity>)

    suspend fun deleteById(id: String)

    suspend fun deleteByIds(ids: List<String>)

    suspend fun hasMovableRecordings(targetDirectoryId: String): Boolean
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
        if (oldVersion < 2 && newVersion >= 2) {
            db.migrateToVersion2()
        }
    }

    override fun onDowngrade(
        db: SQLiteDatabase,
        oldVersion: Int,
        newVersion: Int,
    ) {
        db.recreateSchema()
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
            writableDatabase.insertWithOnConflict(
                TABLE_RECORDINGS,
                null,
                recording.toContentValues(),
                SQLiteDatabase.CONFLICT_REPLACE,
            )
        }

        override suspend fun upsertAll(recordings: List<RecordingEntity>) {
            if (recordings.isEmpty()) return
            writableDatabase.beginTransaction()
            try {
                recordings.forEach { recording ->
                    writableDatabase.insertWithOnConflict(
                        TABLE_RECORDINGS,
                        null,
                        recording.toContentValues(),
                        SQLiteDatabase.CONFLICT_REPLACE,
                    )
                }
                writableDatabase.setTransactionSuccessful()
            } finally {
                writableDatabase.endTransaction()
            }
        }

        override suspend fun deleteById(id: String) {
            writableDatabase.delete(TABLE_RECORDINGS, "$COLUMN_ID = ?", arrayOf(id))
        }

        override suspend fun deleteByIds(ids: List<String>) {
            if (ids.isEmpty()) return
            val db = writableDatabase
            db.beginTransaction()
            try {
                ids.chunked(MAX_DELETE_BIND_ARGS).forEach { batch ->
                    val placeholders = batch.joinToString(",") { "?" }
                    db.delete(TABLE_RECORDINGS, "$COLUMN_ID IN ($placeholders)", batch.toTypedArray())
                }
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
        }

        override suspend fun hasMovableRecordings(targetDirectoryId: String): Boolean {
            return readableDatabase.rawQuery(
                "SELECT 1 FROM $TABLE_RECORDINGS WHERE $COLUMN_DIRECTORY_ID != ? LIMIT 1",
                arrayOf(targetDirectoryId),
            ).use { it.moveToFirst() }
        }
    }

    companion object {
        private const val DATABASE_NAME = ReverbConfig.DATABASE_FILE_NAME
        private const val DATABASE_VERSION = 2
        private const val MAX_DELETE_BIND_ARGS = 500
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
            ${RecordingDatabase.COLUMN_CREATED_AT_MILLIS} INTEGER NOT NULL,
            ${RecordingDatabase.COLUMN_LAST_SEEN_AT_MILLIS} INTEGER NOT NULL,
            ${RecordingDatabase.COLUMN_MISSING_SINCE_MILLIS} INTEGER
        )
        """.trimIndent(),
    )
}

private fun SQLiteDatabase.migrateToVersion2() {
    execSQL(
        """
        ALTER TABLE ${RecordingDatabase.TABLE_RECORDINGS}
        ADD COLUMN ${RecordingDatabase.COLUMN_LAST_SEEN_AT_MILLIS} INTEGER NOT NULL DEFAULT 0
        """.trimIndent(),
    )
    execSQL(
        """
        UPDATE ${RecordingDatabase.TABLE_RECORDINGS}
        SET ${RecordingDatabase.COLUMN_LAST_SEEN_AT_MILLIS} = ${RecordingDatabase.COLUMN_CREATED_AT_MILLIS}
        """.trimIndent(),
    )
    execSQL(
        """
        ALTER TABLE ${RecordingDatabase.TABLE_RECORDINGS}
        ADD COLUMN ${RecordingDatabase.COLUMN_MISSING_SINCE_MILLIS} INTEGER
        """.trimIndent(),
    )
}

private fun SQLiteDatabase.recreateSchema() {
    execSQL("DROP TABLE IF EXISTS ${RecordingDatabase.TABLE_RECORDINGS}")
    createSchema()
}

private fun RecordingEntity.toContentValues(): ContentValues {
    return ContentValues(12).apply {
        put(RecordingDatabase.COLUMN_ID, id)
        put(RecordingDatabase.COLUMN_DISPLAY_NAME, displayName)
        put(RecordingDatabase.COLUMN_MIME_TYPE, mimeType)
        put(RecordingDatabase.COLUMN_STARTED_AT_MILLIS, startedAtMillis)
        put(RecordingDatabase.COLUMN_DURATION_MILLIS, durationMillis)
        put(RecordingDatabase.COLUMN_SIZE_BYTES, sizeBytes)
        put(RecordingDatabase.COLUMN_CODEC_SUMMARY, codecSummary)
        put(RecordingDatabase.COLUMN_STORAGE_TYPE, storageType)
        put(RecordingDatabase.COLUMN_DIRECTORY_ID, directoryId)
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
                createdAtMillis = cursor.getLong(createdAtMillisIndex),
                lastSeenAtMillis = cursor.getLong(lastSeenAtMillisIndex),
                missingSinceMillis =
                    if (cursor.isNull(missingSinceMillisIndex)) null else cursor.getLong(missingSinceMillisIndex),
            ),
        )
    }
    return result
}
