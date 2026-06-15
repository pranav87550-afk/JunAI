package com.junai.app

import android.os.Bundle
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import org.json.JSONArray
import org.json.JSONObject

class NegativeResponsesActivity : AppCompatActivity() {

    private val negativeList = mutableListOf<JSONObject>()
    private lateinit var adapter: RecyclerView.Adapter<*>
    private val PREFS = "negative_prefs"
    private val KEY = "negative_list"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_negative_responses)

        findViewById<Button>(R.id.backButton).setOnClickListener { finish() }

        loadNegatives()
        setupRecyclerView()
    }

    private fun setupRecyclerView() {
        val recyclerView = findViewById<RecyclerView>(R.id.negativeRecyclerView)
        recyclerView.layoutManager = LinearLayoutManager(this)

        adapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
            override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
                val view = LayoutInflater.from(parent.context)
                    .inflate(R.layout.item_negative_response, parent, false)
                return object : RecyclerView.ViewHolder(view) {}
            }

            override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
                val item = negativeList[position]
                val question = item.getString("question")
                val answer = item.getString("answer")

                holder.itemView.findViewById<TextView>(R.id.negativeQuestion).text = "Q: $question"
                holder.itemView.findViewById<TextView>(R.id.negativeAnswer).text = "Wrong answer: $answer"

                val input = holder.itemView.findViewById<EditText>(R.id.correctAnswerInput)

                holder.itemView.findViewById<Button>(R.id.saveCorrectButton).setOnClickListener {
                    val correct = input.text.toString().trim()
                    if (correct.isEmpty()) {
                        Toast.makeText(this@NegativeResponsesActivity, "Enter correct answer!", Toast.LENGTH_SHORT).show()
                        return@setOnClickListener
                    }

                    // Save correct answer to knowledge base
                    val prefs = getSharedPreferences("knowledge_prefs", MODE_PRIVATE)
                    val json = prefs.getString("knowledge_list", "{}") ?: "{}"
                    val obj = JSONObject(json)
                    obj.put(question.lowercase().trim(), correct)
                    prefs.edit().putString("knowledge_list", obj.toString()).apply()

                    // Remove from negative list
                    negativeList.removeAt(position)
                    notifyItemRemoved(position)
                    notifyItemRangeChanged(position, negativeList.size)
                    saveNegatives()

                    Toast.makeText(this@NegativeResponsesActivity, "Correct answer saved! ✅", Toast.LENGTH_SHORT).show()
                }

                holder.itemView.findViewById<Button>(R.id.deleteNegativeButton).setOnClickListener {
                    negativeList.removeAt(position)
                    notifyItemRemoved(position)
                    notifyItemRangeChanged(position, negativeList.size)
                    saveNegatives()
                }
            }

            override fun getItemCount() = negativeList.size
        }

        recyclerView.adapter = adapter
    }

    private fun saveNegatives() {
        val array = JSONArray()
        negativeList.forEach { array.put(it) }
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(KEY, array.toString()).apply()
    }

    private fun loadNegatives() {
        negativeList.clear()
        val json = getSharedPreferences(PREFS, MODE_PRIVATE).getString(KEY, "[]") ?: "[]"
        val array = JSONArray(json)
        for (i in 0 until array.length()) {
            negativeList.add(array.getJSONObject(i))
        }
    }

    companion object {
        fun addNegative(context: android.content.Context, question: String, answer: String) {
            val prefs = context.getSharedPreferences("negative_prefs", android.content.Context.MODE_PRIVATE)
            val json = prefs.getString("negative_list", "[]") ?: "[]"
            val array = JSONArray(json)

            // Check duplicate
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                if (obj.getString("question").lowercase() == question.lowercase() &&
                    obj.getString("answer").lowercase() == answer.lowercase()) return
            }

            val obj = JSONObject()
            obj.put("question", question)
            obj.put("answer", answer)
            array.put(obj)
            prefs.edit().putString("negative_list", array.toString()).apply()
        }
    }
}
