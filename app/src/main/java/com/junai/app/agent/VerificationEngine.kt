package com.junai.app.agent

import com.junai.app.agent.screen.ScreenContextEngine
import kotlinx.coroutines.delay

enum class VerificationResult { SUCCESS, FAILED }

data class VerificationOutcome(
    val result: VerificationResult,
    val attemptsUsed: Int,
    val reason: String? = null   // populated only on FAILED — always a clear, honest explanation
)

/**
 * VerificationEngine — after ActionEngine executes a step, this confirms it
 * actually worked by polling ScreenContextEngine for expected on-screen
 * evidence. Retries up to 2 times (per spec) before giving up.
 *
 * Per spec: "Jun must never silently fail." A FAILED outcome always carries
 * a human-readable [VerificationOutcome.reason] for AgentEngine to relay to
 * the user — there is no code path that swallows a failure quietly.
 */
object VerificationEngine {

    private const val MAX_RETRIES = 2
    private const val DEFAULT_TIMEOUT_MS = 5000L

    /**
     * Simple text-based verification — most steps just need to confirm some
     * expected word/phrase shows up on screen after the action.
     *
     * @param onRetry invoked before each retry attempt, e.g. so ActionEngine
     *   can re-run the action. If null, verification just waits again without re-acting.
     */
    suspend fun verify(
        expectedText: String,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS,
        maxRetries: Int = MAX_RETRIES,
        onRetry: (suspend () -> Unit)? = null
    ): VerificationOutcome {
        var attempt = 0
        while (attempt <= maxRetries) {
            val found = ScreenContextEngine.waitForText(expectedText, timeoutMs)
            if (found) {
                return VerificationOutcome(VerificationResult.SUCCESS, attemptsUsed = attempt + 1)
            }
            attempt++
            if (attempt <= maxRetries) onRetry?.invoke()
        }
        return VerificationOutcome(
            result = VerificationResult.FAILED,
            attemptsUsed = attempt,
            reason = "Expected to see \"$expectedText\" on screen after $attempt attempt(s), but it never " +
                "appeared. The action may not have worked, or the app's screen may have changed."
        )
    }

    /**
     * Custom-condition variant for verifications that aren't a simple text
     * match — e.g. "currentApp switched to whatsapp" or "an input field is
     * now focused". Polls [condition] against the live ScreenContext.
     */
    suspend fun verifyCondition(
        timeoutMs: Long = DEFAULT_TIMEOUT_MS,
        maxRetries: Int = MAX_RETRIES,
        onRetry: (suspend () -> Unit)? = null,
        condition: (ScreenContextEngine.ScreenContext) -> Boolean
    ): VerificationOutcome {
        var attempt = 0
        while (attempt <= maxRetries) {
            if (pollUntil(timeoutMs, condition)) {
                return VerificationOutcome(VerificationResult.SUCCESS, attemptsUsed = attempt + 1)
            }
            attempt++
            if (attempt <= maxRetries) onRetry?.invoke()
        }
        return VerificationOutcome(
            result = VerificationResult.FAILED,
            attemptsUsed = attempt,
            reason = "The expected screen condition wasn't met after $attempt attempt(s)."
        )
    }

    private suspend fun pollUntil(
        timeoutMs: Long,
        condition: (ScreenContextEngine.ScreenContext) -> Boolean
    ): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition(ScreenContextEngine.getCurrentContext())) return true
            delay(250L)
        }
        return false
    }
}
