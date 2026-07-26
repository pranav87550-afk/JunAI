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
    // v2: bumped filename (not just content) so devices with an old
    // no-prefix cache rebuild fresh instead of reusing stale vectors —
    // the hash key below is based on raw topic text and wouldn't
    // otherwise change just because we started prefixing what gets embedded.
    private const val CACHE_FILE_NAME = "embeddings_cache_v2.json"

    // EmbeddingGemma is an asymmetric retrieval model — queries and
    // documents need different instructional prefixes prepended before
    // embedding, or similarity scores come out weak/noisy (Google's
    // documented format). We were embedding raw text with no prefix on
    // either side — likely root cause of the "cooking_012 exists but
    // doesn't get retrieved" bug.
    private const val QUERY_PREFIX = "task: search result | query: "
    private const val DOCUMENT_PREFIX = "title: none | text: "

    data class Entry(
        val id: String,
        val domain: String,
        val topic: String,
        val content: String,
    )

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
     */
    suspend fun retrieve(context: Context, query: String, maxResults: Int = 2, minSimilarity: Double = 0.45): List<Entry> {
        ensureReady(context)
        if (!isReady()) return emptyList()
        val queryVector = EmbeddingEngine.embedVector(QUERY_PREFIX + query) ?: return emptyList()
        return withContext(Dispatchers.Default) {
            cache
                .map { it.entry to EmbeddingEngine.cosineSimilarity(queryVector, it.vector) }
                .filter { (_, score) -> score >= minSimilarity }
                .sortedByDescending { (_, score) -> score }
                .take(maxResults)
                .map { (entry, _) -> entry }
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
                EmbeddingEngine.embedVector(DOCUMENT_PREFIX + entry.topic) ?: continue
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
