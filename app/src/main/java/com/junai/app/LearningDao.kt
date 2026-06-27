package com.junai.app

import androidx.room.*

@Dao
interface LearningDao {

    // ==================== LEARNING ITEMS ====================
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertLearningItem(item: LearningItem)

    @Query("SELECT * FROM learning_items WHERE status = 'PENDING' ORDER BY dateAdded DESC")
    suspend fun getPendingItems(): List<LearningItem>

    @Query("SELECT * FROM learning_items ORDER BY dateAdded DESC")
    suspend fun getAllLearningItems(): List<LearningItem>

    @Query("UPDATE learning_items SET status = :status, lastUpdated = :time WHERE id = :id")
    suspend fun updateLearningStatus(id: Int, status: String, time: Long = System.currentTimeMillis())

    @Delete
    suspend fun deleteLearningItem(item: LearningItem)

    @Query("DELETE FROM learning_items WHERE status = 'TRAINED'")
    suspend fun clearTrainedItems()

    // ==================== KNOWLEDGE ITEMS ====================
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertKnowledge(item: KnowledgeItem)

    @Query("SELECT * FROM knowledge_items WHERE question = :question LIMIT 1")
    suspend fun getKnowledgeByQuestion(question: String): KnowledgeItem?

    @Query("SELECT * FROM knowledge_items ORDER BY timesAsked DESC")
    suspend fun getAllKnowledge(): List<KnowledgeItem>

    @Query("SELECT COUNT(*) FROM knowledge_items")
    suspend fun getKnowledgeCount(): Int

    @Query("UPDATE knowledge_items SET timesAsked = timesAsked + 1, lastUpdated = :time WHERE id = :id")
    suspend fun incrementTimesAsked(id: Int, time: Long = System.currentTimeMillis())

    // ── NEW: Feedback-driven confidence update ──
    @Query("""
        UPDATE knowledge_items 
        SET confidence = :confidence,
            timesCorrect = timesCorrect + CASE WHEN :incrementCorrect = 1 THEN 1 ELSE 0 END,
            lastUpdated = :time
        WHERE id = :id
    """)
    suspend fun updateConfidence(
        id: Int,
        confidence: Float,
        incrementCorrect: Int,
        time: Long = System.currentTimeMillis()
    )

    @Delete
    suspend fun deleteKnowledge(item: KnowledgeItem)

    @Query("DELETE FROM knowledge_items")
    suspend fun clearAllKnowledge()

    // ==================== COMMAND ITEMS ====================
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCommand(item: CommandItem)

    @Query("SELECT * FROM command_items ORDER BY timesUsed DESC")
    suspend fun getAllCommands(): List<CommandItem>

    @Query("SELECT * FROM command_items WHERE intent = :intent")
    suspend fun getCommandsByIntent(intent: String): List<CommandItem>

    @Query("SELECT COUNT(*) FROM command_items")
    suspend fun getCommandCount(): Int

    @Query("UPDATE command_items SET timesUsed = timesUsed + 1 WHERE id = :id")
    suspend fun incrementCommandUsed(id: Int)

    @Delete
    suspend fun deleteCommand(item: CommandItem)

    // ==================== SKILL ITEMS ====================
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSkill(item: SkillItem)

    @Query("SELECT * FROM skill_items WHERE isActive = 1")
    suspend fun getActiveSkills(): List<SkillItem>

    @Query("SELECT * FROM skill_items ORDER BY dateAdded DESC")
    suspend fun getAllSkills(): List<SkillItem>

    @Query("SELECT COUNT(*) FROM skill_items")
    suspend fun getSkillCount(): Int

    @Delete
    suspend fun deleteSkill(item: SkillItem)

    // ==================== ALIAS ITEMS ====================
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAlias(item: AliasItem)

    @Query("SELECT * FROM alias_items WHERE knowledgeId = :knowledgeId")
    suspend fun getAliasesForKnowledge(knowledgeId: Int): List<AliasItem>

    @Query("SELECT * FROM alias_items")
    suspend fun getAllAliases(): List<AliasItem>

    @Delete
    suspend fun deleteAlias(item: AliasItem)

    // ==================== RELATED QUESTIONS ====================
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRelatedQuestion(item: RelatedQuestionItem)

    @Query("SELECT * FROM related_questions WHERE questionId = :questionId")
    suspend fun getRelatedQuestions(questionId: Int): List<RelatedQuestionItem>

    @Delete
    suspend fun deleteRelatedQuestion(item: RelatedQuestionItem)

    // ==================== FAILURE LOG ====================
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertFailureLog(log: FailureLog)

    @Query("SELECT * FROM failure_log ORDER BY timestamp DESC LIMIT 100")
    suspend fun getRecentFailures(): List<FailureLog>

    @Query("SELECT COUNT(*) FROM failure_log")
    suspend fun getFailureCount(): Int

    @Query("SELECT failureReason, COUNT(*) as count FROM failure_log GROUP BY failureReason")
    suspend fun getFailureStats(): List<FailureReasonCount>

    @Query("DELETE FROM failure_log")
    suspend fun clearFailureLog()

    // ==================== STATISTICS ====================
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertStatistics(stats: LearningStatistics)

    @Query("SELECT * FROM learning_statistics WHERE id = 1")
    suspend fun getStatistics(): LearningStatistics?

    @Query("""
        UPDATE learning_statistics SET 
        knowledgeLearned = (SELECT COUNT(*) FROM knowledge_items),
        commandsLearned = (SELECT COUNT(*) FROM command_items),
        skillsLearned = (SELECT COUNT(*) FROM skill_items),
        failedQueries = (SELECT COUNT(*) FROM failure_log),
        lastUpdated = :time
        WHERE id = 1
    """)
    suspend fun refreshStatistics(time: Long = System.currentTimeMillis())
}

// Helper data class for failure stats
data class FailureReasonCount(
    val failureReason: String,
    val count: Int
)
