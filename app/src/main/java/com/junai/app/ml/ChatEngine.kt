package com.junai.app.ml

import android.content.Context
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import java.io.File

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
 * setModelAssetPath() resolves automatically. That means the .litertlm
 * file has to be copied out of assets/ into app-private storage once,
 * on first use — copyModelToInternalStorageIfNeeded() below does that.
 * (This copy needs ~600MB of free space temporarily alongside the
 * bundled copy — worth knowing if a low-storage device ever fails here.)
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

    private const val MODEL_ASSET_PATH = "qwen3_0_6b_mixed_int4.litertlm"
    private const val TAG = "ChatEngine"

    @Volatile
    private var engine: Engine? = null

    @Volatile
    private var conversation: com.google.ai.edge.litertlm.Conversation? = null

    @Volatile
    private var initFailed = false

    suspend fun init(context: Context) {
        if (engine != null || initFailed) return
        withContext(Dispatchers.IO) {
            if (engine != null || initFailed) return@withContext
            try {
                val modelFile = copyModelToInternalStorageIfNeeded(context)
                val engineConfig = EngineConfig(modelPath = modelFile.absolutePath)
                val newEngine = Engine(engineConfig)
                // engine.initialize() can take up to ~10s per Google's own
                // docs — we're already on Dispatchers.IO here, so this is
                // safe, just don't call init() from the main thread.
                newEngine.initialize()
                conversation = newEngine.createConversation()
                engine = newEngine
            } catch (e: Exception) {
                initFailed = true
                android.util.Log.e(TAG, "Qwen3/LiteRT-LM failed to load: ${e.message}", e)
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
    fun streamChat(text: String): Flow<StreamState>? {
        val conv = conversation ?: return null
        return flow {
            var raw = ""
            conv.sendMessageAsync(text).collect { chunk ->
                raw += chunk.toString()
                val parsed = parseThinkingAndAnswer(raw)
                emit(StreamState(thinkingSoFar = parsed.thinking ?: "", answerSoFar = parsed.answer, isFinal = false))
            }
            val final = parseThinkingAndAnswer(raw)
            emit(StreamState(thinkingSoFar = final.thinking ?: "", answerSoFar = final.answer, isFinal = true))
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
     * updates and just wants the final result.
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
            return ChatResponse(thinking = null, answer = raw.trim())
        }
        val closeIdx = raw.indexOf(closeTag, startIndex = openIdx)
        return if (closeIdx == -1) {
            // Closing tag never showed up — whole remainder is thinking,
            // no reliable answer to show.
            ChatResponse(thinking = raw.substring(openIdx + openTag.length).trim(), answer = "")
        } else {
            val thinking = raw.substring(openIdx + openTag.length, closeIdx).trim()
            val answer = raw.substring(closeIdx + closeTag.length).trim()
            ChatResponse(thinking = thinking.takeIf { it.isNotBlank() }, answer = answer)
        }
    }

    /**
     * LiteRT-LM's Engine wants a real filesystem path, not an
     * assets-relative one — copy once into app-private files dir, then
     * reuse that copy on subsequent launches (checked via file size match
     * so a partial/corrupt previous copy gets redone rather than trusted).
     */
    private fun copyModelToInternalStorageIfNeeded(context: Context): File {
        val outFile = File(context.filesDir, MODEL_ASSET_PATH)
        val assetManager = context.assets
        val assetSize = assetManager.openFd(MODEL_ASSET_PATH).use { it.length }
        if (outFile.exists() && outFile.length() == assetSize) {
            return outFile
        }
        assetManager.open(MODEL_ASSET_PATH).use { input ->
            outFile.outputStream().use { output ->
                input.copyTo(output)
            }
        }
        return outFile
    }

    fun close() {
        conversation?.close()
        engine?.close()
        conversation = null
        engine = null
    }
}
