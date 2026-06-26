package com.junai.app

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/**
 * UserPreferenceManager — Learns and remembers everything about the user.
 *
 * Stores data in SharedPreferences (no extra DB table needed).
 * Tracks: name, language style, frequently used features, conversation tone,
 * time-of-day patterns, and custom facts the user teaches Jun about themselves.
 *
 * Usage:
 *   val prefs = UserPreferenceManager(context)
 *   prefs.setUserName("Pranav")
 *   prefs.recordIntent("PLAY_MUSIC")
 *   val name = prefs.getUserName()   // "Pranav"
 */
class UserPreferenceManager(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("jun_user_prefs", Context.MODE_PRIVATE)

    // ─────────────────────────────────────────────
    // KEYS
    // ─────────────────────────────────────────────
    companion object {
        private const val KEY_USER_NAME         = "user_name"
        private const val KEY_LANGUAGE_STYLE    = "language_style"   // ENGLISH, HINGLISH, HINDI
        private const val KEY_TONE_STYLE        = "tone_style"       // CASUAL, FORMAL, FRIENDLY
        private const val KEY_INTENT_COUNTS     = "intent_counts"    // JSON object
        private const val KEY_HOUR_COUNTS       = "hour_counts"      // JSON object {0..23: count}
        private const val KEY_USER_FACTS        = "user_facts"       // JSON object {key: value}
        private const val KEY_TOTAL_MESSAGES    = "total_messages"
        private const val KEY_FIRST_SEEN        = "first_seen"
        private const val KEY_LAST_SEEN         = "last_seen"
        private const val KEY_PREFERRED_GREETING= "preferred_greeting"
        private const val KEY_EMOJI_STYLE       = "emoji_style"      // HIGH, LOW, NONE
        private const val KEY_RESPONSE_LENGTH   = "response_length"  // SHORT, MEDIUM, LONG

        // Language style constants
        const val STYLE_ENGLISH  = "ENGLISH"
        const val STYLE_HINGLISH = "HINGLISH"
        const val STYLE_HINDI    = "HINDI"

        // Tone constants
        const val TONE_CASUAL   = "CASUAL"
        const val TONE_FORMAL   = "FORMAL"
        const val TONE_FRIENDLY = "FRIENDLY"

        // Emoji style constants
        const val EMOJI_HIGH = "HIGH"
        const val EMOJI_LOW  = "LOW"
        const val EMOJI_NONE = "NONE"

        // Response length constants
        const val LENGTH_SHORT  = "SHORT"
        const val LENGTH_MEDIUM = "MEDIUM"
        const val LENGTH_LONG   = "LONG"
    }

    // ─────────────────────────────────────────────
    // USER IDENTITY
    // ─────────────────────────────────────────────

    fun setUserName(name: String) {
        prefs.edit().putString(KEY_USER_NAME, name.trim()).apply()
    }

    fun getUserName(): String? {
        return prefs.getString(KEY_USER_NAME, null)
    }

    /** Returns "Hey [name]!" if name is known, else "Hey!" */
    fun getGreeting(): String {
        val name = getUserName()
        val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
        val timeGreeting = when {
            hour in 5..11  -> "Good morning"
            hour in 12..16 -> "Good afternoon"
            hour in 17..20 -> "Good evening"
            else           -> "Hey"
        }
        return if (name != null) "$timeGreeting, $name!" else "$timeGreeting!"
    }

    // ─────────────────────────────────────────────
    // LANGUAGE & TONE DETECTION
    // ─────────────────────────────────────────────

    /**
     * Automatically detects and saves the user's language style
     * based on what they type. Call this on every user message.
     */
    fun detectAndSaveLanguageStyle(input: String) {
        val lower = input.lowercase()

        val hindiMarkers = listOf(
            "hai", "hain", "kya", "nahi", "kar", "ho", "mera", "tera",
            "karo", "batao", "dekho", "bolo", "suno", "jao", "aao",
            "theek", "accha", "bahut", "bilkul", "zaroor", "kyunki",
            "lekin", "aur", "ya", "toh", "bhi", "sirf", "abhi"
        )

        val hindiCount = hindiMarkers.count { lower.contains(it) }
        val totalWords = lower.split(" ").size.coerceAtLeast(1)
        val hindiRatio = hindiCount.toFloat() / totalWords

        val detectedStyle = when {
            hindiRatio >= 0.5f -> STYLE_HINDI
            hindiRatio >= 0.15f -> STYLE_HINGLISH
            else -> STYLE_ENGLISH
        }

        // Only update if we have enough signal (more than 3 words)
        if (totalWords >= 3) {
            val current = getLanguageStyle()
            // Blend with existing — don't flip on single message
            if (current == null || detectedStyle == current) {
                prefs.edit().putString(KEY_LANGUAGE_STYLE, detectedStyle).apply()
            } else {
                // If detected differs, store Hinglish as middle ground
                prefs.edit().putString(KEY_LANGUAGE_STYLE, STYLE_HINGLISH).apply()
            }
        }
    }

    fun getLanguageStyle(): String? {
        return prefs.getString(KEY_LANGUAGE_STYLE, null)
    }

    fun setToneStyle(tone: String) {
        prefs.edit().putString(KEY_TONE_STYLE, tone).apply()
    }

    fun getToneStyle(): String {
        return prefs.getString(KEY_TONE_STYLE, TONE_FRIENDLY) ?: TONE_FRIENDLY
    }

    fun setEmojiStyle(style: String) {
        prefs.edit().putString(KEY_EMOJI_STYLE, style).apply()
    }

    fun getEmojiStyle(): String {
        return prefs.getString(KEY_EMOJI_STYLE, EMOJI_HIGH) ?: EMOJI_HIGH
    }

    fun setResponseLength(length: String) {
        prefs.edit().putString(KEY_RESPONSE_LENGTH, length).apply()
    }

    fun getResponseLength(): String {
        return prefs.getString(KEY_RESPONSE_LENGTH, LENGTH_MEDIUM) ?: LENGTH_MEDIUM
    }

    // ─────────────────────────────────────────────
    // INTENT USAGE TRACKING
    // ─────────────────────────────────────────────

    /**
     * Records every intent the user triggers.
     * Builds a frequency map to understand user habits.
     * Call this whenever an intent is successfully handled.
     */
    fun recordIntent(intent: String) {
        val counts = getIntentCounts()
        counts[intent] = (counts[intent] ?: 0) + 1
        saveIntentCounts(counts)

        // Also record the hour of day
        val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
        recordHourActivity(hour)

        // Increment total messages
        val total = prefs.getInt(KEY_TOTAL_MESSAGES, 0)
        prefs.edit().putInt(KEY_TOTAL_MESSAGES, total + 1).apply()

        // Update last seen
        prefs.edit().putLong(KEY_LAST_SEEN, System.currentTimeMillis()).apply()

        // Set first seen if not already set
        if (prefs.getLong(KEY_FIRST_SEEN, 0L) == 0L) {
            prefs.edit().putLong(KEY_FIRST_SEEN, System.currentTimeMillis()).apply()
        }
    }

    /** Returns the user's top N most used intents */
    fun getTopIntents(n: Int = 3): List<String> {
        return getIntentCounts()
            .entries
            .sortedByDescending { it.value }
            .take(n)
            .map { it.key }
    }

    /** Returns the single most used intent */
    fun getFavoriteIntent(): String? {
        return getIntentCounts().maxByOrNull { it.value }?.key
    }

    /** Returns true if user frequently uses this intent (top 3) */
    fun isFrequentIntent(intent: String): Boolean {
        return getTopIntents(3).contains(intent)
    }

    private fun getIntentCounts(): MutableMap<String, Int> {
        val json = prefs.getString(KEY_INTENT_COUNTS, "{}") ?: "{}"
        val obj = JSONObject(json)
        val map = mutableMapOf<String, Int>()
        obj.keys().forEach { key -> map[key] = obj.getInt(key) }
        return map
    }

    private fun saveIntentCounts(counts: Map<String, Int>) {
        val obj = JSONObject()
        counts.forEach { (k, v) -> obj.put(k, v) }
        prefs.edit().putString(KEY_INTENT_COUNTS, obj.toString()).apply()
    }

    // ─────────────────────────────────────────────
    // TIME-OF-DAY PATTERNS
    // ─────────────────────────────────────────────

    private fun recordHourActivity(hour: Int) {
        val counts = getHourCounts()
        counts[hour] = (counts[hour] ?: 0) + 1
        val obj = JSONObject()
        counts.forEach { (k, v) -> obj.put(k.toString(), v) }
        prefs.edit().putString(KEY_HOUR_COUNTS, obj.toString()).apply()
    }

    /** Returns the hour (0-23) when user is most active */
    fun getMostActiveHour(): Int? {
        val counts = getHourCounts()
        return counts.maxByOrNull { it.value }?.key
    }

    /** Returns a human-readable active time description */
    fun getActiveTimeDescription(): String {
        val hour = getMostActiveHour() ?: return "throughout the day"
        return when {
            hour in 5..11  -> "in the morning"
            hour in 12..16 -> "in the afternoon"
            hour in 17..20 -> "in the evening"
            else           -> "at night"
        }
    }

    private fun getHourCounts(): MutableMap<Int, Int> {
        val json = prefs.getString(KEY_HOUR_COUNTS, "{}") ?: "{}"
        val obj = JSONObject(json)
        val map = mutableMapOf<Int, Int>()
        obj.keys().forEach { key -> map[key.toInt()] = obj.getInt(key) }
        return map
    }

    // ─────────────────────────────────────────────
    // USER FACTS (Personal Memory)
    // ─────────────────────────────────────────────

    /**
     * Stores a personal fact about the user.
     * Example: saveFact("city", "Mumbai")
     *          saveFact("job", "software engineer")
     *          saveFact("hobby", "music")
     */
    fun saveFact(key: String, value: String) {
        val facts = getUserFacts()
        facts[key.lowercase().trim()] = value.trim()
        val obj = JSONObject()
        facts.forEach { (k, v) -> obj.put(k, v) }
        prefs.edit().putString(KEY_USER_FACTS, obj.toString()).apply()
    }

    fun getFact(key: String): String? {
        return getUserFacts()[key.lowercase().trim()]
    }

    fun getAllFacts(): Map<String, String> {
        return getUserFacts()
    }

    fun removeFact(key: String) {
        val facts = getUserFacts()
        facts.remove(key.lowercase().trim())
        val obj = JSONObject()
        facts.forEach { (k, v) -> obj.put(k, v) }
        prefs.edit().putString(KEY_USER_FACTS, obj.toString()).apply()
    }

    /**
     * Tries to auto-extract personal facts from user input.
     * Handles patterns like "my name is X", "I am from X", "I work at X"
     */
    fun tryExtractFact(input: String) {
        val lower = input.lowercase().trim()

        val patterns = listOf(
            // Name patterns
            Pair(Regex("my name is ([\\w\\s]+)", RegexOption.IGNORE_CASE), "name"),
            Pair(Regex("i am ([\\w]+) and", RegexOption.IGNORE_CASE), "name"),
            Pair(Regex("call me ([\\w]+)", RegexOption.IGNORE_CASE), "name"),
            Pair(Regex("mera naam ([\\w\\s]+) hai", RegexOption.IGNORE_CASE), "name"),
            Pair(Regex("mujhe ([\\w]+) bolte hain", RegexOption.IGNORE_CASE), "name"),

            // Location patterns
            Pair(Regex("i(?:'m| am) from ([\\w\\s]+)", RegexOption.IGNORE_CASE), "city"),
            Pair(Regex("i live in ([\\w\\s]+)", RegexOption.IGNORE_CASE), "city"),
            Pair(Regex("main ([\\w\\s]+) mein rehta", RegexOption.IGNORE_CASE), "city"),
            Pair(Regex("main ([\\w\\s]+) se hun", RegexOption.IGNORE_CASE), "city"),

            // Job patterns
            Pair(Regex("i(?:'m| am) a ([\\w\\s]+)", RegexOption.IGNORE_CASE), "job"),
            Pair(Regex("i work as a ([\\w\\s]+)", RegexOption.IGNORE_CASE), "job"),
            Pair(Regex("i work at ([\\w\\s]+)", RegexOption.IGNORE_CASE), "job"),
            Pair(Regex("main ([\\w\\s]+) hun", RegexOption.IGNORE_CASE), "job"),

            // Age patterns
            Pair(Regex("i(?:'m| am) (\\d+) years old", RegexOption.IGNORE_CASE), "age"),
            Pair(Regex("meri umar (\\d+) hai", RegexOption.IGNORE_CASE), "age"),
            Pair(Regex("meri age (\\d+) hai", RegexOption.IGNORE_CASE), "age"),

            // Hobby/interest patterns
            Pair(Regex("i (?:like|love|enjoy) ([\\w\\s]+)", RegexOption.IGNORE_CASE), "interest"),
            Pair(Regex("my hobby is ([\\w\\s]+)", RegexOption.IGNORE_CASE), "hobby"),
            Pair(Regex("mujhe ([\\w\\s]+) pasand hai", RegexOption.IGNORE_CASE), "interest")
        )

        for ((regex, factKey) in patterns) {
            val match = regex.find(input)
            if (match != null) {
                val value = match.groupValues[1].trim()
                if (value.length in 2..40) {  // Reasonable length check
                    saveFact(factKey, value)
                    // If we found a name, also set it as the user name
                    if (factKey == "name") {
                        setUserName(value.split(" ").first())  // First word as name
                    }
                    break
                }
            }
        }
    }

    // ─────────────────────────────────────────────
    // SESSION & STATS
    // ─────────────────────────────────────────────

    fun getTotalMessages(): Int {
        return prefs.getInt(KEY_TOTAL_MESSAGES, 0)
    }

    fun getFirstSeenDate(): Long {
        return prefs.getLong(KEY_FIRST_SEEN, 0L)
    }

    fun getLastSeenDate(): Long {
        return prefs.getLong(KEY_LAST_SEEN, 0L)
    }

    fun getDaysSinceFirstUse(): Int {
        val first = getFirstSeenDate()
        if (first == 0L) return 0
        val diff = System.currentTimeMillis() - first
        return (diff / (1000 * 60 * 60 * 24)).toInt()
    }

    // ─────────────────────────────────────────────
    // PROFILE SUMMARY (for ResponseBuilder use)
    // ─────────────────────────────────────────────

    /**
     * Returns a complete snapshot of what Jun knows about the user.
     * Used by ResponseBuilder to personalize answers.
     */
    fun getUserProfile(): UserProfile {
        return UserProfile(
            name            = getUserName(),
            languageStyle   = getLanguageStyle() ?: STYLE_ENGLISH,
            toneStyle       = getToneStyle(),
            emojiStyle      = getEmojiStyle(),
            responseLength  = getResponseLength(),
            topIntents      = getTopIntents(5),
            activeTime      = getActiveTimeDescription(),
            totalMessages   = getTotalMessages(),
            daysSinceJoined = getDaysSinceFirstUse(),
            facts           = getAllFacts()
        )
    }

    // ─────────────────────────────────────────────
    // RESET
    // ─────────────────────────────────────────────

    /** Clears all user preference data */
    fun clearAll() {
        prefs.edit().clear().apply()
    }

    private fun getUserFacts(): MutableMap<String, String> {
        val json = prefs.getString(KEY_USER_FACTS, "{}") ?: "{}"
        val obj = JSONObject(json)
        val map = mutableMapOf<String, String>()
        obj.keys().forEach { key -> map[key] = obj.getString(key) }
        return map
    }
}

// ─────────────────────────────────────────────
// DATA CLASS — snapshot of user profile
// ─────────────────────────────────────────────

data class UserProfile(
    val name: String?,
    val languageStyle: String,
    val toneStyle: String,
    val emojiStyle: String,
    val responseLength: String,
    val topIntents: List<String>,
    val activeTime: String,
    val totalMessages: Int,
    val daysSinceJoined: Int,
    val facts: Map<String, String>
)
