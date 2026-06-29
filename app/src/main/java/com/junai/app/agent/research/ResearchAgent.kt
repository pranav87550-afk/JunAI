package com.junai.app.agent.research

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.junai.app.LearningRepository
import com.junai.app.agent.screen.ScreenContextEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder

data class SourceResult(val sourceName: String, val text: String?, val url: String? = null)

data class ResearchResult(
    val answer: String,
    val sources: List<String>,
    val confident: Boolean,
    val caveat: String? = null
)

/**
 * ResearchAgent — finds information from the internet without a paid API,
 * for cases where IntentDetector classifies the goal as RESEARCH.
 *
 * IMPORTANT DESIGN NOTE on "use existing WebSearchHelper": the existing
 * WebSearchHelper class is tightly coupled to an Activity + ChatAdapter +
 * RecyclerView and produces UI side effects directly (typing indicator,
 * chat bubbles, TTS) — it has no headless "give me a string back" API,
 * so it can't be called from a background agent flow that may run without
 * the chat screen open. Rather than rewriting it (against the rules) or
 * forcing fake UI dependencies into it, this file re-implements the SAME
 * two data sources (Wikipedia summary API + DuckDuckGo Instant Answer
 * API) with the same fallback order, as plain suspend functions. The
 * approach is identical; only the delivery mechanism differs.
 *
 * Knowledge caching/saving genuinely reuses the existing LearningRepository
 * (findAnswer / trainKnowledge) — no duplication there.
 */
object ResearchAgent {

    private val httpClient = OkHttpClient()

    /**
     * @param minSourcesForConfidence per spec: need at least 2 independent
     *   sources for a *confident* answer on a fresh query. A cache hit from
     *   LearningRepository counts as already-verified prior research, so it
     *   short-circuits this requirement — it isn't a fresh, unverified claim.
     */
    suspend fun research(
        query: String,
        context: Context,
        learningRepository: LearningRepository,
        minSourcesForConfidence: Int = 2
    ): ResearchResult {
        // 1. Check existing knowledge first — genuinely reuses LearningRepository.
        val cached = learningRepository.findAnswer(query)
        if (!cached.answer.isNullOrBlank() && cached.confidence >= 90f) {
            return ResearchResult(
                answer = cached.answer,
                sources = listOf("Jun's memory (previously researched)"),
                confident = true
            )
        }

        // 2. Fetch from the same two sources WebSearchHelper uses.
        val wiki = fetchWikipedia(query)
        val ddg = fetchDuckDuckGo(query)
        var results = listOfNotNull(wiki, ddg).filter { !it.text.isNullOrBlank() }

        // 3. If both came back empty, fall back to opening a browser search
        // and reading the results page directly via ScreenContextEngine.
        if (results.isEmpty()) {
            fetchViaBrowserFallback(context, query)?.let { results = listOf(it) }
        }

        if (results.isEmpty()) {
            return ResearchResult(
                answer = "I couldn't find anything reliable about \"$query\" right now.",
                sources = emptyList(),
                confident = false,
                caveat = "No source returned a usable result — could be a network issue or a very obscure topic. I'm not going to guess."
            )
        }

        val confident = results.size >= minSourcesForConfidence
        val deduped = dedupe(results)
        val contradictionNote = detectNumericContradiction(deduped)
        val answer = deduped.joinToString(" ") { it.text!! }

        // 4. Save useful knowledge back — genuinely reuses LearningRepository.
        if (confident) {
            learningRepository.trainKnowledge(query, answer, category = "Research")
        }

        return ResearchResult(
            answer = answer,
            sources = results.map { it.sourceName },
            confident = confident,
            caveat = contradictionNote ?: if (!confident) {
                "Only one source was available for this — worth double-checking elsewhere."
            } else null
        )
    }

    // ── Sources ───────────────────────────────────────────────────

    private suspend fun fetchWikipedia(query: String): SourceResult? = withContext(Dispatchers.IO) {
        try {
            val encoded = URLEncoder.encode(query, "UTF-8")
            val url = "https://en.wikipedia.org/api/rest_v1/page/summary/$encoded"
            val response = httpClient.newCall(Request.Builder().url(url).build()).execute()
            val body = response.body?.string() ?: return@withContext null
            val json = JSONObject(body)
            val extract = json.optString("extract", "")
            if (extract.length <= 20) return@withContext null

            val short = extract.split(". ").take(4).joinToString(". ").trim()
            val finalText = if (!short.endsWith(".")) "$short." else short
            val pageUrl = "https://en.wikipedia.org/wiki/${query.replace(" ", "_")}"
            SourceResult("Wikipedia", finalText, pageUrl)
        } catch (e: Exception) {
            null
        }
    }

    private suspend fun fetchDuckDuckGo(query: String): SourceResult? = withContext(Dispatchers.IO) {
        try {
            val encoded = URLEncoder.encode(query, "UTF-8")
            val url = "https://api.duckduckgo.com/?q=$encoded&format=json&no_html=1&skip_disambig=1"
            val response = httpClient.newCall(Request.Builder().url(url).build()).execute()
            val body = response.body?.string() ?: return@withContext null
            val json = JSONObject(body)

            val answer = json.optString("Answer", "")
            val abstractText = json.optString("AbstractText", "")
            val definition = json.optString("Definition", "")
            val related = try {
                json.optJSONArray("RelatedTopics")?.optJSONObject(0)?.optString("Text", "") ?: ""
            } catch (e: Exception) { "" }

            val text = when {
                answer.isNotEmpty() -> answer
                abstractText.isNotEmpty() -> abstractText.split(". ").take(4).joinToString(". ").trim()
                definition.isNotEmpty() -> definition
                related.isNotEmpty() -> related.split(". ").take(2).joinToString(". ").trim()
                else -> null
            }
            val sourceUrl = json.optString("AbstractURL", "").ifBlank { null }
            text?.let { SourceResult("DuckDuckGo", it, sourceUrl) }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Last-resort fallback when neither API returns anything: open a browser
     * search and read the page text via ScreenContextEngine. Uses a direct
     * search-URL Intent rather than ActionEngine.openApp(), since there's no
     * "open this URL" primitive there yet and adding one just for this rare
     * fallback path isn't worth re-touching that file.
     */
    private suspend fun fetchViaBrowserFallback(context: Context, query: String): SourceResult? {
        return try {
            val searchUrl = "https://www.google.com/search?q=${Uri.encode(query)}"
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(searchUrl)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            delay(2500L)

            val texts = ScreenContextEngine.getCurrentContext().visibleTexts
            val extracted = texts.filter { it.length > 40 }.take(3).joinToString(" ").trim()
            if (extracted.isBlank()) null else SourceResult("Browser search", extracted, searchUrl)
        } catch (e: Exception) {
            null
        }
    }

    // ── Comparison / synthesis ──────────────────────────────────────

    /** Drops near-duplicate text across sources, keeping the longer version of each. */
    private fun dedupe(results: List<SourceResult>): List<SourceResult> {
        val kept = mutableListOf<SourceResult>()
        for (result in results.sortedByDescending { it.text?.length ?: 0 }) {
            val text = result.text ?: continue
            val isDuplicate = kept.any { wordOverlapRatio(text, it.text ?: "") > 0.6 }
            if (!isDuplicate) kept.add(result)
        }
        return kept
    }

    private fun wordOverlapRatio(a: String, b: String): Double {
        val wordsA = a.lowercase().split(Regex("\\W+")).filter { it.length > 3 }.toSet()
        val wordsB = b.lowercase().split(Regex("\\W+")).filter { it.length > 3 }.toSet()
        if (wordsA.isEmpty() || wordsB.isEmpty()) return 0.0
        return wordsA.intersect(wordsB).size.toDouble() / minOf(wordsA.size, wordsB.size)
    }

    /**
     * Lightweight contradiction heuristic: if sources mention completely
     * disjoint sets of numbers, flag it. This is NOT real fact-checking —
     * just a cheap signal worth surfacing as a caveat rather than silence.
     */
    private fun detectNumericContradiction(results: List<SourceResult>): String? {
        val numberSets = results.mapNotNull { it.text }
            .map { text -> Regex("\\d+(\\.\\d+)?").findAll(text).map { it.value }.toSet() }
            .filter { it.isNotEmpty() }
        if (numberSets.size < 2) return null
        val anyOverlap = numberSets.zipWithNext().any { (a, b) -> a.intersect(b).isNotEmpty() }
        return if (!anyOverlap) "Sources mention different numbers for this — worth double-checking." else null
    }
}
