package com.victorypoint.zldreventreporter.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(entities = [EventStatEntity::class], version = 3, exportSchema = false)
abstract class ZldrReporterDatabase : RoomDatabase() {
    abstract fun eventStatDao(): EventStatDao

    companion object {
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE event_stats ADD COLUMN signedUpFrozen INTEGER NOT NULL DEFAULT 0"
                )
            }
        }

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE event_stats ADD COLUMN durationInSeconds INTEGER NOT NULL DEFAULT 3600"
                )
            }
        }
    }
}
