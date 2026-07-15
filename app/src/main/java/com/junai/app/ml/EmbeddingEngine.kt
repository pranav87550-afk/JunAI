package com.junai.app.ml

import android.content.Context
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.text.textembedder.TextEmbedder
import com.google.mediapipe.tasks.text.textembedder.TextEmbedder.TextEmbedderOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * EmbeddingEngine — on-device semantic similarity via EmbeddingGemma
 * (model file: assets/embeddinggemma-300M_seq1024_mixed-precision.tflite).
 *
 * IMPORTANT COMPATIBILITY NOTE, read before debugging a crash here:
 * This wraps MediaPipe's high-level TextEmbedder task API, which
 * historically expects a self-contained .tflite bundle (embedded metadata
 * + tokenizer, the format MediaPipe's own Model Maker produces — e.g. the
 * Universal Sentence Encoder model in Google's docs). EmbeddingGemma's
 * exported .tflite is NOT confirmed to be packaged that way — the
 * Hugging Face repo ships a SEPARATE `sentencepiece.model` tokenizer file
 * alongside the .tflite, which is a strong signal the .tflite has no
 * embedded tokenizer of its own.
 *
 * What this means practically: createFromOptions() below may throw at
 * runtime (something like "model does not have required metadata") on
 * this specific model file. If that happens, this task-level API is the
 * wrong tool for EmbeddingGemma specifically, and the fallback is a raw
 * org.tensorflow:tensorflow-lite Interpreter + a separate SentencePiece
 * tokenizer (would need `sentencepiece.model` downloaded too, and a JNI
 * SentencePiece binding — a bigger follow-up change, not done here).
 *
 * Tried first because it's ~20 lines instead of a full manual tokenizer
 * pipeline — worth the one runtime test before committing to the bigger
 * fallback. If init() logs the metadata error, report back and we'll
 * switch to the raw-Interpreter path.
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
    private var initFailed = false

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
        withContext(Dispatchers.IO) {
            if (embedder != null || initFailed) return@withContext
            if (!ModelDownloadManager.isDownloaded(context, ModelCatalog.ModelId.EMBEDDING_GEMMA)) {
                android.util.Log.w(TAG, "EmbeddingGemma model not downloaded yet — visit the Models screen.")
                return@withContext
            }
            try {
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
                embedder = TextEmbedder.createFromOptions(context.applicationContext, options)
            } catch (e: Exception) {
                initFailed = true
                android.util.Log.e(
                    TAG,
                    "EmbeddingGemma failed to load via TextEmbedder task API — see this file's " +
                        "header comment, this may mean the model needs the raw-Interpreter " +
                        "fallback instead. Error: ${e.message}",
                    e
                )
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
        return withContext(Dispatchers.Default) {
            try {
                val embedA = engine.embed(a).embeddingResult().embeddings()[0]
                val embedB = engine.embed(b).embeddingResult().embeddings()[0]
                TextEmbedder.cosineSimilarity(embedA, embedB).toDouble()
            } catch (e: Exception) {
                android.util.Log.w(TAG, "similarity() failed for inputs, falling back to null: ${e.message}")
                null
            }
        }
    }

    /** Release native resources — call from Application.onTerminate() equivalent if ever needed. */
    fun close() {
        embedder?.close()
        embedder = null
    }
}
