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
    private lateinit var tabExecute: TextView
    private lateinit var tabAnalytics: TextView
    private lateinit var contentArea: LinearLayout
    private var activeTab = "PENDING"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_learning_center)

        learningRepo = LearningRepository(this)

        findViewById<Button>(R.id.backButton).setOnClickListener { finish() }

        tabPending = findViewById(R.id.tabPending)
        tabKnowledge = findViewById(R.id.tabKnowledge)
        tabCommands = findViewById(R.id.tabCommands)
        tabExecute = findViewById(R.id.tabExecute)
        tabAnalytics = findViewById(R.id.tabAnalytics)
        contentArea = findViewById(R.id.contentArea)

        tabPending.setOnClickListener { activeTab = "PENDING"; showPendingTab() }
        tabKnowledge.setOnClickListener { activeTab = "KNOWLEDGE"; showKnowledgeTab() }
        tabCommands.setOnClickListener { activeTab = "COMMANDS"; showCommandsTab() }
        tabExecute.setOnClickListener { activeTab = "EXECUTE"; showExecuteTab() }
        tabAnalytics.setOnClickListener {
            activeTab = "ANALYTICS"
            CoroutineScope(Dispatchers.IO).launch {
                AppDatabase.getInstance(this@LearningCenterActivity)
                    .learningDao().refreshStatistics()
                withContext(Dispatchers.Main) { showAnalyticsTab() }
            }
        }

        showPendingTab()
    }

    override fun onResume() {
        super.onResume()
        when (activeTab) {
            "PENDING" -> showPendingTab()
            "KNOWLEDGE" -> showKnowledgeTab()
            "COMMANDS" -> showCommandsTab()
            "EXECUTE" -> showExecuteTab()
            "ANALYTICS" -> showAnalyticsTab()
        }
    }

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
                    contentArea.addView(TextView(this@LearningCenterActivity).apply {
                        text = "Koi pending item nahi!\nJun sikh rahi hai! 🎉"
                        setTextColor(android.graphics.Color.WHITE)
                        textSize = 16f
                        gravity = android.view.Gravity.CENTER
                        setPadding(32, 64, 32, 32)
                    })
                    return@withContext
                }

                recyclerView.adapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
                    val itemList = items.toMutableList()

                    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
                        val view = LayoutInflater.from(parent.context)
                            .inflate(R.layout.item_learning_pending, parent, false)
                        return object : RecyclerView.ViewHolder(view) {}
                    }

                    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
                        val item = itemList[position]
                        holder.itemView.findViewById<TextView>(R.id.pendingQuestion).text = item.question
                        holder.itemView.findViewById<TextView>(R.id.pendingIntent).text = "Intent: ${item.suggestedIntent}"
                        holder.itemView.findViewById<TextView>(R.id.pendingCategory).text = "Category: ${item.suggestedCategory}"
                        holder.itemView.findViewById<TextView>(R.id.pendingReason).text = "Reason: ${item.failureReason}"

                        holder.itemView.findViewById<Button>(R.id.btnTrainKnowledge).setOnClickListener {
                            showTrainKnowledgeDialog(item)
                        }
                        holder.itemView.findViewById<Button>(R.id.btnTrainCommand).setOnClickListener {
                            showTrainCommandDialog(item)
                        }
                        holder.itemView.findViewById<Button>(R.id.btnExecute).setOnClickListener {
                            showExecuteConfirmDialog(item)
                        }
                        holder.itemView.findViewById<Button>(R.id.btnIgnore).setOnClickListener {
                            AlertDialog.Builder(this@LearningCenterActivity, R.style.DarkDialog)
                                .setTitle("Ignore karein?")
                                .setMessage("\"${item.question}\" ko permanently ignore kar doon? Ye wapas nahi aayega.")
                                .setPositiveButton("Yes") { _, _ ->
                                    CoroutineScope(Dispatchers.IO).launch {
                                        learningRepo.updateStatus(item.id, "IGNORED")
                                        withContext(Dispatchers.Main) {
                                            val pos = holder.adapterPosition
                                            if (pos != RecyclerView.NO_ID.toInt()) {
                                                itemList.removeAt(pos)
                                                notifyItemRemoved(pos)
                                                notifyItemRangeChanged(pos, itemList.size)
                                            }
                                            if (itemList.isEmpty()) showPendingTab()
                                        }
                                    }
                                }
                                .setNegativeButton("No", null)
                                .show()
                        }
                    }

                    override fun getItemCount() = itemList.size
                }
            }
        }
    }

    private fun showExecuteTab() {
        setActiveTab(tabExecute)
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
            val items = AppDatabase.getInstance(this@LearningCenterActivity).recordedMacroDao().getAll()
            withContext(Dispatchers.Main) {
                if (items.isEmpty()) {
                    contentArea.removeAllViews()
                    contentArea.addView(TextView(this@LearningCenterActivity).apply {
                        text = "Koi learned action nahi abhi! 🎬\nPending tab se \"Execute\" try karo."
                        setTextColor(android.graphics.Color.WHITE)
                        textSize = 16f
                        gravity = android.view.Gravity.CENTER
                        setPadding(32, 64, 32, 32)
                    })
                    return@withContext
                }

                recyclerView.adapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
                    val itemList = items.toMutableList()

                    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
                        val view = LayoutInflater.from(parent.context)
                            .inflate(R.layout.item_learning_macro, parent, false)
                        return object : RecyclerView.ViewHolder(view) {}
                    }

                    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
                        val item = itemList[position]
                        holder.itemView.findViewById<TextView>(R.id.macroPhrase).text = item.displayPhrase
                        holder.itemView.findViewById<TextView>(R.id.macroSteps).text = "🎬 ${item.stepCount} steps"
                        holder.itemView.findViewById<TextView>(R.id.macroUsage).text =
                            if (item.timesReplayed > 0) "Used: ${item.timesReplayed}x" else "Kabhi replay nahi hua abhi"

                        holder.itemView.findViewById<ImageButton>(R.id.redoMacroButton).setOnClickListener {
                            showRedemonstrateDialog(item)
                        }

                        holder.itemView.findViewById<ImageButton>(R.id.deleteMacroButton).setOnClickListener {
                            AlertDialog.Builder(this@LearningCenterActivity, R.style.DarkDialog)
                                .setTitle("Delete karein?")
                                .setMessage("\"${item.displayPhrase}\" ke liye seekha hua action delete kar doon? Ye wapas nahi aayega.")
                                .setPositiveButton("Yes") { _, _ ->
                                    CoroutineScope(Dispatchers.IO).launch {
                                        AppDatabase.getInstance(this@LearningCenterActivity)
                                            .recordedMacroDao().delete(item.id)
                                        AppDatabase.getInstance(this@LearningCenterActivity)
                                            .learningDao().deleteLearningItemByQuestion(item.displayPhrase)
                                        withContext(Dispatchers.Main) {
                                            val pos = holder.adapterPosition
                                            if (pos != RecyclerView.NO_ID.toInt()) {
                                                itemList.removeAt(pos)
                                                notifyItemRemoved(pos)
                                                notifyItemRangeChanged(pos, itemList.size)
                                            }
                                            if (itemList.isEmpty()) showExecuteTab()
                                        }
                                    }
                                }
                                .setNegativeButton("No", null)
                                .show()
                        }
                    }

                    override fun getItemCount() = itemList.size
                }
            }
        }
    }

    /**
     * IMPROVEMENT: lets a fragile/wrong macro be re-taught without first
     * deleting it and losing its trigger phrase + usage stats. Reuses the
     * exact same multi-demo recording flow as a fresh "sikhao" — same 2x
     * (occasionally 3x) demonstration + merge — but RecordingEngine.start's
     * existingMacroId makes the final merged result overwrite this row's
     * steps in place instead of inserting a new macro.
     */
    private fun showRedemonstrateDialog(item: com.junai.app.learning.RecordedMacroEntity) {
        val svc = com.junai.app.agent.action.JunAccessibilityService.instance
        if (svc == null) {
            AlertDialog.Builder(this, R.style.DarkDialog)
                .setTitle("Accessibility Service Off")
                .setMessage("Dobara demonstrate karne ke liye pehle Accessibility Service ON karna hoga (Settings mein).")
                .setPositiveButton("OK", null)
                .show()
            return
        }

        AlertDialog.Builder(this, R.style.DarkDialog)
            .setTitle("Dobara sikhao 🔁")
            .setMessage(
                "\"${item.displayPhrase}\" ke purane steps (${item.stepCount}) replace ho jaayenge:\n\n" +
                "1. Main ab home screen pe chali jaaungi\n" +
                "2. Task poora, sahi tareeke se karo\n" +
                "3. Khatam hone pe Volume Up ya Down daba do\n" +
                "4. Fir bilkul wahi task ek aur baar karna hoga — confirm karne ke liye\n\n" +
                "Purana macro tabhi replace hoga jab dono naye demo poore ho jaayenge. Ready?"
            )
            .setPositiveButton("Start") { _, _ ->
                com.junai.app.agent.action.RecordingEngine.start(
                    triggerPhrase = item.triggerPhrase,
                    displayPhrase = item.displayPhrase,
                    existingMacroId = item.id
                )
                svc.enableRecordingMode()
                Toast.makeText(this, "Recording shuru! Volume button dabao jab ho jaaye.", Toast.LENGTH_LONG).show()
                svc.pressHome()
                finish()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

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
                    contentArea.addView(TextView(this@LearningCenterActivity).apply {
                        text = "Koi knowledge nahi abhi!\nQ=A format se sikhao. 📚"
                        setTextColor(android.graphics.Color.WHITE)
                        textSize = 16f
                        gravity = android.view.Gravity.CENTER
                        setPadding(32, 64, 32, 32)
                    })
                    return@withContext
                }

                recyclerView.adapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
                    val itemList = items.toMutableList()

                    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
                        val view = LayoutInflater.from(parent.context)
                            .inflate(R.layout.item_learning_knowledge, parent, false)
                        return object : RecyclerView.ViewHolder(view) {}
                    }

                    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
                        val item = itemList[position]
                        holder.itemView.findViewById<TextView>(R.id.knowledgeQuestion).text = item.question
                        holder.itemView.findViewById<TextView>(R.id.knowledgeAnswer).text = item.answer
                        holder.itemView.findViewById<TextView>(R.id.knowledgeCategory).text = "📁 ${item.category} • Asked: ${item.timesAsked}x"

                        holder.itemView.findViewById<ImageButton>(R.id.deleteKnowledgeButton).setOnClickListener {
                            AlertDialog.Builder(this@LearningCenterActivity, R.style.DarkDialog)
                                .setTitle("Delete Knowledge")
                                .setMessage("\"${item.question}\" delete karo?")
                                .setPositiveButton("Delete") { _, _ ->
                                    CoroutineScope(Dispatchers.IO).launch {
                                        AppDatabase.getInstance(this@LearningCenterActivity)
                                            .learningDao().deleteKnowledge(item)
                                        AppDatabase.getInstance(this@LearningCenterActivity)
                                            .knowledgeDao().delete(item.question)
                                        AppDatabase.getInstance(this@LearningCenterActivity)
                                            .learningDao().deleteLearningItemByQuestion(item.question)
                                        withContext(Dispatchers.Main) {
                                            val pos = holder.adapterPosition
                                            if (pos != RecyclerView.NO_ID.toInt()) {
                                                itemList.removeAt(pos)
                                                notifyItemRemoved(pos)
                                                notifyItemRangeChanged(pos, itemList.size)
                                            }
                                            if (itemList.isEmpty()) showKnowledgeTab()
                                            Toast.makeText(this@LearningCenterActivity, "Deleted!", Toast.LENGTH_SHORT).show()
                                        }
                                    }
                                }
                                .setNegativeButton("Cancel", null)
                                .show()
                        }
                    }

                    override fun getItemCount() = itemList.size
                }
            }
        }
    }

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
                    contentArea.addView(TextView(this@LearningCenterActivity).apply {
                        text = "Koi command nahi abhi! ⚡\nCommands yahan dikhenge."
                        setTextColor(android.graphics.Color.WHITE)
                        textSize = 16f
                        gravity = android.view.Gravity.CENTER
                        setPadding(32, 64, 32, 32)
                    })
                    return@withContext
                }

                recyclerView.adapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
                    val itemList = items.toMutableList()

                    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
                        val view = LayoutInflater.from(parent.context)
                            .inflate(R.layout.item_learning_command, parent, false)
                        return object : RecyclerView.ViewHolder(view) {}
                    }

                    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
                        val item = itemList[position]
                        holder.itemView.findViewById<TextView>(R.id.commandPhrase).text = item.phrase
                        holder.itemView.findViewById<TextView>(R.id.commandIntent).text = "→ ${item.intent}"
                        holder.itemView.findViewById<TextView>(R.id.commandTarget).text =
                            if (item.target.isNotEmpty()) "Target: ${item.target}" else ""
                        holder.itemView.findViewById<TextView>(R.id.commandUsed).text = "Used: ${item.timesUsed}x"

                        holder.itemView.findViewById<ImageButton>(R.id.deleteCommandButton).setOnClickListener {
                            AlertDialog.Builder(this@LearningCenterActivity, R.style.DarkDialog)
                                .setTitle("Delete Command")
                                .setMessage("\"${item.phrase}\" delete karo?")
                                .setPositiveButton("Delete") { _, _ ->
                                    CoroutineScope(Dispatchers.IO).launch {
                                        AppDatabase.getInstance(this@LearningCenterActivity)
                                            .learningDao().deleteCommand(item)
                                        AppDatabase.getInstance(this@LearningCenterActivity)
                                            .learningDao().deleteLearningItemByQuestion(item.phrase)
                                        withContext(Dispatchers.Main) {
                                            val pos = holder.adapterPosition
                                            if (pos != RecyclerView.NO_ID.toInt()) {
                                                itemList.removeAt(pos)
                                                notifyItemRemoved(pos)
                                                notifyItemRangeChanged(pos, itemList.size)
                                            }
                                            if (itemList.isEmpty()) showCommandsTab()
                                            Toast.makeText(this@LearningCenterActivity, "Deleted!", Toast.LENGTH_SHORT).show()
                                        }
                                    }
                                }
                                .setNegativeButton("Cancel", null)
                                .show()
                        }
                    }

                    override fun getItemCount() = itemList.size
                }
            }
        }
    }

    private fun showAnalyticsTab() {
        setActiveTab(tabAnalytics)
        contentArea.removeAllViews()

        CoroutineScope(Dispatchers.IO).launch {
            AppDatabase.getInstance(this@LearningCenterActivity)
                .learningDao().refreshStatistics()
            val stats = learningRepo.getStats()
            val failures = learningRepo.getFailureLog()
            val achievements = learningRepo.getAchievements()
            val totalQueries = (stats?.knowledgeLearned ?: 0) + (stats?.failedQueries ?: 0)

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

                addStatCard(inner, "🧠 Knowledge Learned", "${stats?.knowledgeLearned ?: 0}")
                addStatCard(inner, "⚡ Commands Learned", "${stats?.commandsLearned ?: 0}")
                addStatCard(inner, "🎯 Skills Learned", "${stats?.skillsLearned ?: 0}")
                addStatCard(inner, "❌ Failed Queries", "${stats?.failedQueries ?: 0}")
                addStatCard(inner, "📊 Total Queries", "$totalQueries")

                inner.addView(TextView(this@LearningCenterActivity).apply {
                    text = "🏆 Achievements"
                    setTextColor(android.graphics.Color.parseColor("#E53935"))
                    textSize = 16f
                    setTypeface(null, android.graphics.Typeface.BOLD)
                    setPadding(0, 24, 0, 8)
                })

                achievements.forEach { achievement ->
                    inner.addView(TextView(this@LearningCenterActivity).apply {
                        text = achievement
                        setTextColor(android.graphics.Color.WHITE)
                        textSize = 13f
                        setBackgroundColor(android.graphics.Color.parseColor("#1A1A1A"))
                        setPadding(16, 12, 16, 12)
                        layoutParams = LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT
                        ).also { it.setMargins(0, 4, 0, 4) }
                    })
                }

                inner.addView(TextView(this@LearningCenterActivity).apply {
                    text = "Recent Failures"
                    setTextColor(android.graphics.Color.parseColor("#E53935"))
                    textSize = 16f
                    setTypeface(null, android.graphics.Typeface.BOLD)
                    setPadding(0, 24, 0, 8)
                })

                if (failures.isEmpty()) {
                    inner.addView(TextView(this@LearningCenterActivity).apply {
                        text = "✅ No failures yet!"
                        setTextColor(android.graphics.Color.WHITE)
                        textSize = 13f
                        setPadding(0, 8, 0, 8)
                    })
                } else {
                    failures.take(10).forEach { log ->
                        inner.addView(TextView(this@LearningCenterActivity).apply {
                            text = "❌ ${log.question}\n→ ${log.failureReason} (${log.confidence.toInt()}%)"
                            setTextColor(android.graphics.Color.WHITE)
                            textSize = 13f
                            setBackgroundColor(android.graphics.Color.parseColor("#1A1A1A"))
                            setPadding(16, 12, 16, 12)
                            layoutParams = LinearLayout.LayoutParams(
                                LinearLayout.LayoutParams.MATCH_PARENT,
                                LinearLayout.LayoutParams.WRAP_CONTENT
                            ).also { it.setMargins(0, 4, 0, 4) }
                        })
                    }
                }
            }
        }
    }

    private fun addStatCard(parent: LinearLayout, title: String, value: String) {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(android.graphics.Color.parseColor("#1A1A1A"))
            setPadding(16, 16, 16, 16)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { it.setMargins(0, 4, 0, 4) }
        }
        card.addView(TextView(this).apply {
            text = title
            setTextColor(android.graphics.Color.WHITE)
            textSize = 14f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        card.addView(TextView(this).apply {
            text = value
            setTextColor(android.graphics.Color.parseColor("#E53935"))
            textSize = 18f
            setTypeface(null, android.graphics.Typeface.BOLD)
        })
        parent.addView(card)
    }

    private fun showExecuteConfirmDialog(item: LearningItem) {
        val svc = com.junai.app.agent.action.JunAccessibilityService.instance
        if (svc == null) {
            AlertDialog.Builder(this, R.style.DarkDialog)
                .setTitle("Accessibility Service Off")
                .setMessage("Learning mode ke liye pehle Accessibility Service ON karna hoga (Settings mein).")
                .setPositiveButton("OK", null)
                .show()
            return
        }

        AlertDialog.Builder(this, R.style.DarkDialog)
            .setTitle("Sikhao 🎬")
            .setMessage(
                "\"${item.question}\" ke liye:\n\n" +
                "1. Main ab home screen pe chali jaaungi\n" +
                "2. Tum khud task perform karo (jo bhi taps/typing chahiye)\n" +
                "3. Khatam hone pe Volume Up ya Down daba do\n" +
                "4. Fir bilkul wahi task ek aur baar karna hoga — 2 baar isliye taaki main sirf consistent steps seekhu, kisi galti se hui extra/missed tap ko khud filter kar sakoon\n" +
                "5. Password/PIN fields kabhi record nahi hongi\n\n" +
                "Ready?"
            )
            .setPositiveButton("Start") { _, _ ->
                com.junai.app.agent.action.RecordingEngine.start(
                    triggerPhrase = item.question,
                    displayPhrase = item.question
                )
                svc.enableRecordingMode()
                CoroutineScope(Dispatchers.IO).launch {
                    learningRepo.updateStatus(item.id, "TRAINED_EXECUTE")
                }
                Toast.makeText(this, "Recording shuru! Volume button dabao jab ho jaaye.", Toast.LENGTH_LONG).show()
                svc.pressHome()
                finish()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showTrainKnowledgeDialog(item: LearningItem) {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 16, 32, 16)
        }

        val answerInput = EditText(this).apply { hint = "Type answer..."; setTextColor(android.graphics.Color.WHITE); setHintTextColor(android.graphics.Color.GRAY) }
        val categoryInput = EditText(this).apply { hint = "Category (e.g. Technology)"; setTextColor(android.graphics.Color.WHITE); setHintTextColor(android.graphics.Color.GRAY); setText(item.suggestedCategory) }
        val aliasInput = EditText(this).apply { hint = "Aliases (separate by |)"; setTextColor(android.graphics.Color.WHITE); setHintTextColor(android.graphics.Color.GRAY) }
        val relatedInput = EditText(this).apply { hint = "Related Questions (separate by |)"; setTextColor(android.graphics.Color.WHITE); setHintTextColor(android.graphics.Color.GRAY) }

        layout.addView(TextView(this).apply { text = "Question: ${item.question}"; setTextColor(android.graphics.Color.WHITE); setPadding(0, 0, 0, 8) })
        layout.addView(TextView(this).apply { text = "Answer:"; setTextColor(android.graphics.Color.GRAY); textSize = 12f })
        layout.addView(answerInput)
        layout.addView(TextView(this).apply { text = "Category:"; setTextColor(android.graphics.Color.GRAY); textSize = 12f; setPadding(0, 8, 0, 0) })
        layout.addView(categoryInput)
        layout.addView(TextView(this).apply { text = "Aliases (optional):"; setTextColor(android.graphics.Color.GRAY); textSize = 12f; setPadding(0, 8, 0, 0) })
        layout.addView(aliasInput)
        layout.addView(TextView(this).apply { text = "Related Questions (optional):"; setTextColor(android.graphics.Color.GRAY); textSize = 12f; setPadding(0, 8, 0, 0) })
        layout.addView(relatedInput)

        AlertDialog.Builder(this, R.style.DarkDialog)
            .setTitle("Train as Knowledge")
            .setView(layout)
            .setPositiveButton("Save") { _, _ ->
                val answer = answerInput.text.toString().trim()
                val category = categoryInput.text.toString().trim().ifEmpty { "General" }
                val aliases = aliasInput.text.toString().trim().split("|").map { it.trim() }.filter { it.isNotEmpty() }
                val related = relatedInput.text.toString().trim().split("|").map { it.trim() }.filter { it.isNotEmpty() }
                if (answer.isNotEmpty()) {
                    CoroutineScope(Dispatchers.IO).launch {
                        learningRepo.trainKnowledgeWithChain(item.question, answer, category, aliases, related)
                        withContext(Dispatchers.Main) {
                            Toast.makeText(this@LearningCenterActivity, "Knowledge saved! ✅", Toast.LENGTH_SHORT).show()
                            showPendingTab()
                        }
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showTrainCommandDialog(item: LearningItem) {
        val intents = arrayOf("OPEN_APP", "CALL_CONTACT", "PLAY_MUSIC", "PAUSE_MUSIC", "SET_REMINDER", "CREATE_NOTE", "SEARCH_WEB", "SHOW_SETTINGS", "TELL_TIME", "TELL_DATE", "TELL_BATTERY", "UNKNOWN")
        var selectedIntent = item.suggestedIntent.ifEmpty { "UNKNOWN" }

        val layout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(32, 16, 32, 16) }
        val targetInput = EditText(this).apply { hint = "Target (e.g. Chrome, Mom)"; setTextColor(android.graphics.Color.WHITE); setHintTextColor(android.graphics.Color.GRAY) }

        layout.addView(TextView(this).apply { text = "Phrase: ${item.question}"; setTextColor(android.graphics.Color.WHITE); setPadding(0, 0, 0, 8) })
        layout.addView(TextView(this).apply { text = "Select Intent:"; setTextColor(android.graphics.Color.GRAY); setPadding(0, 8, 0, 4) })

        val spinner = Spinner(this).apply {
            adapter = ArrayAdapter(this@LearningCenterActivity, android.R.layout.simple_spinner_dropdown_item, intents)
            val idx = intents.indexOf(selectedIntent)
            if (idx >= 0) setSelection(idx)
            onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: android.view.View?, position: Int, id: Long) { selectedIntent = intents[position] }
                override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
            }
        }
        layout.addView(spinner)
        layout.addView(TextView(this).apply { text = "Target (optional):"; setTextColor(android.graphics.Color.GRAY); textSize = 12f; setPadding(0, 8, 0, 0) })
        layout.addView(targetInput)

        AlertDialog.Builder(this, R.style.DarkDialog)
            .setTitle("Train as Command")
            .setView(layout)
            .setPositiveButton("Save") { _, _ ->
                val target = targetInput.text.toString().trim()
                CoroutineScope(Dispatchers.IO).launch {
                    learningRepo.trainCommand(item.question, selectedIntent, target)
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@LearningCenterActivity, "Command saved! ✅", Toast.LENGTH_SHORT).show()
                        showPendingTab()
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun setActiveTab(activeTab: TextView) {
        listOf(tabPending, tabKnowledge, tabCommands, tabExecute, tabAnalytics).forEach {
            it.setBackgroundColor(android.graphics.Color.parseColor("#1A1A1A"))
            it.setTextColor(android.graphics.Color.GRAY)
        }
        activeTab.setBackgroundColor(android.graphics.Color.parseColor("#E53935"))
        activeTab.setTextColor(android.graphics.Color.WHITE)
    }
}
