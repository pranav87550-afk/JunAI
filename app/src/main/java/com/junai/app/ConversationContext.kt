package com.junai.app

/**
 * ConversationContext — Gives Jun short-term memory within a session.
 *
 * Tracks the last N messages so Jun can:
 * - Resolve pronouns ("it", "he", "she", "that", "woh", "yeh")
 * - Answer follow-up questions ("tell me more", "aur batao")
 * - Know what topic is being discussed right now
 * - Detect repeated confusion (same question asked twice = user not satisfied)
 *
 * This is a singleton — one shared instance for the whole session.
 * Cleared when user clears chat or app restarts.
 */
class ConversationContext {

    companion object {
        val instance = ConversationContext()
        private const val MAX_WINDOW = 10  // Remember last 10 exchanges
    }

    // ── Data ──────────────────────────────────────────────────────

    data class Turn(
        val userMessage: String,
        val junResponse: String,
        val intent: String,
        val entity: String,          // Main noun/target of this turn
        val timestamp: Long = System.currentTimeMillis()
    )

    private val history = ArrayDeque<Turn>(MAX_WINDOW)

    // Last resolved subject for pronoun replacement
    private var lastSubject: String = ""
    private var lastIntent: String = ""
    private var lastEntity: String = ""
    private var lastTopic: String = ""

    // Tracks repeated questions (confusion detection)
    private var lastUserMessage: String = ""
    private var sameMessageCount: Int = 0

    // ── Public API ────────────────────────────────────────────────

    /**
     * Call this after every exchange completes.
     * Records what was said and updates context state.
     */
    fun record(
        userMessage: String,
        junResponse: String,
        intent: String,
        entity: String = ""
    ) {
        // Confusion detection — same message repeated?
        val normalized = userMessage.lowercase().trim()
        if (normalized == lastUserMessage) {
            sameMessageCount++
        } else {
            sameMessageCount = 0
            lastUserMessage = normalized
        }

        // Update subject tracking
        if (entity.isNotEmpty()) {
            lastSubject = entity
            lastEntity  = entity
        }
        lastIntent = intent
        lastTopic  = extractTopic(userMessage, entity)

        // Slide window
        if (history.size >= MAX_WINDOW) history.removeFirst()
        history.addLast(Turn(userMessage, junResponse, intent, entity))
    }

    /**
     * Resolves pronouns and follow-up references in the new user input.
     * Call this BEFORE intent detection.
     *
     * Example:
     *   User: "who is Elon Musk?"  → Jun answers
     *   User: "how old is he?"     → resolved to "how old is Elon Musk?"
     */
    fun resolveInput(rawInput: String): String {
        var input = rawInput.trim()
        if (lastSubject.isEmpty()) return input

        val lower = input.lowercase()

        // English pronouns
        val pronounsHe   = listOf("he", "him", "his")
        val pronounsShe  = listOf("she", "her", "hers")
        val pronounsIt   = listOf("it", "its", "this", "that", "the same")
        val followUps    = listOf("tell me more", "explain more", "more details",
                                  "elaborate", "go on", "continue", "and then")

        // Hinglish pronouns
        val pronounsHinglish = listOf("woh", "yeh", "ye", "iske", "uske",
                                       "iska", "uska", "isi", "usi", "inke")

        val allPronouns = pronounsHe + pronounsShe + pronounsIt + pronounsHinglish

        // Check if it's a pure follow-up ("tell me more")
        if (followUps.any { lower.contains(it) } || lower == "aur batao" || lower == "aur?") {
            return if (lastTopic.isNotEmpty()) "tell me more about $lastTopic" else rawInput
        }

        // Replace pronouns with last known subject
        for (pronoun in allPronouns) {
            // Match whole word only
            val regex = Regex("\\b${Regex.escape(pronoun)}\\b", RegexOption.IGNORE_CASE)
            if (regex.containsMatchIn(input)) {
                input = regex.replace(input, lastSubject)
                break  // Replace first pronoun found only
            }
        }

        return input
    }

    /**
     * Returns true if the user seems confused —
     * asked the same thing twice, or said "I don't understand", etc.
     */
    fun isUserConfused(input: String): Boolean {
        val lower = input.lowercase()
        val normalized = lower.trim()
        val confusionPhrases = listOf(
            "i don't understand", "i dont understand", "what do you mean",
            "samajh nahi aaya", "kya matlab", "phir se batao", "dobara batao",
            "repeat", "say again", "again", "huh", "what?", "???"
        )
        // BUGFIX: previously just `sameMessageCount >= 2`, which reflects
        // repeats through the PREVIOUS turn — checked here before record()
        // has processed the CURRENT input. So after 3 identical repeats
        // (sameMessageCount reaches 2), the very next message — even a
        // completely different, freshly-reworded one — got flagged as
        // "confused" too, because the stale count was still >= 2. Require
        // the CURRENT input to also match lastUserMessage, so this only
        // fires while the repetition is still actually happening.
        val isStillRepeating = normalized == lastUserMessage && sameMessageCount >= 2
        return isStillRepeating || confusionPhrases.any { lower.contains(it) }
    }

    /**
     * Returns a simpler rephrased response hint when user is confused.
     * ChatIntentHandler can use this to give a shorter answer.
     */
    fun getConfusionHint(): String {
        return if (history.isNotEmpty()) {
            "Let me put it simply: ${history.last().junResponse.take(100)}..."
        } else {
            "Could you rephrase your question? I'll try my best!"
        }
    }

    // ── Context Getters ───────────────────────────────────────────

    fun getLastIntent(): String = lastIntent
    fun getLastEntity(): String = lastEntity
    fun getLastTopic(): String  = lastTopic
    fun getLastSubject(): String = lastSubject

    /** Returns last N user messages as a list */
    fun getRecentUserMessages(n: Int = 3): List<String> {
        return history.takeLast(n).map { it.userMessage }
    }

    /** Returns the full conversation window */
    fun getHistory(): List<Turn> = history.toList()

    /**
     * Returns true if current intent is a natural follow-up to the last one.
     * Example: GREET → HOW_ARE_YOU is natural.
     *          PLAY_MUSIC → NEXT_SONG is natural.
     */
    fun isNaturalFollowUp(currentIntent: String): Boolean {
        val naturalChains = mapOf(
            "GREET"        to listOf("HOW_ARE_YOU", "WHO_ARE_YOU", "THANK"),
            "PLAY_MUSIC"   to listOf("NEXT_SONG", "PREV_SONG", "PAUSE_MUSIC", "STOP_MUSIC"),
            "TELL_TIME"    to listOf("TELL_DATE", "SET_REMINDER"),
            "CREATE_NOTE"  to listOf("SHOW_NOTES"),
            "SET_REMINDER" to listOf("SHOW_REMINDER"),
            "SEARCH_WEB"   to listOf("SEARCH_WEB", "OPEN_APP"),
            "UNKNOWN"      to listOf("LEARN_QA")
        )
        val allowed = naturalChains[lastIntent] ?: return false
        return currentIntent in allowed
    }

    /** Clears all context — call when user clears chat */
    fun clear() {
        history.clear()
        lastSubject      = ""
        lastIntent       = ""
        lastEntity       = ""
        lastTopic        = ""
        lastUserMessage  = ""
        sameMessageCount = 0
    }

    // ── Private Helpers ───────────────────────────────────────────

    private fun extractTopic(message: String, entity: String): String {
        // Entity is most specific topic
        if (entity.isNotEmpty()) return entity

        // Remove question words to get topic
        val stopWords = setOf(
            "what", "who", "where", "when", "why", "how", "is", "are", "was",
            "tell", "me", "about", "the", "a", "an", "do", "does", "kya",
            "kaun", "kahan", "kab", "kyun", "kaise", "batao", "bata", "hai",
            "hain", "tha", "thi", "please", "pls", "can", "you", "mujhe"
        )
        val words = message.lowercase().split(Regex("\\s+"))
        val topicWords = words.filter { it !in stopWords && it.length > 2 }
        return topicWords.take(3).joinToString(" ")
    }
}
