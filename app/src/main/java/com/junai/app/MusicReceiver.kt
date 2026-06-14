package com.junai.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class MusicReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val serviceIntent = Intent(context, MusicService::class.java)
        when (intent.action) {
            MusicService.ACTION_PLAY_PAUSE -> serviceIntent.action = MusicService.ACTION_PLAY_PAUSE
            MusicService.ACTION_NEXT -> serviceIntent.action = MusicService.ACTION_NEXT
            MusicService.ACTION_PREV -> serviceIntent.action = MusicService.ACTION_PREV
            MusicService.ACTION_STOP -> context.stopService(serviceIntent)
        }
    }
}
