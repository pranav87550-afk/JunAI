package com.junai.app

import android.app.Service
import android.content.Context
import android.os.BatteryManager
import android.os.Handler
import android.os.Looper

/**
 * Periodically checks battery level (every minute, starting 30s after launch)
 * and shows a low-battery warning bubble. Extracted from FloatingBotService
 * — zero logic change, just relocated.
 */
class BotBatteryMonitor(
    private val service: Service,
    private val onWarn: (String) -> Unit
) {
    companion object {
        const val BATTERY_WARN_INTERVAL = 300_000L  // 5 min between battery warnings
    }

    private val batteryHandler = Handler(Looper.getMainLooper())
    private var batteryRunnable: Runnable? = null
    private var lastBatteryWarnTime = 0L
    private var lastBatteryLevel = -1

    fun start() {
        val runnable = object : Runnable {
            override fun run() {
                checkBattery()
                batteryHandler.postDelayed(this, 60_000L) // check every 1 min
            }
        }
        batteryRunnable = runnable
        batteryHandler.postDelayed(runnable, 30_000L) // first check after 30 sec
    }

    /** Stops the repeating check — call from onDestroy to avoid a dangling Handler loop. */
    fun stop() {
        batteryRunnable?.let { batteryHandler.removeCallbacks(it) }
        batteryRunnable = null
    }

    private fun checkBattery() {
        try {
            val bm = service.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
            val level = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
            val isCharging = bm.isCharging
            val now = System.currentTimeMillis()

            if (isCharging) return
            if (now - lastBatteryWarnTime < BATTERY_WARN_INTERVAL) return

            val message = when {
                level <= 5  -> "Critical battery! Plug me in NOW! 🔴🔋"
                level <= 15 -> "Battery very low! $level% 🔋 Charge me please!"
                level <= 20 -> "I'm getting tired... $level% battery left 🔋"
                else        -> return
            }

            if (level != lastBatteryLevel) {
                lastBatteryLevel    = level
                lastBatteryWarnTime = now
                onWarn(message)
            }
        } catch (e: Exception) { /* ignore */ }
    }
}
