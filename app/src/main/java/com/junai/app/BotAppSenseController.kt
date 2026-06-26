package com.junai.app

import android.content.Context
import android.os.Handler
import android.os.Looper

/**
 * Periodically checks the foreground app and shows a contextual bubble
 * message via AppSenseManager. Extracted from FloatingBotService — zero
 * logic change, just relocated. `enabled` mirrors the old `appSenseEnabled`
 * field and is toggled directly by FloatingPrefs handling, same as
 * `movement.roamingEnabled`.
 */
class BotAppSenseController(
    private val context: Context,
    private val isBotHidden: () -> Boolean,
    private val onMessage: (String) -> Unit
) {
    companion object {
        const val APP_SENSE_INTERVAL_MS = 4_000L // check foreground app every 4 sec
    }

    var enabled = false

    private val appSenseHandler = Handler(Looper.getMainLooper())
    private var appSenseRunnable: Runnable? = null

    fun start() {
        stop()
        val runnable = object : Runnable {
            override fun run() {
                if (!enabled || isBotHidden()) {
                    appSenseHandler.postDelayed(this, APP_SENSE_INTERVAL_MS)
                    return
                }
                val pkg = AppSenseManager.getForegroundApp(context)
                if (!pkg.isNullOrEmpty()) {
                    val message = AppSenseManager.getMessageForApp(pkg)
                    if (!message.isNullOrEmpty()) {
                        onMessage(message)
                    }
                }
                appSenseHandler.postDelayed(this, APP_SENSE_INTERVAL_MS)
            }
        }
        appSenseRunnable = runnable
        appSenseHandler.postDelayed(runnable, APP_SENSE_INTERVAL_MS)
    }

    fun stop() {
        appSenseRunnable?.let { appSenseHandler.removeCallbacks(it) }
        appSenseRunnable = null
    }
}
