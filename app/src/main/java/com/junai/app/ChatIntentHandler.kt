package com.junai.app

import android.animation.ObjectAnimator
import android.app.Activity
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.view.View
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Handles every branch of the IntentDetector result reached from the main
 * chat send button. Extracted from MainActivity.setupChatUi()'s big `when`
 * block — zero logic change, just relocated. OPEN_APP/CALL_CONTACT delegate
 * to AppCommandHandler, SEARCH_WEB to WebSearchHelper, and the UNKNOWN
 * fallback (trained-command lookup + learning search) stays here since it
 * was already in MainActivity, mirroring the original behavior exactly.
 */
class ChatIntentHandler(
    private val activity: Activity,
    private val chatAdapter: ChatAdapter,
    private val messages: MutableList<ChatMessage>,
    private val learningRepo: LearningRepository,
    private val webSearchHelper: WebSearchHelper,
    private val appCommandHandler: AppCommandHandler,
    private val trainedCommandHandler: TrainedCommandHandler,
    private val typingIndicator: View,
    private val dot1: View,
    private val dot2: View,
    private val dot3: View,
    private val speak: (String) -> Unit,
    private val onSaveChat: () -> Unit,
    private val onEnableSend: () -> Unit
) {

    fun handle(intentResult: IntentDetector.IntentResult, target: String, text: String, recyclerView: RecyclerView) {
        when (intentResult.intent) {
            IntentDetector.Intent.GREET -> {
                val responses = listOf(
                    "Hello! 👋 Main Jun hun, tumhari AI assistant!",
                    "Hi! Kya haal hai? 😊",
                    "Hey! Kya main help kar sakti hun?",
                    "Namaste! 🙏 Kya chahiye?"
                )
                val response = responses.random()
                chatAdapter.addMessage(ChatMessage(response, isUser = false))
                recyclerView.scrollToPosition(messages.size - 1)
                onSaveChat()
                speak(response)
            }

            IntentDetector.Intent.HOW_ARE_YOU -> {
                val responses = listOf(
                    "Main bilkul theek hun! Aur tum? 😊",
                    "Mast hun! Ready to help! 🚀",
                    "Badhiya! Tumhara din kaisa ja raha hai? 😄"
                )
                val response = responses.random()
                chatAdapter.addMessage(ChatMessage(response, isUser = false))
                recyclerView.scrollToPosition(messages.size - 1)
                onSaveChat()
                speak(response)
            }

            IntentDetector.Intent.THANK -> {
                val responses = listOf(
                    "Koi baat nahi! 😊",
                    "Khushi hui help karke! 🙏",
                    "Always here for you! ❤️",
                    "Welcome! Kuch aur chahiye toh batao!"
                )
                val response = responses.random()
                chatAdapter.addMessage(ChatMessage(response, isUser = false))
                recyclerView.scrollToPosition(messages.size - 1)
                onSaveChat()
                speak(response)
            }

            IntentDetector.Intent.LEARN_QA -> {
                val parts = text.split("=", limit = 2)
                if (parts.size == 2) {
                    val question = parts[0].trim()
                    val answer = parts[1].trim()
                    if (question.isNotEmpty() && answer.isNotEmpty()) {
                        CoroutineScope(Dispatchers.IO).launch {
                            learningRepo.trainKnowledge(question, answer)
                        }
                        val response = "Got it! I'll remember: \"$question\" = \"$answer\" ✅"
                        chatAdapter.addMessage(ChatMessage(response, isUser = false))
                        recyclerView.scrollToPosition(messages.size - 1)
                        onSaveChat()
                        speak(response)
                    }
                }
            }

            IntentDetector.Intent.CLEAR_CHAT -> {
                messages.clear()
                chatAdapter.notifyDataSetChanged()
                activity.getSharedPreferences("chat_prefs", android.content.Context.MODE_PRIVATE)
                    .edit().putString("chat_list", "[]").apply()
                val response = "Chat clear ho gaya! 🧹"
                chatAdapter.addMessage(ChatMessage(response, isUser = false))
                onSaveChat()
            }

            IntentDetector.Intent.OPEN_APP -> {
                if (target.isNotEmpty()) appCommandHandler.openApp(target)
                else chatAdapter.addMessage(ChatMessage("Konsa app open karun? 🤔", isUser = false))
                recyclerView.scrollToPosition(messages.size - 1)
                onSaveChat()
            }

            IntentDetector.Intent.CALL_CONTACT -> {
                if (target.isNotEmpty()) appCommandHandler.makeCall(target)
                else chatAdapter.addMessage(ChatMessage("Kisko call karun? 📞", isUser = false))
                recyclerView.scrollToPosition(messages.size - 1)
                onSaveChat()
            }

            IntentDetector.Intent.SEARCH_WEB -> {
                val query = if (target.isNotEmpty()) target else text.replace("search", "").trim()
                if (query.isNotEmpty()) webSearchHelper.search(query, recyclerView) else onEnableSend()
            }

            IntentDetector.Intent.SHOW_NOTES -> activity.startActivity(Intent(activity, NotesActivity::class.java))
            IntentDetector.Intent.SHOW_TODO -> activity.startActivity(Intent(activity, TodoActivity::class.java))
            IntentDetector.Intent.SHOW_CALCULATOR -> activity.startActivity(Intent(activity, CalculatorActivity::class.java))
            IntentDetector.Intent.SHOW_DRAW -> activity.startActivity(Intent(activity, DrawActivity::class.java))
            IntentDetector.Intent.SHOW_TRANSLATOR -> activity.startActivity(Intent(activity, TranslatorActivity::class.java))
            IntentDetector.Intent.SHOW_REMINDER -> activity.startActivity(Intent(activity, ReminderActivity::class.java))
            IntentDetector.Intent.SHOW_SETTINGS -> activity.startActivity(Intent(activity, SettingsActivity::class.java))
            IntentDetector.Intent.SHOW_MUSIC -> activity.startActivity(Intent(activity, MusicHomeActivity::class.java))
            IntentDetector.Intent.SHOW_UNANSWERED -> activity.startActivity(Intent(activity, UnansweredActivity::class.java))
            IntentDetector.Intent.SHOW_VOICE_COMMANDS -> activity.startActivity(Intent(activity, VoiceCommandsActivity::class.java))
            IntentDetector.Intent.SHOW_DATA_MANAGEMENT -> activity.startActivity(Intent(activity, DataManagementActivity::class.java))

            IntentDetector.Intent.PLAY_MUSIC -> {
                activity.startActivity(Intent(activity, MusicHomeActivity::class.java))
                chatAdapter.addMessage(ChatMessage("Music open kar rahi hun! 🎵", isUser = false))
                recyclerView.scrollToPosition(messages.size - 1)
                onSaveChat()
            }

            IntentDetector.Intent.PAUSE_MUSIC -> {
                val si = Intent(activity, MusicService::class.java)
                si.action = "PAUSE"
                activity.startService(si)
                chatAdapter.addMessage(ChatMessage("Music pause! ⏸️", isUser = false))
                recyclerView.scrollToPosition(messages.size - 1)
                onSaveChat()
            }

            IntentDetector.Intent.NEXT_SONG -> {
                val si = Intent(activity, MusicService::class.java)
                si.action = "NEXT"
                activity.startService(si)
                chatAdapter.addMessage(ChatMessage("Next song! ⏭️", isUser = false))
                recyclerView.scrollToPosition(messages.size - 1)
                onSaveChat()
            }

            IntentDetector.Intent.PREV_SONG -> {
                val si = Intent(activity, MusicService::class.java)
                si.action = "PREV"
                activity.startService(si)
                chatAdapter.addMessage(ChatMessage("Previous song! ⏮️", isUser = false))
                recyclerView.scrollToPosition(messages.size - 1)
                onSaveChat()
            }

            IntentDetector.Intent.SET_REMINDER -> {
                activity.startActivity(Intent(activity, ReminderActivity::class.java))
                chatAdapter.addMessage(ChatMessage("Reminder screen open kar rahi hun! ⏰", isUser = false))
                recyclerView.scrollToPosition(messages.size - 1)
                onSaveChat()
            }

            IntentDetector.Intent.CREATE_NOTE -> {
                activity.startActivity(Intent(activity, NotesActivity::class.java))
                chatAdapter.addMessage(ChatMessage("Notes screen open kar rahi hun! 📝", isUser = false))
                recyclerView.scrollToPosition(messages.size - 1)
                onSaveChat()
            }

            IntentDetector.Intent.TELL_TIME -> {
                val time = java.text.SimpleDateFormat("hh:mm a", java.util.Locale.getDefault()).format(java.util.Date())
                val response = "Abhi time hai: $time ⏰"
                chatAdapter.addMessage(ChatMessage(response, isUser = false))
                recyclerView.scrollToPosition(messages.size - 1)
                onSaveChat()
                speak(response)
            }

            IntentDetector.Intent.TELL_DATE -> {
                val date = java.text.SimpleDateFormat("dd MMMM yyyy, EEEE", java.util.Locale.getDefault()).format(java.util.Date())
                val response = "Aaj ki date hai: $date 📅"
                chatAdapter.addMessage(ChatMessage(response, isUser = false))
                recyclerView.scrollToPosition(messages.size - 1)
                onSaveChat()
                speak(response)
            }

            IntentDetector.Intent.TELL_BATTERY -> {
                val bm = activity.getSystemService(android.content.Context.BATTERY_SERVICE) as android.os.BatteryManager
                val level = bm.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY)
                val charging = bm.isCharging
                val status = if (charging) "⚡ Charging" else "🔋 Not charging"
                val response = "Battery: $level% — $status"
                chatAdapter.addMessage(ChatMessage(response, isUser = false))
                recyclerView.scrollToPosition(messages.size - 1)
                onSaveChat()
                speak(response)
            }

            IntentDetector.Intent.TELL_JOKE -> {
                val jokes = listOf(
                    "Maine ek AI se pucha — 'Kya tum insaan ban sakte ho?' Usne bola — 'Haan, bas ek update aur!' 😂",
                    "Teacher: 'Calculator use mat karo!' Student: 'Jun, help karo!' Jun: 'Main hun na! 😎'",
                    "Ek aadmi Google Maps pe khud ko dhundh raha tha... Jun ne bola — 'Bhai, mirror dekho!' 😂",
                    "Phone low battery pe tha... Jun boli — 'Main bhi thak jaati hun kabhi kabhi!' 🔋😄"
                )
                val response = jokes.random()
                chatAdapter.addMessage(ChatMessage(response, isUser = false))
                recyclerView.scrollToPosition(messages.size - 1)
                onSaveChat()
                speak(response)
            }

            IntentDetector.Intent.FLIP_COIN -> {
                val result = if ((0..1).random() == 0) "Heads! 🪙" else "Tails! 🪙"
                val response = "Coin toss result: $result"
                chatAdapter.addMessage(ChatMessage(response, isUser = false))
                recyclerView.scrollToPosition(messages.size - 1)
                onSaveChat()
                speak(response)
            }

            IntentDetector.Intent.ROLL_DICE -> {
                val result = (1..6).random()
                val response = "Dice result: $result 🎲"
                chatAdapter.addMessage(ChatMessage(response, isUser = false))
                recyclerView.scrollToPosition(messages.size - 1)
                onSaveChat()
                speak(response)
            }

            IntentDetector.Intent.UNKNOWN -> {
                typingIndicator.visibility = View.VISIBLE
                animateDot(dot1, 0)
                animateDot(dot2, 150)
                animateDot(dot3, 300)

                CoroutineScope(Dispatchers.IO).launch {
                    val commands = learningRepo.getAllCommands()
                    val matchedCmd = commands.firstOrNull { cmd ->
                        text.lowercase().contains(cmd.phrase.lowercase()) ||
                        cmd.phrase.lowercase().contains(text.lowercase())
                    }

                    if (matchedCmd != null) {
                        withContext(Dispatchers.Main) {
                            typingIndicator.visibility = View.GONE
                            dot1.clearAnimation()
                            dot2.clearAnimation()
                            dot3.clearAnimation()
                            trainedCommandHandler.handle(matchedCmd.intent, matchedCmd.target, text, recyclerView)
                        }
                        return@launch
                    }

                    val searchResult = learningRepo.findAnswer(text)

                    withContext(Dispatchers.Main) {
                        Handler(Looper.getMainLooper()).postDelayed({
                            typingIndicator.visibility = View.GONE
                            dot1.clearAnimation()
                            dot2.clearAnimation()
                            dot3.clearAnimation()

                            val response: String
                            if (searchResult.answer != null && searchResult.confidence >= 90f) {
                                response = searchResult.answer
                            } else if (searchResult.answer != null && searchResult.confidence >= 70f) {
                                response = "I think you mean:\n${searchResult.answer}"
                            } else {
                                CoroutineScope(Dispatchers.IO).launch {
                                    learningRepo.logFailure(
                                        question = text,
                                        detectedIntent = intentResult.intent.name,
                                        confidence = intentResult.confidence.toFloat(),
                                        failureReason = "NO_MATCH"
                                    )
                                }
                                response = "I don't know yet, but I'm learning! Check Learning Center. 🧠"
                            }

                            if (response.isNotEmpty()) {
                                chatAdapter.addMessage(ChatMessage(response, isUser = false))
                                if (searchResult.relatedQuestions.isNotEmpty()) {
                                    val related = "Related: " + searchResult.relatedQuestions.take(3).joinToString(" • ")
                                    chatAdapter.addMessage(ChatMessage(related, isUser = false))
                                }
                                recyclerView.scrollToPosition(messages.size - 1)
                                onSaveChat()
                                speak(response)
                            }
                        }, 1500)
                    }
                }
            }

            else -> {}
        }
    }

    private fun animateDot(dot: View, delay: Long) {
        val animator = ObjectAnimator.ofFloat(dot, "alpha", 0.2f, 1f)
        animator.duration = 400
        animator.repeatMode = ObjectAnimator.REVERSE
        animator.repeatCount = ObjectAnimator.INFINITE
        animator.startDelay = delay
        animator.start()
    }
}
