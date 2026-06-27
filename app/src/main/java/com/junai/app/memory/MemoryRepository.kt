package com.junai.app.memory

import android.content.Context
import com.junai.app.AppDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * MemoryRepository — Single entry point for Jun's hybrid memory system.
 *
 * Responsibilities (Phase 1 scope only):
 * - Save new memories (default: SHORT_TERM)
 * - Promote SHORT_TERM -> LONG_TERM when importance crosses threshold
 * - Promote SHORT_TERM -> WORKING when a memory is accessed repeatedly
 *   within a short window (actively relevant right now)
 * - Forget low-importance, stale SHORT_TERM memories
 * - Provide read access by type / category / tag
 *
 * NOTE: This phase does NOT hook into ConversationContext or
 * ChatIntentHandler yet — that wiring is intentionally deferred to
 * Phase 2 (Importance Engine), where scoring logic decides what's
 * worth remembering. For now this is a clean, testable storage layer.
 */
class MemoryRepository(context: Context) {

    private val dao = AppDatabase.getInstance(context).memoryDao()
    private val scope = CoroutineScope(Dispatchers.IO)

    companion object {
        const val PROMOTION_THRESHOLD = 0.65f   // importance >= this -> LONG_TERM
        const val FORGET_THRESHOLD = 0.20f      // importance < this -> eligible to forget
        const val FORGET_AGE_MS = 7L * 24 * 60 * 60 * 1000  // 7 days
        const val WORKING_ACCESS_COUNT = 3      // accessed 3+ times -> promote to WORKING
    }

    data class MaintenanceResult(val promoted: Int, val workingPromoted: Int, val forgotten: Int)

    // ── Write ─────────────────────────────────────────────────────

    /** Save a new memory. Defaults to SHORT_TERM unless type is specified. */
    suspend fun remember(
        summary: String,
        category: String = "GENERAL",
        source: String = "CONVERSATION",
        importance: Float = 0.3f,
        confidence: Float = 1.0f,
        tags: String = "",
        memoryType: String = "SHORT_TERM"
    ): Long {
        val entity = MemoryEntity(
            summary = summary,
            category = category,
            source = source,
            memoryType = memoryType,
            importance = importance.coerceIn(0f, 1f),
            confidence = confidence.coerceIn(0f, 1f),
            tags = tags
        )
        return dao.insert(entity)
    }

    /** Save a memory tied to a specific event (always EPISODIC). */
    suspend fun rememberEvent(summary: String, tags: String = "", importance: Float = 0.5f): Long {
        return remember(
            summary = summary,
            category = "EVENT",
            source = "CONVERSATION",
            importance = importance,
            tags = tags,
            memoryType = "EPISODIC"
        )
    }

    // ── Access tracking ───────────────────────────────────────────

    /**
     * Call this whenever a memory is actually used/referenced in a response.
     * Repeated access signals real relevance -> auto-promotes SHORT_TERM to WORKING.
     */
    suspend fun touch(id: Int) {
        dao.recordAccess(id)
        val memory = dao.getById(id) ?: return
        if (memory.memoryType == "SHORT_TERM" && memory.accessCount + 1 >= WORKING_ACCESS_COUNT) {
            dao.changeType(id, "WORKING")
        }
    }

    // ── Maintenance (promotion + forgetting) ─────────────────────

    /**
     * Runs one maintenance pass. Should be called periodically
     * (e.g. on app start, or a background trigger added in a later phase).
     */
    suspend fun runMaintenance(): MaintenanceResult {
        var promoted = 0
        var workingPromoted = 0
        var forgotten = 0

        // 1. Promote high-importance SHORT_TERM -> LONG_TERM
        val promotionCandidates = dao.getPromotionCandidates(PROMOTION_THRESHOLD)
        for (memory in promotionCandidates) {
            dao.changeType(memory.id, "LONG_TERM")
            promoted++
        }

        // 2. Promote frequently-accessed SHORT_TERM -> WORKING
        val shortTerm = dao.getShortTerm()
        for (memory in shortTerm) {
            if (memory.accessCount >= WORKING_ACCESS_COUNT) {
                dao.changeType(memory.id, "WORKING")
                workingPromoted++
            }
        }

        // 3. Forget low-importance, stale SHORT_TERM memories
        val cutoff = System.currentTimeMillis() - FORGET_AGE_MS
        val forgettingCandidates = dao.getForgettingCandidates(FORGET_THRESHOLD, cutoff)
        for (memory in forgettingCandidates) {
            dao.delete(memory.id)
            forgotten++
        }

        return MaintenanceResult(promoted, workingPromoted, forgotten)
    }

    /** Fire-and-forget version for calling from UI code without suspend context. */
    fun runMaintenanceAsync() {
        scope.launch { runMaintenance() }
    }

    // ── Read ──────────────────────────────────────────────────────

    suspend fun getLongTermMemories(): List<MemoryEntity> = dao.getLongTerm()

    suspend fun getWorkingMemories(): List<MemoryEntity> = dao.getWorking()

    suspend fun getShortTermMemories(): List<MemoryEntity> = dao.getShortTerm()

    suspend fun getEpisodicMemories(): List<MemoryEntity> = dao.getEpisodic()

    suspend fun getByCategory(category: String): List<MemoryEntity> = dao.getByCategory(category)

    suspend fun getByTag(tag: String): List<MemoryEntity> = dao.getByTag(tag)
}
