package com.junai.app.planning

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update

@Dao
interface PlanDao {

    @Insert
    suspend fun insertPlan(plan: PlanEntity): Long

    @Insert
    suspend fun insertSteps(steps: List<PlanStepEntity>)

    @Update
    suspend fun updatePlan(plan: PlanEntity)

    @Update
    suspend fun updateStep(step: PlanStepEntity)

    @Query("SELECT * FROM plans WHERE status = 'ACTIVE' ORDER BY updatedAt DESC")
    suspend fun getActivePlans(): List<PlanEntity>

    @Query("SELECT * FROM plans WHERE id = :planId")
    suspend fun getPlanById(planId: Int): PlanEntity?

    @Query("SELECT * FROM plan_steps WHERE planId = :planId ORDER BY stepOrder ASC")
    suspend fun getStepsForPlan(planId: Int): List<PlanStepEntity>

    @Query("SELECT * FROM plan_steps WHERE planId = :planId AND isCompleted = 0 ORDER BY stepOrder ASC LIMIT 1")
    suspend fun getNextIncompleteStep(planId: Int): PlanStepEntity?

    @Query("SELECT COUNT(*) FROM plan_steps WHERE planId = :planId AND isCompleted = 0")
    suspend fun getIncompleteStepCount(planId: Int): Int

    @Query("UPDATE plans SET status = :status, updatedAt = :now WHERE id = :planId")
    suspend fun updatePlanStatus(planId: Int, status: String, now: Long = System.currentTimeMillis())
}
