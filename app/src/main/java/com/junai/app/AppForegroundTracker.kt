package com.junai.app

import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner

/**
 * Whole-APP (not per-Activity) foreground/background signal, backed by
 * ProcessLifecycleOwner — fires once whether one Activity or five are
 * visible, and once when the user leaves the app entirely rather than
 * once per Activity's onStop (which would fire even during a normal
 * in-app screen transition). GenerationForegroundService uses this to
 * decide whether the "Jun replying…" / "reply complete" notifications
 * should show at all — no notification while the user's actually
 * looking at the chat, only when they've stepped away.
 *
 * Registered once from JunApplication.onCreate(); reads isAppInForeground
 * from anywhere, no context needed.
 */
object AppForegroundTracker : DefaultLifecycleObserver {

    @Volatile
    var isAppInForeground: Boolean = false
        private set

    fun register() {
        ProcessLifecycleOwner.get().lifecycle.addObserver(this)
    }

    override fun onStart(owner: LifecycleOwner) {
        isAppInForeground = true
    }

    override fun onStop(owner: LifecycleOwner) {
        isAppInForeground = false
    }
}
