package com.vitrace.app.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

@Dao
interface HealthConnectQualitySnapshotDao {
    @Insert
    suspend fun insertAll(snapshots: List<HealthConnectQualitySnapshotEntity>)

    @Query("SELECT COUNT(*) FROM health_connect_quality_snapshots")
    suspend fun count(): Int
}
