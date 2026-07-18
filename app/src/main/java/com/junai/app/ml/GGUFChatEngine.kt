package com.junai.app.ml

import android.content.Context
import android.net.Uri
import org.nehuatl.llamacpp.LlamaHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * GGUFChatEngine — Piece 2 of the llama.cpp migration (see ChatEngine.kt
 * for the full backstory/diagnosis of why LiteRT-LM is being replaced).
 *
 * NOT wired into the router yet — this exists standalone so it can be
 * tested/loaded/chatted with in isolation (e.g. from a debug screen)
 * before it ever touches the real ChatIntentHandler fallback chain.
 * Swapping ChatEngine → GGUFChatEngine in the router is a later piece,
 * once this is confirmed working on-device.
 *
 * API shape deliberately mirrors ChatEngine.kt (init/isReady/streamChat/
 * tryChat/close) even though the underlying library (kotlinllamacpp's
 * LlamaHelper) has a very different internal design — event-flow based
 * (MutableSharedFlow<LLMEvent>) rather than a suspend-returning
 * Conversation object. All of that translation lives in here so callers
 * never need to know the difference.
 */
object GGUFChatEngine {

    private const val TAG = "GGUFChatEngine"

    private const val CONTEXT_LENGTH = 2048

    private const val GENERATION_TIMEOUT_MS = 120_000L

    @Volatile
    private var llamaHelper: LlamaHelper? = null

    @Volatile
    private var loaded = false

    @Volatile
    private var appContext: Context? = null

    private val initMutex = Mutex()

    private val inferenceMutex = Mutex()

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val llmFlow = MutableSharedFlow<LlamaHelper.LLMEvent>(
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

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
                    val helper = LlamaHelper(
                        context.applicationContext.contentResolver,
                        scope,
                        llmFlow,
                    )
                    val modelFile = ModelDownloadManager.localPathFor(context, ModelCatalog.ModelId.QWEN3_CHAT_GGUF)
                    val modelUri = Uri.fromFile(modelFile).toString()

                    Breadcrumb.log(context, "GGUFChatEngine: about to call helper.load() (native)")
                    suspendCancellableCoroutine<Unit> { cont ->
                        try {
                            helper.load(path = modelUri, contextLength = CONTEXT_LENGTH) {
                                if (cont.isActive) cont.resume(Unit)
                            }
                        } catch (e: Exception) {
                            if (cont.isActive) cont.cancel(e)
                        }
                    }
                    Breadcrumb.log(context, "GGUFChatEngine: helper.load() callback returned OK")
                    llamaHelper = helper
                    loaded = true
                    android.util.Log.i(TAG, "GGUF model loaded OK")
                } catch (e: Exception) {
                    android.util.Log.e(TAG, "GGUF model failed to load: ${e.message}", e)
                    llamaHelper = null
                    loaded = false
                }
            }
        }
    }

    fun isReady(): Boolean = llamaHelper != null && loaded

    data class StreamState(val answerSoFar: String, val isFinal: Boolean)

    fun streamChat(text: String): Flow<StreamState>? {
        if (!isReady()) return null
        val helper = llamaHelper ?: return null
        return channelFlow {
            inferenceMutex.withLock {
                val sb = StringBuilder()
                val collectJob = launch {
                    llmFlow.collect { event ->
                        when (event) {
                            is LlamaHelper.LLMEvent.Ongoing -> {
                                sb.append(event.word)
                                send(StreamState(sb.toString(), isFinal = false))
                            }
                            is LlamaHelper.LLMEvent.Done -> {
                                send(StreamState(sb.toString(), isFinal = true))
                                close()
                            }
                            is LlamaHelper.LLMEvent.Error -> {
                                close(RuntimeException("GGUF generation failed"))
                            }
                            else -> {}
                        }
                    }
                }
                try {
                    helper.predict(text)
                } catch (e: Exception) {
                    android.util.Log.w(TAG, "predict() threw: ${e.message}")
                    close(e)
                }
                awaitClose { collectJob.cancel() }
            }
        }
    }

    suspend fun tryChat(text: String): String? {
        if (!isReady()) return null
        val helper = llamaHelper ?: return null
        return inferenceMutex.withLock {
            withContext(Dispatchers.Default) {
                val sb = StringBuilder()
                val done = CompletableDeferred<Boolean>()
                val collectJob = scope.launch {
                    llmFlow.collect { event ->
                        when (event) {
                            is LlamaHelper.LLMEvent.Ongoing -> sb.append(event.word)
                            is LlamaHelper.LLMEvent.Done -> done.complete(true)
                            is LlamaHelper.LLMEvent.Error -> done.complete(false)
                            else -> {}
                        }
                    }
                }
                val result = try {
                    appContext?.let { Breadcrumb.log(it, "GGUFChatEngine: about to call helper.predict() (native)") }
                    helper.predict(text)
                    val success = withTimeoutOrNull(GENERATION_TIMEOUT_MS) { done.await() } ?: false
                    appContext?.let { Breadcrumb.log(it, "GGUFChatEngine: predict() flow completed, success=$success") }
                    if (success && sb.isNotBlank()) sb.toString().trim() else null
                } catch (e: Exception) {
                    android.util.Log.w(TAG, "tryChat failed: ${e.message}")
                    null
                } finally {
                    collectJob.cancel()
                }
                result
            }
        }
    }

    fun close() {
        llamaHelper = null
        loaded = false
    }
}
