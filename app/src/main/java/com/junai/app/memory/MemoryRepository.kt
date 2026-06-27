package com.junai.app.memory

import android.content.Context
import com.junai.app.AppDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * MemoryRepository — Single entry point for Jun's hybrid memory system.
 *
 * Phase 1: storage layer (remember / promote / forget / read).
 * Phase 2 (NEW): wired to ImportanceEngine — captureTurn() scores real
 * conversation text automatically instead of relying on a fixed default
 * importance value. Low-value turns score near zero and get cleaned up
 * by runMaintenance() on their own — no manual filtering needed.
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

    /**
     * NEW (Phase 2) — Captures a real conversation turn and scores it
     * automatically via ImportanceEngine. Called from ChatIntentHandler
     * after every user message.
     *
     * @param isCorrection reserved for a future phase once FeedbackLearner's
     * thumbs-down signal is wired into this call — defaults to false for now.
     */
    suspend fun captureTurn(text: String, intentName: String, isCorrection: Boolean = false): Long {
        if (text.isBlank()) return -1L

        val category = mapIntentToCategory(intentName)
        val tag = category.lowercase()
        val repetitionCount = dao.getByTag(tag).size
        val topicFrequency = dao.getByCategory(category).size

        val score = ImportanceEngine.score(
            text = text,
            isCorrection = isCorrection,
            repetitionCount = repetitionCount,
            topicFrequency = topicFrequency
        )

        return remember(
            summary = text,
            category = category,
            source = "CONVERSATION",
            importance = score.total,
            tags = tag
        )
    }

    private fun mapIntentToCategory(intentName: String): String = when (intentName) {
        "USER_INFO"    -> "PREFERENCE"
        "LEARN_QA"     -> "FACT"
        "SET_REMINDER" -> "REMINDER"
        "CREATE_NOTE"  -> "TASK"
        else           -> "GENERAL"
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
     * Runs one maintenance pass. Triggered from ChatIntentHandler's init
     * block (fire-and-forget) so ranking/forgetting actually runs each session.
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
