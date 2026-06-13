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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class Note(val title: String, val date: String)

class NotesActivity : AppCompatActivity() {

    private val notes = mutableListOf<Note>()
    private lateinit var adapter: RecyclerView.Adapter<*>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_notes)

        findViewById<Button>(R.id.backButton).setOnClickListener {
            finish()
        }

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
                }
            }

            override fun getItemCount() = notes.size
        }

        recyclerView.adapter = adapter

        // Add note button
        findViewById<ImageButton>(R.id.addNoteButton).setOnClickListener {
            val input = findViewById<EditText>(R.id.searchNotes)
            val title = input.text.toString().trim()
            if (title.isEmpty()) return@setOnClickListener
            val date = SimpleDateFormat("MMM dd", Locale.getDefault()).format(Date())
            notes.add(0, Note("Note ${notes.size + 1}: \"$title\"", date))
            adapter.notifyItemInserted(0)
            recyclerView.scrollToPosition(0)
            input.setText("")
        }
    }
}
