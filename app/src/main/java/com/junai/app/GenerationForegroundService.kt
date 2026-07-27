package com.junai.app

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner

/**
 * GenerationForegroundService — keeps the process alive at foreground
 * priority ONLY for the portion of a Qwen3 generation that actually
 * happens while the app is backgrounded, and only shows a notification
 * during that portion. Behavior (per Pranav):
 *   - App in foreground the whole time a reply generates → no
 *     notification at all, nothing changes from before this existed.
 *   - App backgrounded at any point during generation → a low-priority
 *     "Jun · Replying…" notification appears for as long as it's
 *     backgrounded (protects the process from being frozen — see class
 *     doc on the original bug), then on completion is replaced by a
 *     one-shot "Jun's reply is complete" notification — same pattern as
 *     Claude/ChatGPT's apps.
 *   - If the user reopens the app while still generating, the
 *     "Replying…" notification is dropped immediately (they can see
 *     the live stream again) with no completion notification following.
 *
 * WHY A SERVICE AT ALL (vs. just posting notifications from
 * ChatIntentHandler directly): the actual problem being solved is
 * ChatIntentHandler's generation coroutine getting its native inference
 * thread frozen by Android/OPPO's ColorOS battery manager once the app
 * backgrounds mid-stream. A foreground service is what buys that
 * protection — the notification is a side effect of being foreground,
 * not the goal by itself.
 *
 * Reacts to AppForegroundTracker transitions live (via its own
 * ProcessLifecycleOwner observer) so it correctly promotes/demotes even
 * if the user backgrounds or reopens the app mid-generation, not just
 * at start/end.
 */
class GenerationForegroundService : Service(), DefaultLifecycleObserver {

    companion object {
        private const val ONGOING_CHANNEL_ID = "jun_generation_channel"
        private const val COMPLETE_CHANNEL_ID = "jun_generation_complete_channel"
        private const val ONGOING_NOTIF_ID = 42
        private const val COMPLETE_NOTIF_ID = 43

        private const val ACTION_BEGIN = "com.junai.app.action.GENERATION_BEGIN"
        private const val ACTION_COMPLETE = "com.junai.app.action.GENERATION_COMPLETE"

        /** Call right before GGUFChatEngine.streamChat() starts. */
        fun start(context: Context) {
            val intent = Intent(context, GenerationForegroundService::class.java)
                .setAction(ACTION_BEGIN)
            // Only launch via startForegroundService() when we're actually
            // about to call startForeground() inside handleBegin() (app
            // already backgrounded) — Android requires that call within a
            // few seconds of startForegroundService() or it crashes the
            // app with ForegroundServiceDidNotStartInTimeException. When
            // the app is foreground, handleBegin() deliberately does NOT
            // promote, so a plain startService() is required instead (and
            // is allowed here specifically because the calling app itself
            // is currently in the foreground).
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !AppForegroundTracker.isAppInForeground) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        /** Call once the reply is fully done (success OR failure — see ChatIntentHandler's finally block). */
        fun complete(context: Context) {
            val intent = Intent(context, GenerationForegroundService::class.java)
                .setAction(ACTION_COMPLETE)
            // Plain startService here (never startForegroundService) — by
            // this point the service is already running from start()
            // above; ACTION_COMPLETE only ever demotes/stops it, never
            // needs to newly promote to foreground.
            context.startService(intent)
        }
    }

    /** True once THIS generation has shown the ongoing "Replying…" notification at least once. */
    private var isForegroundPromoted = false

    override fun onCreate() {
        super<Service>.onCreate()
        ProcessLifecycleOwner.get().lifecycle.addObserver(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_COMPLETE -> handleComplete()
            else -> handleBegin() // ACTION_BEGIN, or null defensively treated the same way
        }
        return START_NOT_STICKY
    }

    private fun handleBegin() {
        if (!AppForegroundTracker.isAppInForeground) {
            promoteToForeground()
        }
        // else: app is foreground right now — service stays alive but
        // non-foreground and silent; onStart()/onStop() below will
        // promote it later only if the user actually backgrounds the
        // app before this generation's complete() call arrives.
    }

    private fun handleComplete() {
        if (isForegroundPromoted && !AppForegroundTracker.isAppInForeground) {
            postCompleteNotification()
        }
        // If the app is foreground right now (either it stayed
        // foreground the whole time, or the user came back mid-stream —
        // see onStart() below, which already dropped the ongoing
        // notification in that case), no completion notification: the
        // person already saw the reply land live in the chat.
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    /** ProcessLifecycleOwner callback — app came back to foreground. */
    override fun onStart(owner: LifecycleOwner) {
        if (isForegroundPromoted) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            isForegroundPromoted = false
        }
    }

    /** ProcessLifecycleOwner callback — app just left the foreground. */
    override fun onStop(owner: LifecycleOwner) {
        promoteToForeground()
    }

    private fun promoteToForeground() {
        createOngoingChannel()
        val notification = NotificationCompat.Builder(this, ONGOING_CHANNEL_ID)
            .setContentTitle("Jun")
            .setContentText("Replying…")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
        startForeground(ONGOING_NOTIF_ID, notification)
        isForegroundPromoted = true
    }

    private fun postCompleteNotification() {
        createCompleteChannel()
        val openIntent = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(this, COMPLETE_CHANNEL_ID)
            .setContentTitle("Jun")
            .setContentText("Jun's reply is complete")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(COMPLETE_NOTIF_ID, notification)
    }

    private fun createOngoingChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(ONGOING_CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(ONGOING_CHANNEL_ID, "Jun replying", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    private fun createCompleteChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(COMPLETE_CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(COMPLETE_CHANNEL_ID, "Jun reply complete", NotificationManager.IMPORTANCE_DEFAULT)
            )
        }
    }

    override fun onDestroy() {
        ProcessLifecycleOwner.get().lifecycle.removeObserver(this)
        super<Service>.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
