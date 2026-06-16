package com.junai.app

import android.os.Bundle
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class LearningCenterActivity : AppCompatActivity() {

    private lateinit var learningRepo: LearningRepository
    private lateinit var tabPending: TextView
    private lateinit var tabKnowledge: TextView
    private lateinit var tabCommands: TextView
    private lateinit var tabAnalytics: TextView
    private lateinit var contentArea: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_learning_center)

        learningRepo = LearningRepository(this)

        findViewById<Button>(R.id.backButton).setOnClickListener { finish() }

        tabPending = findViewById(R.id.tabPending)
        tabKnowledge = findViewById(R.id.tabKnowledge)
        tabCommands = findViewById(R.id.tabCommands)
        tabAnalytics = findViewById(R.id.tabAnalytics)
        contentArea = findViewById(R.id.contentArea)

        tabPending.setOnClickListener { showPendingTab() }
        tabKnowledge.setOnClickListener { showKnowledgeTab() }
        tabCommands.setOnClickListener { showCommandsTab() }
        tabAnalytics.setOnClickListener { showAnalyticsTab() }

        // Default tab
        showPendingTab()
    }

    // ==================== PENDING TAB ====================
    private fun showPendingTab() {
        setActiveTab(tabPending)
        contentArea.removeAllViews()

        val recyclerView = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@LearningCenterActivity)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.MATCH_PARENT
            )
        }
        contentArea.addView(recyclerView)

        CoroutineScope(Dispatchers.IO).launch {
            val items = learningRepo.getPendingItems()
            withContext(Dispatchers.Main) {
                if (items.isEmpty()) {
                    contentArea.removeAllViews()
                    val empty = TextView(this@LearningCenterActivity).apply {
                        text = "🎉 No pending items!\nJun is learning well."
                        setTextColor(android.graphics.Color.WHITE)
                        textSize = 16f
                        gravity = android.view.Gravity.CENTER
                        setPadding(32, 64, 32, 32)
                    }
                    contentArea.addView(empty)
                    return@withContext
                }

                recyclerView.adapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
                    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
                        val view = LayoutInflater.from(parent.context)
                            .inflate(R.layout.item_learning_pending, parent, false)
                        return object : RecyclerView.ViewHolder(view) {}
                    }

                    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
                        val item = items[position]
                        holder.itemView.findViewById<TextView>(R.id.pendingQuestion).text = item.question
                        holder.itemView.findViewById<TextView>(R.id.pendingIntent).text = "Intent: ${item.suggestedIntent}"
                        holder.itemView.findViewById<TextView>(R.id.pendingCategory).text = "Category: ${item.suggestedCategory}"
                        holder.itemView.findViewById<TextView>(R.id.pendingReason).text = "Reason: ${item.failureReason}"

                        // Train as Knowledge
                        holder.itemView.findViewById<Button>(R.id.btnTrainKnowledge).setOnClickListener {
                            showTrainKnowledgeDialog(item)
                        }

                        // Train as Command
                        holder.itemView.findViewById<Button>(R.id.btnTrainCommand).setOnClickListener {
                            showTrainCommandDialog(item)
                        }

                        // Ignore
                        holder.itemView.findViewById<Button>(R.id.btnIgnore).setOnClickListener {
                            CoroutineScope(Dispatchers.IO).launch {
                                learningRepo.getAllLearningItems()
                                    .firstOrNull { it.id == item.id }?.let {
                                        learningRepo.getPendingItems()
                                    }
                            }
                            showPendingTab()
                        }
                    }

                    override fun getItemCount() = items.size
                }
            }
        }
    }

    // ==================== KNOWLEDGE TAB ====================
    private fun showKnowledgeTab() {
        setActiveTab(tabKnowledge)
        contentArea.removeAllViews()

        val recyclerView = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@LearningCenterActivity)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.MATCH_PARENT
            )
        }
        contentArea.addView(recyclerView)

        CoroutineScope(Dispatchers.IO).launch {
            val items = learningRepo.getAllKnowledge()
            withContext(Dispatchers.Main) {
                if (items.isEmpty()) {
                    contentArea.removeAllViews()
                    val empty = TextView(this@LearningCenterActivity).apply {
                        text = "📚 No knowledge yet!\nTeach Jun using Q=A format."
                        setTextColor(android.graphics.Color.WHITE)
                        textSize = 16f
                        gravity = android.view.Gravity.CENTER
                        setPadding(32, 64, 32, 32)
                    }
                    contentArea.addView(empty)
                    return@withContext
                }

                recyclerView.adapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
                    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
                        val view = LayoutInflater.from(parent.context)
                            .inflate(R.layout.item_learning_knowledge, parent, false)
                        return object : RecyclerView.ViewHolder(view) {}
                    }

                    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
                        val item = items[position]
                        holder.itemView.findViewById<TextView>(R.id.knowledgeQuestion).text = item.question
                        holder.itemView.findViewById<TextView>(R.id.knowledgeAnswer).text = item.answer
                        holder.itemView.findViewById<TextView>(R.id.knowledgeCategory).text = "📁 ${item.category} • Asked: ${item.timesAsked}x"
                    }

                    override fun getItemCount() = items.size
                }
            }
        }
    }

    // ==================== COMMANDS TAB ====================
    private fun showCommandsTab() {
        setActiveTab(tabCommands)
        contentArea.removeAllViews()

        val recyclerView = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@LearningCenterActivity)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.MATCH_PARENT
            )
        }
        contentArea.addView(recyclerView)

        CoroutineScope(Dispatchers.IO).launch {
            val items = learningRepo.getAllCommands()
            withContext(Dispatchers.Main) {
                if (items.isEmpty()) {
                    contentArea.removeAllViews()
                    val empty = TextView(this@LearningCenterActivity).apply {
                        text = "⚡ No commands yet!\nCommands will appear here."
                        setTextColor(android.graphics.Color.WHITE)
                        textSize = 16f
                        gravity = android.view.Gravity.CENTER
                        setPadding(32, 64, 32, 32)
                    }
                    contentArea.addView(empty)
                    return@withContext
                }

                recyclerView.adapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
                    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
                        val view = LayoutInflater.from(parent.context)
                            .inflate(R.layout.item_learning_command, parent, false)
                        return object : RecyclerView.ViewHolder(view) {}
                    }

                    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
                        val item = items[position]
                        holder.itemView.findViewById<TextView>(R.id.commandPhrase).text = item.phrase
                        holder.itemView.findViewById<TextView>(R.id.commandIntent).text = "→ ${item.intent}"
                        holder.itemView.findViewById<TextView>(R.id.commandTarget).text = if (item.target.isNotEmpty()) "Target: ${item.target}" else ""
                        holder.itemView.findViewById<TextView>(R.id.commandUsed).text = "Used: ${item.timesUsed}x"
                    }

                    override fun getItemCount() = items.size
                }
            }
        }
    }

    // ==================== ANALYTICS TAB ====================
    private fun showAnalyticsTab() {
        setActiveTab(tabAnalytics)
        contentArea.removeAllViews()

        CoroutineScope(Dispatchers.IO).launch {
            val stats = learningRepo.getStats()
            val failures = learningRepo.getFailureLog()
            withContext(Dispatchers.Main) {
                val scroll = ScrollView(this@LearningCenterActivity).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.MATCH_PARENT
                    )
                }
                val inner = LinearLayout(this@LearningCenterActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(16, 16, 16, 16)
                }
                scroll.addView(inner)
                contentArea.addView(scroll)

                // Stats cards
                addStatCard(inner, "🧠 Knowledge Learned", "${stats?.knowledgeLearned ?: 0}")
                addStatCard(inner, "⚡ Commands Learned", "${stats?.commandsLearned ?: 0}")
                addStatCard(inner, "🎯 Skills Learned", "${stats?.skillsLearned ?: 0}")
                addStatCard(inner, "❌ Failed Queries", "${stats?.failedQueries ?: 0}")

                // Recent failures
                val failTitle = TextView(this@LearningCenterActivity).apply {
                    text = "Recent Failures"
                    setTextColor(android.graphics.Color.parseColor("#E53935"))
                    textSize = 16f
                    setTypeface(null, android.graphics.Typeface.BOLD)
                    setPadding(0, 24, 0, 8)
                }
                inner.addView(failTitle)

                failures.take(10).forEach { log ->
                    val card = TextView(this@LearningCenterActivity).apply {
                        text = "❌ ${log.question}\n→ ${log.failureReason} (${log.confidence.toInt()}%)"
                        setTextColor(android.graphics.Color.WHITE)
                        textSize = 13f
                        setBackgroundColor(android.graphics.Color.parseColor("#1A1A1A"))
                        setPadding(16, 12, 16, 12)
                        val params = LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT
                        )
                        params.setMargins(0, 4, 0, 4)
                        layoutParams = params
                    }
                    inner.addView(card)
                }
            }
        }
    }

    private fun addStatCard(parent: LinearLayout, title: String, value: String) {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(android.graphics.Color.parseColor("#1A1A1A"))
            setPadding(16, 16, 16, 16)
            val params = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            params.setMargins(0, 4, 0, 4)
            layoutParams = params
        }

        val titleView = TextView(this).apply {
            text = title
            setTextColor(android.graphics.Color.WHITE)
            textSize = 14f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        val valueView = TextView(this).apply {
            text = value
            setTextColor(android.graphics.Color.parseColor("#E53935"))
            textSize = 18f
            setTypeface(null, android.graphics.Typeface.BOLD)
        }

        card.addView(titleView)
        card.addView(valueView)
        parent.addView(card)
    }

    // ==================== DIALOGS ====================
    private fun showTrainKnowledgeDialog(item: LearningItem) {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 16, 32, 16)
        }

        val answerInput = EditText(this).apply {
            hint = "Type answer..."
            setTextColor(android.graphics.Color.WHITE)
            setHintTextColor(android.graphics.Color.GRAY)
            setText("")
        }

        val categoryInput = EditText(this).apply {
            hint = "Category (e.g. Technology)"
            setTextColor(android.graphics.Color.WHITE)
            setHintTextColor(android.graphics.Color.GRAY)
            setText(item.suggestedCategory)
        }

        val aliasInput = EditText(this).apply {
            hint = "Aliases (separate by |)"
            setTextColor(android.graphics.Color.WHITE)
            setHintTextColor(android.graphics.Color.GRAY)
        }

        layout.addView(TextView(this).apply {
            text = "Question: ${item.question}"
            setTextColor(android.graphics.Color.WHITE)
            setPadding(0, 0, 0, 8)
        })
        layout.addView(answerInput)
        layout.addView(categoryInput)
        layout.addView(aliasInput)

        AlertDialog.Builder(this, R.style.DarkDialog)
            .setTitle("Train as Knowledge")
            .setView(layout)
            .setPositiveButton("Save") { _, _ ->
                val answer = answerInput.text.toString().trim()
                val category = categoryInput.text.toString().trim().ifEmpty { "General" }
                val aliases = aliasInput.text.toString().trim()
                    .split("|").map { it.trim() }.filter { it.isNotEmpty() }

                if (answer.isNotEmpty()) {
                    CoroutineScope(Dispatchers.IO).launch {
                        learningRepo.trainKnowledge(item.question, answer, category, aliases)
                    }
                    Toast.makeText(this, "Knowledge saved! ✅", Toast.LENGTH_SHORT).show()
                    showPendingTab()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showTrainCommandDialog(item: LearningItem) {
        val intents = arrayOf(
            "OPEN_APP", "CALL_CONTACT", "PLAY_MUSIC", "PAUSE_MUSIC",
            "SET_REMINDER", "CREATE_NOTE", "SEARCH_WEB", "SHOW_SETTINGS",
            "TELL_TIME", "TELL_DATE", "TELL_BATTERY", "UNKNOWN"
        )
        var selectedIntent = item.suggestedIntent.ifEmpty { "UNKNOWN" }

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 16, 32, 16)
        }

        val targetInput = EditText(this).apply {
            hint = "Target (e.g. Chrome, Mom)"
            setTextColor(android.graphics.Color.WHITE)
            setHintTextColor(android.graphics.Color.GRAY)
        }

        layout.addView(TextView(this).apply {
            text = "Phrase: ${item.question}"
            setTextColor(android.graphics.Color.WHITE)
            setPadding(0, 0, 0, 8)
        })
        layout.addView(TextView(this).apply {
            text = "Select Intent:"
            setTextColor(android.graphics.Color.GRAY)
            setPadding(0, 8, 0, 4)
        })

        val spinner = Spinner(this).apply {
            adapter = ArrayAdapter(this@LearningCenterActivity,
                android.R.layout.simple_spinner_dropdown_item, intents)
            val idx = intents.indexOf(selectedIntent)
            if (idx >= 0) setSelection(idx)
            onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: android.view.View?, position: Int, id: Long) {
                    selectedIntent = intents[position]
                }
                override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
            }
        }
        layout.addView(spinner)
        layout.addView(targetInput)

        AlertDialog.Builder(this, R.style.DarkDialog)
            .setTitle("Train as Command")
            .setView(layout)
            .setPositiveButton("Save") { _, _ ->
                val target = targetInput.text.toString().trim()
                CoroutineScope(Dispatchers.IO).launch {
                    learningRepo.trainCommand(item.question, selectedIntent, target)
                }
                Toast.makeText(this, "Command saved! ✅", Toast.LENGTH_SHORT).show()
                showPendingTab()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun setActiveTab(activeTab: TextView) {
        listOf(tabPending, tabKnowledge, tabCommands, tabAnalytics).forEach {
            it.setBackgroundColor(android.graphics.Color.parseColor("#1A1A1A"))
            it.setTextColor(android.graphics.Color.GRAY)
        }
        activeTab.setBackgroundColor(android.graphics.Color.parseColor("#E53935"))
        activeTab.setTextColor(android.graphics.Color.WHITE)
    }
}
