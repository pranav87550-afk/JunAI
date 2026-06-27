package com.junai.app.planning

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * PlanStepEntity — One step inside a PlanEntity, ordered by stepOrder.
 */
@Entity(tableName = "plan_steps")
data class PlanStepEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Int = 0,
    val planId: Int,
    val stepText: String,
    val stepOrder: Int,
    val isCompleted: Boolean = false,
    val completedAt: Long? = null
)
