package com.junai.app.memory

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * MemoryEntity — Single unit of Jun's hybrid memory system.
 *
 * memoryType decides which layer this memory currently lives in:
 *   SHORT_TERM  - Recent, unscored, may be forgotten soon
 *   WORKING     - Actively relevant to the current conversation/task
 *   LONG_TERM   - Promoted because importance crossed the threshold
 *   EPISODIC    - Tied to a specific event/time
 *
 * importance and confidence are kept separate:
 *   importance  - how much this matters to the user (drives promotion/forgetting)
 *   confidence  - how sure Jun is that this memory is accurate
 */
@Entity(tableName = "memory_items")
data class MemoryEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Int = 0,
    val summary: String,
    val category: String = "GENERAL",       // PREFERENCE, FACT, GOAL, EVENT, CORRECTION
    val source: String = "CONVERSATION",    // CONVERSATION, USER_STATED, INFERRED, SYSTEM
    val memoryType: String = "SHORT_TERM",  // SHORT_TERM, WORKING, LONG_TERM, EPISODIC
    val importance: Float = 0.3f,           // 0.0 - 1.0, drives promotion
    val confidence: Float = 1.0f,           // 0.0 - 1.0
    val tags: String = "",                  // comma-separated, e.g. "python,language"
    val timestamp: Long = System.currentTimeMillis(),
    val lastAccessed: Long = System.currentTimeMillis(),
    val accessCount: Int = 0
)
