package com.junai.app.agent.memory

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "agent_memory")
data class AgentMemoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val goalText: String,
    val wasSuccessful: Boolean,
    val stepsUsed: String,           // JSON array of AgentStep
    val toolsUsed: String,           // JSON array of tool names
    val preferredApps: String,       // JSON array of app packages used
    val failureReason: String? = null,
    val improvementNote: String? = null,
    val timestamp: Long = System.currentTimeMillis()
)
