package com.junai.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat

/**
 * Builds and manages the persistent foreground-service notification for the
 * floating bot. Extracted from FloatingBotService — zero logic change, just
 * relocated. ACTION_SHOW/ACTION_HIDE stay on FloatingBotService since
 * MiniJunSettingsActivity references them externally.
 */
class BotNotificationHelper(
    private val service: Service,
    private val isBotHidden: () -> Boolean
) {
    companion object {
        const val CHANNEL_ID = "floating_bot_channel"
        const val NOTIF_ID = 42
    }

    fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "Jun Floating Bot",
                NotificationManager.IMPORTANCE_MIN
            ).apply { setShowBadge(false) }
            service.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    fun updateNotification() {
        service.getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification())
    }

    fun buildNotification(): Notification {
        val botHidden = isBotHidden()
        val actionToSend = if (botHidden) FloatingBotService.ACTION_SHOW else FloatingBotService.ACTION_HIDE
        val actionLabel  = if (botHidden) "Show" else "Hide"
        val actionIntent = PendingIntent.getService(
            service, 0,
            Intent(service, FloatingBotService::class.java).apply { action = actionToSend },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(service, CHANNEL_ID)
            .setContentTitle("Jun is active")
            .setContentText(if (botHidden) "Jun hidden — tap to show" else "Tap to hide")
            .setSmallIcon(R.drawable.ic_mic)
            .addAction(0, actionLabel, actionIntent)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build()
    }
}
