package com.junai.app.ml

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * KnowledgeBase — small curated RAG content store, injected into Qwen3's
 * prompt when relevant (see ChatIntentHandler.buildRagContext()).
 *
 * WHY THIS EXISTS: fine-tuning teaches Qwen3 style, not facts — a 0.6B
 * model's "knowledge" is capped by its size no matter how it's trained.
 * RAG sidesteps that: instead of the model trying to recall facts from
 * its own weights, we hand it the relevant facts directly in the prompt
 * right before it answers — Jun becomes a "teacher reading from a book"
 * rather than "a puppet reciting from memory" (Pranav's framing).
 *
 * SCOPE (deliberately starting small): only "about Jun/JunAI itself" for
 * now — this is the one domain with genuinely zero internet source
 * (Qwen3 obviously has no idea what JunAI is), so it's both the most
 * clear-cut proof that RAG is working AND immediately useful (fixes Jun
 * inventing random names, not knowing its own capabilities, etc.).
 * Medical/career/finance/etc. entries are a deliberate follow-up, not
 * included here — see the TODO list. ENTRIES below should be reviewed
 * and expanded by Pranav; what's here is a reasonable starting draft
 * based on this project's own history, not authoritative.
 *
 * RETRIEVAL: brute-force cosine similarity over every entry via
 * EmbeddingEngine (already loaded for TriggerMatcher) — fine at this
 * small scale (tens of entries). Would need a real vector index if this
 * ever grows into the thousands, but that's far off.
 */
object KnowledgeBase {

    data class Entry(val topic: String, val content: String)

    // Starting content — facts about JunAI itself. Edit/expand freely;
    // this isn't meant to be the final word, just a working draft to
    // prove the pipeline end-to-end.
    private val entries = listOf(
        Entry(
            "what Jun is",
            "Jun is a personal AI assistant app for Android, built by Pranav. " +
            "It runs fully offline — no internet or cloud servers are used for " +
            "its AI features, everything happens on the user's own phone."
        ),
        Entry(
            "Jun's on-device models",
            "Jun uses three small AI models bundled directly in the app: " +
            "EmbeddingGemma for matching similar phrases, FunctionGemma for " +
            "recognizing when the user wants an action performed (like opening " +
            "an app or calling someone), and Qwen3 for general conversation and " +
            "answering questions."
        ),
        Entry(
            "what Jun can do — actions",
            "Jun can open apps, call contacts, play or pause music, set " +
            "reminders, create notes, search the web, open settings, and tell " +
            "the time, date, or battery level — all triggered by typing or " +
            "speaking a natural request."
        ),
        Entry(
            "Jun's macro/automation feature",
            "Jun can learn a sequence of taps and actions by watching the user " +
            "demonstrate it once, then repeat that same sequence automatically " +
            "later when asked — this is called a macro."
        ),
        Entry(
            "Jun and privacy",
            "Because Jun runs its AI models on-device instead of sending data " +
            "to a server, conversations and personal information stay on the " +
            "user's phone rather than being sent anywhere else."
        ),
        Entry(
            "how Jun is built",
            "Jun is developed entirely from an Android phone, without a laptop " +
            "— code is edited on GitHub's mobile site, and the app is built " +
            "into an installable APK using GitHub Actions running in the cloud."
        ),
        Entry(
            "Jun's language support",
            "Jun is designed to understand and reply in Hinglish (a natural mix " +
            "of Hindi and English) as well as plain English, matching whichever " +
            "way the user is writing."
        ),
        Entry(
            "Jun's name",
            "The assistant's name is Jun. If asked who it is or what its name " +
            "is, it should always answer \"Jun\" — never a different name."
        )
    )

    /**
     * Top matching entries (by semantic similarity) for `query`, above a
     * minimum relevance bar — empty list if nothing matches well enough
     * or if EmbeddingEngine isn't ready yet (never blocks/waits on it).
     */
    suspend fun retrieve(query: String, maxResults: Int = 2, minSimilarity: Double = 0.45): List<Entry> {
        if (!EmbeddingEngine.isReady()) return emptyList()
        return withContext(Dispatchers.Default) {
            entries
                .mapNotNull { entry ->
                    val score = EmbeddingEngine.similarity(query, entry.topic) ?: return@mapNotNull null
                    entry to score
                }
                .filter { (_, score) -> score >= minSimilarity }
                .sortedByDescending { (_, score) -> score }
                .take(maxResults)
                .map { (entry, _) -> entry }
        }
    }
}
