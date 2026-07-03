package com.junai.app

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class LearningRepository(private val context: Context) {

    private val db = AppDatabase.getInstance(context)
    private val learningDao = db.learningDao()
    private val knowledgeDao = db.knowledgeDao()

    // ==================== FAILURE LOGGING ====================

    suspend fun logFailure(
        question: String,
        detectedIntent: String = "UNKNOWN",
        confidence: Float = 0f,
        failureReason: String = "NO_MATCH"
    ) {
        // BUGFIX: was storing the raw, untrimmed `question` — if the
        // original phrase (whatever the user first typed) had incidental
        // leading/trailing whitespace, every later comparison against it
        // (the dedup check right below, AND deleteLearningItemByQuestion
        // when the trained item is later deleted) had to match that exact
        // whitespace too. Trimming once here, at the single insertion
        // point, keeps every downstream comparison consistent.
        val trimmedQuestion = question.trim()

        // Failure log mein save karo
        learningDao.insertFailureLog(
            FailureLog(
                question = trimmedQuestion,
                detectedIntent = detectedIntent,
                confidence = confidence,
                failureReason = failureReason
            )
        )

        // Learning item mein bhi add karo (PENDING status)
        val existing = learningDao.getAllLearningItems()
            .any { it.question.trim().lowercase() == trimmedQuestion.lowercase() }

        if (!existing) {
            learningDao.insertLearningItem(
                LearningItem(
                    question = trimmedQuestion,
                    detectedIntent = detectedIntent,
                    confidence = confidence,
                    failureReason = failureReason,
                    suggestedIntent = suggestIntent(trimmedQuestion),
                    suggestedCategory = suggestCategory(trimmedQuestion)
                )
            )
        }

        // Stats update karo
        refreshStats()
    }

    

    // ==================== KNOWLEDGE TRAINING ====================

    suspend fun trainKnowledge(
        question: String,
        answer: String,
        category: String = "General",
        aliases: List<String> = emptyList()
    ) {
        // Knowledge table mein save
        val knowledge = KnowledgeItem(
            question = question.lowercase().trim(),
            answer = answer,
            category = category
        )
        learningDao.insertKnowledge(knowledge)

        // Old knowledge table mein bhi save (backward compatible)
        knowledgeDao.insert(
            KnowledgeEntity(
                question = question.lowercase().trim(),
                answer = answer,
                category = category
            )
        )

        // Aliases save karo
        val savedKnowledge = learningDao.getKnowledgeByQuestion(question.lowercase().trim())
        savedKnowledge?.let { k ->
            aliases.forEach { alias ->
                learningDao.insertAlias(
                    AliasItem(
                        knowledgeId = k.id,
                        alias = alias.lowercase().trim()
                    )
                )
            }
        }

        // Mark learning item as trained
        val pendingItems = learningDao.getAllLearningItems()
        pendingItems.firstOrNull {
            it.question.lowercase() == question.lowercase()
        }?.let {
            learningDao.updateLearningStatus(it.id, "TRAINED")
        }

        refreshStats()
    }

    suspend fun addRelatedQuestion(knowledgeId: Int, relatedQuestion: String, type: String = "RELATED") {
    learningDao.insertRelatedQuestion(
        RelatedQuestionItem(
            questionId = knowledgeId,
            relatedQuestion = relatedQuestion,
            relationshipType = type
        )
    )
}

suspend fun trainKnowledgeWithChain(
    question: String,
    answer: String,
    category: String = "General",
    aliases: List<String> = emptyList(),
    relatedQuestions: List<String> = emptyList()
) {
    trainKnowledge(question, answer, category, aliases)
    val saved = learningDao.getKnowledgeByQuestion(question.lowercase().trim())
    saved?.let { k ->
        relatedQuestions.forEach { related ->
            addRelatedQuestion(k.id, related)
        }
    }
}

    // ==================== COMMAND TRAINING ====================

    suspend fun trainCommand(
        phrase: String,
        intent: String,
        target: String = "",
        category: String = "GENERAL"
    ) {
        learningDao.insertCommand(
            CommandItem(
                phrase = phrase.lowercase().trim(),
                intent = intent,
                target = target,
                category = category
            )
        )

        // Mark as trained
        val pendingItems = learningDao.getAllLearningItems()
        pendingItems.firstOrNull {
            it.question.lowercase() == phrase.lowercase()
        }?.let {
            learningDao.updateLearningStatus(it.id, "TRAINED")
        }

        refreshStats()
    }

    // ==================== SKILL TRAINING ====================

    suspend fun trainSkill(
        phrase: String,
        skillType: String,
        description: String = ""
    ) {
        learningDao.insertSkill(
            SkillItem(
                phrase = phrase.lowercase().trim(),
                skillType = skillType,
                description = description
            )
        )
        refreshStats()
    }

    // ==================== SEARCH ====================

    suspend fun findAnswer(query: String): SearchResult {
        val q = query.lowercase().trim()

        // 1. Exact match — Knowledge Items
        val exactKnowledge = learningDao.getKnowledgeByQuestion(q)
        if (exactKnowledge != null) {
            learningDao.incrementTimesAsked(exactKnowledge.id)
            return SearchResult(
                answer = exactKnowledge.answer,
                confidence = 100f,
                matchType = "EXACT",
                relatedQuestions = getRelatedQuestions(exactKnowledge.id)
            )
        }

        // 2. Old knowledge table exact match
        val oldExact = knowledgeDao.getAnswer(q)
        if (oldExact != null) {
            return SearchResult(
                answer = oldExact,
                confidence = 100f,
                matchType = "EXACT"
            )
        }

        // 3. Alias match
        val allAliases = learningDao.getAllAliases()
        val aliasMatch = allAliases.firstOrNull {
            it.alias.lowercase() == q
        }
        if (aliasMatch != null) {
            val knowledge = learningDao.getAllKnowledge()
                .firstOrNull { it.id == aliasMatch.knowledgeId }
            if (knowledge != null) {
                return SearchResult(
                    answer = knowledge.answer,
                    confidence = 95f,
                    matchType = "ALIAS"
                )
            }
        }

        // 4. Fuzzy match
        val fuzzyResult = fuzzySearch(q)
        if (fuzzyResult != null && fuzzyResult.confidence >= 70f) {
            return fuzzyResult
        }

        // 5. No match
        return SearchResult(
            answer = null,
            confidence = 0f,
            matchType = "NO_MATCH"
        )
    }

    private suspend fun fuzzySearch(query: String): SearchResult? {
        val allKnowledge = learningDao.getAllKnowledge()
        val oldKnowledge = knowledgeDao.getAll()

        var bestAnswer: String? = null
        var bestScore = 0f
        var bestId = -1

        // New knowledge table
        for (item in allKnowledge) {
            val score = calculateSimilarity(query, item.question)
            if (score > bestScore) {
                bestScore = score
                bestAnswer = item.answer
                bestId = item.id
            }
        }

        // Old knowledge table
        for (item in oldKnowledge) {
            val score = calculateSimilarity(query, item.question)
            if (score > bestScore) {
                bestScore = score
                bestAnswer = item.answer
            }
        }

        // Alias fuzzy match
        val allAliases = learningDao.getAllAliases()
        for (alias in allAliases) {
            val score = calculateSimilarity(query, alias.alias)
            if (score > bestScore) {
                bestScore = score
                val knowledge = allKnowledge.firstOrNull { it.id == alias.knowledgeId }
                bestAnswer = knowledge?.answer
                bestId = alias.knowledgeId
            }
        }

        if (bestAnswer == null) return null

        val relatedQuestions = if (bestId >= 0) getRelatedQuestions(bestId) else emptyList()

        return SearchResult(
            answer = bestAnswer,
            confidence = bestScore,
            matchType = if (bestScore >= 90f) "FUZZY_HIGH"
                       else if (bestScore >= 75f) "FUZZY_MEDIUM"
                       else "FUZZY_LOW",
            relatedQuestions = relatedQuestions
        )
    }

    private fun calculateSimilarity(query: String, stored: String): Float {
        val q = query.lowercase().trim()
        val s = stored.lowercase().trim()

        if (q == s) return 100f

        // BUGFIX: this used to return a flat 90 for ANY containment match,
        // regardless of how short the strings were. A short trigger phrase
        // like "oka" is a substring of countless unrelated stored
        // questions purely by coincidence (or the reverse: a short stored
        // question like "ok" is a substring of almost anything). That's
        // exactly why deleting a learned knowledge/command/macro and
        // re-typing the SAME short phrase could still get "answered" —
        // this fuzzy step grabbed a totally unrelated entry with a
        // false-positive 90% score, so the real "I don't know this
        // anymore" path (logFailure → Pending tab) never ran, and which
        // unrelated entry won depended on whatever else happened to be in
        // the DB — hence the "sometimes works, sometimes doesn't"
        // inconsistency. Only trust a bare containment match once there's
        // enough text on BOTH sides for it to mean something; anything
        // shorter falls through to the word-overlap scoring below, which
        // isn't prone to single-short-string coincidental collisions.
        val minMeaningfulLen = 5
        if (q.length >= minMeaningfulLen && s.length >= minMeaningfulLen && (s.contains(q) || q.contains(s))) {
            // Scale by how much of the longer string is actually covered —
            // "turn on wifi" fully inside "please turn on wifi for me"
            // should score high, but a small overlap inside a much longer
            // string shouldn't get the same near-max score.
            val shorter = minOf(q.length, s.length)
            val longer = maxOf(q.length, s.length)
            return (60f + 30f * (shorter.toFloat() / longer.toFloat())).coerceAtMost(90f)
        }

        val qWords = q.split(" ").toSet()
        val sWords = s.split(" ").toSet()
        val common = qWords.intersect(sWords).size
        val total = qWords.union(sWords).size
        var wordScore = if (total == 0) 0f else (common * 100f / total)

        // Same coincidental-collision risk applies to a single short word
        // matching a single word buried in an otherwise unrelated longer
        // question — down-weight one-word queries unless the word itself
        // is reasonably long (avoids "oka" scoring 100 against some
        // unrelated multi-word question that also happens to contain it
        // as an isolated token).
        if (qWords.size == 1 && (qWords.first().length < minMeaningfulLen)) {
            wordScore *= 0.5f
        }

        val importantWords = setOf(
            "naam", "name", "kya", "kaisa", "batao", "bata",
            "tera", "tumhara", "your", "what", "how", "why",
            "when", "where", "who", "which"
        )
        val qImportant = qWords.intersect(importantWords)
        val sImportant = sWords.intersect(importantWords)
        val keywordBonus = if (qImportant.intersect(sImportant).isNotEmpty()) 15f else 0f

        return (wordScore + keywordBonus).coerceAtMost(100f)
    }

    private suspend fun getRelatedQuestions(knowledgeId: Int): List<String> {
        return learningDao.getRelatedQuestions(knowledgeId)
            .map { it.relatedQuestion }
    }

    // ==================== STATS ====================

    private suspend fun refreshStats() {
        val stats = learningDao.getStatistics()
        if (stats == null) {
            learningDao.insertStatistics(LearningStatistics())
        }
        learningDao.refreshStatistics()
    }

    suspend fun getStats(): LearningStatistics? {
        return learningDao.getStatistics()
    }

    suspend fun getAchievements(): List<String> {
    val stats = getStats() ?: return emptyList()
    val achievements = mutableListOf<String>()

    // Knowledge achievements
    when {
        stats.knowledgeLearned >= 500 -> achievements.add("🏆 Knowledge Master — 500+ questions!")
        stats.knowledgeLearned >= 100 -> achievements.add("🥇 Knowledge Expert — 100+ questions!")
        stats.knowledgeLearned >= 50 -> achievements.add("🥈 Knowledge Learner — 50+ questions!")
        stats.knowledgeLearned >= 10 -> achievements.add("🥉 Knowledge Starter — 10+ questions!")
        stats.knowledgeLearned >= 1 -> achievements.add("⭐ First Knowledge Added!")
    }

    // Command achievements
    when {
        stats.commandsLearned >= 100 -> achievements.add("⚡ Command Master — 100+ commands!")
        stats.commandsLearned >= 50 -> achievements.add("⚡ Command Expert — 50+ commands!")
        stats.commandsLearned >= 10 -> achievements.add("⚡ Command Learner — 10+ commands!")
        stats.commandsLearned >= 1 -> achievements.add("⭐ First Command Trained!")
    }

    // Failure achievements (weakness tracking)
    when {
        stats.failedQueries >= 100 -> achievements.add("📊 100+ failures — Jun needs more training!")
        stats.failedQueries >= 50 -> achievements.add("📊 50+ failures — Keep teaching Jun!")
        stats.failedQueries >= 10 -> achievements.add("📊 10+ failures logged!")
    }

    // Combo achievements
    if (stats.knowledgeLearned >= 10 && stats.commandsLearned >= 5) {
        achievements.add("🎯 Balanced Trainer — Knowledge + Commands!")
    }

    if (achievements.isEmpty()) {
        achievements.add("🌱 Just getting started! Teach Jun something!")
    }

    return achievements
    }

    // ==================== SUGGESTIONS ====================

    private fun suggestIntent(question: String): String {
        val lower = question.lowercase()
        return when {
            lower.contains("open") || lower.contains("launch") || lower.contains("start") -> "OPEN_APP"
            lower.contains("call") || lower.contains("phone") -> "CALL_CONTACT"
            lower.contains("play") || lower.contains("music") || lower.contains("song") -> "PLAY_MUSIC"
            lower.contains("remind") || lower.contains("alarm") -> "SET_REMINDER"
            lower.contains("note") -> "CREATE_NOTE"
            lower.contains("search") || lower.contains("find") -> "SEARCH_WEB"
            lower.contains("time") -> "TELL_TIME"
            lower.contains("date") -> "TELL_DATE"
            lower.contains("battery") -> "TELL_BATTERY"
            else -> "UNKNOWN"
        }
    }

    private fun suggestCategory(question: String): String {
        val lower = question.lowercase()
        return when {
            lower.contains("what is") || lower.contains("kya hai") -> "Knowledge"
            lower.contains("how to") || lower.contains("kaise") -> "HowTo"
            lower.contains("open") || lower.contains("launch") -> "AppControl"
            lower.contains("call") || lower.contains("message") -> "Communication"
            lower.contains("play") || lower.contains("music") -> "Entertainment"
            lower.contains("remind") || lower.contains("alarm") -> "Productivity"
            else -> "General"
        }
    }

    // ==================== PENDING ITEMS ====================

    suspend fun getPendingItems(): List<LearningItem> {
        return learningDao.getPendingItems()
    }

    suspend fun updateStatus(id: Int, status: String) {
    learningDao.updateLearningStatus(id, status)
    }

    suspend fun getAllLearningItems(): List<LearningItem> {
        return learningDao.getAllLearningItems()
    }

    suspend fun getFailureLog(): List<FailureLog> {
        return learningDao.getRecentFailures()
    }

    suspend fun getAllKnowledge(): List<KnowledgeItem> {
        return learningDao.getAllKnowledge()
    }

    suspend fun getAllCommands(): List<CommandItem> {
        return learningDao.getAllCommands()
    }

    // ==================== FEEDBACK LEARNING ====================

    suspend fun updateKnowledgeConfidence(id: Int, confidence: Float, incrementCorrect: Boolean) {
        db.learningDao().updateConfidence(
            id = id,
            confidence = confidence,
            incrementCorrect = if (incrementCorrect) 1 else 0
        )
        refreshStats()
    }

    suspend fun getAllSkills(): List<SkillItem> {
        return learningDao.getAllSkills()
    }
}

// Search result data class
data class SearchResult(
    val answer: String?,
    val confidence: Float,
    val matchType: String,
    val relatedQuestions: List<String> = emptyList()
)
