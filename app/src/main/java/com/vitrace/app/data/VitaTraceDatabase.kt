package com.vitrace.app.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        HealthConnectQualitySnapshotEntity::class,
        DailyActivitySummaryEntity::class,
        DailyHeartSummaryEntity::class,
        DailySleepSummaryEntity::class,
        DailyWorkoutSummaryEntity::class,
        DailyBodySummaryEntity::class,
    ],
    version = 2,
    exportSchema = false,
)
abstract class VitaTraceDatabase : RoomDatabase() {
    abstract fun healthConnectQualitySnapshotDao(): HealthConnectQualitySnapshotDao
    abstract fun dailySummaryDao(): DailySummaryDao

    companion object {
        @Volatile
        private var instance: VitaTraceDatabase? = null

        private val migration1To2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS daily_activity_summaries (
                        date TEXT NOT NULL PRIMARY KEY,
                        steps INTEGER NOT NULL,
                        distanceMeters REAL NOT NULL,
                        activeCaloriesKcal REAL NOT NULL,
                        source TEXT NOT NULL,
                        syncedAtEpochMs INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS daily_heart_summaries (
                        date TEXT NOT NULL PRIMARY KEY,
                        sampleCount INTEGER NOT NULL,
                        minBpm INTEGER,
                        maxBpm INTEGER,
                        avgBpm REAL,
                        source TEXT NOT NULL,
                        lastRecordAtEpochMs INTEGER,
                        syncedAtEpochMs INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS daily_sleep_summaries (
                        date TEXT NOT NULL PRIMARY KEY,
                        sessionCount INTEGER NOT NULL,
                        totalSleepMinutes INTEGER NOT NULL,
                        source TEXT NOT NULL,
                        lastRecordAtEpochMs INTEGER,
                        syncedAtEpochMs INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS daily_workout_summaries (
                        date TEXT NOT NULL PRIMARY KEY,
                        sessionCount INTEGER NOT NULL,
                        totalDurationMinutes INTEGER NOT NULL,
                        source TEXT NOT NULL,
                        lastRecordAtEpochMs INTEGER,
                        syncedAtEpochMs INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS daily_body_summaries (
                        date TEXT NOT NULL PRIMARY KEY,
                        latestWeightKg REAL,
                        latestVo2Max REAL,
                        latestSpo2Percent REAL,
                        weightRecordCount INTEGER NOT NULL,
                        vo2MaxRecordCount INTEGER NOT NULL,
                        spo2RecordCount INTEGER NOT NULL,
                        source TEXT NOT NULL,
                        lastRecordAtEpochMs INTEGER,
                        syncedAtEpochMs INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
            }
        }

        fun get(context: Context): VitaTraceDatabase {
            return instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    VitaTraceDatabase::class.java,
                    "vitrace.db",
                )
                    .addMigrations(migration1To2)
                    .build().also { database ->
                    instance = database
                }
            }
        }
    }
}
