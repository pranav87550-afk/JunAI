package com.junai.app

import android.app.*
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Binder
import android.os.IBinder
import androidx.core.app.NotificationCompat

class MusicService : Service() {

    private var mediaPlayer: MediaPlayer? = null
    private val binder = MusicBinder()
    private var songs = mutableListOf<SongItem>()
    private var currentIndex = 0
    private var isPlaylist = false
    private var playlistName = ""

    companion object {
        const val ACTION_PLAY_PAUSE = "com.junai.app.PLAY_PAUSE"
        const val ACTION_NEXT = "com.junai.app.NEXT"
        const val ACTION_PREV = "com.junai.app.PREV"
        const val ACTION_STOP = "com.junai.app.STOP"
        const val CHANNEL_ID = "music_channel"
        const val NOTIF_ID = 2
    }

    inner class MusicBinder : Binder() {
        fun getService() = this@MusicService
    }

    private val musicReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                ACTION_PLAY_PAUSE -> togglePlayPause()
                ACTION_NEXT -> nextSong()
                ACTION_PREV -> prevSong()
                ACTION_STOP -> stopSelf()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        val filter = IntentFilter().apply {
            addAction(ACTION_PLAY_PAUSE)
            addAction(ACTION_NEXT)
            addAction(ACTION_PREV)
            addAction(ACTION_STOP)
        }
        registerReceiver(musicReceiver, filter)
    }

    override fun onBind(intent: Intent): IBinder = binder

    fun setSongs(songList: List<SongItem>, index: Int, playlist: Boolean = false, pName: String = "") {
        songs = songList.toMutableList()
        currentIndex = index
        isPlaylist = playlist
        playlistName = pName
        playSong()
    }

    fun playSong() {
        if (songs.isEmpty()) return
        val song = songs[currentIndex]
        mediaPlayer?.release()
        mediaPlayer = MediaPlayer().apply {
            setAudioAttributes(
                AudioAttributes.Builder()
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .build()
            )
            setDataSource(song.path)
            prepare()
            start()
            setOnCompletionListener { nextSong() }
        }
        showNotification()
    }

    fun togglePlayPause() {
        mediaPlayer?.let {
            if (it.isPlaying) it.pause() else it.start()
            showNotification()
        }
    }

    fun nextSong() {
        if (songs.isEmpty()) return
        currentIndex = (currentIndex + 1) % songs.size
        playSong()
    }

    fun prevSong() {
        if (songs.isEmpty()) return
        currentIndex = if (currentIndex - 1 < 0) songs.size - 1 else currentIndex - 1
        playSong()
    }

    fun isPlaying() = mediaPlayer?.isPlaying ?: false
    fun getCurrentPosition() = mediaPlayer?.currentPosition ?: 0
    fun getDuration() = mediaPlayer?.duration ?: 0
    fun getCurrentSong() = if (songs.isNotEmpty()) songs[currentIndex] else null
    fun getCurrentIndex() = currentIndex

    fun seekTo(position: Int) {
        mediaPlayer?.seekTo(position)
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID, "Jun DJ",
            NotificationManager.IMPORTANCE_LOW
        )
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun showNotification() {
        val song = songs.getOrNull(currentIndex) ?: return
        val isPlaying = mediaPlayer?.isPlaying ?: false

        val playPauseIntent = PendingIntent.getBroadcast(this, 0,
            Intent(ACTION_PLAY_PAUSE), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val nextIntent = PendingIntent.getBroadcast(this, 1,
            Intent(ACTION_NEXT), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val prevIntent = PendingIntent.getBroadcast(this, 2,
            Intent(ACTION_PREV), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val stopIntent = PendingIntent.getBroadcast(this, 3,
            Intent(ACTION_STOP), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

        val openIntent = PendingIntent.getActivity(this, 0,
            Intent(this, MusicPlayerActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(song.title)
            .setContentText(song.artist)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentIntent(openIntent)
            .addAction(android.R.drawable.ic_media_previous, "Prev", prevIntent)
            .addAction(
                if (isPlaying) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play,
                if (isPlaying) "Pause" else "Play", playPauseIntent
            )
            .addAction(android.R.drawable.ic_media_next, "Next", nextIntent)
            .addAction(android.R.drawable.ic_delete, "End", stopIntent)
            .setStyle(androidx.media.app.NotificationCompat.MediaStyle()
                .setShowActionsInCompactView(0, 1, 2))
            .setOngoing(true)
            .build()

        startForeground(NOTIF_ID, notification)
    }

    override fun onDestroy() {
        mediaPlayer?.release()
        unregisterReceiver(musicReceiver)
        super.onDestroy()
    }
}
