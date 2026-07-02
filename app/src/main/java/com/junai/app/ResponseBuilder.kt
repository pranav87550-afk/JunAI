package com.junai.app

import java.util.Calendar

/**
 * ResponseBuilder — Generates personalized, high-quality responses for Jun.
 *
 * Uses UserProfile from UserPreferenceManager to tailor:
 * - Tone (casual / formal / friendly)
 * - Language style (English / Hinglish)
 * - Emoji usage (high / low / none)
 * - Response length (short / medium / long)
 * - Personal context (name, facts, habits)
 *
 * Usage:
 *   val builder = ResponseBuilder(userPrefs.getUserProfile())
 *   builder.forIntent(IntentDetector.Intent.GREET)
 *   builder.forUnknown("what is quantum entanglement")
 *   builder.withFact("battery", "82%")
 */
class ResponseBuilder(private val profile: UserProfile) {

    private val name get() = profile.name
    private val isHinglish get() = profile.languageStyle == UserPreferenceManager.STYLE_HINGLISH
        || profile.languageStyle == UserPreferenceManager.STYLE_HINDI
    private val isFormal get() = profile.toneStyle == UserPreferenceManager.TONE_FORMAL
    private val showEmoji get() = profile.emojiStyle != UserPreferenceManager.EMOJI_NONE
    private val isLong get() = profile.responseLength == UserPreferenceManager.LENGTH_LONG
    private val isShort get() = profile.responseLength == UserPreferenceManager.LENGTH_SHORT

    // ─────────────────────────────────────────────────────────────
    // INTENT-BASED RESPONSES
    // ─────────────────────────────────────────────────────────────

    fun forIntent(
        intent: IntentDetector.Intent,
        entity: String = "",
        extra: String = ""
    ): String {
        return when (intent) {
            IntentDetector.Intent.GREET        -> buildGreet()
            IntentDetector.Intent.HOW_ARE_YOU  -> buildHowAreYou()
            IntentDetector.Intent.THANK        -> buildThank()
            IntentDetector.Intent.WHO_ARE_YOU  -> buildWhoAreYou()
            IntentDetector.Intent.USER_INFO    -> buildUserInfoAck(entity)
            IntentDetector.Intent.TELL_TIME    -> buildTime()
            IntentDetector.Intent.TELL_DATE    -> buildDate()
            IntentDetector.Intent.TELL_JOKE    -> buildJoke()
            IntentDetector.Intent.FLIP_COIN    -> buildCoinFlip()
            IntentDetector.Intent.ROLL_DICE    -> buildDiceRoll()
            IntentDetector.Intent.TELL_BATTERY -> withFact("battery", extra)
            IntentDetector.Intent.PLAY_MUSIC   -> buildAction("Playing music", "🎵", "Gaana chalu kar raha hun")
            IntentDetector.Intent.PAUSE_MUSIC  -> buildAction("Music paused", "⏸️", "Gaana rok diya")
            IntentDetector.Intent.NEXT_SONG    -> buildAction("Next song", "⏭️", "Agla gaana")
            IntentDetector.Intent.PREV_SONG    -> buildAction("Previous song", "⏮️", "Pichla gaana")
            IntentDetector.Intent.STOP_MUSIC   -> buildAction("Music stopped", "⏹️", "Gaana band kar diya")
            IntentDetector.Intent.SET_REMINDER -> buildAction("Opening Reminders", "🔔", "Reminder screen aa gayi")
            IntentDetector.Intent.CREATE_NOTE  -> buildAction("Opening Notes", "📝", "Note screen ready hai")
            IntentDetector.Intent.SHOW_NOTES   -> buildAction("Here are your notes", "📒", "Yeh rahi teri notes")
            IntentDetector.Intent.SHOW_TODO    -> buildAction("Your tasks", "✅", "Yeh raha tera todo")
            IntentDetector.Intent.SHOW_CALCULATOR -> buildAction("Calculator", "🧮", "Hisaab karo")
            IntentDetector.Intent.SHOW_DRAW    -> buildAction("Drawing canvas ready", "🎨", "Draw karo")
            IntentDetector.Intent.SHOW_TRANSLATOR -> buildAction("Translator ready", "🌐", "Translate karo")
            IntentDetector.Intent.SHOW_REMINDER -> buildAction("Your reminders", "🔔", "Yeh rahe tere reminders")
            IntentDetector.Intent.SHOW_SETTINGS -> buildAction("Settings", "⚙️", "Settings khol raha hun")
            IntentDetector.Intent.SHOW_MUSIC   -> buildAction("Music Player", "🎵", "Music player aa gaya")
            IntentDetector.Intent.CLEAR_CHAT   -> buildAction("Chat cleared", "🗑️", "Chat saaf ho gayi")
            IntentDetector.Intent.CALL_CONTACT -> buildContactAction("Calling", entity, "📞")
            IntentDetector.Intent.SEND_MESSAGE -> buildContactAction("Messaging", entity, "💬")
            IntentDetector.Intent.SEARCH_WEB   -> buildSearchConfirm(entity)
            IntentDetector.Intent.LEARN_QA     -> buildLearnAck()
            else -> forUnknown("")
        }
    }

    // ─────────────────────────────────────────────────────────────
    // SPECIFIC BUILDERS
    // ─────────────────────────────────────────────────────────────

    private fun buildGreet(): String {
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        val timeGreet = when {
            hour in 5..11  -> if (isHinglish) "Good morning" else "Good morning"
            hour in 12..16 -> if (isHinglish) "Good afternoon" else "Good afternoon"
            hour in 17..20 -> if (isHinglish) "Good evening" else "Good evening"
            else           -> if (isHinglish) "Hey" else "Hello"
        }

        val nameTag = if (name != null) ", $name" else ""
        val emoji = if (showEmoji) " 👋" else ""

        val base = "$timeGreet$nameTag!$emoji"

        if (isShort) return base

        val extras = if (isHinglish) {
            " Kya haal hai? Kuch kaam hai mujhse?"
        } else {
            " How can I help you today?"
        }

        return base + if (isLong) extras + buildPersonalizedHint() else extras
    }

    private fun buildHowAreYou(): String {
        val responses = if (isHinglish) listOf(
            "Main ekdum badhiya hun${emoji("😄")} Tera kya haal hai${if (name != null) ", $name" else ""}?",
            "Mast hun bhai${emoji("⚡")} Bol, kya kaam hai?",
            "Jun is always running at full power${emoji("🤖")} Tu bata, kya scene hai?"
        ) else listOf(
            "I'm doing great, thanks for asking${emoji("😄")}${if (name != null) " $name" else ""}! How about you?",
            "All systems running perfectly${emoji("⚡")} What can I do for you?",
            "Always ready to help${emoji("🤖")} What's on your mind?"
        )
        return responses.random()
    }

    private fun buildThank(): String {
        val responses = if (isHinglish) listOf(
            "Koi baat nahi${emoji("😊")} Yahi toh mera kaam hai!",
            "Arre${if (name != null) " $name" else ""}${emoji("🙌")}, mujhe khushi hui help kar ke!",
            "Always here for you${emoji("⚡")} Aur kuch chahiye?"
        ) else listOf(
            "You're welcome${emoji("😊")}${if (name != null) ", $name" else ""}! That's what I'm here for.",
            "Happy to help anytime${emoji("🙌")} Anything else?",
            "Glad I could assist${emoji("✅")} Let me know if you need more!"
        )
        return responses.random()
    }

    private fun buildWhoAreYou(): String {
        val intro = if (isHinglish) {
            "Main hun Jun${emoji("🤖")} — tera personal AI assistant! " +
            "Main tere commands samajhta hun, yaad rakhta hun, aur help karta hun. " +
            "Aur main fully offline hun${emoji("📴")} — tera data kahin nahi jaata!"
        } else {
            "I'm Jun${emoji("🤖")} — your personal offline AI assistant! " +
            "I understand your commands, remember your preferences, and get smarter the more you use me. " +
            "And the best part? I work completely offline${emoji("📴")} — your data stays only with you!"
        }

        if (isShort) return "I'm Jun, your personal AI assistant${emoji("🤖")}"

        val capabilities = if (isHinglish) {
            "\n\nMain kar sakta hun:\n• Music, Notes, Reminders, Calculator\n• Calls, Messages, Web search\n• Aur bahut kuch — bas bol!"
        } else {
            "\n\nI can help with:\n• Music, Notes, Reminders, Calculator\n• Calls, Messages, Web search\n• And much more — just ask!"
        }

        return intro + if (isLong) capabilities else ""
    }

    private fun buildUserInfoAck(entity: String): String {
        return if (isHinglish) {
            "Theek hai${emoji("✅")}, yaad kar liya maine!" +
            if (name != null) " Ab main tujhe $name bolunga." else ""
        } else {
            "Got it${emoji("✅")}, I've made a note of that!" +
            if (name != null) " I'll remember to call you $name." else ""
        }
    }

    private fun buildTime(): String {
        val cal = Calendar.getInstance()
        val hour = cal.get(Calendar.HOUR_OF_DAY)
        val minute = cal.get(Calendar.MINUTE)
        val amPm = if (hour >= 12) "PM" else "AM"
        val hour12 = if (hour > 12) hour - 12 else if (hour == 0) 12 else hour
        val minStr = minute.toString().padStart(2, '0')
        val timeStr = "$hour12:$minStr $amPm"

        return if (isHinglish) {
            "Abhi time hai $timeStr${emoji("🕐")}"
        } else {
            "The current time is $timeStr${emoji("🕐")}"
        }
    }

    private fun buildDate(): String {
        val cal = Calendar.getInstance()
        val days = arrayOf("Sunday", "Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday")
        val months = arrayOf("January", "February", "March", "April", "May", "June",
            "July", "August", "September", "October", "November", "December")

        val day = days[cal.get(Calendar.DAY_OF_WEEK) - 1]
        val date = cal.get(Calendar.DAY_OF_MONTH)
        val month = months[cal.get(Calendar.MONTH)]
        val year = cal.get(Calendar.YEAR)

        return if (isHinglish) {
            "Aaj hai $day, $date $month $year${emoji("📅")}"
        } else {
            "Today is $day, $date $month $year${emoji("📅")}"
        }
    }

    private fun buildJoke(): String {
        val jokes = listOf(
            "Why don't scientists trust atoms?\nBecause they make up everything!${emoji("😄")}",
            "I told my computer I needed a break.\nNow it won't stop sending me Kit-Kat ads.${emoji("😂")}",
            "Why do programmers prefer dark mode?\nBecause light attracts bugs!${emoji("🐛")}",
            "I asked Jun to tell me a joke.\nHe said — you're reading it right now.${emoji("🤖")}",
            "Parallel lines have so much in common.\nIt's a shame they'll never meet.${emoji("😏")}",
            "Why did the smartphone go to therapy?\nBecause it had too many hang-ups.${emoji("📱")}"
        )
        val hinglishJokes = listOf(
            "Ek baar ek programmer apni girlfriend ke saath movie dekhne gaya.\nGirlfriend: 'Kitna romantic scene hai!'\nProgrammer: 'Haan, magar O(n²) complexity hai.'${emoji("😂")}",
            "Teacher: 'Ek sentence mein batao light kitni tez hoti hai?'\nStudent: 'Sir, itni tez ki refrigerator khulte hi andar ki light on ho jaati hai!'${emoji("😄")}",
            "Jun se pucha: 'Tujhe neend aati hai?'\nJun: 'Nahin, main sirf battery save mode mein chala jaata hun.'${emoji("🤖")}"
        )

        return if (isHinglish) hinglishJokes.random() else jokes.random()
    }

    private fun buildCoinFlip(): String {
        val result = if (Math.random() < 0.5) "Heads" else "Tails"
        return if (isHinglish) {
            "Sikka uchala${emoji("🪙")}... aur aaya — $result!"
        } else {
            "Flipping the coin${emoji("🪙")}... and it's — $result!"
        }
    }

    private fun buildDiceRoll(): String {
        val result = (1..6).random()
        return if (isHinglish) {
            "Pasa pheka${emoji("🎲")}... nikla — $result!"
        } else {
            "Rolling the dice${emoji("🎲")}... you got — $result!"
        }
    }

    private fun buildAction(english: String, emojiChar: String, hinglish: String): String {
        val base = if (isHinglish) hinglish else english
        val em = if (showEmoji) " $emojiChar" else ""
        return "$base$em"
    }

    private fun buildContactAction(action: String, contact: String, emojiChar: String): String {
        // BUGFIX: when no contact name was actually extracted (e.g. the user
        // just typed the bare trigger word "contact" — which happens to
        // ALSO be a CALL_CONTACT keyword, so contact ends up empty here),
        // this used to fall back to the literal placeholder string
        // "contact" and build "Calling contact📞" — a sentence that reads
        // exactly like a real action just happened, misleading the user
        // into thinking Jun actually dialed someone. It should ask who,
        // not pretend "Contact" is a person's name.
        if (contact.isEmpty()) {
            val em = if (showEmoji) " $emojiChar" else ""
            val verb = if (action == "Calling") "call" else "message"
            return if (isHinglish) "Kisko $verb karun? Naam batao!$em" else "Who should I $verb? Tell me a name!$em"
        }
        val em = if (showEmoji) " $emojiChar" else ""
        return "$action $contact$em"
    }

    private fun buildSearchConfirm(query: String): String {
        val q = if (query.isNotEmpty()) "\"$query\"" else "that"
        return if (isHinglish) {
            "Dhundh raha hun $q${emoji("🔍")}..."
        } else {
            "Searching for $q${emoji("🔍")}..."
        }
    }

    private fun buildLearnAck(): String {
        return if (isHinglish) {
            "Seekh liya${emoji("🧠")} Ab main yeh yaad rakhunga!"
        } else {
            "Got it${emoji("🧠")} I've learned that and will remember it!"
        }
    }

    // ─────────────────────────────────────────────────────────────
    // UNKNOWN INPUT — when Jun can't match intent
    // ─────────────────────────────────────────────────────────────

    fun forUnknown(input: String): String {
        val question = input.trim()

        // If it looks like a question, try to give a thoughtful fallback
        if (question.endsWith("?") || IntentDetector.isQuestion(question)) {
            return if (isHinglish) {
                "Hmm${emoji("🤔")}, yeh mujhe abhi nahi pata. " +
                "Lekin tu mujhe sikhaa sakta hai! Bas likh:\n" +
                "\"${question.removeSuffix("?")} = [answer]\"\n" +
                "Aur main yaad kar lunga."
            } else {
                "Hmm${emoji("🤔")}, I don't know that yet. " +
                "But you can teach me! Just type:\n" +
                "\"$question = [your answer]\"\n" +
                "And I'll remember it."
            }
        }

        // General unknown
        val responses = if (isHinglish) listOf(
            "Yeh samajh nahi aaya${emoji("🤷")} Thoda aur clearly bol?",
            "Hmm${emoji("🤔")}, iska matlab clear nahi hua. Kuch aur tarike se bol?",
            "Yeh mere commands mein nahi hai abhi${emoji("😅")} Mujhe sikhao!"
        ) else listOf(
            "I didn't quite understand that${emoji("🤷")} Could you rephrase it?",
            "Hmm${emoji("🤔")}, that's not something I know how to handle yet.",
            "I'm not sure what you mean${emoji("😅")} Try saying it differently!"
        )

        return responses.random()
    }

    // ─────────────────────────────────────────────────────────────
    // FACT-BASED RESPONSES (battery, name, etc.)
    // ─────────────────────────────────────────────────────────────

    fun withFact(factKey: String, value: String): String {
        return when (factKey) {
            "battery" -> {
                if (isHinglish) "Tera phone ka charge hai $value${emoji("🔋")}"
                else "Your battery is at $value${emoji("🔋")}"
            }
            "name" -> {
                if (isHinglish) "Haan, main jaanta hun — tu hai ${profile.name ?: value}${emoji("😊")}"
                else "Yes, I know — you are ${profile.name ?: value}${emoji("😊")}"
            }
            else -> value
        }
    }

    // ─────────────────────────────────────────────────────────────
    // LOW CONFIDENCE — Jun matched but isn't sure
    // ─────────────────────────────────────────────────────────────

    fun forLowConfidence(guessedIntent: IntentDetector.Intent, input: String): String {
        val intentLabel = guessedIntent.name.lowercase().replace("_", " ")
        return if (isHinglish) {
            "Kya tu \"$intentLabel\" karna chahta hai${emoji("🤔")}? Confirm kar!"
        } else {
            "Did you mean to \"$intentLabel\"${emoji("🤔")}? Let me know!"
        }
    }

    // ─────────────────────────────────────────────────────────────
    // PERSONALIZED HINT (Long mode only)
    // ─────────────────────────────────────────────────────────────

    private fun buildPersonalizedHint(): String {
        val topIntent = profile.topIntents.firstOrNull() ?: return ""
        val label = topIntent.lowercase().replace("_", " ")
        return if (isHinglish) {
            " Teri favorite cheez toh lagta hai $label hai${emoji("⚡")}"
        } else {
            " Looks like your favorite thing is $label${emoji("⚡")}"
        }
    }

    // ─────────────────────────────────────────────────────────────
    // UTILITY
    // ─────────────────────────────────────────────────────────────

    private fun emoji(e: String): String = if (showEmoji) e else ""
}
