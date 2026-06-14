package com.junai.app

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageButton
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import org.json.JSONArray
import org.json.JSONObject

class PlaylistActivity : AppCompatActivity() {

    private var playlistIndex = 0
    private val playlistSongs = mutableListOf<SongItem>()
    private val allSongs = mutableListOf<SongItem>()
    private lateinit var adapter: RecyclerView.Adapter<*>
    private var playlistName = ""
    private val PREFS = "music_prefs"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_playlist)

        playlistIndex = intent.getIntExtra("playlistIndex", 0)
        val allSongsList = intent.getSerializableExtra("allSongs") as? ArrayList<SongItem>
        allSongs.addAll(allSongsList ?: emptyList())

        loadPlaylist()

        findViewById<Button>(R.id.backButton).setOnClickListener { finish() }

        findViewById<ImageButton>(R.id.editPlaylistName).setOnClickListener {
            showEditNameDialog()
        }

        findViewById<Button>(R.id.addSongsButton).setOnClickListener {
            showAddSongsDialog()
        }

        setupRecyclerView()
    }

    private fun loadPlaylist() {
        val json = getSharedPreferences(PREFS, MODE_PRIVATE).getString("playlists", "[]") ?: "[]"
        val array = JSONArray(json)
        if (playlistIndex < array.length()) {
            val obj = array.getJSONObject(playlistIndex)
            playlistName = obj.getString("name")
            findViewById<TextView>(R.id.playlistName).text = playlistName
            val songsArray = obj.getJSONArray("songs")
            playlistSongs.clear()
            for (i in 0 until songsArray.length()) {
                val s = songsArray.getJSONObject(i)
                playlistSongs.add(SongItem(
                    s.getLong("id"),
                    s.getString("title"),
                    s.getString("artist"),
                    s.getString("path"),
                    s.getLong("duration")
                ))
            }
        }
    }

    private fun setupRecyclerView() {
        val recyclerView = findViewById<RecyclerView>(R.id.playlistSongsRecyclerView)
        recyclerView.layoutManager = LinearLayoutManager(this)

        adapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
            override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
                val view = LayoutInflater.from(parent.context)
                    .inflate(R.layout.item_song, parent, false)
                return object : RecyclerView.ViewHolder(view) {}
            }

            override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
                val song = playlistSongs[position]
                holder.itemView.findViewById<TextView>(R.id.songItemTitle).text = song.title
                holder.itemView.findViewById<TextView>(R.id.songItemArtist).text = song.artist
                holder.itemView.findViewById<ImageButton>(R.id.songItemDelete).apply {
                    visibility = android.view.View.VISIBLE
                    setOnClickListener {
                        playlistSongs.removeAt(position)
                        notifyItemRemoved(position)
                        notifyItemRangeChanged(position, playlistSongs.size)
                        savePlaylist()
                    }
                }
                holder.itemView.setOnClickListener {
                    val intent = Intent(this@PlaylistActivity, MusicPlayerActivity::class.java)
                    intent.putExtra("songs", ArrayList(playlistSongs))
                    intent.putExtra("index", position)
                    intent.putExtra("isPlaylist", true)
                    intent.putExtra("playlistName", playlistName)
                    startActivity(intent)
                }
            }

            override fun getItemCount() = playlistSongs.size
        }

        recyclerView.adapter = adapter
    }

    private fun showEditNameDialog() {
        val input = android.widget.EditText(this).apply {
            setText(playlistName)
            setTextColor(android.graphics.Color.WHITE)
            setPadding(32, 24, 32, 24)
        }
        AlertDialog.Builder(this)
            .setTitle("Rename Playlist")
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                val newName = input.text.toString().trim()
                if (newName.isNotEmpty()) {
                    playlistName = newName
                    findViewById<TextView>(R.id.playlistName).text = playlistName
                    savePlaylist()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showAddSongsDialog() {
        val sortedSongs = allSongs.sortedBy { it.title }
        val selected = BooleanArray(sortedSongs.size)
        val names = sortedSongs.map { it.title }.toTypedArray()

        AlertDialog.Builder(this)
            .setTitle("Add Songs")
            .setMultiChoiceItems(names, selected) { _, which, isChecked ->
                selected[which] = isChecked
            }
            .setPositiveButton("Add") { _, _ ->
                sortedSongs.forEachIndexed { index, song ->
                    if (selected[index] && !playlistSongs.contains(song)) {
                        playlistSongs.add(song)
                    }
                }
                playlistSongs.sortBy { it.title }
                adapter.notifyDataSetChanged()
                savePlaylist()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun savePlaylist() {
        val json = getSharedPreferences(PREFS, MODE_PRIVATE).getString("playlists", "[]") ?: "[]"
        val array = JSONArray(json)
        val obj = JSONObject()
        obj.put("name", playlistName)
        val songsArray = JSONArray()
        playlistSongs.forEach { song ->
            val songObj = JSONObject()
            songObj.put("id", song.id)
            songObj.put("title", song.title)
            songObj.put("artist", song.artist)
            songObj.put("path", song.path)
            songObj.put("duration", song.duration)
            songsArray.put(songObj)
        }
        obj.put("songs", songsArray)
        array.put(playlistIndex, obj)
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString("playlists", array.toString()).apply()
    }
}
