package com.junai.app.passive

import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * A listener registered separately from JunAccessibilityService's main
 * recording/replay event handling (per the Phase 2 design check-in choice
 * of "separate hook/callback registration" over inlining into
 * onAccessibilityEvent directly).
 *
 * JunAccessibilityService dispatches to each registered listener inside
 * its OWN try-catch per listener (see JunAccessibilityService's
 * passiveListeners dispatch loop) — this is what actually delivers ground
 * rule "a bug in the passive capturer must never be able to crash or
 * destabilize the accessibility service." A throw from one listener can't
 * even affect a second listener, let alone the recording/replay path.
 *
 * [rootProvider] is lazy (a function, not the node itself) so a listener
 * that doesn't need the node tree for this particular event — e.g. it's
 * rate-limited and dropping this event, or the app isn't Allowed — never
 * pays for fetching rootInActiveWindow at all.
 */
interface PassiveEventListener {
    fun onEvent(event: AccessibilityEvent, eventPackage: String?, rootProvider: () -> AccessibilityNodeInfo?)
}
