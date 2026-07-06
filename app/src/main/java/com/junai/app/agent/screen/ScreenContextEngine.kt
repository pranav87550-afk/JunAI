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
     * Cheap update used when a full re-scan is being throttled (see
     * JunAccessibilityService's debounce) — keeps isAppInForeground()
     * accurate between full snapshots without paying for a tree-walk.
     * Leaves visibleTexts/clickables/inputFields as they were from the
     * last full snapshot (slightly stale, but foreground detection is
     * what actually needs to stay real-time, not the element lists).
     */
    fun updateCurrentAppOnly(currentApp: String) {
        _currentContext.value = _currentContext.value.copy(currentApp = currentApp)
    }

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

    /**
     * BUGFIX (multi-device): context-aware overload — the ONLY one that
     * understands com.junai.app.agent.action.RecordingEngine.HOME_SCREEN_SENTINEL.
     * A macro step whose packageName is that sentinel isn't tied to any
     * literal launcher string; this resolves THIS device's actual current
     * launcher fresh (via MacroReplayEngine.getDefaultLauncherPackage(),
     * same PackageManager lookup navigateToApp() already uses) and checks
     * foreground against that instead. Falls through to the plain
     * substring check above for every other packageName, exactly as
     * before — this only changes behavior for the sentinel case.
     */
    fun isAppInForeground(context: android.content.Context, packageNameFragment: String): Boolean {
        if (packageNameFragment == com.junai.app.agent.action.RecordingEngine.HOME_SCREEN_SENTINEL) {
            val devicesLauncher = com.junai.app.agent.action.MacroReplayEngine.getDefaultLauncherPackage(context)
            if (devicesLauncher == null) {
                android.util.Log.w("ScreenContextEngine", "HOME_SCREEN_SENTINEL check: getDefaultLauncherPackage() returned null — can't resolve this device's launcher at all.")
                return false
            }
            val matched = isAppInForeground(devicesLauncher)
            if (!matched) {
                // DIAGNOSTIC: if this shows up right when a home-screen step
                // fails, it tells us exactly what's mismatched — either the
                // resolved launcher is wrong for THIS device (e.g. ColorOS
                // resolving to something other than what's actually
                // visible, possibly because more than one launcher-capable
                // app is installed), or currentApp is stale (a timing/race
                // issue, not a resolution issue).
                android.util.Log.w("ScreenContextEngine", "HOME_SCREEN_SENTINEL mismatch — resolved launcher=\"$devicesLauncher\" but currentApp=\"${_currentContext.value.currentApp}\"")
            }
            return matched
        }
        return isAppInForeground(packageNameFragment)
    }

    // Common phrases across Android biometric prompts, PIN/pattern lock
    // screens, and OEM app-lockers (MIUI App Lock, Samsung Secure Folder,
    // WhatsApp's own in-app "App Lock" privacy feature, etc). Not exhaustive
    // — heuristic, not a guarantee — but enough to distinguish "the app
    // genuinely failed to open" from "the app opened behind a lock prompt
    // and is waiting on the user".
    private val lockScreenKeywords = listOf(
        "fingerprint", "enter pin", "enter your pin", "enter pattern",
        "unlock", "use screen lock", "authenticate", "face unlock",
        "enter passcode", "confirm it's you", "touch sensor", "app lock",
        "use fingerprint", "verify it's you",
        // BUGFIX: this device's OS-level lock screen shows "Verify with
        // face" and "Use privacy password" — neither matched any existing
        // phrase (closest was "face unlock", not "verify with face"), so
        // looksLikeLockScreen() returned false and the whole extended-wait
        // path never ran even though a real lock prompt was on screen.
        "verify with face", "privacy password", "verify with fingerprint",
        "use password", "use pin", "use pattern"
    )

    /** Best-effort check for whether the current screen looks like a lock/auth prompt. */
    fun looksLikeLockScreen(): Boolean {
        val texts = _currentContext.value.visibleTexts.map { it.lowercase() }
        return lockScreenKeywords.any { kw -> texts.any { it.contains(kw) } }
    }

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
