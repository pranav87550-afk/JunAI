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
                activity.runOnUiThread {
                    hideTyping(typingIndicator, dot1, dot2, dot3)
                    chatAdapter.addMessage(ChatMessage("No internet. Can't search right now.", isUser = false))
                    recyclerView.scrollToPosition(messages.size - 1)
                    onSaveChat()
                }
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
                            else -> "I couldn't find an answer for \"$query\". Try rephrasing!"
                        }
                        storeAndRespond(query, finalAnswer, recyclerView, typingIndicator, dot1, dot2, dot3)
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
