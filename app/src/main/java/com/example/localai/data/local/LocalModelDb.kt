package com.example.localai.data.local

import android.content.Context
import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

/**
 * A file the user asked to download. Persists download progress only; runtime compatibility is
 * recomputed on demand and deliberately not stored.
 */
@Entity(tableName = "local_models")
data class LocalModelEntity(
    /** repoId/revision/path: one row per file, which also prevents duplicate downloads. */
    @PrimaryKey val id: String,
    val repoId: String,
    val revision: String,
    val path: String,
    val expectedSizeBytes: Long?,
    /** SHA-256 from the Hub's LFS pointer; the only checksum treated as trustworthy. */
    val expectedSha256: String?,
    /** Bytes known to be durably written (fsynced) to the partial file. */
    val downloadedBytes: Long,
    /** Name of a [LocalDownloadState]; kept as text so new states need no migration. */
    val state: String,
    val failureReason: String?,
    val updatedAtMillis: Long,
    // ---- Added in version 2 ----
    /** App-generated file name inside the models directory; never derived from the repo path. */
    @ColumnInfo(defaultValue = "NULL") val localFileName: String? = null,
    /** Total size reported by the server (Content-Range), used to detect a changed file on resume. */
    @ColumnInfo(defaultValue = "NULL") val remoteTotalBytes: Long? = null,
    /** Strong ETag of the response the partial file came from, if the server sent one. */
    @ColumnInfo(defaultValue = "NULL") val remoteEtag: String? = null,
    /** Name of a [com.example.localai.data.download.DownloadFailureKind] when [state] is FAILED. */
    @ColumnInfo(defaultValue = "NULL") val failureKind: String? = null,
    /** Consecutive automatic attempts for the current request; reset by a user retry. */
    @ColumnInfo(defaultValue = "0") val attempts: Int = 0,
    /** How the completed file was checked: SHA256 or SIZE_ONLY. */
    @ColumnInfo(defaultValue = "NULL") val verification: String? = null,
    @ColumnInfo(defaultValue = "0") val createdAtMillis: Long = 0,
    @ColumnInfo(defaultValue = "NULL") val completedAtMillis: Long? = null
)

enum class LocalDownloadState { QUEUED, DOWNLOADING, VERIFYING, COMPLETED, FAILED, PAUSED, CANCELLED }

@Dao
interface LocalModelDao {
    @Query("SELECT * FROM local_models ORDER BY updatedAtMillis DESC")
    fun observeAll(): Flow<List<LocalModelEntity>>

    @Query("SELECT * FROM local_models")
    suspend fun all(): List<LocalModelEntity>

    @Query("SELECT * FROM local_models WHERE id = :id")
    suspend fun find(id: String): LocalModelEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: LocalModelEntity)

    /** Inserts only if absent; returns -1 when a row already exists. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIfAbsent(entity: LocalModelEntity): Long

    /** Progress write that cannot overwrite a state the user changed meanwhile (pause, cancel). */
    @Query("UPDATE local_models SET downloadedBytes = :bytes, updatedAtMillis = :now WHERE id = :id AND state = 'DOWNLOADING'")
    suspend fun updateProgress(id: String, bytes: Long, now: Long): Int

    /** Compare-and-set of the state; returns 1 when the transition happened. */
    @Query("UPDATE local_models SET state = :to, updatedAtMillis = :now WHERE id = :id AND state = :from")
    suspend fun transition(id: String, from: String, to: String, now: Long): Int

    @Query("DELETE FROM local_models WHERE id = :id")
    suspend fun delete(id: String)
}

@Database(entities = [LocalModelEntity::class], version = 2, exportSchema = true)
abstract class LocalModelDatabase : RoomDatabase() {
    abstract fun localModelDao(): LocalModelDao

    companion object {
        const val NAME = "local_models.db"

        /** Version 1 had no download metadata; every new column is nullable or defaulted. */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE local_models ADD COLUMN localFileName TEXT DEFAULT NULL")
                db.execSQL("ALTER TABLE local_models ADD COLUMN remoteTotalBytes INTEGER DEFAULT NULL")
                db.execSQL("ALTER TABLE local_models ADD COLUMN remoteEtag TEXT DEFAULT NULL")
                db.execSQL("ALTER TABLE local_models ADD COLUMN failureKind TEXT DEFAULT NULL")
                db.execSQL("ALTER TABLE local_models ADD COLUMN attempts INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE local_models ADD COLUMN verification TEXT DEFAULT NULL")
                db.execSQL("ALTER TABLE local_models ADD COLUMN createdAtMillis INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE local_models ADD COLUMN completedAtMillis INTEGER DEFAULT NULL")
            }
        }

        /** No destructive fallback: a missing migration must fail loudly, not wipe downloads. */
        fun build(context: Context): LocalModelDatabase =
            Room.databaseBuilder(context.applicationContext, LocalModelDatabase::class.java, NAME)
                .addMigrations(MIGRATION_1_2)
                .build()
    }
}
