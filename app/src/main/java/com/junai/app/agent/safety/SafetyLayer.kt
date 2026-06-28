package com.junai.app.agent.safety

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID

/**
 * Risk levels used across the agent system (DecisionEngine assigns these,
 * SafetyLayer enforces what happens for each).
 *   LOW       — reading, searching, opening apps → proceed silently
 *   MEDIUM    — typing, navigating, filling forms → proceed, but notify user
 *   HIGH      — sending messages, making calls, sharing info → must confirm
 *   CRITICAL  — payments, purchases, deleting data → must confirm, extra clear warning
 */
enum class RiskLevel { LOW, MEDIUM, HIGH, CRITICAL }

enum class ApprovalResult { APPROVED, DENIED, TIMEOUT }

/**
 * Named categories from the "Jun must NEVER automatically perform" list.
 * Every one of these (except READ_CREDENTIAL) requires explicit user
 * confirmation before proceeding — none of them are ever silently allowed,
 * regardless of how confident DecisionEngine or ConfidenceEngine is.
 */
enum class SafetyConcern {
    SEND_MESSAGE,         // WhatsApp / SMS / Email — must show content before sending
    MAKE_CALL,            // must ask before dialing
    PAYMENT,              // any purchase or payment — never automatic
    DELETE_DATA,          // deleting files, contacts, messages, notes
    SHARE_PRIVATE_INFO,   // sharing personal/private info with anyone
    READ_CREDENTIAL       // passwords / PINs / OTPs — absolutely prohibited, not even askable
}

/**
 * SafetyLayer — called by DecisionEngine before every HIGH/CRITICAL action.
 * No other agent module (ActionEngine, ToolOrchestratorV2, ResearchAgent)
 * is allowed to bypass this and act directly. This file only manages the
 * approval lifecycle (request → wait → timeout/respond); the actual dialog
 * or bubble UI that shows the confirmation lives elsewhere and talks to
 * this object via [activeRequest] (to display) and [respond] (to answer).
 */
object SafetyLayer {

    data class ConfirmationRequest(
        val id: String = UUID.randomUUID().toString(),
        val actionDescription: String,   // e.g. "Send WhatsApp message to Mom"
        val whatWillHappen: String,      // e.g. "Mom will receive: 'Running late, be there by 7'"
        val riskLevel: RiskLevel
    )

    private val pendingRequests = mutableMapOf<String, CompletableDeferred<Boolean>>()

    private val _activeRequest = MutableStateFlow<ConfirmationRequest?>(null)
    /** Observed by whichever UI (bubble/dialog) is responsible for showing the prompt. */
    val activeRequest: StateFlow<ConfirmationRequest?> = _activeRequest

    /**
     * Suspends until the user responds via [respond], or [timeoutMs] elapses
     * (default 30s, per spec). LOW/MEDIUM risk should never reach here — see
     * [guard] for the safe entry point that handles that routing for you.
     */
    suspend fun requestApproval(request: ConfirmationRequest, timeoutMs: Long = 30_000L): ApprovalResult {
        if (request.riskLevel == RiskLevel.LOW) return ApprovalResult.APPROVED

        val deferred = CompletableDeferred<Boolean>()
        pendingRequests[request.id] = deferred
        _activeRequest.value = request

        val result = withTimeoutOrNull(timeoutMs) { deferred.await() }

        pendingRequests.remove(request.id)
        if (_activeRequest.value?.id == request.id) _activeRequest.value = null

        return when (result) {
            true -> ApprovalResult.APPROVED
            false -> ApprovalResult.DENIED
            null -> ApprovalResult.TIMEOUT
        }
    }

    /** Called by the confirmation UI when the user taps Yes or No. */
    fun respond(requestId: String, approved: Boolean) {
        pendingRequests[requestId]?.complete(approved)
    }

    /** True only for READ_CREDENTIAL — no confirmation flow can ever make this OK. */
    fun isAbsolutelyProhibited(concern: SafetyConcern): Boolean =
        concern == SafetyConcern.READ_CREDENTIAL

    /** Risk level mapped to each named concern. Null means "not a confirmable risk — just don't do it". */
    fun riskLevelFor(concern: SafetyConcern): RiskLevel? = when (concern) {
        SafetyConcern.SEND_MESSAGE, SafetyConcern.MAKE_CALL, SafetyConcern.SHARE_PRIVATE_INFO -> RiskLevel.HIGH
        SafetyConcern.PAYMENT, SafetyConcern.DELETE_DATA -> RiskLevel.CRITICAL
        SafetyConcern.READ_CREDENTIAL -> null
    }

    /**
     * Convenience one-call entry point for ActionEngine/DecisionEngine:
     * classifies the concern and either denies outright (READ_CREDENTIAL)
     * or runs the full confirmation flow for its mapped risk level.
     */
    suspend fun guard(
        concern: SafetyConcern,
        actionDescription: String,
        whatWillHappen: String,
        timeoutMs: Long = 30_000L
    ): ApprovalResult {
        if (isAbsolutelyProhibited(concern)) return ApprovalResult.DENIED
        val risk = riskLevelFor(concern) ?: RiskLevel.HIGH
        return requestApproval(
            ConfirmationRequest(actionDescription = actionDescription, whatWillHappen = whatWillHappen, riskLevel = risk),
            timeoutMs
        )
    }

    /** Human-readable reason, shown in the Transparent Action Summary when something is blocked. */
    fun describeConcern(concern: SafetyConcern): String = when (concern) {
        SafetyConcern.SEND_MESSAGE -> "Jun always shows message content before sending — never sends silently."
        SafetyConcern.MAKE_CALL -> "Jun always asks before making a call."
        SafetyConcern.PAYMENT -> "Jun never makes payments or purchases automatically."
        SafetyConcern.DELETE_DATA -> "Jun never deletes files, contacts, messages, or notes without confirmation."
        SafetyConcern.SHARE_PRIVATE_INFO -> "Jun never shares personal information without asking first."
        SafetyConcern.READ_CREDENTIAL -> "Jun never reads or stores passwords, PINs, or OTPs — no exceptions."
    }
}
