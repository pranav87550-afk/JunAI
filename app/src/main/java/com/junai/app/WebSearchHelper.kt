package com.junai.app

import android.app.Activity
import android.view.View
import android.widget.LinearLayout
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Handles Wikipedia + DuckDuckGo web search and caches results via LearningRepository.
 * All UI callbacks are posted back on the main thread via activity.runOnUiThread.
 */
class WebSearchHelper(
    private val activity: Activity,
    private val chatAdapter: ChatAdapter,
    private val messages: MutableList<ChatMessage>,
    private val learningRepo: LearningRepository,
    private val onSaveChat: () -> Unit,
    private val onSpeak: (String) -> Unit
) {
    // Managed scope — cancelled from MainActivity.onDestroy() via cancel()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    fun cancel() { scope.cancel() }
    fun search(query: String, recyclerView: RecyclerView) {
        val typingIndicator = activity.findViewById<LinearLayout>(R.id.typingIndicator)
        val dot1 = activity.findViewById<View>(R.id.dot1)
        val dot2 = activity.findViewById<View>(R.id.dot2)
        val dot3 = activity.findViewById<View>(R.id.dot3)

        typingIndicator.visibility = View.VISIBLE
        animateDot(dot1, 0)
        animateDot(dot2, 150)
        animateDot(dot3, 300)

        scope.launch {
            val storedAnswer = AppDatabase.getInstance(activity)
                .knowledgeDao()
                .getAnswer(query.lowercase().trim())

            if (storedAnswer != null) {
                activity.runOnUiThread {
                    hideTyping(typingIndicator, dot1, dot2, dot3)
                    chatAdapter.addMessage(ChatMessage(storedAnswer, isUser = false))
                    recyclerView.scrollToPosition(messages.size - 1)
                    onSaveChat()
                    onSpeak(storedAnswer)
                }
                return@launch
            }

            // Not cached — try Wikipedia first
            activity.runOnUiThread {
                val client = okhttp3.OkHttpClient()
                val wikiUrl = "https://en.wikipedia.org/api/rest_v1/page/summary/${
                    java.net.URLEncoder.encode(query, "UTF-8")
                }"
                val wikiRequest = okhttp3.Request.Builder().url(wikiUrl).build()

                client.newCall(wikiRequest).enqueue(object : okhttp3.Callback {
                    override fun onFailure(call: okhttp3.Call, e: java.io.IOException) {
                        fetchFromDuckDuckGo(query, recyclerView, client, typingIndicator, dot1, dot2, dot3)
                    }
                    override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                        val body = response.body?.string()
                        activity.runOnUiThread {
                            try {
                                val json = org.json.JSONObject(body ?: "")
                                val extract = json.optString("extract", "")
                                if (extract.isNotEmpty() && extract.length > 20) {
                                    val sentences = extract.split(". ")
                                    val short = sentences.take(3).joinToString(". ").trim()
                                    val finalAnswer = if (!short.endsWith(".")) "$short." else short
                                    storeAndRespond(query, finalAnswer, recyclerView, typingIndicator, dot1, dot2, dot3)
                                } else {
                                    fetchFromDuckDuckGo(query, recyclerView, client, typingIndicator, dot1, dot2, dot3)
                                }
                            } catch (e: Exception) {
                                fetchFromDuckDuckGo(query, recyclerView, client, typingIndicator, dot1, dot2, dot3)
                            }
                        }
                    }
                })
            }
        }
    }

    /**
     * BUGFIX: previously, whenever the DuckDuckGo API responded but had
     * nothing useful (very common — its Instant Answer API doesn't know
     * about most things, e.g. movie queries), OR when there was no
     * internet at all, this class showed a dead-end message ("I
     * couldn't find an answer... Try rephrasing!" / "No internet. Can't
     * search right now.") and — worse — storeAndRespond() CACHED that
     * failure text via learningRepo.trainKnowledge(), so the exact same
     * wrong "answer" kept coming back for that query forever, and even
     * showed up as a false "I think you mean:" MEDIUM-confidence fuzzy
     * match for similar-but-not-identical rephrasings. JunAI's whole
     * model stack (Qwen3 specifically) exists to answer exactly these
     * general-knowledge questions without needing the internet, so this
     * now tries Qwen3 instead of dead-ending — and deliberately does
     * NOT cache Qwen3's answer via trainKnowledge(), since an LLM
     * answer isn't necessarily correct forever the way an intentionally
     * user-taught fact is, and caching it would freeze that answer for
     * every future identical query the same way the original bug did.
     */
    private fun fallbackToQwen3OrGiveUp(
        query: String,
        recyclerView: RecyclerView,
        typingIndicator: View,
        dot1: View, dot2: View, dot3: View
    ) {
        scope.launch {
            if (!com.junai.app.ml.GGUFChatEngine.isReady()) {
                com.junai.app.ml.GGUFChatEngine.init(activity)
            }
            val response = com.junai.app.ml.GGUFChatEngine.tryChat(query)
            activity.runOnUiThread {
                hideTyping(typingIndicator, dot1, dot2, dot3)
                val answer = response?.answer?.takeIf { it.isNotBlank() }
                    ?: "I couldn't find an answer for \"$query\". Try rephrasing!"
                chatAdapter.addMessage(ChatMessage(answer, isUser = false))
                recyclerView.scrollToPosition(messages.size - 1)
                onSaveChat()
                // Not auto-spoken when it's a genuine Qwen3 answer — same
                // manual-trigger-only rule as everywhere else Qwen3
                // responds (see ChatIntentHandler). The true give-up
                // message isn't a Qwen3 answer, so that case is fine to
                // speak as before.
                if (response?.answer.isNullOrBlank()) {
                    onSpeak(answer)
                }
            }
        }
    }

    private fun fetchFromDuckDuckGo(
        query: String,
        recyclerView: RecyclerView,
        client: okhttp3.OkHttpClient,
        typingIndicator: View,
        dot1: View, dot2: View, dot3: View
    ) {
        val url = "https://api.duckduckgo.com/?q=${
            java.net.URLEncoder.encode(query, "UTF-8")
        }&format=json&no_html=1&skip_disambig=1"
        val request = okhttp3.Request.Builder().url(url).build()

        client.newCall(request).enqueue(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, e: java.io.IOException) {
                fallbackToQwen3OrGiveUp(query, recyclerView, typingIndicator, dot1, dot2, dot3)
            }
            override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                val body = response.body?.string()
                activity.runOnUiThread {
                    try {
                        val json = org.json.JSONObject(body ?: "")
                        val abstractText = json.optString("AbstractText", "")
                        val answer = json.optString("Answer", "")
                        val definition = json.optString("Definition", "")
                        val related = try {
                            json.optJSONArray("RelatedTopics")
                                ?.optJSONObject(0)?.optString("Text", "") ?: ""
                        } catch (e: Exception) { "" }

                        val finalAnswer = when {
                            answer.isNotEmpty()      -> answer
                            abstractText.isNotEmpty() -> abstractText.split(". ").take(3).joinToString(". ").trim()
                            definition.isNotEmpty()  -> definition
                            related.isNotEmpty()     -> related.split(". ").take(2).joinToString(". ").trim()
                            else -> null
                        }
                        if (finalAnswer != null) {
                            storeAndRespond(query, finalAnswer, recyclerView, typingIndicator, dot1, dot2, dot3)
                        } else {
                            fallbackToQwen3OrGiveUp(query, recyclerView, typingIndicator, dot1, dot2, dot3)
                        }
                    } catch (e: Exception) {
                        hideTyping(typingIndicator, dot1, dot2, dot3)
                        chatAdapter.addMessage(ChatMessage("Search failed. Try again!", isUser = false))
                        recyclerView.scrollToPosition(messages.size - 1)
                        onSaveChat()
                    }
                }
            }
        })
    }

    private fun storeAndRespond(
        query: String,
        answer: String,
        recyclerView: RecyclerView,
        typingIndicator: View,
        dot1: View, dot2: View, dot3: View
    ) {
        scope.launch {
            learningRepo.trainKnowledge(query, answer, "Search")
        }
        hideTyping(typingIndicator, dot1, dot2, dot3)
        chatAdapter.addMessage(ChatMessage(answer, isUser = false))
        recyclerView.scrollToPosition(messages.size - 1)
        onSaveChat()
        onSpeak(answer)
    }

    private fun hideTyping(indicator: View, dot1: View, dot2: View, dot3: View) {
        indicator.visibility = View.GONE
        dot1.clearAnimation()
        dot2.clearAnimation()
        dot3.clearAnimation()
    }

    private fun animateDot(dot: View, delay: Long) {
        android.animation.ObjectAnimator.ofFloat(dot, "alpha", 0.2f, 1f).apply {
            duration = 400
            repeatMode = android.animation.ObjectAnimator.REVERSE
            repeatCount = android.animation.ObjectAnimator.INFINITE
            startDelay = delay
            start()
        }
    }
}
