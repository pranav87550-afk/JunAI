package com.junai.app

import android.graphics.Paint
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
import android.widget.Toast
import androidx.appcompat.app.AlertDialog

data class TodoItem(val text: String, var isCompleted: Boolean = false)

class TodoActivity : AppCompatActivity() {

    private val todos = mutableListOf<TodoItem>()
    private lateinit var adapter: RecyclerView.Adapter<*>
    private lateinit var pendingCount: TextView
    private val PREFS = "todo_prefs"
    private val KEY = "todo_list_v2"

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
                val item = todos[position]
                val textView = holder.itemView.findViewById<TextView>(R.id.todoText)
                val circleBtn = holder.itemView.findViewById<ImageButton>(R.id.checkCircleButton)

                textView.text = item.text
                applyCompletedStyle(textView, circleBtn, item.isCompleted)

                circleBtn.setOnClickListener {
                    val pos = holder.adapterPosition
                    if (pos == RecyclerView.NO_ID.toInt()) return@setOnClickListener
                    todos[pos].isCompleted = !todos[pos].isCompleted
                    applyCompletedStyle(textView, circleBtn, todos[pos].isCompleted)
                    updateCount()
                    saveTodos()
                }

                holder.itemView.findViewById<ImageButton>(R.id.deleteTodoButton).setOnClickListener {
                    val pos = holder.adapterPosition
                    if (pos == RecyclerView.NO_ID.toInt()) return@setOnClickListener
                    todos.removeAt(pos)
                    notifyItemRemoved(pos)
                    notifyItemRangeChanged(pos, todos.size)
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
            todos.add(0, TodoItem(text))
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

    private fun applyCompletedStyle(textView: TextView, circleBtn: ImageButton, completed: Boolean) {
        if (completed) {
            // Strikethrough + green text
            textView.paintFlags = textView.paintFlags or Paint.STRIKE_THRU_TEXT_FLAG
            textView.setTextColor(0xFF4CAF50.toInt())
            // Green filled circle
            circleBtn.setBackgroundResource(R.drawable.todo_circle_checked)
        } else {
            // Normal — remove strikethrough
            textView.paintFlags = textView.paintFlags and Paint.STRIKE_THRU_TEXT_FLAG.inv()
            textView.setTextColor(0xFFFFFFFF.toInt())
            // Red stroke empty circle
            circleBtn.setBackgroundResource(R.drawable.circle_icon_badge)
        }
    }

    private fun updateCount() {
        val pending = todos.count { !it.isCompleted }
        pendingCount.text = "You have $pending pending tasks"
    }

    private fun saveTodos() {
        val array = JSONArray()
        todos.forEach { item ->
            val obj = JSONObject()
            obj.put("text", item.text)
            obj.put("completed", item.isCompleted)
            array.put(obj)
        }
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(KEY, array.toString()).apply()
    }

    private fun loadTodos() {
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)

        // Migrate old plain-string format if needed
        val oldJson = prefs.getString("todo_list", null)
        if (oldJson != null && prefs.getString(KEY, null) == null) {
            val oldArray = JSONArray(oldJson)
            for (i in 0 until oldArray.length()) {
                todos.add(TodoItem(oldArray.getString(i)))
            }
            saveTodos()
            return
        }

        val json = prefs.getString(KEY, "[]") ?: "[]"
        val array = JSONArray(json)
        for (i in 0 until array.length()) {
            val obj = array.getJSONObject(i)
            todos.add(TodoItem(
                text = obj.getString("text"),
                isCompleted = obj.optBoolean("completed", false)
            ))
        }
    }

    companion object {
        fun clearTodos(context: android.content.Context) {
            context.getSharedPreferences("todo_prefs", MODE_PRIVATE).edit().clear().apply()
        }
    }
}
