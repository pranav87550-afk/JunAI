package com.junai.app.ml

import android.content.Context
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import kotlinx.coroutines.Dispatchers
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
     * One-shot general response, or null if the engine isn't ready or
     * generation failed — caller falls back to the existing forUnknown()
     * template in that case, same non-blocking pattern as the other
     * ml/ engines.
     */
    suspend fun tryChat(text: String): String? {
        val conv = conversation ?: return null
        return withContext(Dispatchers.Default) {
            try {
                val response = conv.sendMessage(text)
                response.toString().takeIf { it.isNotBlank() }
            } catch (e: Exception) {
                android.util.Log.w(TAG, "tryChat failed, falling back to null: ${e.message}")
                null
            }
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
