package com.junai.app.ml

import android.content.Context
import com.llamatik.library.platform.LlamaBridge
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * GGUFChatEngine — llama.cpp/GGUF runtime via Llamatik. Public API
 * deliberately mirrors ChatEngine.kt exactly (same StreamState/
 * ChatResponse shapes, same init/isReady/streamChat/tryChat/close
 * signatures) so the router (ChatIntentHandler/MainActivity/
 * WebSearchHelper) can swap ChatEngine → GGUFChatEngine by changing
 * only the qualified name, not the call sites themselves.
 *
 * Hard-won fixes baked in here (found via on-device crash debugging):
 *  1. LlamaBridge.sessionReset() before every generate() call —
 *     Llamatik keeps KV cache across calls, and skipping this caused a
 *     silent native crash once the cache overflowed CONTEXT_LENGTH on
 *     a second message.
 *  2. LlamaBridge.applyChatTemplate() — Qwen3 needs its own chat
 *     template applied (system/user/assistant turns) or it free-
 *     completes raw text instead of answering, with no stop token.
 *  3. Uses LlamaBridge.generate() (non-streaming), NOT generateStream().
 *     Confirmed via on-device tombstone (SIGABRT, JNI DETECTED ERROR —
 *     Llamatik issue #164): generateStream()'s per-chunk
 *     nativeGenerateStream() → NewStringUTF() call can hard-crash the
 *     whole process if a multi-byte UTF-8 character (emoji) happens to
 *     land split across two delta chunks. generate() builds the full
 *     response natively before ever crossing the JNI boundary, so
 *     there's no partial-UTF-8 chunk to crash on. The "streaming" feel
 *     in streamChat() below is a simulated reveal over the already-
 *     complete text, done purely on the Kotlin side — no native
 *     streaming call, no crash risk, same UX.
 *  4. SYSTEM_INSTRUCTION below is ported verbatim from ChatEngine.kt's
 *     buildConversationConfig() systemInstruction. That path set it via
 *     LiteRT-LM's ConversationConfig, which has no Llamatik equivalent
 *     — without carrying it over explicitly here, Jun had zero
 *     persona/instruction and would sometimes literally comment on the
 *     bracketed [User context: ...] text out loud (e.g. talking about
 *     "the user prefers Hinglish" instead of just replying in
 *     Hinglish) instead of acting on it.
 *
 * HISTORY: because of (1), every generate() call is a fresh KV cache —
 * there's no persistent multi-turn memory the way ChatEngine's
 * `conversation` object had (LiteRT-LM kept server-side history).
 * streamChat() now takes a `history` param (see ChatIntentHandler,
 * which builds it from the last few messages) and replays those turns
 * as real chat-template messages on every call instead — costs some
 * extra prompt-processing time per turn, but restores "does Jun
 * remember what we were just talking about" without needing a
 * persistent cache. Capped at MAX_HISTORY_TURNS to keep prompts inside
 * CONTEXT_LENGTH and responses from getting slower with every turn.
 */
object GGUFChatEngine {

    private const val TAG = "GGUFChatEngine"
    private const val CONTEXT_LENGTH = 3072 // was 2048 — bumped so maxTokens=1024
    // output doesn't eat half the context, leaving too little room for
    // SYSTEM_INSTRUCTION + MAX_HISTORY_TURNS of resent history + the new
    // user turn. 0.6B model's KV cache is small enough that this extra
    // headroom is cheap even on the 6GB device.

    @Volatile
    private var loaded = false

    @Volatile
    private var appContext: Context? = null

    private val initMutex = Mutex()

    // Only one generation at a time — matches ChatEngine's reasoning,
    // and doubly necessary here since sessionReset() + generate() must
    // run as one atomic unit per call.
    private val inferenceMutex = Mutex()

    suspend fun init(context: Context) {
        if (isReady()) return
        initMutex.withLock {
            if (isReady()) return@withLock
            if (!ModelDownloadManager.isDownloaded(context, ModelCatalog.ModelId.QWEN3_CHAT_GGUF)) {
                android.util.Log.w(TAG, "GGUF model not downloaded yet — visit the Models screen.")
                return@withLock
            }
            withContext(Dispatchers.IO) {
                try {
                    appContext = context.applicationContext
                    Breadcrumb.log(context, "GGUFChatEngine(Llamatik): about to updateGenerateParams")
                    LlamaBridge.updateGenerateParams(
                        temperature = 0.7f,
                        maxTokens = 1024,
                        topP = 0.95f,
                        topK = 40,
                        repeatPenalty = 1.1f,
                        contextLength = CONTEXT_LENGTH,
                        numThreads = 4,
                        useMmap = true,
                        flashAttention = false,
                        batchSize = 512,
                        gpuLayers = 0,
                    )

                    val modelFile = ModelDownloadManager.localPathFor(context, ModelCatalog.ModelId.QWEN3_CHAT_GGUF)
                    Breadcrumb.log(context, "GGUFChatEngine(Llamatik): about to initGenerateModel, size=${modelFile.length()}")

                    val ok = LlamaBridge.initGenerateModel(modelFile.absolutePath)
                    Breadcrumb.log(context, "GGUFChatEngine(Llamatik): initGenerateModel returned $ok")

                    loaded = ok
                } catch (e: Exception) {
                    android.util.Log.e(TAG, "GGUF model failed to load: ${e.message}", e)
                    loaded = false
                }
            }
        }
    }

    fun isReady(): Boolean = loaded

    /** Same shape as ChatEngine.ChatResponse — thinking is null if the model emitted no <think> block. */
    data class ChatResponse(val thinking: String?, val answer: String)

    /** Same shape as ChatEngine.StreamState. */
    data class StreamState(val thinkingSoFar: String, val answerSoFar: String, val isFinal: Boolean)

    // Ported verbatim from ChatEngine.kt's buildConversationConfig()
    // systemInstruction — see fix (4) in the class doc above.
    // TRIMMED FURTHER (per Pranav's request): removed the explicit
    // "Language: match Hindi/Hinglish vs English" rule and the "keep
    // thinking short, answer can be long" rule entirely. Both were meant
    // to help, but PocketPal gets naturally longer, better-formatted,
    // correctly-languaged answers out of the SAME base model with NO such
    // rules at all — so over-instructing a 0.6B model on language/length
    // was actively working against it, not for it. Left with only the
    // three rules that are genuinely non-negotiable: identity,
    // anti-impersonation, and context-bracket handling.
    // DIAGNOSTIC TEST (temporary, per Pranav's request): added the last
    // paragraph below to check whether the short-answer problem is
    // prompt-controllable at all. If answers get noticeably longer with
    // just this one line, the model IS listening and we can tune wording
    // properly. If answers stay just as short even with this, the cause
    // is deeper than the prompt (sampling params or chat-template
    // handling) and no amount of prompt-tuning will fix it — remove this
    // paragraph once the test result is in either way, don't leave it as
    // a permanent instruction.
    private const val SYSTEM_INSTRUCTION =
        "You are Jun, a personal AI assistant running fully offline on the " +
        "user's Android phone. If asked your name, you are Jun — never invent " +
        "a different name.\n\n" +
        "If the user asks about a real person other than you (e.g. \"who is " +
        "X\"), that question is about X — describe X normally using he/she/" +
        "they as appropriate. Only for your OWN identity: never claim to be " +
        "X or any other real person.\n\n" +
        "Messages may start with a bracketed block like \"[User context: " +
        "...]\" or \"[Known facts: ...]\" before the user's actual question. " +
        "These are private background notes for you only — use them " +
        "silently to inform your answer, but never mention, repeat, quote, " +
        "or comment on the bracketed text itself. Only respond to the " +
        "actual question that follows it. Trust a \"[Known facts...]\" " +
        "block over your own uncertain recall, especially for anything " +
        "about Jun/JunAI itself.\n\n" +
        "Give detailed, thorough answers with specific facts and examples " +
        "where relevant, not a brief one-paragraph summary.\n\n" +
        "Never use LaTeX or math notation (no $...$, no ^{...}). The chat " +
        "only renders plain text and basic Markdown, so write formulas and " +
        "numbers out in plain words instead, e.g. \"A = P times (1 + r " +
        "divided by n) to the power of nt\" or just describe the " +
        "calculation in words."

    // How many past exchanges to carry forward as real conversation
    // turns (not just a text summary) each time. Capped low because
    // every past turn's tokens get re-processed from scratch on every
    // single call now (see the class doc on why persistent KV cache
    // isn't safe here) — too high a number both slows every response
    // down and risks pushing the prompt past CONTEXT_LENGTH.
    private const val MAX_HISTORY_TURNS = 3

    private fun buildPrompt(text: String, userContext: String?, history: List<Pair<String, String>>): String {
        val userTurn = if (userContext.isNullOrBlank()) text else "$userContext\n\n$text"
        val messages = mutableListOf("system" to SYSTEM_INSTRUCTION)
        messages.addAll(history.takeLast(MAX_HISTORY_TURNS * 2))
        messages.add("user" to userTurn)
        return LlamaBridge.applyChatTemplate(
            messages = messages,
            addAssistantPrefix = true
        ) ?: "$SYSTEM_INSTRUCTION\n\n$userTurn"
    }

    // Reveal pacing for the simulated typing effect — tuned to feel
    // lively without meaningfully adding to total response time.
    private const val REVEAL_CHARS_PER_TICK = 3
    private const val REVEAL_TICK_MS = 12L

    /**
     * "Streaming" version — same contract as ChatEngine.streamChat():
     * null immediately if not ready, otherwise a Flow of growing
     * thinking/answer text ending with isFinal=true.
     *
     * Not actually native streaming (see fix #3 in the class doc) — the
     * full response comes back from one generate() call, then this
     * reveals it progressively on the Kotlin side so the "Jun is
     * thinking..." → typewriter UI behaves exactly as before, with no
     * native streaming call and therefore no crash risk.
     */
    fun streamChat(text: String, userContext: String? = null, history: List<Pair<String, String>> = emptyList()): Flow<StreamState>? {
        if (!isReady()) return null
        return flow {
            inferenceMutex.withLock {
                val prompt = buildPrompt(text, userContext, history)
                appContext?.let { Breadcrumb.log(it, "GGUFChatEngine(Llamatik): streamChat about to sessionReset + generate") }
                LlamaBridge.sessionReset()
                val raw = LlamaBridge.generate(prompt)
                appContext?.let { Breadcrumb.log(it, "GGUFChatEngine(Llamatik): generate returned, len=${raw.length}") }

                if (raw.isBlank()) {
                    emit(StreamState(thinkingSoFar = "", answerSoFar = "", isFinal = true))
                    return@withLock
                }

                val parsed = parseThinkingAndAnswer(raw)
                val thinkingFull = parsed.thinking ?: ""
                val answerFull = parsed.answer

                // Reveal thinking first (if any), then the answer —
                // mirrors how the real thing would have arrived.
                var shown = 0
                while (shown < thinkingFull.length) {
                    shown = (shown + REVEAL_CHARS_PER_TICK).coerceAtMost(thinkingFull.length)
                    emit(StreamState(thinkingSoFar = thinkingFull.substring(0, shown), answerSoFar = "", isFinal = false))
                    delay(REVEAL_TICK_MS)
                }
                shown = 0
                while (shown < answerFull.length) {
                    shown = (shown + REVEAL_CHARS_PER_TICK).coerceAtMost(answerFull.length)
                    emit(StreamState(thinkingSoFar = thinkingFull, answerSoFar = answerFull.substring(0, shown), isFinal = false))
                    delay(REVEAL_TICK_MS)
                }
                emit(StreamState(thinkingSoFar = thinkingFull, answerSoFar = answerFull, isFinal = true))
            }
        }.flowOn(Dispatchers.Default)
    }

    /**
     * One-shot version — same contract as ChatEngine.tryChat(). This is
     * what the Models-screen "Test GGUF Chat" button calls; it uses the
     * same crash-safe generate() path as streamChat().
     */
    suspend fun tryChat(text: String): ChatResponse? {
        if (!isReady()) return null
        return inferenceMutex.withLock {
            withContext(Dispatchers.Default) {
                val prompt = buildPrompt(text, null, emptyList())
                try {
                    LlamaBridge.sessionReset()
                    appContext?.let { Breadcrumb.log(it, "GGUFChatEngine(Llamatik): tryChat about to sessionReset + generate") }
                    val raw = LlamaBridge.generate(prompt)
                    appContext?.let { Breadcrumb.log(it, "GGUFChatEngine(Llamatik): tryChat generate returned, len=${raw.length}") }
                    if (raw.isBlank()) null else parseThinkingAndAnswer(raw)
                } catch (e: Exception) {
                    android.util.Log.w(TAG, "tryChat failed: ${e.message}")
                    null
                }
            }
        }
    }

    /** Copied from ChatEngine.parseThinkingAndAnswer() — same <think> splitting rules. */
    private fun parseThinkingAndAnswer(raw: String): ChatResponse {
        val openTag = "<think>"
        val closeTag = "</think>"
        val openIdx = raw.indexOf(openTag)
        if (openIdx == -1) {
            return ChatResponse(thinking = null, answer = stripLeakedContextBrackets(raw.trim()))
        }
        val closeIdx = raw.indexOf(closeTag, startIndex = openIdx)
        return if (closeIdx == -1) {
            ChatResponse(thinking = raw.substring(openIdx + openTag.length).trim(), answer = "")
        } else {
            val thinking = raw.substring(openIdx + openTag.length, closeIdx).trim()
            val answer = stripLeakedContextBrackets(raw.substring(closeIdx + closeTag.length).trim())
            ChatResponse(thinking = thinking.takeIf { it.isNotBlank() }, answer = answer)
        }
    }

    /**
     * Ported from ChatEngine.kt — this bug was never actually
     * LiteRT-LM-specific, it's a small-model instruction-following
     * reliability problem: Qwen3 sometimes echoes the literal
     * "[User context: ...]" block back into its visible answer despite
     * SYSTEM_INSTRUCTION explicitly telling it not to, because that
     * block is concatenated into the SAME user-turn text as the real
     * question (see buildPrompt()'s "$userContext\n\n$text"), and a
     * 0.6B model doesn't reliably treat it as background-only metadata
     * rather than quotable content. Confirmed leaking on-device even
     * with the system instruction in place. Restructuring how context
     * is delivered (a separate conversation turn instead of prepended
     * text) is the deeper fix and worth trying later, but this
     * post-processing strip is a guaranteed safety net regardless of
     * whether the model behaves, since it runs after generation and
     * before the text ever reaches the UI.
     */
    private fun stripLeakedContextBrackets(text: String): String {
        val pattern = Regex(
            """^\s*\[(User context|Known facts)[^\]]*\]\s*""",
            RegexOption.IGNORE_CASE
        )
        var result = text
        while (true) {
            val stripped = pattern.replace(result, "")
            if (stripped == result) break
            result = stripped.trimStart('\n', ' ')
        }
        return result
    }

    fun close() {
        LlamaBridge.shutdown()
        loaded = false
    }
}
