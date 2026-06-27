package com.junai.app.planning

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * PlanEntity — One user goal, broken into PlanStepEntity rows.
 * status: ACTIVE, COMPLETED, ABANDONED
 */
@Entity(tableName = "plans")
data class PlanEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Int = 0,
    val goalText: String,
    val status: String = "ACTIVE",
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)
