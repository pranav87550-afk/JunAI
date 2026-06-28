package com.junai.app.learning

import com.junai.app.LearningRepository

/**
 * LearningEngineV2Repository — Android-aware bridge between
 * RepeatedFailureDetector and the existing LearningRepository.
 *
 * Takes an existing LearningRepository instance (doesn't create its own
 * Context dependency) — ChatIntentHandler already has one.
 */
class LearningEngineV2Repository(private val learningRepo: LearningRepository) {

    /**
     * Returns a nudge message if some question has failed repeatedly AND
     * still isn't answerable today. Returns null otherwise.
     *
     * Important correctness check: failure_log rows from OLD failures
     * aren't deleted once a question gets trained later (Phase 9/LEARN_QA
     * just adds a knowledge_items row, it doesn't clean up failure_log).
     * So before nudging, we re-check whether the question is now
     * confidently answerable — if it is, the failure history is stale
     * and we stay quiet instead of nagging about something already fixed.
     */
    suspend fun getRepeatedFailureNudge(): String? {
        val failures = learningRepo.getFailureLog().map {
            RepeatedFailureDetector.FailureSnapshot(it.question, it.timestamp)
        }
        val repeated = RepeatedFailureDetector.findMostRepeated(failures) ?: return null

        val current = learningRepo.findAnswer(repeated.question)
        if (current.answer != null && current.confidence >= 70f) return null  // already fixed

        return "Tumne \"${repeated.question}\" ${repeated.count} baar pucha hai aur main answer nahi de saka — " +
                "\"${repeated.question} = <answer>\" likh ke sikha do? \uD83D\uDE4F"
    }
}
