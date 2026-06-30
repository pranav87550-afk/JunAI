package com.junai.app.agent

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * AgentTaskEntity — persists one long-running agent workflow so it survives
 * interruption (app killed, user says "stop", phone call comes in) and can
 * be resumed exactly where it left off.
 *
 * Moved into its own file (previously lived inside MultiStepTaskManager.kt)
 * because AppDatabase.kt imports it as `com.junai.app.agent.AgentTaskEntity`
 * — Room/Kotlin needs this to resolve as its own compilation unit for the
 * generated Room schema/DAO implementation to build correctly.
 *
 * Columns match the spec precisely: goalText, agentTaskParams (JSON),
 * steps (JSON), currentStep, status, timestamp. No extra columns added.
 */
@Entity(tableName = "agent_tasks")
data class AgentTaskEntity(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val goalText: String,
    val agentTaskParams: String,   // JSON-encoded IntentDetector.AgentTaskParams
    val steps: String,             // JSON-encoded List<AgentStep>
    val currentStep: Int,
    val status: String,            // TaskStatus.name
    val timestamp: Long = System.currentTimeMillis()
)
