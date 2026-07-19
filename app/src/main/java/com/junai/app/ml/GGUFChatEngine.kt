package com.junai.app.ml

import android.content.Context
import com.llamatik.library.platform.GenStream
import com.llamatik.library.platform.LlamaBridge
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
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
 * Two hard-won fixes baked in here (found via on-device crash
 * debugging — see /areas/junai.md history):
 *  1. LlamaBridge.sessionReset() before every generateStream() call —
 *     Llamatik keeps KV cache across calls, and skipping this caused a
 *     silent native crash (no exception, no onError) once the cache
 *     overflowed CONTEXT_LENGTH on a second message.
 *  2. LlamaBridge.applyChatTemplate() — Qwen3 needs its own chat
 *     template applied (system/user/assistant turns) or it free-
 *     completes raw text instead of answering, with no stop token.
 *
 * KNOWN LIMITATION: because of (1), every call here is a fresh,
 * single-turn generation — there's no persistent multi-turn memory
 * the way ChatEngine's `conversation` object has (LiteRT-LM keeps
 * server-side history; Llamatik's KV cache can't safely be reused
 * across turns here). userContext is folded into the prompt text each
 * time as a substitute, same as ChatEngine's carryover pattern, but
 * true conversational memory across turns is a gap to revisit later.
 */
object GGUFChatEngine {

    private const val TAG = "GGUFChatEngine"
    private const val CONTEXT_LENGTH = 2048

    @Volatile
    private var loaded = false

    @Volatile
    private var appContext: Context? = null

    private val initMutex = Mutex()

    // Only one generation at a time — matches ChatEngine's reasoning,
    // and doubly necessary here since sessionReset() + generateStream()
    // must run as one atomic unit per call.
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
                        maxTokens = 512,
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
    // systemInstruction — that path set this via LiteRT-LM's
    // ConversationConfig, which doesn't exist in Llamatik. Without it
    // here, the model got zero persona/instruction and would sometimes
    // literally comment on bracketed [User context: ...] text out loud
    // instead of acting on it (e.g. talking about "the user prefers
    // Hinglish" instead of just replying in Hinglish) — the exact
    // failure mode this instruction was written to prevent.
    private const val SYSTEM_INSTRUCTION =
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

    private fun buildPrompt(text: String, userContext: String?): String {
        val userTurn = if (userContext.isNullOrBlank()) text else "$userContext\n\n$text"
        return LlamaBridge.applyChatTemplate(
            messages = listOf("system" to SYSTEM_INSTRUCTION, "user" to userTurn),
            addAssistantPrefix = true
        ) ?: "$SYSTEM_INSTRUCTION\n\n$userTurn"
    }

    /**
     * Live-streaming version — same contract as ChatEngine.streamChat():
     * null immediately if not ready, otherwise a Flow of growing
     * thinking/answer text ending with isFinal=true.
     */
    fun streamChat(text: String, userContext: String? = null): Flow<StreamState>? {
        if (!isReady()) return null
        return flow {
            inferenceMutex.withLock {
                val prompt = buildPrompt(text, userContext)
                var raw = ""
                val done = CompletableDeferred<Boolean>()
                appContext?.let { Breadcrumb.log(it, "GGUFChatEngine(Llamatik): streamChat about to sessionReset + generateStream") }
                LlamaBridge.sessionReset()
                LlamaBridge.generateStream(prompt, object : GenStream {
                    override fun onDelta(text: String) { raw += text }
                    override fun onComplete() { done.complete(true) }
                    override fun onError(message: String) {
                        android.util.Log.w(TAG, "streamChat onError: $message")
                        done.complete(false)
                    }
                })
                // Llamatik's generateStream() delivers deltas via the
                // GenStream callback synchronously on the calling
                // thread per its docs, so by the time done.await()
                // resolves, `raw` already holds the complete text —
                // this emits once at the end rather than mid-stream.
                // (Revisit if Llamatik's docs turn out to mean
                // otherwise — this was written without a way to verify
                // callback timing on-device beyond the working test.)
                done.await()
                val parsed = parseThinkingAndAnswer(raw)
                emit(StreamState(thinkingSoFar = parsed.thinking ?: "", answerSoFar = parsed.answer, isFinal = true))
            }
        }.flowOn(Dispatchers.Default)
    }

    /** One-shot version — same contract as ChatEngine.tryChat(). */
    suspend fun tryChat(text: String): ChatResponse? {
        if (!isReady()) return null
        return inferenceMutex.withLock {
            withContext(Dispatchers.Default) {
                val prompt = buildPrompt(text, null)
                val sb = StringBuilder()
                val done = CompletableDeferred<Boolean>()
                try {
                    LlamaBridge.sessionReset()
                    LlamaBridge.generateStream(prompt, object : GenStream {
                        override fun onDelta(text: String) { sb.append(text) }
                        override fun onComplete() { done.complete(true) }
                        override fun onError(message: String) {
                            android.util.Log.w(TAG, "tryChat onError: $message")
                            done.complete(false)
                        }
                    })
                    val success = done.await()
                    if (success && sb.isNotBlank()) parseThinkingAndAnswer(sb.toString()) else null
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
            return ChatResponse(thinking = null, answer = raw.trim())
        }
        val closeIdx = raw.indexOf(closeTag, startIndex = openIdx)
        return if (closeIdx == -1) {
            ChatResponse(thinking = raw.substring(openIdx + openTag.length).trim(), answer = "")
        } else {
            val thinking = raw.substring(openIdx + openTag.length, closeIdx).trim()
            val answer = raw.substring(closeIdx + closeTag.length).trim()
            ChatResponse(thinking = thinking.takeIf { it.isNotBlank() }, answer = answer)
        }
    }

    fun close() {
        LlamaBridge.shutdown()
        loaded = false
    }
}
