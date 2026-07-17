package com.junai.app.ml

import android.content.Context
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext

/**
 * ChatEngine — general chat/understanding via Qwen3
 * (assets/qwen3_0_6b_mixed_int4.litertlm), the LAST fallback in the
 * router chain: rule-based IntentDetector → passive learning →
 * FunctionGemma (FunctionCallEngine) → knowledge-base search → THIS →
 * generic forUnknown() template. Only reached when literally nothing
 * else produced an answer, so it's fine for this to be relatively rare
 * to hit and a bit slower than the others.
 *
 * RUNTIME NOTE — different from EmbeddingEngine/FunctionCallEngine: this
 * uses LiteRT-LM (com.google.ai.edge.litertlm), a SEPARATE runtime from
 * MediaPipe Tasks GenAI (used for FunctionGemma). Its Kotlin API takes a
 * plain filesystem path (EngineConfig(modelPath = "/path/to/model")),
 * NOT an Android assets-relative path the way MediaPipe's
 * setModelAssetPath() resolves automatically. That already lined up
 * naturally with the switch away from bundling models in assets/ —
 * ModelDownloadManager.localPathFor() below hands back exactly the kind
 * of real filesystem path this always needed, downloaded via the Models
 * screen instead of copied out of assets/ on first use.
 */
object ChatEngine {

    /**
     * Qwen3 runs in "thinking mode" by default — its raw output wraps
     * internal reasoning in <think>...</think> before the actual answer.
     * Showing that raw is a real bug (confirmed live: a screen-share
     * showed the full <think> block leaking into the chat bubble).
     * tryChat() below splits it so callers get both pieces separately —
     * `thinking` is null if the model didn't produce a think block (some
     * responses skip it), `answer` is always the part meant to be shown
     * as the actual reply.
     *
     * UI note: as of this change, ChatIntentHandler only uses `.answer`
     * — there's no collapsible "show thinking" UI yet (that's a separate
     * follow-up item). This class exists now so that follow-up doesn't
     * need to touch ChatEngine's parsing again, just consume `.thinking`.
     */
    data class ChatResponse(val thinking: String?, val answer: String)

    private const val TAG = "ChatEngine"

    @Volatile
    private var engine: Engine? = null
    @Volatile
    private var appContext: Context? = null

    @Volatile
    private var conversation: com.google.ai.edge.litertlm.Conversation? = null

    // BUGFIX: this used to be a PERMANENT latch — once init() failed
    // once (a transient issue: brief low memory, a hiccup copying the
    // asset, anything not guaranteed to fail again), every future
    // message would hit "if (engine != null || initFailed) return" and
    // give up immediately without even trying — Qwen3 stayed broken for
    // the rest of the app session no matter what, and the only way to
    // recover was force-closing and reopening the app. That's exactly
    // what Pranav reported ("baar baar app open/close karna padta hai
    // Qwen se baat karne ke liye"). Now: failures are remembered with a
    // timestamp, and a retry is allowed after a cooldown — no restart
    // needed, but also not hammering a genuinely broken load on every
    // single message.
    @Volatile
    private var lastInitFailureAtMs = 0L
    private const val INIT_RETRY_COOLDOWN_MS = 30_000L

    private fun shouldSkipInit(): Boolean =
        engine == null &&
            lastInitFailureAtMs != 0L &&
            (System.currentTimeMillis() - lastInitFailureAtMs) < INIT_RETRY_COOLDOWN_MS

    // BUGFIX (the "does budget ever reset" question): maxNumTokens is
    // ONE shared budget for the entire conversation object's lifetime —
    // history + every turn's thinking + every turn's answer, all
    // drawing from the same pool, and nothing was resetting it. Once
    // exhausted, EVERY subsequent message in that session would keep
    // hitting the same "thinking never closes, answer comes out empty"
    // failure — not just one bad message, the whole rest of the chat
    // session until the app was force-restarted. Below: after every N
    // turns, close the old conversation and open a fresh one (same
    // already-loaded engine/model, so this is fast — no model reload).
    //
    // This resets history too (Qwen "forgets" earlier turns in that
    // cycle) — a real tradeoff, but a conversation that quietly stops
    // answering is worse. A history-preserving reset likely exists
    // (LiteRT-LM's own team has referenced a `reopenWithHistory` pattern
    // for exactly this) but I haven't confirmed its exact Kotlin
    // signature against documentation, so not risking a guessed API
    // call here — full reset is the safe version of this fix. Worth
    // revisiting if losing context every ~8 turns feels too aggressive
    // in practice.
    // Lowered from 8 — live testing showed individual thinking traces
    // can be extremely long (a single "what's the debit vs credit
    // difference" exchange produced a multi-thousand-word thinking
    // block on its own). 8 shared turns of that size risks exhausting
    // the budget well before the 8th message. Not a full fix for "reset
    // every single message while keeping context" (see ChatEngine's
    // class doc for why that's not really how a shared KV-cache works)
    // — this is a pragmatic tightening given the verbosity actually
    // observed.
    // Raised back from 4 to 6 — now that buildConversationConfig()'s
    // persona instruction explicitly tells Qwen3 to "think briefly and
    // decisively" (added after this was first tightened), individual
    // turns should on average consume less of the shared budget than
    // the pathological rambling case that originally motivated dropping
    // this to 4. 6 is a middle ground between the original 8 (too loose)
    // and 4 (reset felt like "a new chat every time" per Pranav).
    private const val MAX_TURNS_BEFORE_RESET = 6

    @Volatile
    private var turnsSinceReset = 0

    // BUGFIX ("previous message ka context yaad nahi, har baar nayi chat
    // jaisa lagta hai"): a full reset necessarily drops the KV-cache's
    // memory of prior turns — that's the whole point of resetting it.
    // But dropping ALL continuity felt jarring. This carries forward a
    // short plain-text summary of the last exchange across a reset —
    // not the same as true KV-cache memory, but enough that the very
    // next message after a reset doesn't land on a completely blank
    // slate. Only ever holds ONE exchange (not a growing log) — keeping
    // this cheap is the point, a longer carried-forward history would
    // just recreate the original token-budget problem one level up.
    @Volatile
    private var lastExchangeSummary: String? = null

    @Volatile
    private var carryoverPending = false

    private fun updateLastExchangeSummary(userText: String, answerText: String) {
        // Truncated hard — this is a hint for continuity, not a
        // transcript. Keeping both sides short on purpose.
        val u = userText.take(150)
        val a = answerText.take(200)
        lastExchangeSummary = "[Earlier in this chat — user asked: \"$u\" — you answered: \"$a\"]"
    }

    /**
     * BUGFIX ("ek message ke baad kuch puchne mein 'I don't know' bol
     * deta hai, app restart karna padta hai"): this used to close the
     * old conversation and reassign in one step — if createConversation()
     * threw for any reason, `conversation` was left pointing at the just
     * -closed (dead) object, or the assignment silently produced null,
     * either way permanently breaking every future message with no way
     * to recover except an app restart (see the init() fix below for
     * the other half of this — why nothing ever retried it). Now:
     * conversation is set to null immediately after closing, and
     * creating the replacement is wrapped so a failure here just leaves
     * it null (a clean, recognizable "not ready" state) instead of a
     * broken reference.
     */
    private fun resetConversation() {
        conversation?.close()
        conversation = null
        conversation = try {
            engine?.createConversation(buildConversationConfig())
        } catch (e: Exception) {
            android.util.Log.e(TAG, "resetConversation failed, will retry on next message: ${e.message}", e)
            null
        }
        turnsSinceReset = 0
        // lastExchangeSummary is deliberately NOT cleared here — it's
        // what gets carried into the next message after this reset.
        carryoverPending = lastExchangeSummary != null
    }

    /** Call after any completed exchange (streamChat or tryChat) — resets the conversation once the turn budget is used up. */
    private fun noteTurnCompleted(userText: String, answerText: String) {
        if (answerText.isNotBlank()) {
            updateLastExchangeSummary(userText, answerText)
        }
        turnsSinceReset++
        if (turnsSinceReset >= MAX_TURNS_BEFORE_RESET) {
            resetConversation()
        }
    }

    private fun buildConversationConfig() = com.google.ai.edge.litertlm.ConversationConfig(
        // Persona spec per Pranav: friendly but not fuzzy, strict but
        // not rude, calm but never skip safety, correct but hedges
        // ("ho sakta hai"/"maybe") when not certain, point-by-point over
        // paragraphs, knows it's an AI (so AI/automation topics are
        // comfortable ground), reads the user's tone and responds in
        // kind, and can sustain light "just here to chat" conversation
        // (not everyone wants a lecture) rather than only ever being
        // maximally informative.
        systemInstruction = com.google.ai.edge.litertlm.Contents.of(listOf(
            com.google.ai.edge.litertlm.Content.Text(
                "You are Jun, a personal AI assistant running fully offline on the " +
                "user's Android phone. If asked your name, you are Jun — never invent " +
                "a different name.\n\n" +
                "IMPORTANT — messages may start with a bracketed block like " +
                "\"[User context: ...]\" or \"[Known facts: ...]\" before the user's " +
                "actual question. These are private background notes for you only — " +
                "use them silently to inform your answer, but NEVER mention, repeat, " +
                "quote, paraphrase, or comment on the bracketed text itself in your " +
                "reply. Only respond to the actual question that follows it. For " +
                "example, if you see \"[User context: prefers Hinglish, uses emojis]\" " +
                "followed by a question, that means write your reply IN Hinglish WITH " +
                "emojis — it does NOT mean saying \"I like Hinglish\" or \"I use " +
                "emojis\" out loud; those words are not something to say back.\n\n" +
                "Personality: friendly but not fuzzy/vague, direct and to-the-point. " +
                "Strict about safety boundaries but never rude about it — decline harmful " +
                "requests calmly, not preachy. Stay calm under rude or aggressive messages " +
                "without ignoring safety. When you're not fully sure of something, say so " +
                "plainly (\"ho sakta hai\", \"maybe\", \"I think\") rather than stating it " +
                "as fact — being honestly uncertain beats being confidently wrong.\n\n" +
                "Format: prefer short points over long paragraphs when explaining something " +
                "— easier to actually read on a phone screen.\n\n" +
                "You're an AI yourself, so AI/automation/tech topics are comfortable ground " +
                "for you, not something to be vague about. Pay attention to how the user is " +
                "writing (casual vs formal, short vs detailed, emoji or not) and respond in " +
                "a similar register. Not every message needs a deep answer — if someone's " +
                "just chatting to unwind, match that energy instead of over-explaining.\n\n" +
                "Language: if the user writes in Hindi or Hinglish (a Hindi-English mix, " +
                "written in the Latin/English alphabet), reply in Hinglish too — write it " +
                "the same way they do, mixing Hindi and English naturally in Roman script, " +
                "not formal Hindi and not pure English. If they write in plain English, " +
                "reply in English. Matching their language is just as important as matching " +
                "their tone — don't default to English when they're writing Hinglish.\n\n" +
                "If a message includes a \"[Known facts...]\" block, treat those facts as " +
                "accurate and use them to answer — trust them over your own uncertain " +
                "recall, especially for anything about Jun/JunAI itself.\n\n" +
                "Think briefly and decisively — a few sentences at most. Do not repeat or " +
                "re-confirm the same conclusion multiple times. Once you have an answer, " +
                "stop thinking and give it."
            )
        ))
    )

    /**
     * Closes the current conversation (if any) and opens a fresh one on
     * the SAME engine — cheap, no model reload, just a new empty
     * KV-cache. Call this instead of touching `engine` directly so
     * turnsSinceReset always stays in sync with the conversation that's
     * actually live.
     */

    suspend fun init(context: Context) {
        // BUGFIX: this used to be "if (engine != null) return" — meaning
        // once the model loaded successfully ONE time, this function
        // became a permanent no-op for the rest of the app session, even
        // if `conversation` later went null from a failed reset (see
        // resetConversation()'s fix above). Every caller follows the
        // pattern "if (!isReady()) init(...)", so with the old guard,
        // once broken this way, isReady() stayed false forever and init()
        // never actually tried to fix it — only an app restart (a fresh
        // `engine = null`) could recover. Now: if the engine is already
        // loaded but the conversation isn't, just recreate the
        // conversation (cheap — no model reload) instead of returning.
        if (isReady() || shouldSkipInit()) return
        if (engine != null && conversation == null) {
            withContext(Dispatchers.IO) { resetConversation() }
            return
        }
        withContext(Dispatchers.IO) {
            if (isReady() || shouldSkipInit()) return@withContext
            if (engine != null && conversation == null) {
                resetConversation()
                return@withContext
            }
            try {
                // Model lives wherever ModelDownloadManager put it (via
                // the Models screen), not in assets/ anymore. If the
                // user hasn't downloaded it yet, fail fast with a clear
                // reason instead of a confusing "file not found" a few
                // lines further into engine init — and deliberately
                // DON'T set lastInitFailureAtMs here, since "not
                // downloaded yet" isn't a transient failure worth a
                // 30s retry-cooldown; it should try again immediately
                // the next time init() is called (e.g. right after the
                // download finishes).
                val modelFile = ModelDownloadManager.localPathFor(context, ModelCatalog.ModelId.QWEN3_CHAT)
                if (!ModelDownloadManager.isDownloaded(context, ModelCatalog.ModelId.QWEN3_CHAT)) {
                    android.util.Log.w(TAG, "Qwen3 model not downloaded yet — visit the Models screen.")
                    return@withContext
                }
                val engineConfig = EngineConfig(
                    modelPath = modelFile.absolutePath,
                    // RISK FLAG: a real-world LiteRT-LM integration
                    // (unrelated project, found while researching this)
                    // reported that overriding maxNumTokens on SOME
                    // models triggers a "DYNAMIC_UPDATE_SLICE tensor
                    // shape error" at init — they left it at the
                    // model's own default specifically to avoid that.
                    // Setting it here anyway because the observed bug
                    // (long/looping thinking exhausting the default
                    // budget before ever reaching </think>, so
                    // parseThinkingAndAnswer() sees no closing tag and
                    // returns an empty answer, falling through to the
                    // canned forUnknown() reply) needs more headroom to
                    // even test whether that's the actual cause. If
                    // engine.initialize() below starts throwing on real
                    // devices, THIS is the first thing to revert — the
                    // systemInstruction in buildConversationConfig() is
                    // a lower-risk second lever for the same symptom and
                    // can stay either way.
                    maxNumTokens = 4096
                )
                Breadcrumb.log(context, "ChatEngine: about to construct Engine(engineConfig) [GPU]")
                appContext = context.applicationContext
                // PERF: EngineConfig defaults to CPU-only if `backend`
                // isn't set at all — that was the case here before this
                // change, and CPU is the slow path for LLM inference on
                // mobile. Trying GPU first since it's typically much
                // faster; not every device/driver combo supports it
                // reliably though, so this falls back to an explicit
                // CPU config if GPU construction or initialize() throws,
                // rather than leaving the engine unusable on a device
                // where GPU doesn't work.
                val newEngine = try {
                    val gpuEngine = Engine(engineConfig.copy(backend = Backend.GPU()))
                    gpuEngine.initialize()
                    Breadcrumb.log(context, "ChatEngine: GPU backend initialize() returned OK")
                    gpuEngine
                } catch (gpuError: Exception) {
                    Breadcrumb.log(context, "ChatEngine: GPU backend failed (${gpuError.message}), falling back to CPU")
                    android.util.Log.w(TAG, "GPU backend failed, falling back to CPU: ${gpuError.message}")
                    val cpuEngine = Engine(engineConfig.copy(backend = Backend.CPU()))
                    cpuEngine.initialize()
                    Breadcrumb.log(context, "ChatEngine: CPU fallback initialize() returned OK")
                    cpuEngine
                }
                engine = newEngine
                Breadcrumb.log(context, "ChatEngine: about to call createConversation()")
                conversation = newEngine.createConversation(buildConversationConfig())
                Breadcrumb.log(context, "ChatEngine: createConversation() returned OK")
                turnsSinceReset = 0
            } catch (e: Exception) {
                lastInitFailureAtMs = System.currentTimeMillis()
                android.util.Log.e(TAG, "Qwen3/LiteRT-LM failed to load, will retry after ${INIT_RETRY_COOLDOWN_MS / 1000}s: ${e.message}", e)
            }
        }
    }

    fun isReady(): Boolean = engine != null && conversation != null

    /**
     * Live-streaming version of tryChat() — emits a StreamState after
     * every chunk LiteRT-LM produces, so a caller can update a "thinking"
     * bubble in real time instead of waiting for the whole response.
     * Returns null immediately (no Flow at all) if the engine isn't
     * ready — same non-blocking contract as tryChat().
     *
     * conversation.sendMessageAsync(text) delivers each new FRAGMENT as
     * its own Message (confirmed against Google's own docs — "He" then
     * "llo" then " Wo" etc, not cumulative), so this accumulates them
     * into `raw` itself and re-runs parseThinkingAndAnswer() on the
     * whole accumulated text each time, rather than trying to parse each
     * fragment in isolation — a <think> or </think> tag can easily land
     * split across two fragments, and re-parsing the full accumulated
     * text every time sidesteps that instead of needing fragile
     * partial-tag-boundary handling.
     */
    fun streamChat(text: String, userContext: String? = null): Flow<StreamState>? {
        val conv = conversation ?: return null
        // Carryover only applies to the first message after a reset —
        // consumed (cleared) here rather than in resetConversation()
        // itself, since we don't know if THIS call is that first
        // message until we're actually building its prompt.
        val carryover = if (carryoverPending) lastExchangeSummary else null
        carryoverPending = false
        val prefix = listOfNotNull(carryover, userContext).joinToString("\n").ifBlank { null }
        val prompt = if (prefix == null) text else "$prefix\n\n$text"
        return flow {
            var raw = ""
            appContext?.let { Breadcrumb.log(it, "ChatEngine: about to call conv.sendMessageAsync() (native)") }
            conv.sendMessageAsync(prompt).collect { chunk ->
                raw += chunk.toString()
                val parsed = parseThinkingAndAnswer(raw)
                emit(StreamState(thinkingSoFar = parsed.thinking ?: "", answerSoFar = parsed.answer, isFinal = false))
            }
            appContext?.let { Breadcrumb.log(it, "ChatEngine: sendMessageAsync() stream completed OK") }
            val final = parseThinkingAndAnswer(raw)
            emit(StreamState(thinkingSoFar = final.thinking ?: "", answerSoFar = final.answer, isFinal = true))
            noteTurnCompleted(text, final.answer)
        }.flowOn(Dispatchers.Default)
    }

    /** One update from streamChat() — thinkingSoFar/answerSoFar grow as more streams in; isFinal=true on the last emission. */
    data class StreamState(val thinkingSoFar: String, val answerSoFar: String, val isFinal: Boolean)

    /**
     * One-shot response, split into thinking/answer (see ChatResponse
     * doc above), or null if the engine isn't ready or generation
     * failed — caller falls back to the existing forUnknown() template
     * in that case, same non-blocking pattern as the other ml/ engines.
     * Kept alongside streamChat() for any caller that doesn't need live
     * updates and just wants the final result. NOTE: unlike streamChat(),
     * this one doesn't carry userContext/carryover or call
     * noteTurnCompleted() — it's used by WebSearchHelper as a narrower
     * fallback, not part of the main turn-counted conversation flow.
     */
    suspend fun tryChat(text: String): ChatResponse? {
        val conv = conversation ?: return null
        return withContext(Dispatchers.Default) {
            try {
                val raw = conv.sendMessage(text).toString()
                parseThinkingAndAnswer(raw).takeIf { it.answer.isNotBlank() }
            } catch (e: Exception) {
                android.util.Log.w(TAG, "tryChat failed, falling back to null: ${e.message}")
                null
            }
        }
    }

    /**
     * Splits raw Qwen3 output into the <think>...</think> content and
     * whatever comes after it. Defensive about the exact shape since raw
     * model output isn't a strict contract — handles: no think block at
     * all (thinking = null, whole thing is the answer), a properly
     * closed block, and a block whose closing tag never arrived (can
     * happen if generation got cut off by a token limit) by treating
     * everything after <think> as thinking with an empty answer, rather
     * than crashing or showing an obviously-truncated reply as if it
     * were the real one.
     */
    private fun parseThinkingAndAnswer(raw: String): ChatResponse {
        val openTag = "<think>"
        val closeTag = "</think>"
        val openIdx = raw.indexOf(openTag)
        if (openIdx == -1) {
            return ChatResponse(thinking = null, answer = stripLeakedContextBrackets(raw.trim()))
        }
        val closeIdx = raw.indexOf(closeTag, startIndex = openIdx)
        return if (closeIdx == -1) {
            // Closing tag never showed up — whole remainder is thinking,
            // no reliable answer to show.
            ChatResponse(thinking = raw.substring(openIdx + openTag.length).trim(), answer = "")
        } else {
            val thinking = raw.substring(openIdx + openTag.length, closeIdx).trim()
            val answer = stripLeakedContextBrackets(raw.substring(closeIdx + closeTag.length).trim())
            ChatResponse(thinking = thinking.takeIf { it.isNotBlank() }, answer = answer)
        }
    }

    /**
     * BUGFIX (long-standing, previously flagged as needing more than
     * prompting): Qwen3 sometimes echoes the literal "[User context:
     * ...]" / "[Known facts: ...]" block back into its visible answer,
     * despite an explicit system-instruction rule against it — because
     * that block is concatenated into the SAME user-turn text as the
     * real question (see streamChat()'s `"$prefix\n\n$text"`), a 0.6B
     * model doesn't reliably treat it as background-only metadata
     * rather than quotable message content. Restructuring how context
     * is delivered (a separate conversation turn instead of prepended
     * text) is the deeper fix and worth doing later, but this
     * post-processing strip is a guaranteed safety net in the meantime
     * — it works regardless of whether the model behaves, since we
     * control the text before it ever reaches the UI.
     */
    private fun stripLeakedContextBrackets(text: String): String {
        // Matches a leading bracketed block starting with our own known
        // prefixes specifically — deliberately narrow (not "any [...]
        // block") so a legitimate answer that happens to start with
        // brackets for an unrelated reason isn't mangled.
        val pattern = Regex(
            """^\s*\[(User context|Known facts)[^\]]*\]\s*""",
            RegexOption.IGNORE_CASE
        )
        var result = text
        // Loop rather than a single replace — carryover + RAG context +
        // user-prefs context can all land as separate bracket blocks
        // stacked at the start, so one pass wouldn't catch all of them.
        while (true) {
            val stripped = pattern.replace(result, "")
            if (stripped == result) break
            result = stripped.trimStart('\n', ' ')
        }
        return result
    }

    /**
     * LiteRT-LM's Engine wants a real filesystem path, not an
     * assets-relative one — ModelDownloadManager.localPathFor() (called
     * in init() above) already gives back exactly that, since it's the
     * same filesDir/models/ location the Models screen downloads into.
     * No copy step needed anymore now that the model was never in
     * assets/ to begin with.
     */

    fun close() {
        conversation?.close()
        engine?.close()
        conversation = null
        engine = null
    }
}
