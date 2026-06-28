package com.junai.app

import android.animation.ObjectAnimator
import android.app.Activity
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.view.View
import androidx.recyclerview.widget.RecyclerView
import com.junai.app.learning.LearningEngineV2Repository
import com.junai.app.memory.GraphQueryResolver
import com.junai.app.memory.KnowledgeGraphRepository
import com.junai.app.memory.MemoryRepository
import com.junai.app.memory.SemanticMemoryRepository
import com.junai.app.memory.SemanticQueryResolver
import com.junai.app.planning.PlanQueryResolver
import com.junai.app.planning.PlanRepository
import com.junai.app.reasoning.ConfidenceEngine
import com.junai.app.reasoning.ConfidenceRepository
import com.junai.app.reasoning.CuriosityEngine
import com.junai.app.reasoning.CuriosityRepository
import com.junai.app.reasoning.ReflectionQueryResolver
import com.junai.app.reasoning.ReflectionRepository
import com.junai.app.reasoning.RuleRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Handles every branch of the IntentDetector result reached from the main
 * chat send button. Integrated with UserPreferenceManager + ResponseBuilder
 * for personalized, high-quality responses.
 *
 * Phase 2: every turn captured into MemoryRepository, scored via ImportanceEngine.
 * Phase 4: SemanticMemoryRepository — fact capture + "what do I like?" answers.
 * Phase 5: KnowledgeGraphRepository — connects facts into chains.
 * Phase 6: RuleRepository/ReasoningEngine — proactive recommendations.
 * Phase 7: ReflectionRepository — once-per-day "what did I learn" log.
 * Phase 8: ConfidenceEngine/ConfidenceRepository — formalized confidence tiers.
 * Phase 9: CuriosityEngine/CuriosityRepository — asks follow-ups instead of
 * guessing, learns from the answer.
 * Phase 10: PlanRepository/GoalDecomposer — multi-step goal tracking.
 * Phase 14 (NEW): LearningEngineV2Repository — surfaces a gentle nudge on
 * GREET when the same question has failed 3+ times and still isn't
 * answerable, asking the user to teach it. The deeper Phase 14 work
 * (reinforcement instead of duplicate facts/edges) lives inside
 * SemanticMemoryRepository.captureFact() and
 * KnowledgeGraphRepository.captureRelation() — no change needed here for
 * that part, since those are called the same way they already were.
 *
 * IMPORTANT (lesson from the Phase 4 bug): ALL question-style intercepts
 * run in handle(), BEFORE the IntentDetector dispatch below — never inside
 * the UNKNOWN branch, since IntentDetector can misclassify these.
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
    private val memoryRepo = MemoryRepository(activity)
    private val semanticMemoryRepo = SemanticMemoryRepository(activity)
    private val knowledgeGraphRepo = KnowledgeGraphRepository(activity)
    private val ruleRepository = RuleRepository(activity)
    private val reflectionRepo = ReflectionRepository(activity, learningRepo, semanticMemoryRepo, knowledgeGraphRepo)
    private val confidenceRepo = ConfidenceRepository(learningRepo)
    private val curiosityRepo = CuriosityRepository(learningRepo)
    private val planRepository = PlanRepository(activity)
    private val learningEngineV2Repo = LearningEngineV2Repository(learningRepo)

    // Single lifecycle-aware scope — cancel() called from MainActivity.onDestroy()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    /** Call from MainActivity.onDestroy() to cancel all in-flight coroutines. */
    fun cancelScope() {
        scope.cancel()
        memoryRepo.cancelScope()
    }

    init {
        // Runs ranking/compression/promotion/forgetting once per session start
        memoryRepo.runMaintenanceAsync()
        // Phase 7 — generates today's reflection once, if not already done today
        scope.launch {
            reflectionRepo.runDailyReflectionIfNeeded()
        }
    }

    // ─────────────────────────────────────────────────────────────
    // MAIN ENTRY POINT
    // ─────────────────────────────────────────────────────────────

    fun handle(intentResult: IntentDetector.IntentResult, target: String, text: String, recyclerView: RecyclerView) {

        // 0. Phase 9 — HIGHEST PRIORITY: if Jun is waiting on an answer to
        // its own curiosity follow-up, resolve it before anything else.
        if (curiosityRepo.hasPendingQuestion()) {
            val looksLikeRealCommand = intentResult.intent != IntentDetector.Intent.UNKNOWN
            if (looksLikeRealCommand) {
                curiosityRepo.cancelPending()
            } else {
                scope.launch {
                    val ack = curiosityRepo.resolveWithAnswer(text)
                    withContext(Dispatchers.Main) {
                        reply(ack, recyclerView)
                    }
                }
                return
            }
        }

        // 1. Resolve pronouns & follow-up context
        context.resolveInput(text)

        // 1b. Confusion detection
        if (context.isUserConfused(text)) {
            reply(context.getConfusionHint(), recyclerView)
            return
        }

        // 1c. Phase 4 — semantic question intercept (runs before dispatch)
        val semanticQuestion = SemanticQueryResolver.resolve(text)
        if (semanticQuestion != null) {
            showTyping()
            scope.launch {
                val answer = semanticMemoryRepo.answerQuery(text)
                withContext(Dispatchers.Main) {
                    hideTyping()
                    if (answer != null) {
                        reply(answer, recyclerView)
                    } else {
                        proceedWithIntentHandling(intentResult, target, text, recyclerView)
                    }
                }
            }
            return
        }

        // 1d. Phase 5 — graph question intercept (also before dispatch)
        val graphQuery = GraphQueryResolver.resolve(text)
        if (graphQuery != null) {
            showTyping()
            scope.launch {
                val answer = when (graphQuery) {
                    is GraphQueryResolver.GraphQuery.RelatedTo ->
                        knowledgeGraphRepo.answerHowRelated(graphQuery.nodeA, graphQuery.nodeB)
                    is GraphQueryResolver.GraphQuery.UsedFor ->
                        knowledgeGraphRepo.answerUsedFor(graphQuery.node)
                    is GraphQueryResolver.GraphQuery.AboutConcept ->
                        knowledgeGraphRepo.answerAboutConcept(graphQuery.node)
                }
                withContext(Dispatchers.Main) {
                    hideTyping()
                    if (answer != null) {
                        reply(answer, recyclerView)
                    } else {
                        proceedWithIntentHandling(intentResult, target, text, recyclerView)
                    }
                }
            }
            return
        }

        // 1e. Phase 7 — reflection question intercept (also before dispatch)
        if (ReflectionQueryResolver.isReflectionQuery(text)) {
            showTyping()
            scope.launch {
                val formatted = reflectionRepo.formatLatestForChat()
                withContext(Dispatchers.Main) {
                    hideTyping()
                    reply(
                        formatted ?: "Abhi tak koi reflection generate nahi hui — thoda data collect hone do, jaldi ban jayegi \uD83D\uDCDD",
                        recyclerView
                    )
                }
            }
            return
        }

        // 1f. Phase 10 — plan command intercept. Deterministic, always returns.
        val planQuery = PlanQueryResolver.resolve(text)
        if (planQuery != null) {
            showTyping()
            scope.launch {
                val responseText = when (planQuery) {
                    is PlanQueryResolver.PlanQuery.CreatePlan -> planRepository.createPlan(planQuery.goalText)
                    is PlanQueryResolver.PlanQuery.ShowPlans -> planRepository.getActivePlansSummary()
                    is PlanQueryResolver.PlanQuery.NextStep -> planRepository.getNextStepText()
                    is PlanQueryResolver.PlanQuery.MarkStepDone -> planRepository.markCurrentStepDone()
                }
                withContext(Dispatchers.Main) {
                    hideTyping()
                    reply(responseText, recyclerView)
                }
            }
            return
        }

        proceedWithIntentHandling(intentResult, target, text, recyclerView)
    }

    // ─────────────────────────────────────────────────────────────
    // NORMAL INTENT DISPATCH (unchanged from earlier phases, extracted
    // into its own function so the intercepts above can fall through to it)
    // ─────────────────────────────────────────────────────────────

    private fun proceedWithIntentHandling(
        intentResult: IntentDetector.IntentResult,
        target: String,
        text: String,
        recyclerView: RecyclerView
    ) {

        // 2. Learn from every message the user sends
        userPrefs.detectAndSaveLanguageStyle(text)
        if (IntentDetector.isPersonalStatement(text)) {
            userPrefs.tryExtractFact(text)
            scope.launch {
                semanticMemoryRepo.captureFact(text)
            }
        }

        // Phase 5 — try to extract a graph relation from EVERY message
        scope.launch {
            knowledgeGraphRepo.captureRelation(text)
        }

        // 2. Record successful intent usage (skip UNKNOWN)
        if (intentResult.intent != IntentDetector.Intent.UNKNOWN) {
            userPrefs.recordIntent(intentResult.intent.name)
        }

        // 3. Dispatch
        when (intentResult.intent) {

            IntentDetector.Intent.GREET -> {
                reply(builder().forIntent(IntentDetector.Intent.GREET), recyclerView)
                // Phase 6 — a greeting is a natural, non-intrusive moment
                // to surface a proactive recommendation if one fires.
                maybeShowRecommendation(recyclerView)
                // Phase 14 — also check for a repeated-failure learning nudge.
                // Separate call (not combined with the rule recommendation
                // above) since it needs a suspend DB read.
                maybeShowLearningNudge(recyclerView)
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
                        scope.launch {
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
                    "Konsa app open karun? \uD83E\uDD14" else "Which app should I open? \uD83E\uDD14", recyclerView)
                recyclerView.scrollToPosition(messages.size - 1)
                onSaveChat()
            }

            IntentDetector.Intent.CALL_CONTACT -> {
                if (target.isNotEmpty()) appCommandHandler.makeCall(target)
                else reply(builder().forIntent(IntentDetector.Intent.CALL_CONTACT, target), recyclerView)
                recyclerView.scrollToPosition(messages.size - 1)
                onSaveChat()
            }

            IntentDetector.Intent.SEND_MESSAGE -> {
                if (target.isNotEmpty()) appCommandHandler.sendMessage(target)
                else reply(
                    if (userPrefs.getUserProfile().languageStyle == UserPreferenceManager.STYLE_HINGLISH)
                        "Kisko message karun? Naam batao! 💬"
                    else "Who should I message? Tell me the name! 💬",
                    recyclerView
                )
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
                val status   = if (charging) "\u26A1 Charging" else "\uD83D\uDD0B Not charging"
                val response = builder().withFact("battery", "$level% — $status")
                reply(response, recyclerView)
                speak(response)
                // Phase 6 — battery is literally what the rules reason about,
                // so this is the most natural place to surface a recommendation.
                maybeShowRecommendation(recyclerView)
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
            // (semantic + graph + reflection + plan checks no longer live
            // here — they run earlier in handle(), before this dispatch)
            IntentDetector.Intent.UNKNOWN -> {
                showTyping()

                scope.launch {
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
                            // Record context here too — trainedCommand path was skipping this
                            context.record(
                                userMessage = text,
                                junResponse = _lastResponse,
                                intent      = intentResult.intent.name,
                                entity      = intentResult.extractedEntity
                            )
                        }
                        return@launch
                    }

                    // 2. Knowledge base lookup
                    val searchResult = learningRepo.findAnswer(text)

                    // Phase 8 — single source of truth for HIGH/MEDIUM/LOW
                    val confidenceLevel = ConfidenceEngine.classify(
                        ConfidenceEngine.normalize(searchResult.confidence, scaleMax = 100f)
                    )

                    withContext(Dispatchers.Main) {
                        Handler(Looper.getMainLooper()).postDelayed({
                            hideTyping()

                            val response: String = when {
                                searchResult.answer != null && confidenceLevel == ConfidenceEngine.ConfidenceLevel.HIGH ->
                                    searchResult.answer

                                searchResult.answer != null && confidenceLevel == ConfidenceEngine.ConfidenceLevel.MEDIUM ->
                                    "I think you mean:\n${searchResult.answer}"

                                else -> {
                                    scope.launch {
                                        if (searchResult.answer != null) {
                                            confidenceRepo.logIfLow(
                                                question = text,
                                                intentName = intentResult.intent.name,
                                                rawConfidence = searchResult.confidence,
                                                scaleMax = 100f
                                            )
                                        } else {
                                            learningRepo.logFailure(
                                                question        = text,
                                                detectedIntent  = intentResult.intent.name,
                                                confidence      = intentResult.confidence.toFloat(),
                                                failureReason   = "NO_MATCH"
                                            )
                                        }
                                    }
                                    // Phase 9 — if this genuinely looks like a question
                                    // and nothing else matched, ask a curious follow-up.
                                    if (CuriosityEngine.isQuestionLike(text)) {
                                        curiosityRepo.askAbout(text)
                                    } else {
                                        builder().forUnknown(text)
                                    }
                                }
                            }

                            if (response.isNotEmpty()) {
                                _lastResponse = response  // sync before record
                                chatAdapter.addMessage(ChatMessage(response, isUser = false))
                                if (searchResult.relatedQuestions.isNotEmpty()) {
                                    val related = "Related: " + searchResult.relatedQuestions.take(3).joinToString(" • ")
                                    chatAdapter.addMessage(ChatMessage(related, isUser = false))
                                }
                                recyclerView.scrollToPosition(messages.size - 1)
                                onSaveChat()
                                speak(response)
                            }

                            // Record AFTER response is ready — avoids empty _lastResponse bug
                            context.record(
                                userMessage = text,
                                junResponse = _lastResponse,
                                intent      = intentResult.intent.name,
                                entity      = intentResult.extractedEntity
                            )
                        }, 1500)
                    }
                }
            }

            else -> {}
        }

        // Record this turn into conversation context (for all intents except UNKNOWN,
        // which records inside its own async block above to avoid empty _lastResponse)
        if (intentResult.intent != IntentDetector.Intent.UNKNOWN) {
            context.record(
                userMessage = text,
                junResponse = _lastResponse,
                intent      = intentResult.intent.name,
                entity      = intentResult.extractedEntity
            )
        }

        // Phase 2: capture this turn into the hybrid memory system.
        scope.launch {
            memoryRepo.captureTurn(text, intentResult.intent.name)
        }
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

    /**
     * Phase 6 — checks the ReasoningEngine for a firing recommendation and,
     * if present, posts it as a follow-up message (no speak() — keeps it
     * a quiet visual nudge rather than an extra voice line).
     */
    private fun maybeShowRecommendation(recyclerView: RecyclerView) {
        val recommendation = ruleRepository.getRecommendation()
        if (recommendation != null) {
            chatAdapter.addMessage(ChatMessage(recommendation.recommendationText, isUser = false))
            recyclerView.scrollToPosition(messages.size - 1)
            onSaveChat()
        }
    }

    /**
     * Phase 14 — checks for a repeated-failure learning nudge and, if one
     * exists, posts it as a quiet follow-up message (no speak()).
     */
    private fun maybeShowLearningNudge(recyclerView: RecyclerView) {
        scope.launch {
            val nudge = learningEngineV2Repo.getRepeatedFailureNudge()
            if (nudge != null) {
                withContext(Dispatchers.Main) {
                    chatAdapter.addMessage(ChatMessage(nudge, isUser = false))
                    recyclerView.scrollToPosition(messages.size - 1)
                    onSaveChat()
                }
            }
        }
    }

    private fun showTyping() {
        typingIndicator.visibility = View.VISIBLE
        animateDot(dot1, 0)
        animateDot(dot2, 150)
        animateDot(dot3, 300)
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
