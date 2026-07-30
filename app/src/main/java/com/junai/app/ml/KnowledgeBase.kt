package com.junai.app.ml

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * KnowledgeBase — RAG content store, injected into Qwen3's prompt when
 * relevant (see ChatIntentHandler.buildRagContext()).
 *
 * WHY THIS EXISTS: fine-tuning teaches Qwen3 style, not facts — a 0.6B
 * model's "knowledge" is capped by its size no matter how it's trained.
 * RAG sidesteps that: instead of the model trying to recall facts from
 * its own weights, we hand it the relevant facts directly in the prompt
 * right before it answers — Jun becomes a "teacher reading from a book"
 * rather than "a puppet reciting from memory" (Pranav's framing).
 *
 * SOURCE OF ENTRIES (v2 — expanded from the original ~8 "about Jun"
 * facts): 13 domain JSON files (medical, career, finance, lifestyle,
 * cooking, apps_games, films, education, tech, programming, ai_ml, gk,
 * webdev — see ModelCatalog.KNOWLEDGE_FILES), hosted alongside the AI
 * models on Hugging Face and downloaded on-demand from the Models
 * screen exactly like EmbeddingGemma/FunctionGemma/Qwen3, instead of
 * being hardcoded here. This keeps the APK tiny (text is a few hundred
 * KB even at thousands of entries — see ModelCatalog's KNOWLEDGE_PACK
 * comment) and lets Pranav edit/expand individual domains later just by
 * updating that one file on Hugging Face, no app rebuild required for
 * content-only changes (though the app does need a fresh
 * ensureReady()/cache-rebuild — see below — to pick up edits, which
 * happens automatically once the on-disk JSON changes).
 *
 * RETRIEVAL: cosine similarity over cached embedding vectors (see
 * ensureReady()) — brute-force over ~195 entries is still cheap; would
 * need a real vector index if this ever grows into the thousands.
 */
object KnowledgeBase {

    private const val TAG = "KnowledgeBase"
    // v3: bumped again — switched underlying embedding model from
    // EmbeddingGemma to Universal Sentence Encoder (see ModelCatalog),
    // so any v2 cache built with the old model's vectors is meaningless
    // now and must rebuild from scratch.
    private const val CACHE_FILE_NAME = "embeddings_cache_v3.json"

    // NOTE: no query/document prefixes here (there used to be — see git
    // history) — those were specific to EmbeddingGemma's asymmetric
    // retrieval format. Universal Sentence Encoder (what's actually
    // downloaded now — EmbeddingGemma turned out to be unsupported by
    // MediaPipe's TextEmbedder task entirely) is a symmetric model with
    // no such distinction; prepending that instructional text would
    // just be noise for USE.

    data class Entry(
        val id: String,
        val domain: String,
        val topic: String,
        val content: String,
    )

    /**
     * A retrieved Entry plus its cosine-similarity score against the
     * query. Exposed to callers (see ChatIntentHandler.lookupRag()) so
     * they can decide whether a match is confident enough to answer
     * near-verbatim from — instead of always asking Qwen3 to rewrite it,
     * which is where hallucination/instruction-leakage risk creeps in.
     */
    data class Match(val entry: Entry, val score: Double)

    private data class CachedEntry(val entry: Entry, val vector: FloatArray)

    @Volatile private var entries: List<Entry> = emptyList()
    @Volatile private var cache: List<CachedEntry> = emptyList()
    @Volatile private var entriesLoaded = false

    private val mutex = Mutex()

    /** True once entries are loaded AND every entry has a cached embedding vector ready for retrieve(). */
    fun isReady(): Boolean = entries.isNotEmpty() && cache.size == entries.size

    /**
     * Loads entries from disk (once) and builds/loads the embedding
     * cache (once EmbeddingEngine is ready — may need a retry on a
     * later call if EmbeddingEngine wasn't loaded yet the first time,
     * same lazy-and-safe-to-call-repeatedly pattern as the model
     * engines' own init()). No-op and cheap if already fully ready.
     * Silently leaves the knowledge base empty if the KNOWLEDGE_PACK
     * hasn't been downloaded yet — same graceful degradation as the
     * three model engines when their model file is missing.
     */
    suspend fun ensureReady(context: Context) {
        if (!entriesLoaded) {
            mutex.withLock {
                if (!entriesLoaded) {
                    withContext(Dispatchers.IO) {
                        if (ModelDownloadManager.isDownloaded(context, ModelCatalog.ModelId.KNOWLEDGE_PACK)) {
                            entries = loadEntriesFromDisk(context)
                        } else {
                            android.util.Log.w(TAG, "Knowledge pack not downloaded yet — visit the Models screen.")
                        }
                        entriesLoaded = true
                    }
                }
            }
        }
        if (entries.isNotEmpty() && cache.size != entries.size && EmbeddingEngine.isReady()) {
            mutex.withLock {
                if (cache.size != entries.size && EmbeddingEngine.isReady()) {
                    cache = withContext(Dispatchers.IO) { buildOrLoadEmbeddingCache(context, entries) }
                }
            }
        }
    }

    /**
     * Top matching entries (by cached-vector cosine similarity) for
     * `query`, above a minimum relevance bar — empty list if nothing
     * matches well enough, or if the knowledge base/EmbeddingEngine
     * isn't ready yet (never blocks/waits beyond one ensureReady() pass).
     * Returns Match (entry + score), not bare Entry, so callers can
     * gate verbatim-vs-rewrite decisions on confidence — see
     * ChatIntentHandler.lookupRag().
     */
    suspend fun retrieve(context: Context, query: String, maxResults: Int = 2, minSimilarity: Double = 0.45): List<Match> {
        ensureReady(context)
        if (!isReady()) {
            // Distinguishes "KB genuinely not ready yet" (still
            // downloading, or EmbeddingEngine hadn't finished its
            // background warm-up when this query landed) from "ready
            // but nothing matched well enough" below — previously both
            // looked identical in the breadcrumb trail as a bare
            // "no match", making a cold-start race indistinguishable
            // from a real retrieval miss without adb.
            Breadcrumb.log(context, "RAG: KB not ready (entries=${entries.size}, cache=${cache.size}, embedderReady=${EmbeddingEngine.isReady()}) for \"$query\"")
            return emptyList()
        }
        val queryVector = EmbeddingEngine.embedVector(query)
        if (queryVector == null) {
            Breadcrumb.log(context, "RAG: embedVector failed for \"$query\"")
            return emptyList()
        }
        return withContext(Dispatchers.Default) {
            val scored = cache.map { Match(it.entry, EmbeddingEngine.cosineSimilarity(queryVector, it.vector)) }
            val result = scored
                .filter { it.score >= minSimilarity }
                .sortedByDescending { it.score }
                .take(maxResults)
            if (result.isEmpty()) {
                val best = scored.maxByOrNull { it.score }
                Breadcrumb.log(
                    context,
                    "RAG: no fact above minSimilarity=$minSimilarity for \"$query\"" +
                        (best?.let { " (closest: ${it.entry.id} @ ${it.score})" } ?: " (KB empty)")
                )
            }
            result
        }
    }

    // ── Loading + caching internals ──

    private fun loadEntriesFromDisk(context: Context): List<Entry> {
        val dir = ModelDownloadManager.localKnowledgeDir(context)
        val result = mutableListOf<Entry>()
        for (kf in ModelCatalog.KNOWLEDGE_FILES) {
            // remoteFileName is "knowledge/xxx.json" (repo-relative);
            // locally it's just the basename inside localKnowledgeDir().
            val file = File(dir, File(kf.remoteFileName).name)
            if (!file.exists()) continue
            try {
                val array = JSONArray(file.readText())
                for (i in 0 until array.length()) {
                    val obj = array.getJSONObject(i)
                    result.add(
                        Entry(
                            id = obj.optString("id", "${kf.domain}_$i"),
                            domain = obj.optString("domain", kf.domain),
                            topic = obj.getString("topic"),
                            content = obj.getString("content"),
                        )
                    )
                }
            } catch (e: Exception) {
                android.util.Log.w(TAG, "Failed to parse ${file.name}: ${e.message}")
            }
        }
        return result
    }

    /**
     * Loads cached vectors from embeddings_cache.json where still valid
     * (matching entry id AND a hash of its topic text, so an edited
     * domain file on Hugging Face invalidates just the changed entries
     * on next download rather than silently serving a stale vector for
     * changed content), embeds only what's missing/changed, then
     * persists the result back to disk. This means the ~195-entry
     * embed pass only happens once per device (first time after
     * downloading the knowledge pack) instead of on every app launch.
     */
    private suspend fun buildOrLoadEmbeddingCache(context: Context, entries: List<Entry>): List<CachedEntry> {
        val dir = ModelDownloadManager.localKnowledgeDir(context)
        val cacheFile = File(dir, CACHE_FILE_NAME)
        val existing = mutableMapOf<String, Pair<Int, FloatArray>>() // id -> (topicHash, vector)

        if (cacheFile.exists()) {
            try {
                val arr = JSONArray(cacheFile.readText())
                for (i in 0 until arr.length()) {
                    val obj = arr.getJSONObject(i)
                    val id = obj.getString("id")
                    val hash = obj.getInt("hash")
                    val vecArr = obj.getJSONArray("vector")
                    val vec = FloatArray(vecArr.length()) { j -> vecArr.getDouble(j).toFloat() }
                    existing[id] = hash to vec
                }
            } catch (e: Exception) {
                android.util.Log.w(TAG, "Embedding cache unreadable, rebuilding from scratch: ${e.message}")
            }
        }

        val result = mutableListOf<CachedEntry>()
        var changed = false
        for (entry in entries) {
            val currentHash = entry.topic.hashCode()
            val cached = existing[entry.id]
            val vector = if (cached != null && cached.first == currentHash) {
                cached.second
            } else {
                changed = true
                EmbeddingEngine.embedVector(entry.topic) ?: continue
            }
            result.add(CachedEntry(entry, vector))
        }

        // Entry count mismatch (e.g. a domain file was removed/shrunk)
        // also means the on-disk cache is stale and should be rewritten.
        if (changed || existing.size != result.size) {
            try {
                val arr = JSONArray()
                for (c in result) {
                    val obj = JSONObject()
                    obj.put("id", c.entry.id)
                    obj.put("hash", c.entry.topic.hashCode())
                    val vecArr = JSONArray()
                    for (f in c.vector) vecArr.put(f.toDouble())
                    obj.put("vector", vecArr)
                    arr.put(obj)
                }
                cacheFile.writeText(arr.toString())
            } catch (e: Exception) {
                android.util.Log.w(TAG, "Failed to persist embedding cache: ${e.message}")
            }
        }
        return result
    }
}
