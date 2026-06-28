package com.junai.app.agent.screen

import android.text.InputType
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * ScreenContextEngine — single source of truth for "what is currently on
 * screen", as seen by Jun's AccessibilityService (built in a later file).
 *
 * IMPORTANT DESIGN NOTE: this engine deliberately stores only plain text /
 * String snapshots, never live android.view.accessibility.AccessibilityNodeInfo
 * references. AccessibilityNodeInfo objects are short-lived and get recycled
 * by the framework — holding onto one after its originating event has passed
 * throws IllegalStateException ("this node is no longer available"). Anything
 * that needs to actually tap/type (ActionEngine, built later) must query a
 * fresh node from the live AccessibilityService at the moment of acting, not
 * from a snapshot held here.
 *
 * The future JunAccessibilityService is responsible for calling
 * [updateContext] on every relevant AccessibilityEvent, and for filtering out
 * sensitive fields BEFORE calling this engine — [isSensitiveInputType] and
 * [looksLikeSensitiveLabel] are provided here as the shared filtering logic
 * so that responsibility lives in one place.
 */
object ScreenContextEngine {

    data class ClickableElement(
        val text: String,
        val viewId: String? = null,
        val className: String? = null
    )

    data class InputField(
        val viewId: String? = null,
        val hint: String? = null
        // Note: no `value` field by design — input field contents are never
        // captured here, sensitive or not, to keep this snapshot lightweight
        // and avoid accidentally retaining typed text longer than needed.
    )

    data class ScreenContext(
        val currentApp: String = "",
        val visibleTexts: List<String> = emptyList(),
        val clickableElements: List<ClickableElement> = emptyList(),
        val inputFields: List<InputField> = emptyList(),
        val scrollableAreas: List<String> = emptyList(),
        val timestamp: Long = System.currentTimeMillis()
    )

    private val _currentContext = MutableStateFlow(ScreenContext())

    /** Latest snapshot, read-only for consumers. */
    val currentContext: StateFlow<ScreenContext> = _currentContext

    /** Called by JunAccessibilityService whenever the screen changes. */
    fun updateContext(
        currentApp: String,
        visibleTexts: List<String>,
        clickableElements: List<ClickableElement> = emptyList(),
        inputFields: List<InputField> = emptyList(),
        scrollableAreas: List<String> = emptyList()
    ) {
        _currentContext.value = ScreenContext(
            currentApp = currentApp,
            visibleTexts = visibleTexts,
            clickableElements = clickableElements,
            inputFields = inputFields,
            scrollableAreas = scrollableAreas,
            timestamp = System.currentTimeMillis()
        )
    }

    fun getCurrentContext(): ScreenContext = _currentContext.value

    /**
     * Polls the current snapshot until [expectedText] appears somewhere in
     * visibleTexts, or [timeoutMs] elapses. Used by VerificationEngine and
     * ActionEngine's waitForScreen() to confirm an action took effect.
     */
    suspend fun waitForText(expectedText: String, timeoutMs: Long = 5000L, pollIntervalMs: Long = 250L): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        val needle = expectedText.lowercase().trim()
        while (System.currentTimeMillis() < deadline) {
            val found = _currentContext.value.visibleTexts.any { it.lowercase().contains(needle) }
            if (found) return true
            delay(pollIntervalMs)
        }
        return false
    }

    /** True if currentApp matches the given package name (case-insensitive contains check). */
    fun isAppInForeground(packageNameFragment: String): Boolean =
        _currentContext.value.currentApp.lowercase().contains(packageNameFragment.lowercase())

    // ─────────────────────────────────────────────────────────────
    // SENSITIVE FIELD FILTERING — shared logic for whoever populates
    // the snapshot (JunAccessibilityService, built in a later file).
    // ScreenContextEngine itself never stores field values, but a
    // future service still needs this to decide what to skip entirely.
    // ─────────────────────────────────────────────────────────────

    /** True if the Android InputType flags mark this field as a password. */
    fun isSensitiveInputType(inputType: Int): Boolean {
        val variation = inputType and InputType.TYPE_MASK_VARIATION
        val isPasswordVariation = variation == InputType.TYPE_TEXT_VARIATION_PASSWORD ||
            variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD ||
            variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD
        val isNumericPassword = (inputType and InputType.TYPE_MASK_CLASS) == InputType.TYPE_CLASS_NUMBER &&
            (inputType and InputType.TYPE_NUMBER_VARIATION_PASSWORD) == InputType.TYPE_NUMBER_VARIATION_PASSWORD
        return isPasswordVariation || isNumericPassword
    }

    /** Heuristic fallback for fields the OS doesn't flag but the label clearly identifies as sensitive. */
    fun looksLikeSensitiveLabel(label: String?): Boolean {
        if (label.isNullOrBlank()) return false
        val lower = label.lowercase()
        val sensitiveWords = listOf("password", "passcode", "otp", "pin", "cvv", "card number", "ifsc")
        return sensitiveWords.any { lower.contains(it) }
    }
}
