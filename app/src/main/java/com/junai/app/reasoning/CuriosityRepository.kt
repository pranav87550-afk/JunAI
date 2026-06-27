package com.junai.app.reasoning

import com.junai.app.LearningRepository

/**
 * CuriosityRepository — Tracks Jun's "I asked, waiting for an answer" state
 * for one pending question, and saves the answer into the EXISTING
 * knowledge base (learningRepo.trainKnowledge) once it arrives — same
 * storage LEARN_QA already uses, no new table.
 *
 * Pending state is in-memory only, scoped to this repository's lifetime
 * (one ChatIntentHandler/session) — if the app is killed mid-question,
 * the pending question is simply forgotten. That's the right behavior;
 * Jun shouldn't resurrect a stale question days later.
 *
 * Decision of WHETHER the next message is actually an answer (vs the user
 * just moving on to a real command) is left to ChatIntentHandler, which
 * already has IntentDetector's classification to use as the signal —
 * this class stays "dumb": if asked to resolve, it always consumes.
 */
class CuriosityRepository(private val learningRepo: LearningRepository) {

    private var pendingQuestion: String? = null

    fun hasPendingQuestion(): Boolean = pendingQuestion != null

    /** Call when curiosity decides to ask — remembers what it's curious about. */
    fun askAbout(originalQuestion: String): String {
        pendingQuestion = originalQuestion
        return CuriosityEngine.generateFollowUp(originalQuestion)
    }

    /**
     * Call on the next message, when ChatIntentHandler has decided it's
     * genuinely an answer. Stores it and clears pending state.
     */
    suspend fun resolveWithAnswer(userAnswer: String): String {
        val question = pendingQuestion ?: return "Hmm, kuch confusion ho gaya \uD83E\uDD14"
        pendingQuestion = null

        if (userAnswer.isBlank() || userAnswer.length > 300) {
            return "Thik hai, koi baat nahi — phir kabhi bata dena! \uD83D\uDC4D"
        }

        learningRepo.trainKnowledge(question, userAnswer.trim())
        return "Theek hai \u2705, yaad kar liya maine! Ab agli baar ye pucho to bata dunga."
    }

    /** Call when the user clearly moved on (matched a real intent) instead of answering. */
    fun cancelPending() {
        pendingQuestion = null
    }
}
