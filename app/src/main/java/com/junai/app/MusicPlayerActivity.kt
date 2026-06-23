package com.junai.app

import android.app.RingtoneManager
import android.content.ComponentName
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.MediaStore
import android.provider.Settings
import android.view.LayoutInflater
import android.view.ViewGroup
import android.view.animation.AnimationUtils
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import org.json.JSONArray
import org.json.JSONObject

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
    private lateinit var songListRecycler: RecyclerView
    private lateinit var albumArtLayout: LinearLayout

    private var allSongs = listOf<SongItem>()
    private val MUSIC_PREFS = "music_prefs"

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
                allSongs = songs.sortedBy { it.title.lowercase() }
                musicService?.setSongs(ArrayList(allSongs), index, isPlaylist, playlistName)
            }

            updateUI()
            startUpdating()
            setupSongList()
            ensureFavoritesPlaylist()
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
        songListRecycler = findViewById(R.id.songListRecycler)
        albumArtLayout = findViewById(R.id.albumArtLayout)

        findViewById<Button>(R.id.backButton).setOnClickListener { finish() }

        playPauseButton.setOnClickListener {
            musicService?.togglePlayPause()
            updatePlayPauseButton()
            playPauseButton.startAnimation(
                AnimationUtils.loadAnimation(this, android.R.anim.fade_in)
            )
        }

        findViewById<ImageButton>(R.id.nextButton).setOnClickListener {
            musicService?.nextSong()
            handler.postDelayed({ updateUI(); highlightCurrentSong() }, 300)
            albumArtLayout.startAnimation(
                AnimationUtils.loadAnimation(this, android.R.anim.fade_in)
            )
        }

        findViewById<ImageButton>(R.id.prevButton).setOnClickListener {
            try {
                musicService?.prevSong()
                handler.postDelayed({ updateUI(); highlightCurrentSong() }, 300)
                albumArtLayout.startAnimation(
                    AnimationUtils.loadAnimation(this, android.R.anim.fade_in)
                )
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) musicService?.seekTo(progress)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        val serviceIntent = Intent(this, MusicService::class.java)
        bindService(serviceIntent, serviceConnection, Context.BIND_AUTO_CREATE)
        startService(serviceIntent)
    }

    private fun setupSongList() {
        songListRecycler.layoutManager = LinearLayoutManager(this)
        songListRecycler.adapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
            override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
                val view = LayoutInflater.from(parent.context)
                    .inflate(R.layout.item_song_list, parent, false)
                return object : RecyclerView.ViewHolder(view) {}
            }

            override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
                val song = allSongs[position]
                val nameView = holder.itemView.findViewById<TextView>(R.id.songItemTitle)
                val menuBtn = holder.itemView.findViewById<ImageButton>(R.id.songItemMenu)
                val indicator = holder.itemView.findViewById<android.view.View>(R.id.currentSongIndicator)

                nameView.text = "${position + 1}. ${song.title}"

                val isCurrent = musicService?.getCurrentIndex() == position
                indicator.visibility = if (isCurrent) android.view.View.VISIBLE else android.view.View.INVISIBLE
                nameView.setTextColor(
                    if (isCurrent) android.graphics.Color.parseColor("#FF5252")
                    else android.graphics.Color.WHITE
                )

                holder.itemView.setOnClickListener {
                    musicService?.setSongs(ArrayList(allSongs), position, false, "")
                    handler.postDelayed({ updateUI(); notifyDataSetChanged() }, 300)
                    albumArtLayout.startAnimation(
                        AnimationUtils.loadAnimation(this@MusicPlayerActivity, android.R.anim.fade_in)
                    )
                }

                menuBtn.setOnClickListener { showSongMenu(song, position) }
            }

            override fun getItemCount() = allSongs.size
        }
    }

    private fun highlightCurrentSong() {
        songListRecycler.adapter?.notifyDataSetChanged()
        val idx = musicService?.getCurrentIndex() ?: 0
        (songListRecycler.layoutManager as? LinearLayoutManager)
            ?.scrollToPositionWithOffset(idx, 100)
    }

    private fun showSongMenu(song: SongItem, position: Int) {
        val options = arrayOf(
            "⭐ Add to Favorites",
            "📋 Add to Playlist",
            "🔔 Set as Ringtone",
            "⏰ Set as Alarm Tone",
            "✏️ Rename",
            "🗑️ Delete",
            "✖ Back"
        )
        AlertDialog.Builder(this, R.style.DarkDialog)
            .setTitle(song.title)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> addToFavorites(song)
                    1 -> showAddToPlaylistDialog(song)
                    2 -> setAsRingtone(song, RingtoneManager.TYPE_RINGTONE)
                    3 -> setAsRingtone(song, RingtoneManager.TYPE_ALARM)
                    4 -> showRenameDialog(song, position)
                    5 -> confirmDelete(song, position)
                    6 -> { /* Back */ }
                }
            }
            .show()
    }

    private fun addToFavorites(song: SongItem) {
        val prefs = getSharedPreferences(MUSIC_PREFS, MODE_PRIVATE)
        val json = prefs.getString("playlists", "[]") ?: "[]"
        val array = JSONArray(json)

        var favIndex = -1
        for (i in 0 until array.length()) {
            if (array.getJSONObject(i).getString("name") == "Favorites") {
                favIndex = i; break
            }
        }

        if (favIndex == -1) {
            val obj = JSONObject()
            obj.put("name", "Favorites")
            obj.put("songs", JSONArray())
            array.put(obj)
            favIndex = array.length() - 1
        }

        val favSongs = array.getJSONObject(favIndex).getJSONArray("songs")
        for (i in 0 until favSongs.length()) {
            if (favSongs.getJSONObject(i).getLong("id") == song.id) {
                Toast.makeText(this, "Already in Favorites!", Toast.LENGTH_SHORT).show()
                return
            }
        }

        val songObj = JSONObject().apply {
            put("id", song.id); put("title", song.title)
            put("artist", song.artist); put("path", song.path)
            put("duration", song.duration)
        }
        favSongs.put(songObj)
        prefs.edit().putString("playlists", array.toString()).apply()
        Toast.makeText(this, "Added to Favorites ⭐", Toast.LENGTH_SHORT).show()
    }

    private fun showAddToPlaylistDialog(song: SongItem) {
        val prefs = getSharedPreferences(MUSIC_PREFS, MODE_PRIVATE)
        val json = prefs.getString("playlists", "[]") ?: "[]"
        val array = JSONArray(json)

        if (array.length() == 0) {
            Toast.makeText(this, "No playlists found. Create one first!", Toast.LENGTH_SHORT).show()
            return
        }

        val names = Array(array.length()) { array.getJSONObject(it).getString("name") }
        AlertDialog.Builder(this, R.style.DarkDialog)
            .setTitle("Add to Playlist")
            .setItems(names) { _, which ->
                val songs = array.getJSONObject(which).getJSONArray("songs")
                for (i in 0 until songs.length()) {
                    if (songs.getJSONObject(i).getLong("id") == song.id) {
                        Toast.makeText(this, "Already in ${names[which]}!", Toast.LENGTH_SHORT).show()
                        return@setItems
                    }
                }
                val songObj = JSONObject().apply {
                    put("id", song.id); put("title", song.title)
                    put("artist", song.artist); put("path", song.path)
                    put("duration", song.duration)
                }
                songs.put(songObj)
                prefs.edit().putString("playlists", array.toString()).apply()
                Toast.makeText(this, "Added to ${names[which]} ✅", Toast.LENGTH_SHORT).show()
            }
            .show()
    }

    private fun setAsRingtone(song: SongItem, type: Int) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.System.canWrite(this)) {
            val intent = Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS)
            intent.data = Uri.parse("package:$packageName")
            startActivity(intent)
            Toast.makeText(this, "Allow write settings, then try again", Toast.LENGTH_LONG).show()
            return
        }
        try {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DATA, song.path)
                put(MediaStore.MediaColumns.TITLE, song.title)
                put(MediaStore.MediaColumns.MIME_TYPE, "audio/mpeg")
                put(MediaStore.Audio.Media.IS_RINGTONE, type == RingtoneManager.TYPE_RINGTONE)
                put(MediaStore.Audio.Media.IS_ALARM, type == RingtoneManager.TYPE_ALARM)
                put(MediaStore.Audio.Media.IS_MUSIC, false)
            }
            val uri = MediaStore.Audio.Media.getContentUriForPath(song.path)!!
            contentResolver.delete(uri, "${MediaStore.MediaColumns.DATA}=?", arrayOf(song.path))
            val newUri = contentResolver.insert(uri, values)!!
            RingtoneManager.setActualDefaultRingtoneUri(this, type, newUri)
            val label = if (type == RingtoneManager.TYPE_RINGTONE) "Ringtone" else "Alarm tone"
            Toast.makeText(this, "Set as $label ✅", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Failed: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showRenameDialog(song: SongItem, position: Int) {
        val input = EditText(this).apply {
            setText(song.title)
            setTextColor(android.graphics.Color.WHITE)
            setPadding(32, 24, 32, 24)
        }
        AlertDialog.Builder(this, R.style.DarkDialog)
            .setTitle("Rename Song")
            .setView(input)
            .setPositiveButton("Rename") { _, _ ->
                val newName = input.text.toString().trim()
                if (newName.isEmpty()) return@setPositiveButton
                try {
                    val values = ContentValues().apply {
                        put(MediaStore.Audio.Media.TITLE, newName)
                    }
                    val uri = ContentUris.withAppendedId(
                        MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, song.id
                    )
                    contentResolver.update(uri, values, null, null)
                    allSongs = allSongs.toMutableList().also { it[position] = song.copy(title = newName) }
                    songListRecycler.adapter?.notifyItemChanged(position)
                    Toast.makeText(this, "Renamed ✅", Toast.LENGTH_SHORT).show()
                } catch (e: Exception) {
                    Toast.makeText(this, "Rename failed: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun confirmDelete(song: SongItem, position: Int) {
        AlertDialog.Builder(this, R.style.DarkDialog)
            .setTitle("Delete Song")
            .setMessage("Delete \"${song.title}\" permanently?")
            .setPositiveButton("Delete") { _, _ ->
                try {
                    val uri = ContentUris.withAppendedId(
                        MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, song.id
                    )
                    contentResolver.delete(uri, null, null)
                    allSongs = allSongs.toMutableList().also { it.removeAt(position) }
                    songListRecycler.adapter?.notifyItemRemoved(position)
                    Toast.makeText(this, "Deleted 🗑️", Toast.LENGTH_SHORT).show()
                } catch (e: Exception) {
                    Toast.makeText(this, "Delete failed: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun ensureFavoritesPlaylist() {
        val prefs = getSharedPreferences(MUSIC_PREFS, MODE_PRIVATE)
        val json = prefs.getString("playlists", "[]") ?: "[]"
        val array = JSONArray(json)
        for (i in 0 until array.length()) {
            if (array.getJSONObject(i).getString("name") == "Favorites") return
        }
        val newArray = JSONArray()
        val favObj = JSONObject().apply {
            put("name", "Favorites")
            put("songs", JSONArray())
        }
        newArray.put(favObj)
        for (i in 0 until array.length()) newArray.put(array.getJSONObject(i))
        prefs.edit().putString("playlists", newArray.toString()).apply()
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
                    seekBar.progress = it.getCurrentPosition()
                    seekBar.max = it.getDuration()
                    currentTime.text = formatTime(it.getCurrentPosition())
                    totalTime.text = formatTime(it.getDuration())
                    updatePlayPauseButton()
                    it.getCurrentSong()?.let { song ->
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

    override fun onResume() {
        super.onResume()
        if (isBound) { updateUI(); startUpdating() }
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacksAndMessages(null)
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        if (isBound) unbindService(serviceConnection)
        super.onDestroy()
    }
}
