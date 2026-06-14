package com.junai.app

import android.os.Bundle
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class Note(val title: String, val date: String)

class NotesActivity : AppCompatActivity() {

    private val notes = mutableListOf<Note>()
    private lateinit var adapter: RecyclerView.Adapter<*>
    private val PREFS = "notes_prefs"
    private val KEY = "notes_list"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_notes)

        findViewById<Button>(R.id.backButton).setOnClickListener { finish() }

        loadNotes()

        val recyclerView = findViewById<RecyclerView>(R.id.notesRecyclerView)
        recyclerView.layoutManager = LinearLayoutManager(this)

        adapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
            override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
                val view = LayoutInflater.from(parent.context)
                    .inflate(R.layout.item_note, parent, false)
                return object : RecyclerView.ViewHolder(view) {}
            }

            override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
                holder.itemView.findViewById<TextView>(R.id.noteTitle).text = notes[position].title
                holder.itemView.findViewById<TextView>(R.id.noteDate).text = notes[position].date
                holder.itemView.findViewById<ImageButton>(R.id.deleteNoteButton).setOnClickListener {
                    notes.removeAt(position)
                    notifyItemRemoved(position)
                    notifyItemRangeChanged(position, notes.size)
                    saveNotes()
                }
            }

            override fun getItemCount() = notes.size
        }

        recyclerView.adapter = adapter

        findViewById<ImageButton>(R.id.addNoteButton).setOnClickListener {
            val input = findViewById<EditText>(R.id.searchNotes)
            val title = input.text.toString().trim()
            if (title.isEmpty()) return@setOnClickListener
            val date = SimpleDateFormat("MMM dd", Locale.getDefault()).format(Date())
            val note = Note("Note ${notes.size + 1}: \"$title\"", date)
            notes.add(0, note)
            adapter.notifyItemInserted(0)
            recyclerView.scrollToPosition(0)
            input.setText("")
            saveNotes()
        }
    }

    private fun saveNotes() {
        val array = JSONArray()
        notes.forEach {
            val obj = JSONObject()
            obj.put("title", it.title)
            obj.put("date", it.date)
            array.put(obj)
        }
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(KEY, array.toString()).apply()
    }

    private fun loadNotes() {
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        val json = prefs.getString(KEY, "[]") ?: "[]"
        val array = JSONArray(json)
        for (i in 0 until array.length()) {
            val obj = array.getJSONObject(i)
            notes.add(Note(obj.getString("title"), obj.getString("date")))
        }
    }

    companion object {
        fun clearNotes(context: android.content.Context) {
            context.getSharedPreferences("notes_prefs", MODE_PRIVATE).edit().clear().apply()
        }
    }
}
