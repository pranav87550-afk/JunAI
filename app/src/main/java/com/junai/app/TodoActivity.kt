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
import android.widget.Toast
import androidx.appcompat.app.AlertDialog

class TodoActivity : AppCompatActivity() {

    private val todos = mutableListOf<String>()
    private lateinit var adapter: RecyclerView.Adapter<*>
    private lateinit var pendingCount: TextView
    private val PREFS = "todo_prefs"
    private val KEY = "todo_list"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_todo)

        findViewById<Button>(R.id.backButton).setOnClickListener { finish() }

        loadTodos()

        pendingCount = findViewById(R.id.pendingCount)
        val recyclerView = findViewById<RecyclerView>(R.id.todoRecyclerView)
        recyclerView.layoutManager = LinearLayoutManager(this)

        adapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
            override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
                val view = LayoutInflater.from(parent.context)
                    .inflate(R.layout.item_todo, parent, false)
                return object : RecyclerView.ViewHolder(view) {}
            }

            override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
                holder.itemView.findViewById<TextView>(R.id.todoText).text = todos[position]
                holder.itemView.findViewById<ImageButton>(R.id.deleteTodoButton).setOnClickListener {
                    todos.removeAt(position)
                    notifyItemRemoved(position)
                    notifyItemRangeChanged(position, todos.size)
                    updateCount()
                    saveTodos()
                }
            }

            override fun getItemCount() = todos.size
        }

        recyclerView.adapter = adapter

        findViewById<ImageButton>(R.id.addTodoButton).setOnClickListener {
            val input = findViewById<EditText>(R.id.todoInput)
            val text = input.text.toString().trim()
            if (text.isEmpty()) return@setOnClickListener
            todos.add(0, text)
            adapter.notifyItemInserted(0)
            recyclerView.scrollToPosition(0)
            input.setText("")
            updateCount()
            saveTodos()
        }

        findViewById<Button>(R.id.clearAllButton).setOnClickListener {
    if (todos.isEmpty()) {
        Toast.makeText(this, "No tasks to clear!", Toast.LENGTH_SHORT).show()
        return@setOnClickListener
    }
    AlertDialog.Builder(this)
        .setTitle("Clear All Tasks")
        .setMessage("Are you sure? All ${todos.size} tasks will be deleted!")
        .setPositiveButton("Yes, Delete All") { _, _ ->
            val size = todos.size
            todos.clear()
            adapter.notifyItemRangeRemoved(0, size)
            updateCount()
            saveTodos()
            Toast.makeText(this, "All tasks cleared!", Toast.LENGTH_SHORT).show()
        }
        .setNegativeButton("Cancel", null)
        .show()
        }

        updateCount()
    }

    private fun updateCount() {
        pendingCount.text = "You have ${todos.size} pending tasks"
    }

    private fun saveTodos() {
        val array = JSONArray()
        todos.forEach { array.put(it) }
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(KEY, array.toString()).apply()
    }

    private fun loadTodos() {
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        val json = prefs.getString(KEY, "[]") ?: "[]"
        val array = JSONArray(json)
        for (i in 0 until array.length()) {
            todos.add(array.getString(i))
        }
    }

    companion object {
        fun clearTodos(context: android.content.Context) {
            context.getSharedPreferences("todo_prefs", MODE_PRIVATE).edit().clear().apply()
        }
    }
}
