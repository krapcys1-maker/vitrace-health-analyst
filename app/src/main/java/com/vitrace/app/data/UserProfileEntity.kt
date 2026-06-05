package com.vitrace.app.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "user_profile")
data class UserProfileEntity(
    @PrimaryKey
    val id: Int = 1,
    val sex: String,
    val ageYears: Int,
    val heightCm: Int,
    val weightKg: Double,
    val stepsPerKm: Int,
    val source: String,
    val updatedAtEpochMs: Long,
)
