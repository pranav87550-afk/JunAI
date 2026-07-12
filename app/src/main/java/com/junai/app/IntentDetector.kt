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
        AGENT_TASK,      // Phase 15: complex, multi-step, or system-level command
        UNKNOWN
    }

    data class IntentResult(
        val intent: Intent,
        val params: Map<String, String> = emptyMap(),
        val confidence: Int = 100,
        val confidenceLevel: ConfidenceLevel = ConfidenceLevel.HIGH,
        val normalizedInput: String = "",   // Cleaned input after normalization
        val extractedEntity: String = "",   // The key noun/target extracted
        val agentTaskParams: AgentTaskParams? = null  // Populated only when intent == AGENT_TASK
    )

    enum class ConfidenceLevel { HIGH, MEDIUM, LOW, NONE }

    // ─────────────────────────────────────────────────────────────
    // AGENT TASK — Phase 15: complex / multi-step / system-level intent
    // This section is purely additive. Nothing below touches the
    // existing legacy phrase-matching pipeline.
    // ─────────────────────────────────────────────────────────────

    enum class AgentActionType {
        SYSTEM_CONTROL,   // wifi, bluetooth, brightness, volume, torch, dnd, hotspot, airplane mode
        SCREEN_READ,      // reading messages / notifications / on-screen content
        RESEARCH,         // multi-source web research, comparisons, price lookups
        MULTI_STEP,       // two or more sequential actions chained together
        RESUME            // continuing an interrupted multi-step task
    }

    /**
     * targetSetting convention: "<setting>:<value>"
     *   e.g. "wifi:on", "bluetooth:off", "brightness:50", "brightness:increase",
     *        "volume:decrease", "torch:on", "dnd:off"
     * Downstream (ActionEngine, Phase 16) parses this colon-separated string.
     */
    data class AgentTaskParams(
        val rawGoal: String,                  // original user text, unmodified
        val actionType: AgentActionType,
        val targetApp: String? = null,        // e.g. "whatsapp", "youtube"
        val targetSetting: String? = null,    // e.g. "wifi:on", "brightness:50"
        val searchQuery: String? = null,      // e.g. "best gaming phone under 30000"
        val targetContact: String? = null     // e.g. "mom"
    )

    // Known app names Jun can recognize as a target app. Extend as new
    // app integrations are added in later agent files.
    private val knownApps = listOf(
        "whatsapp", "youtube", "instagram", "facebook", "chrome", "gmail",
        "spotify", "bookmyshow", "twitter", "telegram", "settings", "camera",
        "gallery", "maps", "playstore", "play store"
    )

    // System setting keyword → canonical setting name
    private val systemSettingKeywords = mapOf(
        "wifi" to "wifi", "wi-fi" to "wifi", "wai fai" to "wifi",
        "bluetooth" to "bluetooth", "blootooth" to "bluetooth",
        "brightness" to "brightness",
        "volume" to "volume", "awaaz" to "volume",
        "flashlight" to "torch", "torch" to "torch",
        "do not disturb" to "dnd", "dnd" to "dnd",
        "hotspot" to "hotspot",
        "airplane mode" to "airplane", "flight mode" to "airplane"
    )

    // Toggle words — used for wifi / bluetooth / torch / dnd / hotspot / airplane
    private val onWords = setOf("on", "enable", "chalu", "chalao", "jalao", "jala", "start")
    private val offWords = setOf("off", "disable", "band", "bandh", "mat", "rok")

    // Relative-adjustment words — used only for brightness / volume
    private val increaseWords = setOf("badao", "badhao", "increase", "zyada", "high", "tez")
    private val decreaseWords = setOf("kam karo", "kam kar", "kam", "decrease", "low", "halka")

    // Resume / continuation keywords — always AGENT_TASK
    private val resumeKeywords = setOf(
        "resume", "continue", "where were we", "carry on",
        "wapas shuru", "phir se shuru", "jaha chhoda tha", "aage badho"
    )

    // Action verbs used to detect chained / multi-step commands
    private val agentActionVerbs = setOf(
        "open", "kholo", "kholna", "khol", "khola",
        "search", "dhundo", "dhundho", "khojo", "find",
        "read", "padho", "padhkar",
        "check", "dekho",
        "send", "bhejo", "bhej", "message", "msg",
        "call", "phone",
        "turn", "set", "compare", "tell", "go", "jao", "navigate",
        "play", "chalao", "chala", "bajao", "lagao", "connect",
        // BUGFIX: "flashlight ON karke phir wifi OFF karo" has none of the
        // verbs above — Hinglish system-toggle phrasing almost always uses
        // bare "on"/"off", not "turn"/"set"/"connect". Without these,
        // verbCount stayed 0 and such commands never registered as
        // MULTI_STEP at all, regardless of how many settings were chained.
        "on", "off"
    )

    // Connector words that join two actions into one chained command
    private val chainConnectors = listOf(
        "and then", "and", "then", "aur", "phir", "uske baad", "ke baad"
    )

    // Specific phrase patterns for screen-reading style agent tasks
    private val screenReadPhrases = listOf(
        "read my whatsapp messages", "read my messages", "check my whatsapp",
        "what does my screen say", "read notifications", "read my notifications",
        "padho mera message", "message padhkar batao", "screen padhkar batao",
        "check my", "read my", "padh kar batao"
    )

    // Specific phrase patterns for research-style agent tasks
    private val researchPhrases = listOf(
        "find the best", "compare", "what is the price of", "price of",
        "tell me about it from the internet", "search and find",
        "dhundo aur batao", "best phone under", "best under",
        // BUGFIX: generic Hinglish research patterns like "weather pata
        // karo aur batao" or "iske baare mein pata karo" had no matching
        // phrase here, so researchScore stayed below the 50-point threshold
        // and these questions fell through to the legacy SEARCH_WEB intent
        // (which has its own separate, lower-quality answer path).
        "pata karo", "pata lagao", "ke baare mein batao", "kya hai"
    )

    /** Word-boundary aware contains check — avoids "on" matching inside "phone" etc. */
    private fun containsWord(text: String, phrase: String): Boolean {
        return Regex("\\b${Regex.escape(phrase)}\\b").containsMatchIn(text)
    }

    /**
     * Lightweight normalization for agent detection. Deliberately does NOT
     * apply the synonymMap (used by normalize()) because that map rewrites
     * words like "chalao" → "open" and "band" → "stop", which would destroy
     * the on/off signal this detector depends on. Only lowercases, strips
     * punctuation (keeping digits and %), and collapses whitespace.
     */
    private fun lightNormalize(input: String): String {
        var result = input.lowercase().trim()
        result = result.replace(Regex("[^a-z0-9\\s%]"), " ")
        result = result.replace(Regex("\\s+"), " ").trim()
        return result
    }

    private fun extractTargetApp(text: String): String? =
        knownApps.firstOrNull { text.contains(it) }

    /** Pattern: "mom's last message" / "mom ka message" / "mom ki whatsapp" */
    private fun extractTargetContact(text: String): String? {
        val possessive = Regex("(\\w+)'s (?:last )?message").find(text)?.groupValues?.get(1)
        if (possessive != null) return possessive
        return Regex("(\\w+) k[ai] (?:last )?message").find(text)?.groupValues?.get(1)
    }

    private fun extractSearchQuery(text: String, triggerWords: List<String>): String? {
        for (trigger in triggerWords) {
            if (text.contains(trigger)) {
                val after = text.substringAfter(trigger).trim()
                if (after.isNotEmpty()) return after
            }
        }
        return null
    }

    /**
     * Returns canonical setting name + target state, or null if no
     * system-level keyword is present.
     * For brightness/volume: state is a number, "increase", "decrease", or
     * "unclear" (ambiguous → lower confidence → DecisionEngine will clarify).
     * For everything else: state is "on" or "off".
     */
    private fun detectSystemControl(text: String): Pair<String, String>? {
        for ((keyword, canonical) in systemSettingKeywords) {
            if (!containsWord(text, keyword)) continue

            if (canonical == "brightness" || canonical == "volume") {
                val numberMatch = Regex("(\\d{1,3})").find(text)
                if (numberMatch != null) return canonical to numberMatch.value

                val state = when {
                    increaseWords.any { containsWord(text, it) } -> "increase"
                    decreaseWords.any { containsWord(text, it) } -> "decrease"
                    else -> "unclear"
                }
                return canonical to state
            }

            val isOff = offWords.any { containsWord(text, it) }
            val isOn = onWords.any { containsWord(text, it) }
            val state = when {
                isOff && !isOn -> "off"
                isOn && !isOff -> "on"
                isOff && isOn  -> "off"  // negation wins, e.g. "wifi mat chalao"
                else           -> "on"   // bare mention defaults to turning ON
            }
            return canonical to state
        }
        return null
    }

    /** Two or more chained action verbs joined by a connector word. */
    private fun detectMultiStep(text: String): Boolean {
        val hasConnector = chainConnectors.any { containsWord(text, it) }
        // BUGFIX: this used to be agentActionVerbs.count { containsWord(...) },
        // which counts DISTINCT verb words present, not occurrences. Since
        // "on" and "off" are separate entries in agentActionVerbs, a chain
        // like "flashlight off phir wifi off phir bluetooth off" only has
        // ONE distinct verb ("off", repeated 3x) — verbCount stayed 1 and
        // never reached the >= 2 threshold, so same-state chains (all-on or
        // all-off) silently fell through to single-step SYSTEM_CONTROL and
        // only the first setting was ever actually planned. Counting total
        // occurrences (not distinct words) fixes this: "off off off" = 3.
        val verbCount = agentActionVerbs.sumOf { verb ->
            Regex("\\b${Regex.escape(verb)}\\b").findAll(text).count()
        }
        if (hasConnector && verbCount >= 2) return true

        // BUGFIX: "WhatsApp khol ke Papa ko message bhejo 'text'" has no
        // word from chainConnectors (only bare "ke", not "ke baad") — yet
        // it's unambiguously a 2-action command (open, then send). Treat
        // an explicit open-word followed later by a send-word as its own
        // multi-step signal, independent of connector words, since adding
        // bare "ke" itself to chainConnectors would cause false positives
        // across countless unrelated single-step phrases that happen to
        // contain "ke" (a very common Hindi particle).
        val openMatch = openWordPattern.find(text) ?: return false
        val sendMatch = sendWordPattern.find(text) ?: return false
        return sendMatch.range.first > openMatch.range.first
    }

    private val openWordPattern = Regex("(?i)\\b(open|khol|kholo|kholna|khola)\\b")
    private val sendWordPattern = Regex("(?i)\\b(send|bhejo|bhej|message|msg)\\b")

    private fun detectResume(text: String): Boolean =
        resumeKeywords.any { containsWord(text, it) }

    /**
     * Core AGENT_TASK detector. Checks structural signals first — resume,
     * multi-step, system control — which are ALWAYS AGENT_TASK per spec.
     * Falls back to phrase scoring for screen-read / research, which must
     * be weighed against the legacy intent match (done by the caller).
     * Returns (params, score) or null if input shows no agent-task signal.
     */
    private fun detectAgentTask(rawInput: String): Pair<AgentTaskParams, Int>? {
        val light = lightNormalize(rawInput)
        val lowerOriginal = rawInput.lowercase().trim()

        if (detectResume(light)) {
            return AgentTaskParams(rawGoal = rawInput, actionType = AgentActionType.RESUME) to 95
        }

        if (detectMultiStep(light)) {
            return AgentTaskParams(
                rawGoal = rawInput,
                actionType = AgentActionType.MULTI_STEP,
                targetApp = extractTargetApp(lowerOriginal),
                searchQuery = extractSearchQuery(lowerOriginal, listOf("search for", "search", "find", "dhundo", "dhundho")),
                targetContact = extractTargetContact(lowerOriginal)
            ) to 88
        }

        detectSystemControl(light)?.let { (setting, state) ->
            val score = if (state == "unclear") 55 else 92
            return AgentTaskParams(
                rawGoal = rawInput,
                actionType = AgentActionType.SYSTEM_CONTROL,
                targetSetting = "$setting:$state"
            ) to score
        }

        val screenScore = screenReadPhrases.maxOfOrNull { scoreMatch(light, it) } ?: 0
        val researchScore = researchPhrases.maxOfOrNull { scoreMatch(light, it) } ?: 0

        return when {
            screenScore >= 50 && screenScore >= researchScore -> AgentTaskParams(
                rawGoal = rawInput,
                actionType = AgentActionType.SCREEN_READ,
                targetApp = extractTargetApp(lowerOriginal),
                targetContact = extractTargetContact(lowerOriginal)
            ) to screenScore

            researchScore >= 50 -> AgentTaskParams(
                rawGoal = rawInput,
                actionType = AgentActionType.RESEARCH,
                searchQuery = extractSearchQuery(lowerOriginal, listOf("find the best", "compare", "price of", "about")) ?: lowerOriginal
            ) to researchScore

            else -> null
        }
    }

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
            // BUGFIX: removed "i am", "i'm", "mujhe" — these are
            // extremely common, generic tokens that appear in tons of
            // unrelated sentences ("I have a problem", "mujhe pata
            // hai", etc.), and were winning via scoreMatch()'s
            // word-overlap fallback even for messages that had nothing
            // to do with sharing a personal fact. Kept the more
            // SPECIFIC compound phrases below, which are much less
            // prone to false-firing.
            "my name is", "call me", "mera naam",
            "i live in", "i'm from", "i work",
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

        // 1.5. AGENT_TASK structural detection (Phase 15) — computed here so it
        // can be weighed against the legacy best-match score below.
        val agentMatch = detectAgentTask(original)

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

        // 5.5. Decide if AGENT_TASK overrides the legacy result.
        // SYSTEM_CONTROL / MULTI_STEP / RESUME always win (per spec, these are
        // structurally unambiguous). SCREEN_READ / RESEARCH must out-score the
        // legacy match, since phrases like "find" or "tell me about" can
        // legitimately belong to the simpler existing SEARCH_WEB intent.
        if (agentMatch != null) {
            val (agentParams, agentScore) = agentMatch
            val agentWins = when (agentParams.actionType) {
                AgentActionType.SYSTEM_CONTROL,
                AgentActionType.MULTI_STEP,
                AgentActionType.RESUME -> true
                else -> agentScore >= finalScore
            }

            if (agentWins) {
                val agentConfidenceLevel = when {
                    agentScore >= 80 -> ConfidenceLevel.HIGH
                    agentScore >= 50 -> ConfidenceLevel.MEDIUM
                    else             -> ConfidenceLevel.LOW
                }
                return IntentResult(
                    intent = Intent.AGENT_TASK,
                    confidence = agentScore,
                    confidenceLevel = agentConfidenceLevel,
                    normalizedInput = normalized,
                    extractedEntity = agentParams.searchQuery ?: agentParams.targetApp ?: agentParams.targetSetting ?: "",
                    agentTaskParams = agentParams
                )
            }
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
            // BUGFIX: word-overlap on a very short input is noisy —
            // "love" vs "i love" shares 1 of 2 phrase-words (50%) and
            // used to score 35 (LOW confidence, but that's still
            // enough to win as bestIntent if nothing else scores
            // higher), even though "love" alone obviously isn't the
            // user sharing a fact about themselves. Word-overlap only
            // kicks in once the input itself has enough words that a
            // shared-word percentage is actually meaningful.
            i.split(Regex("\\s+")).size < 3 -> 0
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
