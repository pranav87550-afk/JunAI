package com.junai.app.ml

import android.content.Context
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.text.textembedder.TextEmbedder
import com.google.mediapipe.tasks.text.textembedder.TextEmbedder.TextEmbedderOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * EmbeddingEngine — on-device semantic similarity via Universal
 * Sentence Encoder (model file: universal_sentence_encoder.tflite,
 * downloaded from Google's own storage.googleapis.com — see
 * ModelCatalog's downloadUrlOverride for the EMBEDDING_GEMMA entry).
 *
 * RESOLVED COMPATIBILITY ISSUE (previously EmbeddingGemma here — kept
 * for context since the ModelId enum constant is still named
 * EMBEDDING_GEMMA to avoid touching every call site): confirmed via
 * on-device MediaPipeException every attempt, EVERY EmbeddingGemma
 * .tflite variant (including "mixed-precision") fails to load through
 * this TextEmbedder task API — not a download/corruption issue (a
 * byte-perfect verified download still failed identically). Root cause:
 * EmbeddingGemma is built for Google's newer LiteRT Compiled Model API
 * or the (deprecated) AI Edge RAG SDK, not MediaPipe Tasks TextEmbedder
 * — confirmed by google-ai-edge/mediapipe issue #6217, an OPEN feature
 * request literally asking for EmbeddingGemma + TextEmbedder support.
 * Universal Sentence Encoder is Google's own documented model for this
 * exact API. Trade-off worth remembering: USE is primarily
 * English-trained, weaker than EmbeddingGemma would have been on
 * Hinglish queries — the multilingual USE variant was considered and
 * rejected too (needs a Flex-ops delegate TextEmbedder doesn't support,
 * mediapipe issue #4929). If Hinglish RAG-matching quality becomes a
 * real problem, revisit the (deprecated but still available) AI Edge
 * RAG SDK, or a raw Interpreter + SentencePiece tokenizer path for
 * EmbeddingGemma specifically (bigger change, not done here).
 * NOTE ON LOADING FROM filesDir/ (added when models moved off
 * assets/): MediaPipe's BaseOptions.setModelAssetPath() is documented
 * specifically for a path in the assets folder — unlike LlmInference's
 * setModelPath() (used by FunctionCallEngine), there's no official
 * confirmation it also accepts an arbitrary absolute filesystem path.
 * Rather than gamble on undocumented behavior for a component already
 * flagged as compatibility-uncertain above, this reads the downloaded
 * file into a MappedByteBuffer and hands it to setModelAssetBuffer()
 * instead — that method's contract is unambiguous regardless of where
 * the bytes came from.
 */
object EmbeddingEngine {

    private const val TAG = "EmbeddingEngine"

    @Volatile
    private var embedder: TextEmbedder? = null

    @Volatile
    private var appContext: Context? = null

    @Volatile
    private var initFailed = false

    // Same race-condition fix as ChatEngine/FunctionCallEngine.
    private val initMutex = Mutex()

    /**
     * Loads the model from filesDir/models/ (downloaded via the Models
     * screen). Safe to call multiple times — a no-op once already
     * initialized (or once already known to have failed, so callers
     * aren't silently retrying a doomed load on every screen). Call
     * once from Application.onCreate() or lazily before first use —
     * either way it must run off the main thread, model load is a
     * multi-hundred-ms disk+init operation.
     */
    suspend fun init(context: Context) {
        if (embedder != null || initFailed) return
        initMutex.withLock {
        if (embedder != null || initFailed) return@withLock
        withContext(Dispatchers.IO) {
            if (embedder != null || initFailed) return@withContext
            if (!ModelDownloadManager.isDownloaded(context, ModelCatalog.ModelId.EMBEDDING_GEMMA)) {
                android.util.Log.w(TAG, "Embedding model not downloaded yet — visit the Models screen.")
                return@withContext
            }
            try {
                appContext = context.applicationContext
                val modelFile = ModelDownloadManager.localPathFor(context, ModelCatalog.ModelId.EMBEDDING_GEMMA)
                val mappedBuffer = java.io.RandomAccessFile(modelFile, "r").use { raf ->
                    raf.channel.use { channel ->
                        channel.map(java.nio.channels.FileChannel.MapMode.READ_ONLY, 0, channel.size())
                    }
                }
                val baseOptions = BaseOptions.builder()
                    .setModelAssetBuffer(mappedBuffer)
                    .build()
                val options = TextEmbedderOptions.builder()
                    .setBaseOptions(baseOptions)
                    // l2Normalize + quantize both default to false; leave
                    // as-is for now — cosineSimilarity() below works
                    // either way, and quantized output would need a
                    // different similarity function.
                    .build()
                Breadcrumb.log(context, "EmbeddingEngine: about to call TextEmbedder.createFromOptions (native)")
                embedder = TextEmbedder.createFromOptions(context.applicationContext, options)
                Breadcrumb.log(context, "EmbeddingEngine: createFromOptions returned OK")
            } catch (e: Exception) {
                initFailed = true
                Breadcrumb.log(context, "EmbeddingEngine: createFromOptions FAILED (${e.javaClass.simpleName}: ${e.message})")
                android.util.Log.e(
                    TAG,
                    "Embedding model failed to load via TextEmbedder task API — see this file's " +
                        "header comment for the EmbeddingGemma-vs-Universal-Sentence-Encoder " +
                        "history. Error: ${e.message}",
                    e
                )
            }
        }
        }
    }

    /** True once init() has completed successfully. */
    fun isReady(): Boolean = embedder != null

    /**
     * Semantic similarity between two short phrases, 0.0 (unrelated) to
     * 1.0 (identical meaning). Returns null if the engine isn't ready
     * (not yet initialized, or init() failed) — callers should fall back
     * to Levenshtein-only matching in that case, not treat null as 0.0.
     */
    suspend fun similarity(a: String, b: String): Double? {
        val engine = embedder ?: return null
        val ctx = appContext
        return withContext(Dispatchers.Default) {
            try {
                if (ctx != null) Breadcrumb.log(ctx, "EmbeddingEngine: about to call engine.embed() (native) for a=\"${a.take(30)}\"")
                val embedA = engine.embed(a).embeddingResult().embeddings()[0]
                if (ctx != null) Breadcrumb.log(ctx, "EmbeddingEngine: embed(a) OK, about to embed(b)")
                val embedB = engine.embed(b).embeddingResult().embeddings()[0]
                if (ctx != null) Breadcrumb.log(ctx, "EmbeddingEngine: embed(b) OK, computing cosineSimilarity")
                TextEmbedder.cosineSimilarity(embedA, embedB).toDouble()
            } catch (e: Exception) {
                android.util.Log.w(TAG, "similarity() failed for inputs, falling back to null: ${e.message}")
                null
            }
        }
    }

    /**
     * Raw embedding vector for one piece of text, for callers that need
     * to compare a query against MANY pre-computed vectors (like
     * KnowledgeBase's ~195 entries) — similarity() above re-embeds BOTH
     * of its inputs on every single call, which is fine for TriggerMatcher's
     * one-off comparisons but wasteful once there's a fixed entry set:
     * embedding all ~195 entry topics once and caching the vectors (see
     * KnowledgeBase.buildOrLoadEmbeddingCache()) means only the query
     * itself needs embedding per user message, not the whole knowledge
     * base every time. Returns null if not ready or on any embed failure.
     */
    suspend fun embedVector(text: String): FloatArray? {
        val engine = embedder ?: return null
        return withContext(Dispatchers.Default) {
            try {
                engine.embed(text).embeddingResult().embeddings()[0].floatEmbedding()
            } catch (e: Exception) {
                android.util.Log.w(TAG, "embedVector() failed: ${e.message}")
                null
            }
        }
    }

    /**
     * Manual cosine similarity between two already-computed vectors
     * (e.g. one from embedVector() just now, one loaded from
     * KnowledgeBase's on-disk cache) — TextEmbedder.cosineSimilarity()
     * only accepts its own Embedding wrapper objects, not raw
     * FloatArrays, so a plain dot-product/norm implementation is needed
     * here instead. Same 0.0-1.0 range as similarity() above for
     * normalized embeddings.
     */
    fun cosineSimilarity(a: FloatArray, b: FloatArray): Double {
        if (a.isEmpty() || b.isEmpty() || a.size != b.size) return 0.0
        var dot = 0.0
        var normA = 0.0
        var normB = 0.0
        for (i in a.indices) {
            dot += a[i] * b[i]
            normA += a[i] * a[i]
            normB += b[i] * b[i]
        }
        if (normA == 0.0 || normB == 0.0) return 0.0
        return dot / (kotlin.math.sqrt(normA) * kotlin.math.sqrt(normB))
    }

    /** Release native resources — call from Application.onTerminate() equivalent if ever needed. */
    fun close() {
        embedder?.close()
        embedder = null
    }
}
