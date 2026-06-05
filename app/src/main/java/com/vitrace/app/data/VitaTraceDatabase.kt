package com.vitrace.app.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [HealthConnectQualitySnapshotEntity::class],
    version = 1,
    exportSchema = false,
)
abstract class VitaTraceDatabase : RoomDatabase() {
    abstract fun healthConnectQualitySnapshotDao(): HealthConnectQualitySnapshotDao

    companion object {
        @Volatile
        private var instance: VitaTraceDatabase? = null

        fun get(context: Context): VitaTraceDatabase {
            return instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    VitaTraceDatabase::class.java,
                    "vitrace.db",
                ).build().also { database ->
                    instance = database
                }
            }
        }
    }
}
