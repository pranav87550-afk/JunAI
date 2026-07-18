package com.junai.app.ml

import android.content.Context
import com.llamatik.library.platform.GenStream
import com.llamatik.library.platform.LlamaBridge
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

object GGUFChatEngine {

    private const val TAG = "GGUFChatEngine"
    private const val CONTEXT_LENGTH = 2048

    @Volatile
    private var loaded = false

    @Volatile
    private var appContext: Context? = null

    private val initMutex = Mutex()
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
                    Breadcrumb.log(context, "GGUFChatEngine(Llamatik): about to initGenerateModel, path=${modelFile.absolutePath}, exists=${modelFile.exists()}, size=${modelFile.length()}")

                    val ok = LlamaBridge.initGenerateModel(modelFile.absolutePath)
                    Breadcrumb.log(context, "GGUFChatEngine(Llamatik): initGenerateModel returned $ok")

                    loaded = ok
                    if (!ok) {
                        android.util.Log.e(TAG, "Llamatik initGenerateModel() returned false")
                    }
                } catch (e: Exception) {
                    android.util.Log.e(TAG, "GGUF model failed to load: ${e.message}", e)
                    Breadcrumb.log(context, "GGUFChatEngine(Llamatik): init threw ${e.javaClass.simpleName}: ${e.message}")
                    loaded = false
                }
            }
        }
    }

    fun isReady(): Boolean = loaded

    data class StreamState(val answerSoFar: String, val isFinal: Boolean)

    fun streamChat(text: String): Flow<StreamState>? {
        if (!isReady()) return null
        return channelFlow {
            inferenceMutex.withLock {
                val sb = StringBuilder()
                // Llamatik keeps KV cache in memory across generateStream() calls.
                // Reset it before every independent generation, otherwise a second
                // call on top of a first can overflow CONTEXT_LENGTH and crash
                // natively (no onError, no exception — the process just dies).
                LlamaBridge.sessionReset()
                // Qwen3 is an instruction-tuned chat model — it needs the GGUF's own
                // chat template applied (system/user/assistant turn tags) or it just
                // free-completes raw text instead of answering, and never emits a
                // stop token (endless repetition). Falls back to raw text if the
                // model has no embedded template.
                val prompt = LlamaBridge.applyChatTemplate(
                    messages = listOf("user" to text),
                    addAssistantPrefix = true
                ) ?: text
                LlamaBridge.generateStream(prompt, object : GenStream {
                    override fun onDelta(text: String) {
                        sb.append(text)
                        trySend(StreamState(sb.toString(), isFinal = false))
                    }
                    override fun onComplete() {
                        trySend(StreamState(sb.toString(), isFinal = true))
                        close()
                    }
                    override fun onError(message: String) {
                        android.util.Log.w(TAG, "streamChat onError: $message")
                        close(RuntimeException("GGUF generation failed: $message"))
                    }
                })
                awaitClose { }
            }
        }.flowOn(Dispatchers.Default)
    }

    suspend fun tryChat(text: String): String? {
        if (!isReady()) return null
        return inferenceMutex.withLock {
            withContext(Dispatchers.Default) {
                val sb = StringBuilder()
                val done = CompletableDeferred<Boolean>()
                try {
                    LlamaBridge.sessionReset()
                    val prompt = LlamaBridge.applyChatTemplate(
                        messages = listOf("user" to text),
                        addAssistantPrefix = true
                    ) ?: text
                    appContext?.let { Breadcrumb.log(it, "GGUFChatEngine(Llamatik): about to call generateStream() (native)") }
                    LlamaBridge.generateStream(prompt, object : GenStream {
                        override fun onDelta(text: String) { sb.append(text) }
                        override fun onComplete() {
                            appContext?.let { Breadcrumb.log(it, "GGUFChatEngine(Llamatik): generateStream onComplete") }
                            done.complete(true)
                        }
                        override fun onError(message: String) {
                            android.util.Log.w(TAG, "tryChat onError: $message")
                            appContext?.let { Breadcrumb.log(it, "GGUFChatEngine(Llamatik): generateStream onError: $message") }
                            done.complete(false)
                        }
                    })
                    val success = done.await()
                    if (success && sb.isNotBlank()) sb.toString().trim() else null
                } catch (e: Exception) {
                    android.util.Log.w(TAG, "tryChat failed: ${e.message}")
                    null
                }
            }
        }
    }

    fun close() {
        LlamaBridge.shutdown()
        loaded = false
    }
}
