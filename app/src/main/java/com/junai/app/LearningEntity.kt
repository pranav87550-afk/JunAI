package com.junai.app

import androidx.room.Entity
import androidx.room.PrimaryKey

// Table 1 - Learning Items (Unanswered / Failed queries)
@Entity(tableName = "learning_items")
data class LearningItem(
    @PrimaryKey(autoGenerate = true)
    val id: Int = 0,
    val question: String,
    val detectedIntent: String = "UNKNOWN",
    val suggestedIntent: String = "",
    val suggestedCategory: String = "",
    val confidence: Float = 0f,
    val failureReason: String = "NO_MATCH",
    val status: String = "PENDING", // PENDING, TRAINED, IGNORED
    val dateAdded: Long = System.currentTimeMillis(),
    val lastUpdated: Long = System.currentTimeMillis()
)

// Table 2 - Knowledge Items
@Entity(tableName = "knowledge_items")
data class KnowledgeItem(
    @PrimaryKey(autoGenerate = true)
    val id: Int = 0,
    val question: String,
    val answer: String,
    val category: String = "General",
    val confidence: Float = 1.0f,
    val timesAsked: Int = 0,
    val timesCorrect: Int = 0,
    val dateAdded: Long = System.currentTimeMillis(),
    val lastUpdated: Long = System.currentTimeMillis(),
    // IMPROVEMENT (Phase 1g): Negative Responses used to keep its own,
    // completely separate SharedPreferences list of flagged-bad Q&A pairs —
    // saving a correction there wrote a brand-new row here via
    // trainKnowledge(), but never touched (or even knew about) whichever
    // row this same question already had, and deleting from one screen had
    // no effect on the other. This flag makes a flagged item and its
    // Learning Center knowledge row the SAME row: true means "this answer
    // is known-bad and awaiting a correction" (shown in Negative
    // Responses); saving a correction flips it back to false in place.
    val needsCorrection: Boolean = false
)

// Table 3 - Command Items
@Entity(tableName = "command_items")
data class CommandItem(
    @PrimaryKey(autoGenerate = true)
    val id: Int = 0,
    val phrase: String,
    val intent: String,
    val target: String = "",
    val category: String = "GENERAL",
    val confidence: Float = 1.0f,
    val timesUsed: Int = 0,
    val dateAdded: Long = System.currentTimeMillis()
)

// Table 4 - Skill Items
@Entity(tableName = "skill_items")
data class SkillItem(
    @PrimaryKey(autoGenerate = true)
    val id: Int = 0,
    val phrase: String,
    val skillType: String, // MEMORY_SKILL, REMINDER_SKILL, PHONE_SKILL, MUSIC_SKILL, APP_CONTROL_SKILL
    val description: String = "",
    val isActive: Boolean = true,
    val dateAdded: Long = System.currentTimeMillis()
)

// Table 5 - Alias Items
@Entity(tableName = "alias_items")
data class AliasItem(
    @PrimaryKey(autoGenerate = true)
    val id: Int = 0,
    val knowledgeId: Int,
    val alias: String,
    val dateAdded: Long = System.currentTimeMillis()
)

// Table 6 - Related Questions
@Entity(tableName = "related_questions")
data class RelatedQuestionItem(
    @PrimaryKey(autoGenerate = true)
    val id: Int = 0,
    val questionId: Int,
    val relatedQuestion: String,
    val relationshipType: String = "RELATED", // RELATED, PARENT, CHILD
    val dateAdded: Long = System.currentTimeMillis()
)

// Table 7 - Failure Log
@Entity(tableName = "failure_log")
data class FailureLog(
    @PrimaryKey(autoGenerate = true)
    val id: Int = 0,
    val question: String,
    val detectedIntent: String = "UNKNOWN",
    val confidence: Float = 0f,
    val failureReason: String, // NO_MATCH, INTENT_NOT_FOUND, TARGET_NOT_FOUND, KNOWLEDGE_NOT_FOUND, LOW_CONFIDENCE
    val timestamp: Long = System.currentTimeMillis()
)

// Table 8 - Learning Statistics
@Entity(tableName = "learning_statistics")
data class LearningStatistics(
    @PrimaryKey
    val id: Int = 1,
    val knowledgeLearned: Int = 0,
    val commandsLearned: Int = 0,
    val skillsLearned: Int = 0,
    val totalIntents: Int = 0,
    val failedQueries: Int = 0,
    val totalQueries: Int = 0,
    val lastUpdated: Long = System.currentTimeMillis()
)
