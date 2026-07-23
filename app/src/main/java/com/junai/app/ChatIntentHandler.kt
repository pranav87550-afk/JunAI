package com.junai.app

import android.animation.ObjectAnimator
import android.app.Activity
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.view.View
import androidx.recyclerview.widget.RecyclerView
import com.junai.app.agent.AgentEngine
import com.junai.app.agent.action.MacroReplayEngine
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
    // Phase 15 — single entry point for all agent (multi-step / system-level) tasks.
    private val agentEngine = AgentEngine(activity)

    // Single lifecycle-aware scope — cancel() called from MainActivity.onDestroy()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    /**
     * PHASE 3 (confidence-aware trigger matching): set when findByTrigger()
     * misses but TriggerMatcher found a close-enough candidate, and we've
     * asked the user "Ye lagta hai '<phrase>' — chalayu?". The very next
     * message is checked against this before anything else (see handle()
     * step 0c) — an affirmative reply (TriggerMatcher.isAffirmative)
     * replays it, anything else just drops the suggestion and that message
     * is processed normally. Never carries across more than one turn.
     */
    private var pendingFuzzyMacro: com.junai.app.learning.RecordedMacroEntity? = null

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

        // 0b. Phase 15 — if an agent task is running and the user says "stop"/
        // "cancel", handle it immediately. IntentDetector alone might classify
        // "stop" as STOP_MUSIC, not "cancel my running task" — so this check
        // runs before normal dispatch, same pattern as the curiosity check above.
        if (agentEngine.isTaskRunning() && AgentEngine.looksLikeStopCommand(text)) {
            scope.launch {
                val ack = agentEngine.cancelCurrentTask()
                withContext(Dispatchers.Main) {
                    reply(ack, recyclerView)
                }
            }
            return
        }

        // 0c. PHASE 3 — resolve a pending fuzzy-match confirmation ("Ye
        // lagta hai '<phrase>' — chalayu?") before anything else, same
        // priority tier as the curiosity check above since it's also
        // waiting on a direct reply to Jun's own question. Only an
        // affirmative reply triggers the replay; anything else just drops
        // the suggestion and falls through to normal handling for
        // whatever the user actually said.
        val fuzzyMacroAwaitingConfirm = pendingFuzzyMacro
        if (fuzzyMacroAwaitingConfirm != null) {
            pendingFuzzyMacro = null
            if (com.junai.app.learning.TriggerMatcher.isAffirmative(text)) {
                if (MacroReplayEngine.isReplaying || agentEngine.isTaskRunning()) {
                    reply("Ek kaam pehle se chal raha hai — pehle wo complete hone do, phir dobara try karo.", recyclerView)
                    return
                }
                reply("Thik hai, kar rahi hoon... 🎬", recyclerView)
                scope.launch {
                    val resultMessage = MacroReplayEngine.replay(activity, fuzzyMacroAwaitingConfirm)
                    com.junai.app.AppDatabase.getInstance(activity).recordedMacroDao()
                        .markReplayed(fuzzyMacroAwaitingConfirm.id, System.currentTimeMillis())
                    withContext(Dispatchers.Main) { reply(resultMessage, recyclerView) }
                }
                return
            }
            // Not a yes — drop the suggestion, don't return; fall through
            // and let the rest of handle() process this message normally.
        }

        // 1. Resolve pronouns & follow-up context
        context.resolveInput(text)

        // 1b. Confusion detection
        if (context.isUserConfused(text)) {
            val hint = context.getConfusionHint()
            reply(hint, recyclerView)
            // IMPORTANT: record this turn so lastUserMessage/sameMessageCount
            // resets on the next differing input — without this, the app gets
            // permanently stuck replying with the confusion hint forever.
            context.record(text, hint, "CONFUSION_HINT")
            return
        }

        // 1c. Phase 16 — Learned macros ("Execute" in Learning Center).
        // Checked BEFORE normal intent dispatch: if the user previously
        // demonstrated exactly this phrase, replay what they taught instead
        // of running it back through GoalPlanner/IntentDetector. Matched on
        // the same normalization used at record time (lowercase + trim), so
        // it only fires for the exact phrase that was taught — not a fuzzy
        // "close enough" match, which could replay the wrong learned task.
        val normalizedInput = text.lowercase().trim()
        scope.launch {
            val macro = com.junai.app.AppDatabase.getInstance(activity).recordedMacroDao().findByTrigger(normalizedInput)
            if (macro != null) {
                // BUGFIX (root cause of "kabhi extra tasks, kabhi kuch
                // execute nahi hota"): previously the "kar rahi hoon"
                // reply below was posted, THEN replay() started — but any
                // bot message re-enables the send button right away (see
                // ChatAdapter.onBotMessageAdded), so the user could fire
                // another macro/task while this one was still mid-flight.
                // Two replays (or a replay + a live agent task) driving
                // the same accessibility gesture channel at once is what
                // caused steps to interleave (extra actions) or cancel
                // each other out (nothing happens). Refuse to start a new
                // one while either is already running, instead of racing.
                if (MacroReplayEngine.isReplaying || agentEngine.isTaskRunning()) {
                    withContext(Dispatchers.Main) {
                        reply("Ek kaam pehle se chal raha hai — pehle wo complete hone do, phir dobara try karo.", recyclerView)
                    }
                    return@launch
                }
                withContext(Dispatchers.Main) { reply("Ye maine seekha hai — kar rahi hoon... 🎬", recyclerView) }
                val resultMessage = MacroReplayEngine.replay(activity, macro)
                com.junai.app.AppDatabase.getInstance(activity).recordedMacroDao()
                    .markReplayed(macro.id, System.currentTimeMillis())
                withContext(Dispatchers.Main) { reply(resultMessage, recyclerView) }
                return@launch
            }
            // No learned macro for this phrase — try a fuzzy fallback
            // (Phase 3) before giving up entirely.
            val allMacros = com.junai.app.AppDatabase.getInstance(activity).recordedMacroDao().getAll()
            val fuzzy = com.junai.app.learning.TriggerMatcher.bestMatch(activity, normalizedInput, allMacros)
            if (fuzzy != null) {
                pendingFuzzyMacro = fuzzy.macro
                withContext(Dispatchers.Main) {
                    reply("Ye lagta hai \"${fuzzy.macro.displayPhrase}\" — chalayu?", recyclerView)
                }
                return@launch
            }
            // No match at all, exact or fuzzy — fall through to normal handling.
            withContext(Dispatchers.Main) { handleAfterMacroCheck(intentResult, target, text, recyclerView) }
        }
        return
    }

    /**
     * Whether Jun is currently mid-flight on a macro replay or a live
     * agent task. MainActivity checks this before honoring the automatic
     * send-button re-enable that fires on every bot message, so an interim
     * status reply ("kar rahi hoon...") can't be mistaken for "done, go
     * ahead and send something else" while work is still running.
     *
     * BUGFIX: also now checks qwenStreaming — without it, the moment the
     * "Jun is thinking…" placeholder bubble gets added (a real
     * addMessage() call, same as any other bot message), MainActivity's
     * onBotMessageAdded callback saw isBusy()==false and re-enabled the
     * send button immediately, while Qwen3 was still streaming in the
     * background. That's what looked like the send button "disappearing
     * and coming back" — it wasn't stuck, it was released too early,
     * making it look safe to send a second message mid-stream.
     */
    fun isBusy(): Boolean = MacroReplayEngine.isReplaying || agentEngine.isTaskRunning() || qwenStreaming

    /**
     * RAG retrieval — pulls relevant entries from KnowledgeBase (see
     * that file's doc for the full reasoning) and formats them as a
     * context block Qwen3 should treat as ground truth, not something
     * to second-guess. Returns null when nothing relevant enough was
     * found, same non-blocking/never-forces-a-match contract as the
     * other ml/ engines.
     */
    private suspend fun buildRagContext(query: String): String? {
        val matches = com.junai.app.ml.KnowledgeBase.retrieve(activity, query)
        if (matches.isEmpty()) return null
        val facts = matches.joinToString("\n") { "- ${it.content}" }
        return "[Known facts, use these if relevant to the question below:\n$facts]"
    }

    /**
     * Compact per-message context prepended to what Qwen3 sees — this is
     * what Pranav called "user memory RAG": UserPreferenceManager was
     * already tracking language/tone/emoji/response-length preference
     * and learned facts, it just never reached Qwen3 before. Kept
     * deliberately short (a few words per field, not full sentences) —
     * every token here goes into the prompt sent for GGUFChatEngine's
     * (currently single-turn, stateless) generation each time, so this
     * shouldn't grow into a large block. Only non-default/meaningful fields are
     * included, so a brand-new user with no tracked preferences yet
     * gets a minimal or empty context rather than a block of defaults.
     */
    private fun buildQwenUserContext(): String? {
        val profile = userPrefs.getUserProfile()
        val parts = mutableListOf<String>()

        if (!profile.name.isNullOrBlank()) parts.add("name=${profile.name}")

        when (profile.languageStyle) {
            UserPreferenceManager.STYLE_HINGLISH -> parts.add("prefers Hinglish")
            UserPreferenceManager.STYLE_HINDI -> parts.add("prefers Hindi")
            // STYLE_ENGLISH is the default — not worth spending tokens to state it.
        }

        if (profile.toneStyle == UserPreferenceManager.TONE_CASUAL) parts.add("casual tone")
        else if (profile.toneStyle == UserPreferenceManager.TONE_FORMAL) parts.add("formal tone")

        if (profile.emojiStyle == "HIGH") parts.add("uses emojis often")
        else if (profile.emojiStyle == "NONE") parts.add("prefers no emojis")

        if (profile.responseLength == UserPreferenceManager.LENGTH_SHORT) parts.add("prefers short replies")

        if (profile.facts.isNotEmpty()) {
            val factsStr = profile.facts.entries.take(5).joinToString(", ") { "${it.key}=${it.value}" }
            parts.add("known facts: $factsStr")
        }

        if (parts.isEmpty()) return null
        return "[User context: ${parts.joinToString(", ")}]"
    }

    /**
     * Builds the last few real exchanges as (role, content) pairs for
     * GGUFChatEngine.streamChat()'s `history` param. Needed because
     * sessionReset() runs before every generate() call now (crash fix —
     * see GGUFChatEngine's class doc) which wipes the KV cache each
     * time, so without this Jun had zero memory of anything said
     * earlier in the conversation — a follow-up like "tell me about
     * it" had no "it" to resolve.
     *
     * beforeIndex is the position of the current turn's user message
     * in `messages` (the thinking placeholder sits right after it) —
     * everything from there back is prior conversation.
     */
    private fun buildQwenHistory(beforeIndex: Int): List<Pair<String, String>> {
        if (beforeIndex <= 0) return emptyList()
        val history = mutableListOf<Pair<String, String>>()
        for (i in 0 until beforeIndex) {
            val msg = messages.getOrNull(i) ?: continue
            if (msg.isThinking) continue // in-progress placeholders never linger, but skip defensively
            if (msg.text.isBlank()) continue
            history.add((if (msg.isUser) "user" else "assistant") to msg.text)
        }
        return history
    }

    @Volatile
    private var qwenStreaming = false

    /** Continuation of handle() once we've confirmed no learned macro matches this input. */
    private fun handleAfterMacroCheck(intentResult: IntentDetector.IntentResult, target: String, text: String, recyclerView: RecyclerView) {

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

        // BUGFIX (found via Pranav's testing — "what his role" and other
        // unrelated messages were getting the TELL_BATTERY canned reply,
        // "do you know last message you sent me" got misrouted to
        // SEND_MESSAGE's "who should I message?" reply, etc.):
        // intentResult.confidenceLevel was computed but never actually
        // used to gate dispatch — ANY match, even a LOW-confidence one
        // that only won via the word-overlap fallback (e.g. sharing one
        // common word with a phrase), fired that intent's dedicated
        // hardcoded branch unconditionally. This one change protects
        // EVERY intent category at once, rather than continuing to prune
        // individual phrase lists one at a time as new false positives
        // turn up — a LOW/NONE confidence match now gets treated as
        // UNKNOWN and flows through the safer trained-command ->
        // passive-learning -> FunctionGemma -> knowledge-base -> Qwen3
        // router instead of a wrong canned reply. HIGH/MEDIUM confidence
        // (i.e. an actual exact/near-exact/substring phrase match) still
        // dispatches normally — this only catches the weak, coincidental
        // matches.
        val effectiveIntent =
            if (intentResult.confidenceLevel == IntentDetector.ConfidenceLevel.LOW ||
                intentResult.confidenceLevel == IntentDetector.ConfidenceLevel.NONE) {
                IntentDetector.Intent.UNKNOWN
            } else {
                intentResult.intent
            }

        // 3. Dispatch
        when (effectiveIntent) {

            // GREET, HOW_ARE_YOU, THANK, WHO_ARE_YOU, and TELL_JOKE used
            // to each have their own canned-reply branch here. Merged
            // into the UNKNOWN router below (see that case label) —
            // these were exactly the loose/over-eager keyword categories
            // that misfired on unrelated words (e.g. "Himalayas" was
            // matching as a greeting). Routing them through
            // FunctionGemma-then-Qwen3 instead means an unrelated word
            // gets a real answer rather than a wrong canned one, while a
            // genuine "hello" still gets a natural reply — just from
            // Qwen3 instead of a fixed template.

            // USER_INFO used to have its own canned "yaad kar liya
            // maine!" reply branch here. Removed for the same reason
            // GREET/HOW_ARE_YOU/THANK/WHO_ARE_YOU/TELL_JOKE were merged
            // into UNKNOWN below: its phrase list ("i love", "i like",
            // etc.) kept needing one-by-one pruning as generic phrases
            // false-fired on unrelated messages (e.g. "I love you" got
            // the memorization ack instead of ever reaching the chat
            // model). Fact-capture itself (tryExtractFact/captureFact)
            // already runs unconditionally above this switch, so
            // Jun still learns the fact — it just no longer short-
            // circuits the reply with a fixed template; the actual
            // reply now comes from the same trained-command ->
            // passive-learning -> FunctionGemma -> knowledge-base ->
            // Qwen3 router as UNKNOWN (see that case label).

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
                    } else {
                        // BUGFIX (Phase 1f): this branch used to do NOTHING —
                        // no reply, no log — the most literal "user typed
                        // something, nothing happened" case in the whole
                        // dispatch. e.g. "= answer" or "question =" with one
                        // side blank.
                        reply(
                            if (userPrefs.getUserProfile().languageStyle == UserPreferenceManager.STYLE_HINGLISH)
                                "Question aur answer dono likho \"X = Y\" format mein! 🤔"
                            else "Give me both a question and an answer, like \"X = Y\"! 🤔",
                            recyclerView
                        )
                        scope.launch {
                            learningRepo.logFailure(
                                question = text,
                                detectedIntent = intentResult.intent.name,
                                confidence = intentResult.confidence.toFloat(),
                                failureReason = "LEARN_QA_INCOMPLETE"
                            )
                        }
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
                if (target.isNotEmpty()) {
                    // IMPROVEMENT (Phase 1f): openApp() now returns whether it
                    // actually opened the app vs fell back to a Play Store
                    // search / "not found" — log the latter as a failure so a
                    // repeatedly-mistyped or uninstalled app name can surface
                    // in Pending instead of silently never being teachable.
                    val opened = appCommandHandler.openApp(target)
                    if (!opened) {
                        scope.launch {
                            learningRepo.logFailure(
                                question = text,
                                detectedIntent = intentResult.intent.name,
                                confidence = intentResult.confidence.toFloat(),
                                failureReason = "APP_NOT_FOUND"
                            )
                        }
                    }
                } else reply(if (userPrefs.getUserProfile().languageStyle == UserPreferenceManager.STYLE_HINGLISH)
                    "Konsa app open karun? \uD83E\uDD14" else "Which app should I open? \uD83E\uDD14", recyclerView)
                recyclerView.scrollToPosition(messages.size - 1)
                onSaveChat()
            }

            IntentDetector.Intent.CALL_CONTACT -> {
                if (target.isNotEmpty()) {
                    // IMPROVEMENT (Phase 1f): same reasoning as OPEN_APP above.
                    val called = appCommandHandler.makeCall(target)
                    if (!called) {
                        scope.launch {
                            learningRepo.logFailure(
                                question = text,
                                detectedIntent = intentResult.intent.name,
                                confidence = intentResult.confidence.toFloat(),
                                failureReason = "CONTACT_NOT_FOUND_OR_PERMISSION"
                            )
                        }
                    }
                } else reply(builder().forIntent(IntentDetector.Intent.CALL_CONTACT, target), recyclerView)
                recyclerView.scrollToPosition(messages.size - 1)
                onSaveChat()
            }

            IntentDetector.Intent.SEND_MESSAGE -> {
                if (target.isNotEmpty()) {
                    // IMPROVEMENT (Phase 1f): same reasoning as OPEN_APP above.
                    val sent = appCommandHandler.sendMessage(target)
                    if (!sent) {
                        scope.launch {
                            learningRepo.logFailure(
                                question = text,
                                detectedIntent = intentResult.intent.name,
                                confidence = intentResult.confidence.toFloat(),
                                failureReason = "CONTACT_NOT_FOUND_OR_PERMISSION"
                            )
                        }
                    }
                } else reply(
                    if (userPrefs.getUserProfile().languageStyle == UserPreferenceManager.STYLE_HINGLISH)
                        "Kisko message karun? Naam batao! 💬"
                    else "Who should I message? Tell me the name! 💬",
                    recyclerView
                )
                recyclerView.scrollToPosition(messages.size - 1)
                onSaveChat()
            }

            IntentDetector.Intent.SEARCH_WEB -> {
                // BUGFIX: text.replace("search", "") matched "search" as a
                // raw substring anywhere in the input — including inside
                // "Research", which turned "Research aaj ka weather..."
                // into "Re aaj ka weather...". Use a word-boundary regex so
                // only standalone "search"/"find"/"search for" tokens are
                // stripped, never partial matches inside other words.
                val query = if (target.isNotEmpty()) target
                    else text.replace(Regex("(?i)\\b(search for|search|find)\\b"), "").trim()
                if (query.isNotEmpty()) {
                    webSearchHelper.search(query, recyclerView)
                } else {
                    // BUGFIX (Phase 1f): used to just call onEnableSend() —
                    // no reply, no log — e.g. user just typed "search" or
                    // "find" alone with nothing to search for.
                    reply(
                        if (userPrefs.getUserProfile().languageStyle == UserPreferenceManager.STYLE_HINGLISH)
                            "Kya search karu? Kuch toh batao! \uD83D\uDD0D" else "What should I search for? \uD83D\uDD0D",
                        recyclerView
                    )
                    onEnableSend()
                    scope.launch {
                        learningRepo.logFailure(
                            question = text,
                            detectedIntent = intentResult.intent.name,
                            confidence = intentResult.confidence.toFloat(),
                            failureReason = "SEARCH_QUERY_EMPTY"
                        )
                    }
                }
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
            // TELL_JOKE merged into the UNKNOWN router — see note above
            // GREET/HOW_ARE_YOU/etc.

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

            // ── Phase 15 — AGENT_TASK: hands off to AgentEngine, the single
            // entry point for all multi-step / system-level / research tasks.
            // AgentEngine never touches RecyclerView/ChatAdapter itself — it
            // just returns an honest summary string for us to relay here.
            IntentDetector.Intent.AGENT_TASK -> {
                showTyping()
                scope.launch {
                    // IMPROVEMENT (Phase 1f): runTask() now returns whether
                    // the task actually succeeded, not just a summary string
                    // — previously that flag was computed internally
                    // (executeSteps' own `succeeded` local) but never
                    // reached here, so a repeatedly-failing agent task could
                    // never be logged and could never re-enter Pending.
                    val taskResult = agentEngine.runTask(intentResult)
                    if (!taskResult.succeeded) {
                        learningRepo.logFailure(
                            question = text,
                            detectedIntent = intentResult.intent.name,
                            confidence = intentResult.confidence.toFloat(),
                            failureReason = "AGENT_TASK_NOT_COMPLETED"
                        )
                    }
                    withContext(Dispatchers.Main) {
                        hideTyping()
                        reply(taskResult.summary, recyclerView)
                    }
                }
            }

            // ── UNKNOWN — trained command lookup + learning fallback ──
            // (semantic + graph + reflection + plan checks no longer live
            // here — they run earlier in handle(), before this dispatch)
            IntentDetector.Intent.GREET,
            IntentDetector.Intent.HOW_ARE_YOU,
            IntentDetector.Intent.THANK,
            IntentDetector.Intent.WHO_ARE_YOU,
            IntentDetector.Intent.TELL_JOKE,
            IntentDetector.Intent.UNKNOWN, IntentDetector.Intent.USER_INFO -> {
                showTyping()

                if (intentResult.intent == IntentDetector.Intent.GREET) {
                    // Preserved from the old standalone GREET branch — a
                    // greeting is still a natural, non-intrusive moment
                    // to surface a proactive recommendation/nudge, even
                    // though the reply itself now comes from the router
                    // below instead of a fixed template.
                    maybeShowRecommendation(recyclerView)
                    maybeShowLearningNudge(recyclerView)
                }

                scope.launch {
                    // 1. Check trained commands first
                    val commands = learningRepo.getAllCommands()
                    val normalizedText = text.lowercase()
                    val matchedCmd = commands.firstOrNull { cmd ->
                        val phrase = cmd.phrase.lowercase()
                        normalizedText.contains(phrase) ||
                            // BUGFIX: this reversed direction (does the
                            // TRAINED phrase contain what the user
                            // typed?) is only safe for inputs long
                            // enough that a coincidental substring match
                            // is unlikely — a bare "i" or "ove" could
                            // spuriously match inside almost any longer
                            // phrase ("love", "move", "discover"...)
                            // regardless of actual relevance. Matches
                            // the same reasoning as the scoreMatch()
                            // short-input guard in IntentDetector.kt.
                            (normalizedText.length >= 4 && phrase.contains(normalizedText))
                    }

                    if (matchedCmd != null) {
                        withContext(Dispatchers.Main) {
                            hideTyping()
                            // IMPROVEMENT (Phase 1f): handle() now returns
                            // whether it actually fulfilled the command —
                            // previously this was completely unobserved, so
                            // a trained command that silently couldn't be
                            // carried out (unrecognized intent string,
                            // app/contact not found) could never be logged
                            // and could never re-enter Pending.
                            val fulfilled = trainedCommandHandler.handle(matchedCmd.intent, matchedCmd.target, text, recyclerView)
                            if (!fulfilled) {
                                scope.launch {
                                    learningRepo.logFailure(
                                        question = text,
                                        detectedIntent = intentResult.intent.name,
                                        confidence = intentResult.confidence.toFloat(),
                                        failureReason = "TRAINED_COMMAND_NOT_FULFILLED"
                                    )
                                }
                            }
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

                    // 1.5. Passive Learning — did we passively learn how to
                    // do this in whatever app is currently in the
                    // foreground? Only fires if that app is Allowed (see
                    // PassiveExecutionCoordinator.resolveTargetPackage) and
                    // there's a known current screen — silently falls
                    // through to the existing knowledge-base lookup below
                    // otherwise, so this is purely additive.
                    val passiveOutcome = com.junai.app.passive.PassiveExecutionCoordinator.handle(
                        context = activity,
                        intentText = text
                    )
                    when (passiveOutcome) {
                        is com.junai.app.passive.PassiveExecutionCoordinator.Outcome.Executed -> {
                            withContext(Dispatchers.Main) {
                                hideTyping()
                                reply(passiveOutcome.message, recyclerView)
                            }
                            return@launch
                        }
                        is com.junai.app.passive.PassiveExecutionCoordinator.Outcome.NeedsManualInput -> {
                            withContext(Dispatchers.Main) {
                                hideTyping()
                                reply(passiveOutcome.message, recyclerView)
                            }
                            return@launch
                        }
                        is com.junai.app.passive.PassiveExecutionCoordinator.Outcome.Failed -> {
                            withContext(Dispatchers.Main) {
                                hideTyping()
                                reply(passiveOutcome.message, recyclerView)
                            }
                            return@launch
                        }
                        is com.junai.app.passive.PassiveExecutionCoordinator.Outcome.LowKnowledgeDisclosure -> {
                            withContext(Dispatchers.Main) {
                                hideTyping()
                                reply(passiveOutcome.message, recyclerView)
                            }
                            return@launch
                        }
                        is com.junai.app.passive.PassiveExecutionCoordinator.Outcome.HelpPopupShown -> {
                            // Overlay is already showing itself (see
                            // PassiveHelpPopupOverlay) — only say something
                            // in chat if the repeat cap was hit and there's
                            // an explicit "sikhao instead" message.
                            withContext(Dispatchers.Main) {
                                hideTyping()
                                passiveOutcome.message?.let { reply(it, recyclerView) }
                            }
                            return@launch
                        }
                        com.junai.app.passive.PassiveExecutionCoordinator.Outcome.NoMatch,
                        com.junai.app.passive.PassiveExecutionCoordinator.Outcome.NoPathFound,
                        com.junai.app.passive.PassiveExecutionCoordinator.Outcome.NotApplicable -> {
                            // Nothing passive-learning could do with this —
                            // fall through to the existing knowledge-base
                            // lookup below, unchanged.
                        }
                    }

                    // 1.7. FunctionGemma router (Pranav's "option 1") — only
                    // reached because IntentDetector already said UNKNOWN
                    // AND no trained command / passive macro matched. Still
                    // gated by isLikelyAction() so we don't burn an
                    // inference on obvious chit-chat that landed here for
                    // unrelated reasons. Kicks off model load lazily on
                    // first use, same fire-and-continue pattern as
                    // EmbeddingEngine in TriggerMatcher — never blocks this
                    // turn on model load finishing.
                    if (com.junai.app.ml.FunctionCallEngine.isLikelyAction(text)) {
                        if (!com.junai.app.ml.FunctionCallEngine.isReady()) {
                            com.junai.app.ml.FunctionCallEngine.init(activity)
                        }
                        val functionCall = com.junai.app.ml.FunctionCallEngine.tryInterpret(text)
                        if (functionCall != null) {
                            withContext(Dispatchers.Main) {
                                hideTyping()
                                val fulfilled = trainedCommandHandler.handle(
                                    functionCall.intent, functionCall.target, text, recyclerView
                                )
                                if (!fulfilled) {
                                    scope.launch {
                                        learningRepo.logFailure(
                                            question = text,
                                            detectedIntent = "FUNCTIONGEMMA_${functionCall.intent}",
                                            confidence = intentResult.confidence.toFloat(),
                                            failureReason = "FUNCTIONGEMMA_CALL_NOT_FULFILLED"
                                        )
                                    }
                                }
                                context.record(
                                    userMessage = text,
                                    junResponse = _lastResponse,
                                    intent      = "FUNCTIONGEMMA_${functionCall.intent}",
                                    entity      = functionCall.target
                                )
                            }
                            return@launch
                        }
                        // functionCall == null (model not ready yet, or it
                        // returned NONE / unparseable) — fall through to
                        // the existing knowledge-base lookup below, same
                        // as the passive-learning NoMatch cases above.
                    }

                    // 2. Knowledge base lookup
                    val searchResult = learningRepo.findAnswer(text)

                    // Phase 8 — single source of truth for HIGH/MEDIUM/LOW
                    val confidenceLevel = ConfidenceEngine.classify(
                        ConfidenceEngine.normalize(searchResult.confidence, scaleMax = 100f)
                    )

                    // 1.8. Qwen3 (ChatEngine) fallback path.
                    val willNeedFallback = searchResult.answer == null ||
                        confidenceLevel == ConfidenceEngine.ConfidenceLevel.LOW

                    if (!willNeedFallback) {
                        // HIGH/MEDIUM confidence knowledge-base answer —
                        // unchanged from before the thinking-UI work:
                        // same artificial typing delay, same reveal,
                        // same always-speak.
                        withContext(Dispatchers.Main) {
                            Handler(Looper.getMainLooper()).postDelayed({
                                hideTyping()
                                val response: String =
                                    if (confidenceLevel == ConfidenceEngine.ConfidenceLevel.HIGH) searchResult.answer!!
                                    else "I think you mean:\n${searchResult.answer}"

                                if (response.isNotEmpty()) {
                                    _lastResponse = response
                                    chatAdapter.addMessage(ChatMessage(response, isUser = false))
                                    if (searchResult.relatedQuestions.isNotEmpty()) {
                                        val related = "Related: " + searchResult.relatedQuestions.take(3).joinToString(" • ")
                                        chatAdapter.addMessage(ChatMessage(related, isUser = false))
                                    }
                                    recyclerView.scrollToPosition(messages.size - 1)
                                    onSaveChat()
                                    speak(response)
                                }

                                context.record(
                                    userMessage = text,
                                    junResponse = _lastResponse,
                                    intent      = intentResult.intent.name,
                                    entity      = intentResult.extractedEntity
                                )
                            }, 1500)
                        }
                    } else {
                        // Fallback path — no knowledge-base answer, or
                        // confidence too low to trust. This is where
                        // Qwen3 (ChatEngine) comes in, now via streaming
                        // instead of a single blocking call: a "Jun is
                        // thinking…" bubble goes up immediately (no
                        // artificial delay — there's already real
                        // latency here, no need to fake more), then gets
                        // rewritten in place as the stream progresses,
                        // and finally becomes the real answer bubble.
                        // Per Pranav: this bubble is NEVER auto-spoken —
                        // only via the per-message speak button.
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

                        qwenStreaming = true
                        withContext(Dispatchers.Main) {
                            hideTyping()
                            chatAdapter.addMessage(
                                ChatMessage(text = "Jun is thinking…", isUser = false, isThinking = true, thinkingText = "")
                            )
                        }
                        val thinkingMsgIndex = chatAdapter.lastIndex()
                        val thinkingMsgTimestamp = messages.getOrNull(thinkingMsgIndex)?.timestamp ?: System.currentTimeMillis()

                        if (!com.junai.app.ml.GGUFChatEngine.isReady()) {
                            com.junai.app.ml.GGUFChatEngine.init(activity)
                        }
                        var finalAnswer = ""
                        var finalThinking = ""
                        try {
                            val userContext = buildQwenUserContext()
                            val ragContext = buildRagContext(text)
                            val combinedContext = listOfNotNull(ragContext, userContext)
                                .joinToString("\n")
                                .ifBlank { null }
                            // thinkingMsgIndex - 1 is this turn's own user
                            // message — everything before that is history.
                            val history = buildQwenHistory(thinkingMsgIndex - 1)
                            com.junai.app.ml.GGUFChatEngine.streamChat(text, combinedContext, history)?.collect { state ->
                                finalThinking = state.thinkingSoFar
                                finalAnswer = state.answerSoFar
                                withContext(Dispatchers.Main) {
                                    chatAdapter.updateMessageAt(
                                        thinkingMsgIndex,
                                        ChatMessage(
                                            text = "Jun is thinking…",
                                            isUser = false,
                                            timestamp = thinkingMsgTimestamp,
                                            isThinking = !state.isFinal,
                                            thinkingText = state.thinkingSoFar
                                        )
                                    )
                                }
                            }
                        } catch (e: Exception) {
                            android.util.Log.w("ChatIntentHandler", "Qwen3 streaming failed: ${e.message}")
                        }

                        withContext(Dispatchers.Main) {
                            qwenStreaming = false
                            // spokenAloud tracks whether this specific
                            // response should auto-speak — true only for
                            // the curiosity/forUnknown fallback (matches
                            // old behavior), false for a genuine Qwen3
                            // answer (Pranav's manual-trigger-only rule).
                            val (response, spokenAloud) = if (finalAnswer.isNotBlank()) {
                                chatAdapter.updateMessageAt(
                                    thinkingMsgIndex,
                                    ChatMessage(
                                        text = finalAnswer,
                                        isUser = false,
                                        timestamp = thinkingMsgTimestamp,
                                        isThinking = false,
                                        thinkingText = finalThinking
                                    )
                                )
                                finalAnswer to false
                            } else {
                                val fallback = if (CuriosityEngine.isQuestionLike(text)) {
                                    // Phase 9 — if this genuinely looks like a
                                    // question and nothing else matched, ask a
                                    // curious follow-up.
                                    curiosityRepo.askAbout(text)
                                } else {
                                    builder().forUnknown(text)
                                }
                                chatAdapter.updateMessageAt(
                                    thinkingMsgIndex,
                                    ChatMessage(text = fallback, isUser = false, timestamp = thinkingMsgTimestamp)
                                )
                                fallback to true
                            }

                            if (response.isNotEmpty()) {
                                _lastResponse = response
                                if (searchResult.relatedQuestions.isNotEmpty()) {
                                    val related = "Related: " + searchResult.relatedQuestions.take(3).joinToString(" • ")
                                    chatAdapter.addMessage(ChatMessage(related, isUser = false))
                                }
                                recyclerView.scrollToPosition(messages.size - 1)
                                onSaveChat()
                                if (spokenAloud) {
                                    speak(response)
                                }
                            }
                            // updateMessageAt() above (turning the
                            // "thinking" bubble into the real answer)
                            // deliberately does NOT fire
                            // onBotMessageAdded the way addMessage() does
                            // — it's an in-place edit, not a new message.
                            // So without this explicit call, the send
                            // button would stay disabled until the 22s
                            // safety timeout whenever there happened to
                            // be no related-questions message to
                            // incidentally trigger it. Safe to call even
                            // when addMessage() above already fired it —
                            // enableSendButton() is a no-op if already
                            // enabled.
                            chatAdapter.onBotMessageAdded?.invoke()

                            context.record(
                                userMessage = text,
                                junResponse = _lastResponse,
                                intent      = intentResult.intent.name,
                                entity      = intentResult.extractedEntity
                            )
                        }
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
