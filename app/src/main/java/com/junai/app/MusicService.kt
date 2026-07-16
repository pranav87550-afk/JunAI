package com.junai.app

import android.app.*
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.BitmapFactory
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Binder
import android.os.IBinder
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.core.app.NotificationCompat

class MusicService : Service() {

    private var mediaPlayer: MediaPlayer? = null
    private val binder = MusicBinder()
    private var songs = mutableListOf<SongItem>()
    private var currentIndex = 0
    private var isPlaylist = false
    private var playlistName = ""

    private lateinit var mediaSession: MediaSessionCompat

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

        mediaSession = MediaSessionCompat(this, "JunMusicSession").apply {
            setCallback(object : MediaSessionCompat.Callback() {
                override fun onPlay() = togglePlayPause()
                override fun onPause() = togglePlayPause()
                override fun onSkipToNext() = nextSong()
                override fun onSkipToPrevious() = prevSong()
                override fun onSeekTo(pos: Long) {
                    seekTo(pos.toInt())
                    showNotification()
                }
                override fun onStop() = stopSelf()
            })
            isActive = true
        }

        val filter = IntentFilter().apply {
            addAction(ACTION_PLAY_PAUSE)
            addAction(ACTION_NEXT)
            addAction(ACTION_PREV)
            addAction(ACTION_STOP)
        }
        registerReceiver(musicReceiver, filter)
    }

    override fun onBind(intent: Intent): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (songs.isEmpty()) return START_NOT_STICKY
    when (intent?.action) {
        "NEXT" -> nextSong()
        "PREV" -> prevSong()
        "PAUSE" -> togglePlayPause()
        "STOP" -> stopSelf()
        ACTION_NEXT -> nextSong()
        ACTION_PREV -> prevSong()
        ACTION_PLAY_PAUSE -> togglePlayPause()
        ACTION_STOP -> stopSelf()
    }
    return START_NOT_STICKY
    }

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

    // MediaStore returns the literal string "<unknown>" (not null) for tracks
    // with no artist tag, so a null-check alone doesn't catch it.
    private fun displayArtist(rawArtist: String?): String {
        val cleaned = rawArtist?.trim()
        return if (cleaned.isNullOrEmpty() || cleaned.equals("<unknown>", ignoreCase = true) ||
            cleaned.equals("unknown", ignoreCase = true)
        ) {
            "Just feel the music \uD83C\uDFB6"
        } else {
            cleaned
        }
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
        val position = getCurrentPosition().toLong()
        val duration = getDuration().toLong()
        val artistText = displayArtist(song.artist)

        val junIcon = BitmapFactory.decodeResource(resources, R.mipmap.ic_launcher)

        // Metadata: powers the album-art thumbnail, title/artist text, and
        // the duration the system uses to draw the seekbar (Android 13+).
        mediaSession.setMetadata(
            MediaMetadataCompat.Builder()
                .putString(MediaMetadataCompat.METADATA_KEY_TITLE, song.title)
                .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, artistText)
                .putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, junIcon)
                .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, duration)
                .build()
        )

        // Playback state: powers play/pause icon sync, the draggable seekbar,
        // and is required for the system to show the Output Switcher (Speaker) chip.
        val state = if (isPlaying) PlaybackStateCompat.STATE_PLAYING else PlaybackStateCompat.STATE_PAUSED
        mediaSession.setPlaybackState(
            PlaybackStateCompat.Builder()
                .setActions(
                    PlaybackStateCompat.ACTION_PLAY or
                        PlaybackStateCompat.ACTION_PAUSE or
                        PlaybackStateCompat.ACTION_PLAY_PAUSE or
                        PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
                        PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or
                        PlaybackStateCompat.ACTION_SEEK_TO or
                        PlaybackStateCompat.ACTION_STOP
                )
                .setState(state, position, 1f)
                .build()
        )

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
            .setContentText(artistText)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setLargeIcon(junIcon)
            .setContentIntent(openIntent)
            .addAction(android.R.drawable.ic_media_previous, "Prev", prevIntent)
            .addAction(
                if (isPlaying) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play,
                if (isPlaying) "Pause" else "Play", playPauseIntent
            )
            .addAction(android.R.drawable.ic_media_next, "Next", nextIntent)
            .addAction(android.R.drawable.ic_delete, "End", stopIntent)
            .setStyle(androidx.media.app.NotificationCompat.MediaStyle()
                .setMediaSession(mediaSession.sessionToken)
                .setShowActionsInCompactView(0, 1, 2))
            .setOngoing(true)
            .build()

        startForeground(NOTIF_ID, notification)
    }

    override fun onDestroy() {
        mediaPlayer?.release()
        mediaSession.isActive = false
        mediaSession.release()
        unregisterReceiver(musicReceiver)
        super.onDestroy()
    }
}
