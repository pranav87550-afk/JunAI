package com.junai.app

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
        UNKNOWN
    }

    data class IntentResult(
        val intent: Intent,
        val params: Map<String, String> = emptyMap(),
        val confidence: Int = 100
    )

    private val intentPhrases = mapOf(

        Intent.GREET to listOf(
            "hello", "hi", "hey", "hii", "helo", "hlo", "namaste", "namaskar",
            "good morning", "good evening", "good night", "good afternoon",
            "sup", "wassup", "yo", "howdy", "salut", "ola", "greetings",
            "kya haal", "kaise ho", "kaisi ho", "kya chal raha", "kya scene"
        ),

        Intent.HOW_ARE_YOU to listOf(
            "how are you", "how r u", "how are u", "tum kaise ho",
            "aap kaise hain", "kya haal hai", "sab theek", "theek ho",
            "how is it going", "all good", "you ok", "you okay",
            "kya chal raha hai", "sab badhiya", "kaisa feel"
        ),

        Intent.THANK to listOf(
            "thank you", "thanks", "thankyou", "thank u", "thx", "ty",
            "shukriya", "dhanyawad", "bahut acha", "great job", "well done",
            "shabash", "wah", "amazing", "awesome", "brilliant", "fantastic",
            "good job", "nice", "perfect", "superb", "mast", "ekdum mast",
            "ek number", "too good", "bahut badhiya"
        ),

        Intent.OPEN_APP to listOf(
            "open", "launch", "start", "run", "load", "boot",
            "chalu kar", "kholo", "open kar", "start kar", "launch kar",
            "chalao", "chala", "on kar", "activate", "initialize",
            "kholna hai", "open karna hai", "start karna hai"
        ),

        Intent.CALL_CONTACT to listOf(
            "call", "phone", "ring", "dial", "contact",
            "call kar", "phone kar", "phone karo", "call karo",
            "baat karni hai", "call lagao", "phone lagao", "ring kar",
            "give a call", "make a call", "call karna hai"
        ),

        Intent.SEND_MESSAGE to listOf(
            "message", "sms", "text", "whatsapp", "msg",
            "message karo", "message bhejo", "sms karo", "text karo",
            "message kar", "bhejo message", "send message", "send msg"
        ),

        Intent.PLAY_MUSIC to listOf(
            "play music", "music play", "gaana bajao", "song bajao",
            "music on", "play song", "gaana chalu", "music chalu",
            "bajao", "music lagao", "song lagao", "gaana lagao",
            "play karo", "music start", "start music", "gana baja",
            "play", "song play", "music chalao"
        ),

        Intent.PAUSE_MUSIC to listOf(
            "pause music", "music pause", "music band", "gaana band",
            "stop music", "music stop", "gaana roko", "pause song",
            "roko", "band karo music", "music rok do"
        ),

        Intent.NEXT_SONG to listOf(
            "next song", "agla gaana", "next track", "skip song",
            "next", "aage", "skip", "agle gaane pe jao",
            "next wala", "forward song", "song skip karo"
        ),

        Intent.PREV_SONG to listOf(
            "previous song", "pichla gaana", "prev song", "back song",
            "previous", "peeche", "pichle gaane pe jao",
            "prev", "last song", "wapas"
        ),

        Intent.STOP_MUSIC to listOf(
            "stop music", "music stop", "music band karo", "gaana band",
            "music off", "stop song", "band kar music", "music khatam"
        ),

        Intent.SET_REMINDER to listOf(
            "set reminder", "reminder set", "remind me", "alarm set",
            "yaad dila", "reminder lagao", "alarm lagao", "remind kar",
            "reminder banana hai", "alarm banana hai", "mujhe yaad dilana",
            "set alarm", "create reminder", "add reminder"
        ),

        Intent.CREATE_NOTE to listOf(
            "create note", "note banao", "note likho", "save note",
            "note bana", "likhlo", "note kar", "note banana hai",
            "add note", "new note", "note save karo", "likh lo"
        ),

        Intent.SHOW_NOTES to listOf(
            "show notes", "open notes", "notes dekho", "meri notes",
            "notes kholo", "notes dikhao", "note dikhao", "notes open",
            "mere notes", "notes screen", "notes pe jao"
        ),

        Intent.SHOW_TODO to listOf(
            "show todo", "open todo", "todo list", "mera todo",
            "todo dikhao", "kaam ki list", "todo kholo", "task list",
            "tasks dikhao", "meri list", "todo screen"
        ),

        Intent.SHOW_CALCULATOR to listOf(
            "show calculator", "open calculator", "calculator kholo",
            "calculator dikhao", "calc open", "hisaab", "calculate",
            "calculator lao", "calculator screen", "calc", "calculation"
        ),

        Intent.SHOW_DRAW to listOf(
            "show draw", "open draw", "drawing kholo", "draw karna hai",
            "drawing app", "sketch", "draw", "drawing open",
            "draw screen", "paint", "drawing dikhao", "canvas"
        ),

        Intent.SHOW_TRANSLATOR to listOf(
            "show translator", "open translator", "translate", "translator kholo",
            "anuvad", "translation", "translator open", "translate karo",
            "translator screen", "bhasha", "language translate"
        ),

        Intent.SHOW_REMINDER to listOf(
            "show reminder", "open reminder", "reminder dekho",
            "reminder dikhao", "mera reminder", "reminder screen",
            "reminder kholo", "alarm dikhao", "reminder open"
        ),

        Intent.SHOW_SETTINGS to listOf(
            "show settings", "open settings", "settings kholo",
            "settings dikhao", "setting", "settings open",
            "settings screen", "settings pe jao", "configuration"
        ),

        Intent.SHOW_MUSIC to listOf(
            "show music", "open music", "jun dj", "music app",
            "music dikhao", "dj kholo", "music screen", "dj open",
            "music player", "songs dikhao", "playlist"
        ),

        Intent.SHOW_UNANSWERED to listOf(
            "show unanswered", "open unanswered", "unanswered questions",
            "unanswered dikhao", "jo nahi pata", "unanswered screen"
        ),

        Intent.SHOW_VOICE_COMMANDS to listOf(
            "show voice commands", "voice commands", "commands list",
            "commands dikhao", "kya kya bol sakta hun", "commands screen"
        ),

        Intent.SHOW_DATA_MANAGEMENT to listOf(
            "show data", "data management", "open data", "knowledge count",
            "data screen", "import data", "data dikhao"
        ),

        Intent.CLEAR_CHAT to listOf(
            "clear chat", "chat clear", "chat delete", "chat hatao",
            "chat saaf karo", "messages delete", "chat saaf",
            "delete chat", "chat khatam", "sab messages hatao"
        ),

        Intent.SEARCH_WEB to listOf(
            "search", "dhundho", "find", "google", "look up",
            "search kar", "batao about", "tell me about", "web search",
            "internet pe dhundho", "google karo", "search karo"
        ),

        Intent.TELL_TIME to listOf(
            "time kya hai", "kitne baje hain", "abhi time", "current time",
            "what time", "time batao", "baje hain", "time kya he",
            "samay kya hai", "ghadi kya bol rahi", "time bolo",
            "kitne baje", "abhi kitne baje", "time"
        ),

        Intent.TELL_DATE to listOf(
            "date kya hai", "aaj ki date", "current date", "what date",
            "date batao", "aaj kaun sa din", "aaj ka din",
            "today date", "aaj kya hai", "date bolo", "date"
        ),

        Intent.TELL_BATTERY to listOf(
            "battery", "charge kitna", "battery kitni", "battery status",
            "kitna charge", "battery level", "battery percent",
            "phone mein kitna charge", "battery check", "charge batao"
        ),

        Intent.TELL_JOKE to listOf(
            "joke", "funny", "hasao", "joke sunao", "koi joke",
            "ek joke", "joke bolo", "make me laugh", "joke suno",
            "mujhe hasao", "funny joke", "joke do"
        ),

        Intent.FLIP_COIN to listOf(
            "flip coin", "toss", "heads or tails", "coin flip",
            "sikka uchalo", "toss karo", "heads tails", "coin toss"
        ),

        Intent.ROLL_DICE to listOf(
            "roll dice", "dice roll", "pasa phenko", "number nikalo",
            "random number", "dice", "pasa", "6 mein se"
        )
    )

    fun detect(input: String): IntentResult {
        val lower = input.lowercase().trim()

        // Q=A learning check
        if (lower.contains("=") && !lower.startsWith("search")) {
            return IntentResult(Intent.LEARN_QA)
        }

        var bestIntent = Intent.UNKNOWN
        var bestScore = 0
        var bestParams = mutableMapOf<String, String>()

        for ((intent, phrases) in intentPhrases) {
            for (phrase in phrases) {
                val score = when {
                    lower == phrase -> 100
                    lower.startsWith("$phrase ") -> 95
                    lower.startsWith(phrase) -> 90
                    lower.contains(" $phrase ") -> 75
                    lower.contains(phrase) -> 65
                    else -> 0
                }

                if (score > bestScore) {
                    bestScore = score
                    bestIntent = intent
                    val param = lower
                        .removePrefix(phrase)
                        .removeSuffix(phrase)
                        .trim()
                    if (param.isNotEmpty()) {
                        bestParams = mutableMapOf("target" to param)
                    }
                }
            }
        }

        return IntentResult(
            intent = bestIntent,
            params = bestParams,
            confidence = bestScore.coerceAtMost(100)
        )
    }
}
