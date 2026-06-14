package com.junai.app

import android.os.Bundle
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import org.json.JSONArray
import org.json.JSONObject

class UnansweredActivity : AppCompatActivity() {

    private val questions = mutableListOf<JSONObject>()
    private lateinit var adapter: RecyclerView.Adapter<*>
    private val PREFS = "unanswered_prefs"
    private val KEY = "questions_list"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_unanswered)

        findViewById<Button>(R.id.backButton).setOnClickListener { finish() }

        loadQuestions()
        setupRecyclerView()
    }

    private fun setupRecyclerView() {
        val recyclerView = findViewById<RecyclerView>(R.id.unansweredRecyclerView)
        recyclerView.layoutManager = LinearLayoutManager(this)

        adapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
            override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
                val view = LayoutInflater.from(parent.context)
                    .inflate(R.layout.item_unanswered, parent, false)
                return object : RecyclerView.ViewHolder(view) {}
            }

            override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
                val item = questions[position]
                val question = item.getString("question")
                val answer = item.optString("answer", "")

                holder.itemView.findViewById<TextView>(R.id.questionText).text = question
                holder.itemView.findViewById<TextView>(R.id.answerText).text =
                    if (answer.isEmpty()) "Tap to add answer" else "Answer: $answer"

                holder.itemView.setOnClickListener {
                    showAnswerDialog(position, question, answer)
                }

                holder.itemView.findViewById<ImageButton>(R.id.deleteQuestionButton).setOnClickListener {
                    questions.removeAt(position)
                    notifyItemRemoved(position)
                    notifyItemRangeChanged(position, questions.size)
                    saveQuestions()
                }
            }

            override fun getItemCount() = questions.size
        }

        recyclerView.adapter = adapter
    }

    private fun showAnswerDialog(position: Int, question: String, currentAnswer: String) {
        val input = EditText(this).apply {
            hint = "Type answer here..."
            setText(currentAnswer)
            setTextColor(android.graphics.Color.WHITE)
            setHintTextColor(android.graphics.Color.GRAY)
            setPadding(32, 24, 32, 24)
        }

        AlertDialog.Builder(this)
            .setTitle("Answer: $question")
            .setView(input)
            .setPositiveButton("Done") { _, _ ->
                val answer = input.text.toString().trim()
                if (answer.isNotEmpty()) {
                    questions[position].put("answer", answer)
                    adapter.notifyItemChanged(position)
                    saveQuestions()
                    saveToKnowledge(question, answer)
                    android.widget.Toast.makeText(this, "Answer saved! Jun will remember this.", android.widget.Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun saveToKnowledge(question: String, answer: String) {
        val prefs = getSharedPreferences("knowledge_prefs", MODE_PRIVATE)
        val json = prefs.getString("knowledge_list", "{}") ?: "{}"
        val obj = JSONObject(json)
        obj.put(question.lowercase().trim(), answer)
        prefs.edit().putString("knowledge_list", obj.toString()).apply()
    }

    private fun saveQuestions() {
        val array = JSONArray()
        questions.forEach { array.put(it) }
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(KEY, array.toString()).apply()
    }

    private fun loadQuestions() {
        questions.clear()
        val json = getSharedPreferences(PREFS, MODE_PRIVATE).getString(KEY, "[]") ?: "[]"
        val array = JSONArray(json)
        for (i in 0 until array.length()) {
            questions.add(array.getJSONObject(i))
        }
    }

    companion object {
        fun addQuestion(context: android.content.Context, question: String) {
            val prefs = context.getSharedPreferences("unanswered_prefs", android.content.Context.MODE_PRIVATE)
            val json = prefs.getString("questions_list", "[]") ?: "[]"
            val array = JSONArray(json)
            
            // Check if question already exists
            for (i in 0 until array.length()) {
                if (array.getJSONObject(i).getString("question").lowercase() == question.lowercase()) return
            }
            
            val obj = JSONObject()
            obj.put("question", question)
            obj.put("answer", "")
            array.put(obj)
            prefs.edit().putString("questions_list", array.toString()).apply()
        }

        fun getAnswer(context: android.content.Context, question: String): String? {
            val prefs = context.getSharedPreferences("knowledge_prefs", android.content.Context.MODE_PRIVATE)
            val json = prefs.getString("knowledge_list", "{}") ?: "{}"
            val obj = JSONObject(json)
            return if (obj.has(question.lowercase().trim())) obj.getString(question.lowercase().trim()) else null
        }
    }
}
