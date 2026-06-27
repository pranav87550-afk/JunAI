package com.junai.app

/**
 * IntentDetector — Understands what the user wants, even when phrased differently.
 *
 * Improvements over v1:
 * - Multi-signal scoring (exact > startsWith > word overlap > partial)
 * - Hinglish normalization before matching
 * - Synonym expansion (maps informal words to canonical forms)
 * - Confidence levels: HIGH (80+), MEDIUM (50-79), LOW (<50)
 * - Context hints passed back for ResponseBuilder to use
 * - Negation detection ("don't play music" → not PLAY_MUSIC)
 * - Entity extraction built into detect() result
 */
object IntentDetector {

    enum class Intent {
        OPEN_APP,
        CALL_CONTACT,
        SEND_MESSAGE,
        PLAY_MUSIC,
        PAUSE_MUSIC,
        NEXT_SONG,
        PREV_SONG,
        STOP_MUSIC,
        SET_REMINDER,
        CREATE_NOTE,
        SHOW_NOTES,
        SHOW_TODO,
        SHOW_CALCULATOR,
        SHOW_DRAW,
        SHOW_TRANSLATOR,
        SHOW_REMINDER,
        SHOW_SETTINGS,
        SHOW_MUSIC,
        SHOW_UNANSWERED,
        SHOW_VOICE_COMMANDS,
        SHOW_DATA_MANAGEMENT,
        CLEAR_CHAT,
        SEARCH_WEB,
        LEARN_QA,
        TELL_TIME,
        TELL_DATE,
        TELL_BATTERY,
        TELL_JOKE,
        FLIP_COIN,
        ROLL_DICE,
        GREET,
        HOW_ARE_YOU,
        THANK,
        WHO_ARE_YOU,
        USER_INFO,       // User telling Jun something about themselves
        UNKNOWN
    }

    data class IntentResult(
        val intent: Intent,
        val params: Map<String, String> = emptyMap(),
        val confidence: Int = 100,
        val confidenceLevel: ConfidenceLevel = ConfidenceLevel.HIGH,
        val normalizedInput: String = "",   // Cleaned input after normalization
        val extractedEntity: String = ""    // The key noun/target extracted
    )

    enum class ConfidenceLevel { HIGH, MEDIUM, LOW, NONE }

    // ─────────────────────────────────────────────────────────────
    // NORMALIZATION — maps informal / misspelled words to canonical
    // ─────────────────────────────────────────────────────────────

    private val synonymMap = mapOf(
        // Greetings
        "hii" to "hi", "helo" to "hello", "hlo" to "hi", "heya" to "hey",
        "wassup" to "what's up", "sup" to "hi", "yo" to "hi",

        // Hinglish action words → English
        "kholo" to "open", "kholna" to "open", "chalao" to "open",
        "chalu" to "start", "chala" to "start", "shuru" to "start",
        "band" to "stop", "roko" to "pause", "rok" to "pause",
        "bajao" to "play", "baja" to "play", "sunao" to "play",
        "lagao" to "open", "lao" to "open",
        "batao" to "tell", "bata" to "tell", "bolo" to "tell",
        "dikhao" to "show", "dekho" to "show", "dikha" to "show",
        "likh" to "write", "likho" to "note", "save" to "save",
        "dhundho" to "search", "dhundo" to "search", "khojo" to "search",
        "call" to "call", "phone" to "call", "ring" to "call",
        "bhejo" to "send", "bhej" to "send",
        "yaad" to "remind", "yaad dila" to "remind",
        "hatao" to "clear", "saaf" to "clear", "delete" to "clear",

        // Common Hindi words
        "gaana" to "song", "gana" to "song", "sangeet" to "music",
        "waqt" to "time", "samay" to "time", "ghadi" to "time",
        "aaj" to "today", "din" to "day", "tarik" to "date",
        "battery" to "battery", "charge" to "battery",
        "hisaab" to "calculate", "calculation" to "calculate",
        "anuvad" to "translate", "bhasha" to "translate",
        "mazak" to "joke", "chutkula" to "joke",
        "sikka" to "coin", "toss" to "coin",
        "pasa" to "dice",

        // Typo corrections
        "remaind" to "remind", "remider" to "reminder", "remaindar" to "reminder",
        "calender" to "calendar", "calander" to "calendar",
        "mesage" to "message", "messege" to "message",
        "serch" to "search", "searh" to "search",
        "calculater" to "calculator", "calculatr" to "calculator",
        "seting" to "settings", "setings" to "settings",
        "translater" to "translator", "tranlate" to "translate"
    )

    // Negation words — if present, we lower confidence and flag it
    private val negationWords = setOf(
        "don't", "dont", "do not", "not", "no", "stop", "cancel",
        "nahi", "mat", "mत", "band karo nahi"
    )

    // ─────────────────────────────────────────────────────────────
    // INTENT PHRASES — patterns for each intent
    // More specific phrases listed first for better matching
    // ─────────────────────────────────────────────────────────────

    private val intentPhrases = mapOf(

        Intent.WHO_ARE_YOU to listOf(
            "who are you", "what are you", "introduce yourself", "tell me about yourself",
            "tum kaun ho", "aap kaun ho", "jun kaun hai", "what is jun",
            "are you an ai", "are you a robot", "are you human",
            "what can you do", "tum kya kar sakte ho", "tumhari abilities",
            "your name", "tumhara naam", "apna naam batao"
        ),

        Intent.USER_INFO to listOf(
            "my name is", "i am", "call me", "i'm", "mera naam",
            "mujhe", "main hun", "i live in", "i'm from", "i work",
            "my job", "my hobby", "i like", "i love", "meri umar",
            "my age", "i am from", "main rehta", "main se hun"
        ),

        Intent.GREET to listOf(
            "good morning", "good evening", "good night", "good afternoon",
            "hello there", "hey there", "hi there",
            "hello", "hi", "hey", "namaste", "namaskar",
            "kya haal", "kaise ho", "kaisi ho", "kya chal raha",
            "howdy", "greetings", "salut", "ola", "kya scene"
        ),

        Intent.HOW_ARE_YOU to listOf(
            "how are you doing", "how are you feeling", "how is it going",
            "how are you", "how r u", "how are u",
            "tum kaise ho", "aap kaise hain", "kya haal hai",
            "sab theek", "theek ho", "all good", "you ok", "you okay",
            "kaisa feel", "sab badhiya"
        ),

        Intent.THANK to listOf(
            "thank you so much", "thanks a lot", "many thanks",
            "thank you", "thanks", "thankyou", "thank u", "thx", "ty",
            "shukriya", "dhanyawad", "bahut acha", "great job", "well done",
            "shabash", "amazing", "awesome", "brilliant", "fantastic",
            "good job", "nice one", "perfect", "superb", "mast", "ek number",
            "too good", "bahut badhiya", "wah", "wah wah"
        ),

        Intent.OPEN_APP to listOf(
            "open the app", "launch the app", "start the app",
            "open", "launch", "start", "run", "load",
            "chalu kar", "open kar", "start kar", "launch kar",
            "on kar", "activate", "kholna hai", "open karna hai"
        ),

        Intent.CALL_CONTACT to listOf(
            "give a call to", "make a call to", "call karna hai",
            "call kar", "phone kar", "phone karo", "call karo",
            "baat karni hai", "call lagao", "phone lagao", "ring kar",
            "call", "phone", "ring", "dial", "contact"
        ),

        Intent.SEND_MESSAGE to listOf(
            "send a message to", "send message to", "message bhejo",
            "message karo", "sms karo", "text karo",
            "message kar", "send message", "send msg",
            "message", "sms", "text", "whatsapp", "msg"
        ),

        Intent.PLAY_MUSIC to listOf(
            "play some music", "play a song", "start the music",
            "play music", "music play", "play song", "music on",
            "gaana chalu", "music chalu", "music lagao", "song lagao",
            "gaana lagao", "play karo", "music start", "gana baja",
            "music chalao", "song chalao"
        ),

        Intent.PAUSE_MUSIC to listOf(
            "pause the music", "pause the song",
            "pause music", "music pause", "music band", "gaana band",
            "gaana roko", "pause song", "music rok do",
            "band karo music"
        ),

        Intent.NEXT_SONG to listOf(
            "play next song", "go to next song", "skip this song",
            "next song", "agla gaana", "next track", "skip song",
            "aage jao", "skip", "next wala", "forward song",
            "next", "aage"
        ),

        Intent.PREV_SONG to listOf(
            "play previous song", "go back to previous",
            "previous song", "pichla gaana", "prev song", "back song",
            "peeche jao", "pichle gaane pe jao", "last song",
            "previous", "peeche", "prev", "wapas"
        ),

        Intent.STOP_MUSIC to listOf(
            "stop the music", "turn off music", "music band karo",
            "stop music", "music stop", "gaana band", "music off",
            "stop song", "music khatam"
        ),

        Intent.SET_REMINDER to listOf(
            "set a reminder for", "remind me to", "set reminder at",
            "set reminder", "reminder set", "remind me", "alarm set",
            "yaad dila", "reminder lagao", "alarm lagao", "remind kar",
            "reminder banana hai", "mujhe yaad dilana",
            "set alarm", "create reminder", "add reminder"
        ),

        Intent.CREATE_NOTE to listOf(
            "create a new note", "write a note about", "save a note",
            "create note", "note banao", "note likho", "save note",
            "note bana", "note kar", "note banana hai",
            "add note", "new note", "note save karo", "likh lo"
        ),

        Intent.SHOW_NOTES to listOf(
            "show me my notes", "open my notes", "let me see my notes",
            "show notes", "open notes", "notes dekho", "meri notes",
            "notes kholo", "notes dikhao", "note dikhao",
            "mere notes", "notes screen"
        ),

        Intent.SHOW_TODO to listOf(
            "show my todo list", "open my tasks", "show tasks",
            "show todo", "open todo", "todo list", "mera todo",
            "todo dikhao", "kaam ki list", "task list",
            "tasks dikhao", "meri list", "todo screen"
        ),

        Intent.SHOW_CALCULATOR to listOf(
            "open the calculator", "i need to calculate",
            "show calculator", "open calculator", "calculator kholo",
            "calculator dikhao", "calc open", "calculate",
            "calculator lao", "calculator screen", "calc", "hisaab"
        ),

        Intent.SHOW_DRAW to listOf(
            "i want to draw", "open the drawing app", "let me sketch",
            "show draw", "open draw", "drawing kholo", "draw karna hai",
            "drawing app", "sketch", "draw", "drawing open",
            "draw screen", "paint", "canvas"
        ),

        Intent.SHOW_TRANSLATOR to listOf(
            "i need to translate", "help me translate", "open translator",
            "show translator", "translate", "translator kholo",
            "translation", "translator open", "translate karo",
            "translator screen", "anuvad", "bhasha"
        ),

        Intent.SHOW_REMINDER to listOf(
            "show my reminders", "let me see my reminders",
            "show reminder", "open reminder", "reminder dekho",
            "reminder dikhao", "mera reminder", "reminder screen",
            "reminder kholo", "alarm dikhao"
        ),

        Intent.SHOW_SETTINGS to listOf(
            "open settings", "go to settings", "change settings",
            "show settings", "settings kholo", "settings dikhao",
            "setting", "settings open", "settings screen", "configuration"
        ),

        Intent.SHOW_MUSIC to listOf(
            "open music player", "go to music", "show music player",
            "show music", "open music", "jun dj", "music app",
            "music dikhao", "dj kholo", "music screen",
            "music player", "songs dikhao", "playlist"
        ),

        Intent.SHOW_UNANSWERED to listOf(
            "show unanswered questions", "what don't you know",
            "show unanswered", "open unanswered", "unanswered questions",
            "unanswered dikhao", "jo nahi pata", "unanswered screen"
        ),

        Intent.SHOW_VOICE_COMMANDS to listOf(
            "what commands do you know", "show all commands",
            "show voice commands", "voice commands", "commands list",
            "commands dikhao", "kya kya bol sakta hun", "commands screen"
        ),

        Intent.SHOW_DATA_MANAGEMENT to listOf(
            "show data management", "manage my data",
            "show data", "data management", "open data",
            "knowledge count", "data screen", "import data"
        ),

        Intent.CLEAR_CHAT to listOf(
            "clear all messages", "delete all chat", "wipe chat",
            "clear chat", "chat clear", "chat delete", "chat hatao",
            "chat saaf karo", "messages delete", "chat saaf",
            "delete chat", "sab messages hatao"
        ),

        Intent.SEARCH_WEB to listOf(
            "search the web for", "look up on google", "find information about",
            "search for", "google this", "search", "dhundho", "find",
            "look up", "tell me about", "web search",
            "internet pe dhundho", "google karo", "search karo",
            "batao about"
        ),

        Intent.TELL_TIME to listOf(
            "what is the current time", "what time is it right now",
            "time kya hai", "kitne baje hain", "abhi time", "current time",
            "what time", "time batao", "baje hain", "time kya he",
            "samay kya hai", "ghadi kya bol rahi", "time bolo",
            "kitne baje", "abhi kitne baje"
        ),

        Intent.TELL_DATE to listOf(
            "what is today's date", "what day is it today",
            "date kya hai", "aaj ki date", "current date", "what date",
            "date batao", "aaj kaun sa din", "aaj ka din",
            "today date", "aaj kya hai", "date bolo"
        ),

        Intent.TELL_BATTERY to listOf(
            "how much battery do i have", "what is my battery level",
            "battery", "charge kitna", "battery kitni", "battery status",
            "kitna charge", "battery level", "battery percent",
            "phone mein kitna charge", "battery check", "charge batao"
        ),

        Intent.TELL_JOKE to listOf(
            "tell me a funny joke", "make me laugh", "say something funny",
            "joke", "funny", "hasao", "joke sunao", "koi joke",
            "ek joke", "joke bolo", "joke suno",
            "mujhe hasao", "funny joke", "joke do"
        ),

        Intent.FLIP_COIN to listOf(
            "flip a coin", "toss a coin", "heads or tails",
            "flip coin", "toss", "coin flip", "coin toss",
            "sikka uchalo", "toss karo"
        ),

        Intent.ROLL_DICE to listOf(
            "roll a dice", "roll the dice", "give me a random number",
            "roll dice", "dice roll", "pasa phenko", "number nikalo",
            "random number", "dice", "pasa", "6 mein se"
        )
    )

    // ─────────────────────────────────────────────────────────────
    // MAIN DETECT FUNCTION
    // ─────────────────────────────────────────────────────────────

    fun detect(input: String): IntentResult {
        val original = input.trim()
        val normalized = normalize(original)

        // 1. Q=A training syntax check
        if (normalized.contains("=") && !normalized.startsWith("search")) {
            return IntentResult(
                intent = Intent.LEARN_QA,
                confidence = 100,
                confidenceLevel = ConfidenceLevel.HIGH,
                normalizedInput = normalized
            )
        }

        // 2. Negation check — if user is saying "don't do X", we want UNKNOWN
        val hasNegation = negationWords.any { neg ->
            normalized.startsWith(neg) || normalized.contains(" $neg ")
        }

        // 3. Score every intent
        var bestIntent = Intent.UNKNOWN
        var bestScore = 0
        var bestEntity = ""

        for ((intent, phrases) in intentPhrases) {
            for (phrase in phrases) {
                val score = scoreMatch(normalized, phrase)
                if (score > bestScore) {
                    bestScore = score
                    bestIntent = intent
                    bestEntity = extractEntity(normalized, phrase)
                }
            }
        }

        // 4. Apply negation penalty
        val finalScore = if (hasNegation && bestIntent != Intent.UNKNOWN) {
            (bestScore * 0.4f).toInt()  // Heavy penalty — likely not what they want
        } else {
            bestScore
        }

        // 5. Map score to confidence level
        val confidenceLevel = when {
            finalScore >= 80 -> ConfidenceLevel.HIGH
            finalScore >= 50 -> ConfidenceLevel.MEDIUM
            finalScore >= 30 -> ConfidenceLevel.LOW
            else             -> ConfidenceLevel.NONE
        }

        // 6. Fall back to UNKNOWN if confidence too low
        val resolvedIntent = if (finalScore < 30) Intent.UNKNOWN else bestIntent

        // 7. Build params map
        val params = mutableMapOf<String, String>()
        if (bestEntity.isNotEmpty()) params["target"] = bestEntity

        return IntentResult(
            intent           = resolvedIntent,
            params           = params,
            confidence       = finalScore.coerceAtMost(100),
            confidenceLevel  = confidenceLevel,
            normalizedInput  = normalized,
            extractedEntity  = bestEntity
        )
    }

    // ─────────────────────────────────────────────────────────────
    // SCORING — multi-signal, weighted
    // ─────────────────────────────────────────────────────────────

    private fun scoreMatch(input: String, phrase: String): Int {
        val i = input.lowercase().trim()
        val p = phrase.lowercase().trim()

        return when {
            i == p                          -> 100  // Exact match
            i.startsWith("$p ")             -> 95   // Phrase at start with space after
            i.startsWith(p)                 -> 90   // Phrase at start
            i.endsWith(" $p")               -> 88   // Phrase at end
            i.contains(" $p ")             -> 82   // Phrase surrounded by spaces
            i.contains(p)                   -> 72   // Phrase anywhere (substring)
            wordOverlapScore(i, p) >= 80   -> 65   // High word overlap
            wordOverlapScore(i, p) >= 60   -> 50   // Medium word overlap
            wordOverlapScore(i, p) >= 40   -> 35   // Low word overlap
            else                            -> 0
        }
    }

    private fun wordOverlapScore(input: String, phrase: String): Int {
        val inputWords = input.split(Regex("\\s+")).toSet()
        val phraseWords = phrase.split(Regex("\\s+")).toSet()
        if (phraseWords.isEmpty()) return 0
        val common = inputWords.intersect(phraseWords).size
        return (common * 100) / phraseWords.size
    }

    // ─────────────────────────────────────────────────────────────
    // ENTITY EXTRACTION — what's left after the intent phrase
    // ─────────────────────────────────────────────────────────────

    private fun extractEntity(input: String, matchedPhrase: String): String {
        val i = input.lowercase().trim()
        val p = matchedPhrase.lowercase().trim()

        // Try removing the matched phrase from start or end
        val afterPhrase = i.removePrefix(p).removePrefix(" ").trim()
        val beforePhrase = i.removeSuffix(p).removeSuffix(" ").trim()

        return when {
            afterPhrase.isNotEmpty() && afterPhrase != i  -> afterPhrase
            beforePhrase.isNotEmpty() && beforePhrase != i -> beforePhrase
            else -> ""
        }
    }

    // ─────────────────────────────────────────────────────────────
    // NORMALIZATION
    // ─────────────────────────────────────────────────────────────

    /**
     * Normalizes user input:
     * 1. Lowercase
     * 2. Remove punctuation (except = for Q=A training)
     * 3. Replace synonym words
     * 4. Collapse multiple spaces
     */
    fun normalize(input: String): String {
        var result = input.lowercase().trim()

        // Preserve = sign for Q=A training
        val hasEquals = result.contains("=")

        // Remove punctuation except apostrophes and =
        result = result.replace(Regex("[^a-z0-9\\s'=]"), " ")

        // Apply synonym map — replace whole words only
        val words = result.split(Regex("\\s+")).toMutableList()
        val normalized = words.map { word ->
            synonymMap[word] ?: word
        }
        result = normalized.joinToString(" ")

        // Collapse spaces
        result = result.replace(Regex("\\s+"), " ").trim()

        return result
    }

    // ─────────────────────────────────────────────────────────────
    // UTILITIES
    // ─────────────────────────────────────────────────────────────

    /** Quick check — is this input a question? */
    fun isQuestion(input: String): Boolean {
        val lower = input.lowercase()
        val questionStarters = listOf(
            "what", "who", "where", "when", "why", "how", "is", "are",
            "can", "could", "would", "should", "do", "does", "did",
            "kya", "kaun", "kahan", "kab", "kyun", "kaise", "kitna", "kitni"
        )
        return lower.endsWith("?") ||
               questionStarters.any { lower.startsWith(it) }
    }

    /** Returns true if input seems like the user is sharing personal info */
    fun isPersonalStatement(input: String): Boolean {
        val lower = input.lowercase()
        val personalMarkers = listOf(
            "my name", "i am", "i'm", "i live", "i work", "i like", "i love",
            "my job", "my hobby", "my age", "i'm from", "call me",
            "mera naam", "main hun", "mujhe", "meri umar", "main rehta"
        )
        return personalMarkers.any { lower.contains(it) }
    }
}          
