package com.junai.app

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.widget.Button
import android.widget.ImageButton
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class MusicPlayerActivity : AppCompatActivity() {

    private var musicService: MusicService? = null
    private var isBound = false
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var seekBar: SeekBar
    private lateinit var currentTime: TextView
    private lateinit var totalTime: TextView
    private lateinit var songTitle: TextView
    private lateinit var songArtist: TextView
    private lateinit var playPauseButton: ImageButton

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as MusicService.MusicBinder
            musicService = binder.getService()
            isBound = true

            val songs = intent.getSerializableExtra("songs") as? ArrayList<SongItem>
            val index = intent.getIntExtra("index", 0)
            val isPlaylist = intent.getBooleanExtra("isPlaylist", false)
            val playlistName = intent.getStringExtra("playlistName") ?: ""

            if (songs != null) {
                musicService?.setSongs(songs, index, isPlaylist, playlistName)
            }

            updateUI()
            startUpdating()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            isBound = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_music_player)

        seekBar = findViewById(R.id.musicSeekBar)
        currentTime = findViewById(R.id.currentTime)
        totalTime = findViewById(R.id.totalTime)
        songTitle = findViewById(R.id.songTitle)
        songArtist = findViewById(R.id.songArtist)
        playPauseButton = findViewById(R.id.playPauseButton)

        findViewById<Button>(R.id.backButton).setOnClickListener { finish() }

        findViewById<ImageButton>(R.id.playPauseButton).setOnClickListener {
            musicService?.togglePlayPause()
            updatePlayPauseButton()
        }

        findViewById<ImageButton>(R.id.nextButton).setOnClickListener {
            musicService?.nextSong()
            handler.postDelayed({ updateUI() }, 300)
        }

        findViewById<ImageButton>(R.id.prevButton).setOnClickListener {
            musicService?.prevSong()
            handler.postDelayed({ updateUI() }, 300)
        }

        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) musicService?.seekTo(progress)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        val intent = Intent(this, MusicService::class.java)
        bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
        startService(intent)
    }

    private fun updateUI() {
        val song = musicService?.getCurrentSong() ?: return
        songTitle.text = song.title
        songArtist.text = song.artist
        seekBar.max = musicService?.getDuration() ?: 0
        updatePlayPauseButton()
    }

    private fun updatePlayPauseButton() {
        val isPlaying = musicService?.isPlaying() ?: false
        playPauseButton.setImageResource(
            if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play
        )
    }

    private fun startUpdating() {
        handler.post(object : Runnable {
            override fun run() {
                musicService?.let {
                    val pos = it.getCurrentPosition()
                    val dur = it.getDuration()
                    seekBar.progress = pos
                    currentTime.text = formatTime(pos)
                    totalTime.text = formatTime(dur)
                    updatePlayPauseButton()
                    val song = it.getCurrentSong()
                    if (song != null) {
                        songTitle.text = song.title
                        songArtist.text = song.artist
                    }
                }
                handler.postDelayed(this, 500)
            }
        })
    }

    private fun formatTime(ms: Int): String {
        val seconds = (ms / 1000) % 60
        val minutes = (ms / 1000) / 60
        return "%d:%02d".format(minutes, seconds)
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        if (isBound) unbindService(serviceConnection)
        super.onDestroy()
    }
}
