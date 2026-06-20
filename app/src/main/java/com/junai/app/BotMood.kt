package com.junai.app

enum class BotMood {
    SMILE,   // Default
    SLEEPY,  // No interaction for 1 minute
    DIZZY,   // Dragging for more than 30 seconds
    ANGRY    // No interaction for 10 minutes
}

object BotMoodMessages {
    val messages = mapOf(
        BotMood.SMILE  to listOf("Hi! 👋", "Hello! 😊", "Greetings! ✨"),
        BotMood.SLEEPY to listOf("Don't disturb me😴", "ZZzz...", "I need rest 🥱"),
        BotMood.DIZZY  to listOf("Slowly😵‍💫", "i..i..i🤮", "My head!😵"),
        BotMood.ANGRY  to listOf("So you are ignoring me?🤨", "Hey I am here too🥺", "I am invisible 😠")
    )
}
