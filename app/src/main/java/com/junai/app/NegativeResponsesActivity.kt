package com.junai.app

import android.os.Bundle
import android.graphics.Color
import android.graphics.Typeface
import android.text.Spannable
import android.text.SpannableString
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import androidx.lifecycle.lifecycleScope

class NegativeResponsesActivity : AppCompatActivity() {

    private val negativeList = mutableListOf<JSONObject>()
    private lateinit var adapter: RecyclerView.Adapter<*>
    private val PREFS = "negative_prefs"
    private val KEY = "negative_list"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_negative_responses)

        findViewById<Button>(R.id.backButton).setOnClickListener { finish() }

        setupRecyclerView()
        loadNegatives()
        adapter.notifyDataSetChanged()
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

                val questionSpan = SpannableString("Q: $question")
                questionSpan.setSpan(ForegroundColorSpan(Color.parseColor("#E53935")), 0, 2, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                questionSpan.setSpan(StyleSpan(Typeface.BOLD), 0, 2, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                holder.itemView.findViewById<TextView>(R.id.negativeQuestion).text = questionSpan
                holder.itemView.findViewById<TextView>(R.id.negativeAnswer).text = answer

                val input = holder.itemView.findViewById<EditText>(R.id.correctAnswerInput)

                holder.itemView.findViewById<Button>(R.id.saveCorrectButton).setOnClickListener {
                    val correct = input.text.toString().trim()
                    if (correct.isEmpty()) {
                        Toast.makeText(this@NegativeResponsesActivity, "Enter correct answer!", Toast.LENGTH_SHORT).show()
                        return@setOnClickListener
                    }

                    // Save correct answer to Room DB
                    lifecycleScope.launch(Dispatchers.IO) {
                        AppDatabase.getInstance(this@NegativeResponsesActivity)
                            .knowledgeDao()
                            .insert(KnowledgeEntity(question.lowercase().trim(), correct))
                    }

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
