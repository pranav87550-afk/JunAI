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
 * Phase 2: ImportanceEngine scoring wired into captureTurn().
 * Phase 3 (NEW): compressShortTermMemories() — once SHORT_TERM volume
 * crosses a batch size, oldest entries get condensed into one summary
 * memory via MemoryCompressor instead of being kept forever individually.
 */
class MemoryRepository(context: Context) {

    private val dao = AppDatabase.getInstance(context).memoryDao()
    private val scope = CoroutineScope(Dispatchers.IO)

    companion object {
        const val PROMOTION_THRESHOLD = 0.65f   // importance >= this -> LONG_TERM
        const val FORGET_THRESHOLD = 0.20f      // importance < this -> eligible to forget
        const val FORGET_AGE_MS = 7L * 24 * 60 * 60 * 1000  // 7 days
        const val WORKING_ACCESS_COUNT = 3      // accessed 3+ times -> promote to WORKING
        const val COMPRESSION_BATCH_SIZE = 20   // oldest N SHORT_TERM entries -> 1 summary
    }

    data class MaintenanceResult(
        val compressed: Int,
        val promoted: Int,
        val workingPromoted: Int,
        val forgotten: Int
    )

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

    /** Captures a real conversation turn, scored automatically via ImportanceEngine. */
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

    suspend fun touch(id: Int) {
        dao.recordAccess(id)
        val memory = dao.getById(id) ?: return
        if (memory.memoryType == "SHORT_TERM" && memory.accessCount + 1 >= WORKING_ACCESS_COUNT) {
            dao.changeType(id, "WORKING")
        }
    }

    // ── Compression (Phase 3) ──────────────────────────────────────

    /**
     * Condenses the oldest batch of SHORT_TERM memories into a single
     * summary memory once volume crosses COMPRESSION_BATCH_SIZE.
     * Repeats until fewer than the batch size remain (handles backlog).
     *
     * Detailed raw entries are only "temporary" by design — once folded
     * into a summary, the originals are removed; the summary itself
     * still goes through normal promotion/forgetting like any memory.
     */
    suspend fun compressShortTermMemories(): Int {
        var totalCompressed = 0

        while (true) {
            val shortTerm = dao.getShortTerm()
            if (shortTerm.size < COMPRESSION_BATCH_SIZE) break

            val batch = shortTerm.sortedBy { it.timestamp }.take(COMPRESSION_BATCH_SIZE)
            val summaryText = MemoryCompressor.compress(batch)
            if (summaryText.isBlank()) break

            val combinedTags = batch
                .flatMap { it.tags.split(",") }
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .distinct()
                .joinToString(",")

            val avgImportance = batch.map { it.importance }.average().toFloat()

            remember(
                summary = summaryText,
                category = "SUMMARY",
                source = "COMPRESSION",
                importance = avgImportance.coerceAtLeast(0.4f),
                tags = combinedTags,
                memoryType = "LONG_TERM"
            )

            for (memory in batch) {
                dao.delete(memory.id)
            }

            totalCompressed += batch.size
        }

        return totalCompressed
    }

    // ── Maintenance (compression + promotion + forgetting) ────────

    /** Runs one full maintenance pass. Triggered once per session via init block. */
    suspend fun runMaintenance(): MaintenanceResult {
        // 1. Compress old SHORT_TERM backlog into summaries first
        val compressed = compressShortTermMemories()

        var promoted = 0
        var workingPromoted = 0
        var forgotten = 0

        // 2. Promote high-importance SHORT_TERM -> LONG_TERM
        val promotionCandidates = dao.getPromotionCandidates(PROMOTION_THRESHOLD)
        for (memory in promotionCandidates) {
            dao.changeType(memory.id, "LONG_TERM")
            promoted++
        }

        // 3. Promote frequently-accessed SHORT_TERM -> WORKING
        val shortTerm = dao.getShortTerm()
        for (memory in shortTerm) {
            if (memory.accessCount >= WORKING_ACCESS_COUNT) {
                dao.changeType(memory.id, "WORKING")
                workingPromoted++
            }
        }

        // 4. Forget low-importance, stale SHORT_TERM memories
        val cutoff = System.currentTimeMillis() - FORGET_AGE_MS
        val forgettingCandidates = dao.getForgettingCandidates(FORGET_THRESHOLD, cutoff)
        for (memory in forgettingCandidates) {
            dao.delete(memory.id)
            forgotten++
        }

        return MaintenanceResult(compressed, promoted, workingPromoted, forgotten)
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
