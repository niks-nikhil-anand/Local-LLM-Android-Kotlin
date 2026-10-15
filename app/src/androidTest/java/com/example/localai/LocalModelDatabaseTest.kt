package com.example.localai

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.localai.data.local.LocalModelDatabase
import com.example.localai.data.local.LocalModelEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Real SQLite: the 1 -> 2 migration and the DAO's guarded updates. */
@RunWith(AndroidJUnit4::class)
class LocalModelDatabaseTest {

    private val dbName = "migration-test.db"

    @get:Rule
    val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), LocalModelDatabase::class.java)

    @Test
    fun migrationFromVersion1KeepsExistingRowsAndAddsDefaults() {
        helper.createDatabase(dbName, 1).apply {
            execSQL(
                "INSERT INTO local_models (id, repoId, revision, path, expectedSizeBytes, expectedSha256, downloadedBytes, state, failureReason, updatedAtMillis) " +
                    "VALUES ('o/r/main/m.gguf', 'o/r', 'main', 'm.gguf', 1000, 'abc', 400, 'DOWNLOADING', NULL, 42)"
            )
            close()
        }
        // Validates the migrated schema against the version 2 entity definition.
        helper.runMigrationsAndValidate(dbName, 2, true, LocalModelDatabase.MIGRATION_1_2).close()

        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.databaseBuilder(context, LocalModelDatabase::class.java, dbName)
            .addMigrations(LocalModelDatabase.MIGRATION_1_2)
            .build()
        try {
            val row = runBlocking { db.localModelDao().find("o/r/main/m.gguf") }!!
            assertEquals(400L, row.downloadedBytes)
            assertEquals("DOWNLOADING", row.state)
            assertEquals("abc", row.expectedSha256)
            assertEquals(0, row.attempts)
            assertEquals(0L, row.createdAtMillis)
            assertNull(row.localFileName)
            assertNull(row.remoteEtag)
            assertNull(row.completedAtMillis)
        } finally {
            db.close()
            context.deleteDatabase(dbName)
        }
    }

    @Test
    fun guardedUpdatesCannotOverwriteAUserStateChange() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.inMemoryDatabaseBuilder(context, LocalModelDatabase::class.java).build()
        val dao = db.localModelDao()
        try {
            val row = LocalModelEntity("id", "o/r", "main", "m.gguf", 1000, null, 0, "DOWNLOADING", null, 1)
            dao.upsert(row)
            assertEquals(1, dao.updateProgress("id", 500, 2))
            assertEquals(1, dao.transition("id", "DOWNLOADING", "PAUSED", 3))
            // The download thread's late progress write must not undo the pause.
            assertEquals(0, dao.updateProgress("id", 900, 4))
            assertEquals(0, dao.transition("id", "DOWNLOADING", "VERIFYING", 5))
            val stored = dao.find("id")!!
            assertEquals("PAUSED", stored.state)
            assertEquals(500L, stored.downloadedBytes)
            assertEquals(-1L, dao.insertIfAbsent(row))
            assertEquals(listOf("id"), dao.observeAll().first().map { it.id })
        } finally {
            db.close()
        }
    }
}
