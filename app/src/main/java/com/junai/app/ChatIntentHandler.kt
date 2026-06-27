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
 * chat send button. Now integrated with UserPreferenceManager + ResponseBuilder
 * for personalized, high-quality responses.
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

    // ── Core intelligence objects ──
    private val userPrefs = UserPreferenceManager(activity)
    private fun builder() = ResponseBuilder(userPrefs.getUserProfile())
    private val context = ConversationContext.instance
    private val feedbackLearner = FeedbackLearner(activity)

    // ─────────────────────────────────────────────────────────────
    // MAIN ENTRY POINT
    // ─────────────────────────────────────────────────────────────

    fun handle(intentResult: IntentDetector.IntentResult, target: String, text: String, recyclerView: RecyclerView) {

        // 1. Resolve pronouns & follow-up context
        val resolvedText = context.resolveInput(text)
        val effectiveText = if (resolvedText != text) resolvedText else text

        // 1b. Confusion detection
        if (context.isUserConfused(text)) {
            reply(context.getConfusionHint(), recyclerView)
            return
        }

        // 2. Learn from every message the user sends
        userPrefs.detectAndSaveLanguageStyle(text)
        if (IntentDetector.isPersonalStatement(text)) {
            userPrefs.tryExtractFact(text)
        }

        // 2. Record successful intent usage (skip UNKNOWN)
        if (intentResult.intent != IntentDetector.Intent.UNKNOWN) {
            userPrefs.recordIntent(intentResult.intent.name)
        }

        // 3. Dispatch
        when (intentResult.intent) {

            IntentDetector.Intent.GREET -> {
                reply(builder().forIntent(IntentDetector.Intent.GREET), recyclerView)
            }

            IntentDetector.Intent.HOW_ARE_YOU -> {
                reply(builder().forIntent(IntentDetector.Intent.HOW_ARE_YOU), recyclerView)
            }

            IntentDetector.Intent.THANK -> {
                reply(builder().forIntent(IntentDetector.Intent.THANK), recyclerView)
            }

            IntentDetector.Intent.WHO_ARE_YOU -> {
                reply(builder().forIntent(IntentDetector.Intent.WHO_ARE_YOU), recyclerView)
            }

            IntentDetector.Intent.USER_INFO -> {
                // Fact already extracted above via tryExtractFact()
                reply(builder().forIntent(IntentDetector.Intent.USER_INFO, intentResult.extractedEntity), recyclerView)
            }

            IntentDetector.Intent.LEARN_QA -> {
                val parts = text.split("=", limit = 2)
                if (parts.size == 2) {
                    val question = parts[0].trim()
                    val answer   = parts[1].trim()
                    if (question.isNotEmpty() && answer.isNotEmpty()) {
                        CoroutineScope(Dispatchers.IO).launch {
                            learningRepo.trainKnowledge(question, answer)
                        }
                        reply(builder().forIntent(IntentDetector.Intent.LEARN_QA), recyclerView)
                    }
                }
            }

            IntentDetector.Intent.CLEAR_CHAT -> {
                messages.clear()
                chatAdapter.notifyDataSetChanged()
                activity.getSharedPreferences("chat_prefs", android.content.Context.MODE_PRIVATE)
                    .edit().putString("chat_list", "[]").apply()
                context.clear()  // Reset conversation memory on chat clear
                reply(builder().forIntent(IntentDetector.Intent.CLEAR_CHAT), recyclerView)
            }

            IntentDetector.Intent.OPEN_APP -> {
                if (target.isNotEmpty()) appCommandHandler.openApp(target)
                else reply(if (userPrefs.getUserProfile().languageStyle == UserPreferenceManager.STYLE_HINGLISH)
                    "Konsa app open karun? 🤔" else "Which app should I open? 🤔", recyclerView)
                recyclerView.scrollToPosition(messages.size - 1)
                onSaveChat()
            }

            IntentDetector.Intent.CALL_CONTACT -> {
                if (target.isNotEmpty()) appCommandHandler.makeCall(target)
                else reply(builder().forIntent(IntentDetector.Intent.CALL_CONTACT, target), recyclerView)
                recyclerView.scrollToPosition(messages.size - 1)
                onSaveChat()
            }

            IntentDetector.Intent.SEARCH_WEB -> {
                val query = if (target.isNotEmpty()) target else text.replace("search", "").trim()
                if (query.isNotEmpty()) webSearchHelper.search(query, recyclerView) else onEnableSend()
            }

            // ── Screen navigation — no response needed, just launch ──
            IntentDetector.Intent.SHOW_NOTES          -> activity.startActivity(Intent(activity, NotesActivity::class.java))
            IntentDetector.Intent.SHOW_TODO           -> activity.startActivity(Intent(activity, TodoActivity::class.java))
            IntentDetector.Intent.SHOW_CALCULATOR     -> activity.startActivity(Intent(activity, CalculatorActivity::class.java))
            IntentDetector.Intent.SHOW_DRAW           -> activity.startActivity(Intent(activity, DrawActivity::class.java))
            IntentDetector.Intent.SHOW_TRANSLATOR     -> activity.startActivity(Intent(activity, TranslatorActivity::class.java))
            IntentDetector.Intent.SHOW_REMINDER       -> activity.startActivity(Intent(activity, ReminderActivity::class.java))
            IntentDetector.Intent.SHOW_SETTINGS       -> activity.startActivity(Intent(activity, SettingsActivity::class.java))
            IntentDetector.Intent.SHOW_MUSIC          -> activity.startActivity(Intent(activity, MusicHomeActivity::class.java))
            IntentDetector.Intent.SHOW_UNANSWERED     -> activity.startActivity(Intent(activity, UnansweredActivity::class.java))
            IntentDetector.Intent.SHOW_VOICE_COMMANDS -> activity.startActivity(Intent(activity, VoiceCommandsActivity::class.java))
            IntentDetector.Intent.SHOW_DATA_MANAGEMENT-> activity.startActivity(Intent(activity, DataManagementActivity::class.java))

            // ── Music controls ──
            IntentDetector.Intent.PLAY_MUSIC -> {
                activity.startActivity(Intent(activity, MusicHomeActivity::class.java))
                reply(builder().forIntent(IntentDetector.Intent.PLAY_MUSIC), recyclerView)
            }

            IntentDetector.Intent.PAUSE_MUSIC -> {
                activity.startService(Intent(activity, MusicService::class.java).apply { action = "PAUSE" })
                reply(builder().forIntent(IntentDetector.Intent.PAUSE_MUSIC), recyclerView)
            }

            IntentDetector.Intent.NEXT_SONG -> {
                activity.startService(Intent(activity, MusicService::class.java).apply { action = "NEXT" })
                reply(builder().forIntent(IntentDetector.Intent.NEXT_SONG), recyclerView)
            }

            IntentDetector.Intent.PREV_SONG -> {
                activity.startService(Intent(activity, MusicService::class.java).apply { action = "PREV" })
                reply(builder().forIntent(IntentDetector.Intent.PREV_SONG), recyclerView)
            }

            IntentDetector.Intent.STOP_MUSIC -> {
                activity.startService(Intent(activity, MusicService::class.java).apply { action = "STOP" })
                reply(builder().forIntent(IntentDetector.Intent.STOP_MUSIC), recyclerView)
            }

            // ── Reminders / Notes (with chat confirmation) ──
            IntentDetector.Intent.SET_REMINDER -> {
                activity.startActivity(Intent(activity, ReminderActivity::class.java))
                reply(builder().forIntent(IntentDetector.Intent.SET_REMINDER), recyclerView)
            }

            IntentDetector.Intent.CREATE_NOTE -> {
                activity.startActivity(Intent(activity, NotesActivity::class.java))
                reply(builder().forIntent(IntentDetector.Intent.CREATE_NOTE), recyclerView)
            }

            // ── Time / Date / Battery ──
            IntentDetector.Intent.TELL_TIME -> {
                val response = builder().forIntent(IntentDetector.Intent.TELL_TIME)
                reply(response, recyclerView)
                speak(response)
            }

            IntentDetector.Intent.TELL_DATE -> {
                val response = builder().forIntent(IntentDetector.Intent.TELL_DATE)
                reply(response, recyclerView)
                speak(response)
            }

            IntentDetector.Intent.TELL_BATTERY -> {
                val bm = activity.getSystemService(android.content.Context.BATTERY_SERVICE)
                        as android.os.BatteryManager
                val level    = bm.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY)
                val charging = bm.isCharging
                val status   = if (charging) "⚡ Charging" else "🔋 Not charging"
                val response = builder().withFact("battery", "$level% — $status")
                reply(response, recyclerView)
                speak(response)
            }

            // ── Fun ──
            IntentDetector.Intent.TELL_JOKE -> {
                val response = builder().forIntent(IntentDetector.Intent.TELL_JOKE)
                reply(response, recyclerView)
                speak(response)
            }

            IntentDetector.Intent.FLIP_COIN -> {
                val response = builder().forIntent(IntentDetector.Intent.FLIP_COIN)
                reply(response, recyclerView)
                speak(response)
            }

            IntentDetector.Intent.ROLL_DICE -> {
                val response = builder().forIntent(IntentDetector.Intent.ROLL_DICE)
                reply(response, recyclerView)
                speak(response)
            }

            // ── UNKNOWN — trained command lookup + learning fallback ──
            IntentDetector.Intent.UNKNOWN -> {
                typingIndicator.visibility = View.VISIBLE
                animateDot(dot1, 0)
                animateDot(dot2, 150)
                animateDot(dot3, 300)

                CoroutineScope(Dispatchers.IO).launch {
                    // 1. Check trained commands first
                    val commands = learningRepo.getAllCommands()
                    val matchedCmd = commands.firstOrNull { cmd ->
                        text.lowercase().contains(cmd.phrase.lowercase()) ||
                        cmd.phrase.lowercase().contains(text.lowercase())
                    }

                    if (matchedCmd != null) {
                        withContext(Dispatchers.Main) {
                            hideTyping()
                            trainedCommandHandler.handle(matchedCmd.intent, matchedCmd.target, text, recyclerView)
                        }
                        return@launch
                    }

                    // 2. Knowledge base lookup
                    val searchResult = learningRepo.findAnswer(text)

                    withContext(Dispatchers.Main) {
                        Handler(Looper.getMainLooper()).postDelayed({
                            hideTyping()

                            val response: String = when {
                                searchResult.answer != null && searchResult.confidence >= 90f ->
                                    searchResult.answer

                                searchResult.answer != null && searchResult.confidence >= 70f ->
                                    "I think you mean:\n${searchResult.answer}"

                                else -> {
                                    // Log failure for Learning Center
                                    CoroutineScope(Dispatchers.IO).launch {
                                        learningRepo.logFailure(
                                            question        = text,
                                            detectedIntent  = intentResult.intent.name,
                                            confidence      = intentResult.confidence.toFloat(),
                                            failureReason   = "NO_MATCH"
                                        )
                                    }
                                    // Personalized unknown response via ResponseBuilder
                                    builder().forUnknown(text)
                                }
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

        // Record this turn into conversation context
        context.record(
            userMessage = text,
            junResponse = _lastResponse,
            intent      = intentResult.intent.name,
            entity      = intentResult.extractedEntity
        )
    }

    // ─────────────────────────────────────────────────────────────
    // HELPERS
    // ─────────────────────────────────────────────────────────────

    /** Posts a message to chat and saves it. Does NOT call speak() — caller decides. */
    private var _lastResponse = ""
    private var _lastIntent  = ""
    private var _lastEntity  = ""

    private fun reply(response: String, recyclerView: RecyclerView) {
        _lastResponse = response
        chatAdapter.addMessage(ChatMessage(response, isUser = false))
        recyclerView.scrollToPosition(messages.size - 1)
        onSaveChat()
        speak(response)
    }

    private fun hideTyping() {
        typingIndicator.visibility = View.GONE
        dot1.clearAnimation()
        dot2.clearAnimation()
        dot3.clearAnimation()
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
