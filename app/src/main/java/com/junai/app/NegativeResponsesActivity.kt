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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.lifecycle.lifecycleScope

// IMPROVEMENT (Phase 1g): this used to keep its own, completely separate
// SharedPreferences JSON list of flagged-bad Q&A pairs. Saving a correction
// wrote a brand-new KnowledgeItem via trainKnowledge() without touching (or
// even knowing about) whichever row Learning Center's Knowledge tab already
// had for that same question, and deleting from either screen had zero
// effect on the other — two completely disconnected copies of "the same
// idea." Now this screen reads/writes the actual knowledge_items table
// directly (via LearningRepository), filtered to needsCorrection = true —
// see KnowledgeItem.needsCorrection's doc comment. A flagged item and its
// Learning Center knowledge row are now literally the same row: editing or
// deleting it here is visible there, and vice versa.
class NegativeResponsesActivity : AppCompatActivity() {

    private val negativeList = mutableListOf<KnowledgeItem>()
    private lateinit var adapter: RecyclerView.Adapter<*>
    private lateinit var learningRepo: LearningRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_negative_responses)
        learningRepo = LearningRepository(this)

        findViewById<Button>(R.id.backButton).setOnClickListener { finish() }

        setupRecyclerView()
        loadNegatives()
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

                val questionSpan = SpannableString("Q: ${item.question}")
                questionSpan.setSpan(ForegroundColorSpan(Color.parseColor("#E53935")), 0, 2, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                questionSpan.setSpan(StyleSpan(Typeface.BOLD), 0, 2, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                holder.itemView.findViewById<TextView>(R.id.negativeQuestion).text = questionSpan
                holder.itemView.findViewById<TextView>(R.id.negativeAnswer).text = item.answer

                val input = holder.itemView.findViewById<EditText>(R.id.correctAnswerInput)

                holder.itemView.findViewById<Button>(R.id.saveCorrectButton).setOnClickListener {
                    val correct = input.text.toString().trim()
                    if (correct.isEmpty()) {
                        Toast.makeText(this@NegativeResponsesActivity, "Enter correct answer!", Toast.LENGTH_SHORT).show()
                        return@setOnClickListener
                    }

                    // Updates the SAME row (answer + needsCorrection=false) —
                    // this is what makes it show up correctly in Learning
                    // Center's Knowledge tab immediately, no duplicate row.
                    lifecycleScope.launch(Dispatchers.IO) {
                        learningRepo.resolveCorrection(item.id, correct)
                        withContext(Dispatchers.Main) {
                            val pos = negativeList.indexOfFirst { it.id == item.id }
                            if (pos != -1) {
                                negativeList.removeAt(pos)
                                notifyItemRemoved(pos)
                                notifyItemRangeChanged(pos, negativeList.size)
                            }
                            Toast.makeText(this@NegativeResponsesActivity, "Correct answer saved! ✅", Toast.LENGTH_SHORT).show()
                        }
                    }
                }

                holder.itemView.findViewById<Button>(R.id.deleteNegativeButton).setOnClickListener {
                    // Deletes the row entirely — same DAO call Learning
                    // Center's Knowledge tab delete button uses, so it
                    // disappears from both screens together, plus the
                    // old backward-compat `knowledge` table entry.
                    lifecycleScope.launch(Dispatchers.IO) {
                        AppDatabase.getInstance(this@NegativeResponsesActivity).learningDao().deleteKnowledge(item)
                        AppDatabase.getInstance(this@NegativeResponsesActivity).knowledgeDao().delete(item.question)
                        withContext(Dispatchers.Main) {
                            val pos = negativeList.indexOfFirst { it.id == item.id }
                            if (pos != -1) {
                                negativeList.removeAt(pos)
                                notifyItemRemoved(pos)
                                notifyItemRangeChanged(pos, negativeList.size)
                            }
                        }
                    }
                }
            }

            override fun getItemCount() = negativeList.size
        }

        recyclerView.adapter = adapter
    }

    private fun loadNegatives() {
        lifecycleScope.launch(Dispatchers.IO) {
            val items = learningRepo.getItemsNeedingCorrection()
            withContext(Dispatchers.Main) {
                negativeList.clear()
                negativeList.addAll(items)
                adapter.notifyDataSetChanged()
            }
        }
    }

    companion object {
        // IMPROVEMENT (Phase 1g): fire-and-forget is intentional here, same
        // as this codebase's other thumbs-up/down feedback calls — the
        // caller (MainActivity's ChatActionListener) isn't a coroutine
        // context, and the user's Toast confirmation doesn't need to wait
        // on this. flagNeedsCorrection() itself handles the "does this
        // question already have a row" check, so no separate duplicate
        // check is needed here anymore.
        fun addNegative(context: android.content.Context, question: String, answer: String) {
            CoroutineScope(Dispatchers.IO).launch {
                LearningRepository(context).flagNeedsCorrection(question, answer)
            }
        }
    }
}
