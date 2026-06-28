package com.junai.app.memory

import android.content.Context
import com.junai.app.AppDatabase
import com.junai.app.learning.LearningEngineV2

/**
 * SemanticMemoryRepository — Connects SemanticFactExtractor (write path),
 * SemanticQueryResolver (read path), and SemanticFactDao (storage).
 *
 * Phase 14 (NEW): captureFact() now checks for an existing exact match
 * before inserting. If the same fact is stated again ("I like Python"
 * said 3 times), it REINFORCES the existing row's confidence via
 * LearningEngineV2 instead of creating duplicate rows that never improve.
 */
class SemanticMemoryRepository(context: Context) {

    private val dao = AppDatabase.getInstance(context).semanticFactDao()

    suspend fun captureFact(text: String): SemanticFactEntity? {
        val extracted = SemanticFactExtractor.extract(text) ?: return null

        val existing = dao.getExactFact(extracted.predicate, extracted.objectValue, extracted.category)
        if (existing != null) {
            val reinforced = existing.copy(
                confidence = LearningEngineV2.reinforce(existing.confidence),
                timestamp = System.currentTimeMillis()
            )
            dao.update(reinforced)
            return reinforced
        }

        val entity = SemanticFactEntity(
            predicate = extracted.predicate,
            objectValue = extracted.objectValue,
            category = extracted.category,
            confidence = extracted.confidence,
            sourceText = text
        )
        dao.insert(entity)
        return entity
    }

    suspend fun answerQuery(text: String): String? {
        val intent = SemanticQueryResolver.resolve(text) ?: return null

        for (predicate in intent.predicates) {
            val fact = if (intent.category != null) {
                dao.getLatestByCategoryAndPredicate(intent.category, predicate)
            } else {
                dao.getByPredicate(predicate).firstOrNull()
            }
            if (fact != null) return formatAnswer(fact)
        }
        return null
    }

    private fun formatAnswer(fact: SemanticFactEntity): String {
        val verb = when (fact.predicate) {
            "LIKES"    -> "you like"
            "DISLIKES" -> "you don't like"
            "PREFERS"  -> "you prefer"
            "USES"     -> "you use"
            "WORKS_ON" -> "you're working on"
            "WANTS"    -> "you want"
            "HAS"      -> "you have"
            "IS"       -> "you are"
            else       -> "you mentioned"
        }
        return "I remember $verb ${fact.objectValue} \uD83D\uDE42"
    }

    suspend fun getAllFacts(): List<SemanticFactEntity> = dao.getAll()
}
