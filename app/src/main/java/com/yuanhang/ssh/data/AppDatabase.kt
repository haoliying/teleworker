package com.yuanhang.ssh.data

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [HostEntity::class, SshKeyEntity::class],
    version = 4,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun hostDao(): HostDao
    abstract fun sshKeyDao(): SshKeyDao

    companion object {

        /**
         * Adds the Microsoft RDP columns.
         *
         * Room validates the schema against the entities after every migration and refuses
         * to open the database if it does not match, so the version bump alone is not
         * enough – the columns have to actually be there. `NOT NULL` columns need a default
         * in SQLite; Room tolerates the SQL default even though the entity declares none.
         */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // A column that already exists (odd databases from earlier builds) must not
                // abort the whole migration, so each statement is applied independently.
                listOf(
                    "ALTER TABLE hosts ADD COLUMN rdpEnabled INTEGER NOT NULL DEFAULT 0",
                    "ALTER TABLE hosts ADD COLUMN rdpRemoteHost TEXT NOT NULL DEFAULT '127.0.0.1'",
                    "ALTER TABLE hosts ADD COLUMN rdpRemotePort INTEGER NOT NULL DEFAULT 3389",
                    "ALTER TABLE hosts ADD COLUMN rdpDomain TEXT NOT NULL DEFAULT ''",
                    "ALTER TABLE hosts ADD COLUMN rdpWidth INTEGER NOT NULL DEFAULT 1280",
                    "ALTER TABLE hosts ADD COLUMN rdpHeight INTEGER NOT NULL DEFAULT 800"
                ).forEach { sql ->
                    runCatching { db.execSQL(sql) }
                }
            }
        }

        /**
         * VNC/RDP stopped being tunnelled through SSH and are dialled directly instead.
         *
         * Old rows stored `127.0.0.1`, which used to mean "the SSH server itself". Kept
         * literally it would now point at the phone, so it is cleared – blank is the new
         * "use the host's own address" marker.
         */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                runCatching {
                    db.execSQL("UPDATE hosts SET vncRemoteHost = '' WHERE vncRemoteHost IN ('127.0.0.1', 'localhost')")
                }
                runCatching {
                    db.execSQL("UPDATE hosts SET rdpRemoteHost = '' WHERE rdpRemoteHost IN ('127.0.0.1', 'localhost')")
                }
            }
        }

        /**
         * Introduces per-protocol host entries.
         *
         * Every row used to be an SSH host that could *additionally* carry a desktop. Now a
         * row has a single [com.yuanhang.ssh.data.HostType], so rows that had a desktop
         * enabled are converted: their `host`/`port` are rewritten to the desktop address
         * and port, because that is what those entries exist for.
         */
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                listOf(
                    "ALTER TABLE hosts ADD COLUMN type TEXT NOT NULL DEFAULT 'SSH'",
                    "ALTER TABLE hosts ADD COLUMN vncPasswordEnc TEXT",
                    "ALTER TABLE hosts ADD COLUMN rdpPasswordEnc TEXT",
                    """
                    UPDATE hosts SET
                        type = 'RDP',
                        host = CASE WHEN rdpRemoteHost <> '' THEN rdpRemoteHost ELSE host END,
                        port = CASE WHEN rdpRemotePort > 0 THEN rdpRemotePort ELSE 3389 END
                    WHERE rdpEnabled = 1
                    """.trimIndent(),
                    """
                    UPDATE hosts SET
                        type = 'VNC',
                        host = CASE WHEN vncRemoteHost <> '' THEN vncRemoteHost ELSE host END,
                        port = CASE WHEN vncRemotePort > 0 THEN vncRemotePort ELSE 5900 END
                    WHERE vncEnabled = 1 AND rdpEnabled = 0
                    """.trimIndent()
                ).forEach { sql ->
                    runCatching { db.execSQL(sql) }
                }
            }
        }
    }
}
