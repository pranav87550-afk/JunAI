package com.junai.app.ml

import android.content.Context
import android.net.Uri
import io.github.ljcamargo.llamacpp.LlamaHelper
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

    // kotlinllamacpp docs use 2048/4096 in their own examples. Starting
    // at 2048 (lower than ChatEngine's 4096) since llama.cpp's KV-cache
    // memory cost scales with context length and this is untested on
    // real devices yet — raise later once we've confirmed no OOM issues
    // on lower-RAM phones.
    private const val CONTEXT_LENGTH = 2048

    // How long to wait for one generation to finish before giving up —
    // generation could in theory hang forever without this (no timeout
    // built into predict() itself as far as the library docs show).
    private const val GENERATION_TIMEOUT_MS = 120_000L

    @Volatile
    private var llamaHelper: LlamaHelper? = null

    @Volatile
    private var loaded = false

    private val initMutex = Mutex()

    // Only ONE generation at a time — llmFlow below is a single shared
    // stream for the whole object, so two concurrent predict() calls
    // would have their tokens interleaved in the same flow with no way
    // to tell which word belongs to which call. Same concern ChatEngine
    // solved with initMutex, just for inference instead of init here.
    private val inferenceMutex = Mutex()

    // Own scope since this is a singleton object, not a ViewModel — the
    // library's recommended pattern (see its README) ties this scope to
    // a ViewModel's lifecycle; we don't have one, so this lives as long
    // as the process does, same as `engine`/`conversation` in ChatEngine.
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
                    val helper = LlamaHelper(
                        context.applicationContext.contentResolver,
                        scope,
                        llmFlow,
                    )
                    val modelFile = ModelDownloadManager.localPathFor(context, ModelCatalog.ModelId.QWEN3_CHAT_GGUF)
                    // Fixed app-storage file, not a user-picked one, so
                    // Uri.fromFile() is enough — no persistable
                    // permission dance needed (that's only required for
                    // the File Picker / content:// case per the
                    // library's README).
                    val modelUri = Uri.fromFile(modelFile).toString()

                    // load() is callback-based, not suspend — bridge it
                    // so init() can be awaited the same way ChatEngine's
                    // init() is.
                    suspendCancellableCoroutine<Unit> { cont ->
                        try {
                            helper.load(path = modelUri, contextLength = CONTEXT_LENGTH) {
                                if (cont.isActive) cont.resume(Unit)
                            }
                        } catch (e: Exception) {
                            if (cont.isActive) cont.cancel(e)
                        }
                    }
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

    /** One update from streamChat() — mirrors ChatEngine.StreamState's shape, minus the thinking/answer split (no <think> tags with this runtime by default). */
    data class StreamState(val answerSoFar: String, val isFinal: Boolean)

    /**
     * Live-streaming version — emits growing text as tokens arrive.
     * Returns null immediately if not ready, same non-blocking contract
     * as ChatEngine.streamChat().
     */
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

    /**
     * One-shot version, mirrors ChatEngine.tryChat() — null if not
     * ready or generation failed/timed out.
     */
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
                    helper.predict(text)
                    val success = withTimeoutOrNull(GENERATION_TIMEOUT_MS) { done.await() } ?: false
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
