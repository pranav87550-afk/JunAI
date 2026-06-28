package com.junai.app.agent

import com.junai.app.agent.safety.ApprovalResult
import com.junai.app.agent.safety.RiskLevel
import com.junai.app.agent.safety.SafetyConcern
import com.junai.app.agent.safety.SafetyLayer
import com.junai.app.reasoning.ConfidenceEngine

enum class DecisionVerdict { PROCEED, PROCEED_WITH_NOTIFICATION, BLOCKED, NEEDS_CLARIFICATION }

data class Decision(
    val verdict: DecisionVerdict,
    val riskLevel: RiskLevel,
    val reason: String? = null   // populated for BLOCKED / NEEDS_CLARIFICATION — shown to the user
)

/**
 * DecisionEngine — the single checkpoint AgentEngine must call before
 * executing any AgentStep. Runs the spec's 5 checks in order; risk
 * evaluation and the approval gate are fused into one call to SafetyLayer,
 * since per spec a HIGH/CRITICAL risk level *is* what triggers approval.
 *
 *   1. Clarity check     — is the step well-formed at all?
 *   2. Confidence check  — reuse Phase 8 ConfidenceEngine
 *   3+4. Risk + Safety   — classify risk, map to a SafetyConcern if relevant
 *   5. Approval gate     — SafetyLayer.guard() handles HIGH/CRITICAL confirmation
 */
object DecisionEngine {

    /**
     * @param step the step about to be executed
     * @param agentConfidenceScore 0-100 confidence from IntentDetector's AGENT_TASK match
     */
    suspend fun evaluate(step: AgentStep, agentConfidenceScore: Int): Decision {

        // 1. Clarity check — never guess, never assume.
        if (step.description.isBlank() || step.target.isBlank()) {
            return Decision(
                verdict = DecisionVerdict.NEEDS_CLARIFICATION,
                riskLevel = RiskLevel.LOW,
                reason = "I'm not fully sure what you want me to do for: \"${step.description}\". Can you rephrase?"
            )
        }

        // 2. Confidence check — reuse Phase 8 ConfidenceEngine, same thresholds as the rest of the app.
        val normalizedConfidence = ConfidenceEngine.normalize(agentConfidenceScore.toFloat(), scaleMax = 100f)
        if (ConfidenceEngine.classify(normalizedConfidence) == ConfidenceEngine.ConfidenceLevel.LOW) {
            return Decision(
                verdict = DecisionVerdict.NEEDS_CLARIFICATION,
                riskLevel = RiskLevel.LOW,
                reason = "I'm not confident I understood this correctly. Could you clarify what you'd like me to do?"
            )
        }

        // 3+4. Risk + Safety — does this step touch a category SafetyLayer always gates?
        val risk = riskLevelFor(step)
        val concern = safetyConcernFor(step)

        if (concern != null) {
            if (SafetyLayer.isAbsolutelyProhibited(concern)) {
                return Decision(DecisionVerdict.BLOCKED, risk, SafetyLayer.describeConcern(concern))
            }
            // 5. Approval gate, fused into SafetyLayer.guard()
            return when (SafetyLayer.guard(concern, step.description, "Target: ${step.target}")) {
                ApprovalResult.APPROVED -> Decision(DecisionVerdict.PROCEED, risk)
                ApprovalResult.DENIED -> Decision(DecisionVerdict.BLOCKED, risk, "You didn't approve this step, so I skipped it.")
                ApprovalResult.TIMEOUT -> Decision(DecisionVerdict.BLOCKED, risk, "I didn't hear back from you in time, so I cancelled this step for safety.")
            }
        }

        // No SafetyConcern mapped — proceed based on risk level alone.
        return when (risk) {
            RiskLevel.LOW -> Decision(DecisionVerdict.PROCEED, risk)
            RiskLevel.MEDIUM -> Decision(DecisionVerdict.PROCEED_WITH_NOTIFICATION, risk)
            RiskLevel.HIGH, RiskLevel.CRITICAL -> {
                // Shouldn't normally happen (HIGH/CRITICAL steps should already have
                // matched a SafetyConcern above) — fail safe instead of auto-proceeding.
                when (SafetyLayer.requestApproval(
                    SafetyLayer.ConfirmationRequest(
                        actionDescription = step.description,
                        whatWillHappen = "Target: ${step.target}",
                        riskLevel = risk
                    )
                )) {
                    ApprovalResult.APPROVED -> Decision(DecisionVerdict.PROCEED, risk)
                    ApprovalResult.DENIED -> Decision(DecisionVerdict.BLOCKED, risk, "You didn't approve this step, so I skipped it.")
                    ApprovalResult.TIMEOUT -> Decision(DecisionVerdict.BLOCKED, risk, "I didn't hear back from you in time, so I cancelled this step for safety.")
                }
            }
        }
    }

    /** Risk classification by StepType, per the spec's 4-tier risk table. */
    private fun riskLevelFor(step: AgentStep): RiskLevel = when (step.type) {
        StepType.READ, StepType.SEARCH, StepType.OPEN, StepType.COMPARE, StepType.RESPOND -> RiskLevel.LOW
        StepType.TYPE, StepType.TAP, StepType.SYSTEM_ACTION -> RiskLevel.MEDIUM
    }

    /**
     * Maps a step's description/target text to a SafetyConcern when it
     * touches a category SafetyLayer always gates. Null means "ordinary
     * step, no SafetyLayer involvement needed at all."
     */
    private fun safetyConcernFor(step: AgentStep): SafetyConcern? {
        val text = "${step.description} ${step.target}".lowercase()
        return when {
            containsAny(text, "password", "otp", "pin", "cvv") -> SafetyConcern.READ_CREDENTIAL
            containsAny(text, "pay", "purchase", "buy", "payment", "order") -> SafetyConcern.PAYMENT
            containsAny(text, "delete", "uninstall") -> SafetyConcern.DELETE_DATA
            containsAny(text, "send", "bhejo") -> SafetyConcern.SEND_MESSAGE
            containsAny(text, "call", "dial") -> SafetyConcern.MAKE_CALL
            containsAny(text, "share") -> SafetyConcern.SHARE_PRIVATE_INFO
            else -> null
        }
    }

    private fun containsAny(text: String, vararg words: String): Boolean =
        words.any { Regex("\\b${Regex.escape(it)}\\b").containsMatchIn(text) }
}
