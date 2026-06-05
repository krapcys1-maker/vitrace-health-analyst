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
        DailyWorkoutTypeSummaryEntity::class,
        DailyBodySummaryEntity::class,
        SleepDetailEntity::class,
        WorkoutSessionEntity::class,
        AnalysisResultEntity::class,
        UserProfileEntity::class,
        HealthNoteEntity::class,
    ],
    version = 7,
    exportSchema = false,
)
abstract class VitaTraceDatabase : RoomDatabase() {
    abstract fun healthConnectQualitySnapshotDao(): HealthConnectQualitySnapshotDao
    abstract fun dailySummaryDao(): DailySummaryDao
    abstract fun userProfileDao(): UserProfileDao
    abstract fun healthNoteDao(): HealthNoteDao
    abstract fun analysisResultDao(): AnalysisResultDao

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

        private val migration2To3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS user_profile (
                        id INTEGER NOT NULL PRIMARY KEY,
                        sex TEXT NOT NULL,
                        ageYears INTEGER NOT NULL,
                        heightCm INTEGER NOT NULL,
                        weightKg REAL NOT NULL,
                        stepsPerKm INTEGER NOT NULL,
                        source TEXT NOT NULL,
                        updatedAtEpochMs INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    INSERT OR REPLACE INTO user_profile (
                        id,
                        sex,
                        ageYears,
                        heightCm,
                        weightKg,
                        stepsPerKm,
                        source,
                        updatedAtEpochMs
                    ) VALUES (
                        1,
                        'male',
                        40,
                        186,
                        88.0,
                        1250,
                        'USER_PROVIDED',
                        1780657200000
                    )
                    """.trimIndent()
                )
            }
        }

        private val migration3To4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS health_notes (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        date TEXT NOT NULL,
                        noteText TEXT NOT NULL,
                        tags TEXT NOT NULL,
                        moodScore INTEGER,
                        physicalScore INTEGER,
                        createdAtEpochMs INTEGER NOT NULL,
                        updatedAtEpochMs INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
            }
        }

        private val migration4To5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS daily_workout_type_summaries (
                        date TEXT NOT NULL,
                        workoutType TEXT NOT NULL,
                        sessionCount INTEGER NOT NULL,
                        totalDurationMinutes INTEGER NOT NULL,
                        distanceMeters REAL NOT NULL,
                        activeCaloriesKcal REAL NOT NULL,
                        steps INTEGER NOT NULL,
                        avgHeartRateBpm REAL,
                        source TEXT NOT NULL,
                        lastRecordAtEpochMs INTEGER,
                        syncedAtEpochMs INTEGER NOT NULL,
                        PRIMARY KEY(date, workoutType)
                    )
                    """.trimIndent()
                )
            }
        }

        private val migration5To6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS sleep_details (
                        date TEXT NOT NULL PRIMARY KEY,
                        bedtimeEpochMs INTEGER,
                        wakeUpEpochMs INTEGER,
                        totalSleepMinutes INTEGER NOT NULL,
                        deepSleepMinutes INTEGER,
                        lightSleepMinutes INTEGER,
                        remSleepMinutes INTEGER,
                        awakeMinutes INTEGER,
                        awakeCount INTEGER,
                        sleepScore INTEGER,
                        segmentCount INTEGER NOT NULL,
                        source TEXT NOT NULL,
                        rawSourceFile TEXT NOT NULL,
                        rawSourceKey TEXT NOT NULL,
                        rawTimestampEpochMs INTEGER,
                        rawPayloadJson TEXT NOT NULL,
                        syncedAtEpochMs INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS workout_sessions (
                        sessionId TEXT NOT NULL PRIMARY KEY,
                        date TEXT NOT NULL,
                        workoutType TEXT NOT NULL,
                        sportName TEXT NOT NULL,
                        rawSportType INTEGER,
                        startAtEpochMs INTEGER,
                        endAtEpochMs INTEGER,
                        durationSeconds INTEGER NOT NULL,
                        distanceMeters REAL NOT NULL,
                        activeCaloriesKcal REAL NOT NULL,
                        totalCaloriesKcal REAL,
                        steps INTEGER NOT NULL,
                        avgHeartRateBpm REAL,
                        minHeartRateBpm INTEGER,
                        maxHeartRateBpm INTEGER,
                        avgPaceSecondsPerKm INTEGER,
                        minPaceSecondsPerKm INTEGER,
                        maxPaceSecondsPerKm INTEGER,
                        avgCadence REAL,
                        maxCadence INTEGER,
                        trainingEffect REAL,
                        recoveryTime INTEGER,
                        vo2Max REAL,
                        gpxUrl TEXT,
                        source TEXT NOT NULL,
                        rawSourceFile TEXT NOT NULL,
                        rawTimestampEpochMs INTEGER,
                        rawPayloadJson TEXT NOT NULL,
                        syncedAtEpochMs INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_sleep_details_date ON sleep_details(date)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_workout_sessions_date ON workout_sessions(date)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_workout_sessions_workoutType_date ON workout_sessions(workoutType, date)")
            }
        }

        private val migration6To7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS analysis_results (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        analysisType TEXT NOT NULL,
                        scope TEXT NOT NULL,
                        engineVersion TEXT NOT NULL,
                        baselineStartDate TEXT,
                        baselineEndDate TEXT,
                        currentStartDate TEXT,
                        currentEndDate TEXT,
                        generatedForDate TEXT NOT NULL,
                        summaryTitle TEXT NOT NULL,
                        summaryText TEXT NOT NULL,
                        confidence TEXT NOT NULL,
                        sampleSize INTEGER NOT NULL,
                        resultJson TEXT NOT NULL,
                        sourceCoverageJson TEXT NOT NULL,
                        timeContextJson TEXT NOT NULL,
                        isCurrent INTEGER NOT NULL,
                        pinned INTEGER NOT NULL,
                        createdAtEpochMs INTEGER NOT NULL,
                        updatedAtEpochMs INTEGER NOT NULL,
                        supersededAtEpochMs INTEGER
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    CREATE INDEX IF NOT EXISTS index_analysis_results_analysisType_scope_isCurrent
                    ON analysis_results(analysisType, scope, isCurrent)
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    CREATE INDEX IF NOT EXISTS index_analysis_results_createdAtEpochMs
                    ON analysis_results(createdAtEpochMs)
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
                    .addMigrations(migration2To3)
                    .addMigrations(migration3To4)
                    .addMigrations(migration4To5)
                    .addMigrations(migration5To6)
                    .addMigrations(migration6To7)
                    .build().also { database ->
                    instance = database
                }
            }
        }
    }
}
