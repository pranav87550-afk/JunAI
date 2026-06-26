package com.junai.app

import android.Manifest
import android.content.ContentUris
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageButton
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import org.json.JSONArray
import org.json.JSONObject

class MusicHomeActivity : AppCompatActivity() {

    private val allSongs = mutableListOf<SongItem>()
    private val playlists = mutableListOf<Pair<String, MutableList<SongItem>>>()
    private lateinit var adapter: RecyclerView.Adapter<*>
    private val PREFS = "music_prefs"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_music_home)

        findViewById<Button>(R.id.backButton).setOnClickListener { finish() }

        requestPermissions()
        loadPlaylists()
        setupRecyclerView()

        findViewById<ImageButton>(R.id.addPlaylistButton).setOnClickListener {
            showCreatePlaylistDialog()
        }
    }

    private fun requestPermissions() {
        val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            Manifest.permission.READ_MEDIA_AUDIO
        else Manifest.permission.READ_EXTERNAL_STORAGE

        if (ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(permission), 100)
        } else {
            loadAllSongs()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 100 && grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            loadAllSongs()
        }
    }

    private fun loadAllSongs() {
        allSongs.clear()
        val collection = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.DATA,
            MediaStore.Audio.Media.DURATION
        )
        val selection = "${MediaStore.Audio.Media.IS_MUSIC} != 0"
        val sortOrder = "${MediaStore.Audio.Media.TITLE} ASC"

        contentResolver.query(collection, projection, selection, null, sortOrder)?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val titleCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
            val artistCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
            val dataCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATA)
            val durationCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)

            while (cursor.moveToNext()) {
                allSongs.add(SongItem(
                    cursor.getLong(idCol),
                    cursor.getString(titleCol) ?: "Unknown",
                    cursor.getString(artistCol) ?: "Unknown",
                    cursor.getString(dataCol),
                    cursor.getLong(durationCol)
                ))
            }
        }
        setupRecyclerView()
    }

    private fun setupRecyclerView() {
        val recyclerView = findViewById<RecyclerView>(R.id.musicHomeRecyclerView)
        recyclerView.layoutManager = LinearLayoutManager(this)

        val items = mutableListOf<Pair<String, String>>()
        items.add(Pair("All Songs", "${allSongs.size} songs"))
        playlists.forEach { items.add(Pair(it.first, "${it.second.size} songs")) }

        adapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
            override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
                val view = LayoutInflater.from(parent.context)
                    .inflate(R.layout.item_music_home, parent, false)
                return object : RecyclerView.ViewHolder(view) {}
            }

            override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
                holder.itemView.findViewById<TextView>(R.id.itemTitle).text = items[position].first
                holder.itemView.findViewById<TextView>(R.id.itemSubtitle).text = items[position].second

                // Icon — music note for All Songs, doc icon for playlists
                val icon = holder.itemView.findViewById<android.widget.ImageView>(R.id.itemIcon)
                if (position == 0) {
                    icon.setImageResource(R.drawable.ic_play)
                    icon.setColorFilter(android.graphics.Color.parseColor("#E53935"))
                } else {
                    icon.setImageResource(R.drawable.ic_note_doc)
                    icon.setColorFilter(android.graphics.Color.parseColor("#FF9800"))
                }

                if (position > 0) {
                    holder.itemView.findViewById<ImageButton>(R.id.itemDeleteButton).apply {
                        visibility = android.view.View.VISIBLE
                        setOnClickListener {
                            playlists.removeAt(position - 1)
                            items.removeAt(position)
                            notifyItemRemoved(position)
                            savePlaylists()
                        }
                    }
                }

                holder.itemView.setOnClickListener {
                    if (position == 0) {
                        val intent = Intent(this@MusicHomeActivity, MusicPlayerActivity::class.java)
                        intent.putExtra("songs", ArrayList(allSongs))
                        intent.putExtra("index", 0)
                        intent.putExtra("isPlaylist", false)
                        startActivity(intent)
                    } else {
                        val intent = Intent(this@MusicHomeActivity, PlaylistActivity::class.java)
                        intent.putExtra("playlistIndex", position - 1)
                        intent.putExtra("allSongs", ArrayList(allSongs))
                        startActivity(intent)
                    }
                }
            }

            override fun getItemCount() = items.size
        }

        recyclerView.adapter = adapter
    }

    private fun showCreatePlaylistDialog() {
        val input = android.widget.EditText(this).apply {
            hint = "Playlist name"
            setTextColor(android.graphics.Color.WHITE)
            setHintTextColor(android.graphics.Color.GRAY)
            setPadding(32, 24, 32, 24)
        }

        AlertDialog.Builder(this, R.style.DarkDialog)
            .setTitle("New Playlist")
            .setView(input)
            .setPositiveButton("Create") { _, _ ->
                val name = input.text.toString().trim()
                if (name.isNotEmpty()) {
                    playlists.add(Pair(name, mutableListOf()))
                    savePlaylists()
                    setupRecyclerView()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun savePlaylists() {
        val array = JSONArray()
        playlists.forEach { (name, songs) ->
            val obj = JSONObject()
            obj.put("name", name)
            val songsArray = JSONArray()
            songs.forEach { song ->
                val songObj = JSONObject()
                songObj.put("id", song.id)
                songObj.put("title", song.title)
                songObj.put("artist", song.artist)
                songObj.put("path", song.path)
                songObj.put("duration", song.duration)
                songsArray.put(songObj)
            }
            obj.put("songs", songsArray)
            array.put(obj)
        }
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString("playlists", array.toString()).apply()
    }

    private fun loadPlaylists() {
        playlists.clear()
        val json = getSharedPreferences(PREFS, MODE_PRIVATE).getString("playlists", "[]") ?: "[]"
        val array = JSONArray(json)
        for (i in 0 until array.length()) {
            val obj = array.getJSONObject(i)
            val name = obj.getString("name")
            val songsArray = obj.getJSONArray("songs")
            val songs = mutableListOf<SongItem>()
            for (j in 0 until songsArray.length()) {
                val s = songsArray.getJSONObject(j)
                songs.add(SongItem(
                    s.getLong("id"),
                    s.getString("title"),
                    s.getString("artist"),
                    s.getString("path"),
                    s.getLong("duration")
                ))
            }
            playlists.add(Pair(name, songs))
        }
    }

    override fun onResume() {
        super.onResume()
        loadPlaylists()
        setupRecyclerView()
    }
}
