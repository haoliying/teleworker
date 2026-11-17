package com.yuanhang.ssh.data

import android.content.Context
import androidx.room.Room

object DatabaseProvider {

    private const val DB_NAME = "yuanhang-ssh.db"

    @Volatile
    private var instance: AppDatabase? = null

    fun get(context: Context): AppDatabase =
        instance ?: synchronized(this) {
            instance ?: build(context.applicationContext).also { instance = it }
        }

    /**
     * Builds (and immediately opens) the database.
     *
     * Opening eagerly matters: Room validates the schema when the file is opened, and an
     * un-migratable or corrupt file throws there. Doing it here means the failure surfaces
     * once, in one place, instead of as a crash from whatever screen issues the first query.
     */
    private fun build(context: Context): AppDatabase {
        val builder = Room.databaseBuilder(context, AppDatabase::class.java, DB_NAME)
            .addMigrations(
                AppDatabase.MIGRATION_1_2,
                AppDatabase.MIGRATION_2_3,
                AppDatabase.MIGRATION_3_4
            )
            // Still the last resort for any version path we have not written a migration for.
            .fallbackToDestructiveMigration()

        runCatching {
            return builder.build().also { it.openHelper.readableDatabase }
        }.onFailure { first ->
            first.printStackTrace()
        }

        // The file cannot be opened (corrupt, or a schema this build cannot reach).
        // Rebuilding loses saved hosts, but that beats an app that refuses to start at all.
        listOf(DB_NAME, "$DB_NAME-wal", "$DB_NAME-shm").forEach { name ->
            runCatching { context.deleteDatabase(name) }
        }
        return builder.build().also { it.openHelper.readableDatabase }
    }
}
