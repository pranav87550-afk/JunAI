package com.junai.app.passive

import android.app.Dialog
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.junai.app.AppDatabase
import com.junai.app.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Passive Learning — Phase 9: "Manage Learning" tab.
 *
 * Deliberately a SEPARATE screen from Phase 1's "Screen Reading" tab (per
 * spec) — Allow/Deny and forget-my-data are independent axes. An app can
 * stay Allowed here while its history is wiped, or stay Denied while its
 * history is kept for if/when it's re-Allowed later. Neither toggle in
 * either screen ever touches the other's state.
 */
class ManageLearningActivity : AppCompatActivity() {

    private data class AppRow(
        val packageName: String,
        val displayName: String,
        val icon: Drawable?,
        val screenCount: Int,
        val lastSeenAt: Long
    )

    private lateinit var adapter: ManageLearningAdapter
    private var rows: List<AppRow> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_manage_learning)

        findViewById<View>(R.id.backButton).setOnClickListener { finish() }

        val recycler = findViewById<RecyclerView>(R.id.appListRecycler)
        recycler.layoutManager = LinearLayoutManager(this)
        recycler.setHasFixedSize(true)
        adapter = ManageLearningAdapter()
        recycler.adapter = adapter

        findViewById<View>(R.id.forgetAllButton).setOnClickListener { confirmForgetAll() }

        loadRows()
    }

    override fun onResume() {
        super.onResume()
        // Coming back from Screen Reading or after a forget action —
        // counts may have changed either way, cheap enough to just reload.
        loadRows()
    }

    private fun loadRows() {
        lifecycleScope.launch {
            val db = AppDatabase.getInstance(applicationContext)
            val summaries = withContext(Dispatchers.IO) { db.passiveScreenDao().getAppSummaries() }

            rows = summaries.map { summary ->
                val (label, icon) = resolveDisplay(summary.packageName)
                AppRow(
                    packageName = summary.packageName,
                    displayName = label,
                    icon = icon,
                    screenCount = summary.screenCount,
                    lastSeenAt = summary.lastSeenAt
                )
            }

            findViewById<View>(R.id.emptyState).visibility = if (rows.isEmpty()) View.VISIBLE else View.GONE
            findViewById<View>(R.id.forgetAllButton).visibility = if (rows.isEmpty()) View.GONE else View.VISIBLE
            adapter.submit(rows)
        }
    }

    private fun resolveDisplay(packageName: String): Pair<String, Drawable?> {
        val systemLabel = when (packageName) {
            ScreenReadingActivity.SURFACE_HOME -> "Home Screen / App Drawer"
            ScreenReadingActivity.SURFACE_QUICK_SETTINGS -> "Quick Settings"
            ScreenReadingActivity.SURFACE_NOTIFICATIONS -> "Notifications"
            ScreenReadingActivity.SURFACE_RECENTS -> "Recents"
            else -> null
        }
        if (systemLabel != null) return systemLabel to null

        return try {
            val pm = packageManager
            val appInfo = pm.getApplicationInfo(packageName, 0)
            appInfo.loadLabel(pm).toString() to try { appInfo.loadIcon(pm) } catch (e: Exception) { null }
        } catch (e: PackageManager.NameNotFoundException) {
            // App was uninstalled since Jun learned about it — still show
            // its history (forgetting is a separate, explicit action), just
            // with the package name as a fallback label and no icon.
            packageName to null
        }
    }

    private fun relativeTime(atMillis: Long): String {
        val diffMs = System.currentTimeMillis() - atMillis
        val minutes = diffMs / 60_000
        val hours = diffMs / 3_600_000
        val days = diffMs / 86_400_000
        return when {
            minutes < 1 -> "abhi abhi"
            minutes < 60 -> "$minutes min pehle"
            hours < 24 -> "$hours ghante pehle"
            days == 1L -> "kal"
            else -> "$days din pehle"
        }
    }

    // ── Per-app forget: simple Cancel/Confirm ───────────────────────────

    private fun confirmForgetApp(row: AppRow) {
        AlertDialog.Builder(this)
            .setTitle("${row.displayName} bhula do?")
            .setMessage("${row.displayName} ka seekha hua sab kuch (${row.screenCount} screens) delete ho jaayega. Iska Allow/Deny status nahi badlega.")
            .setPositiveButton("Bhula do") { _, _ -> forgetApp(row.packageName) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun forgetApp(packageName: String) {
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                val db = AppDatabase.getInstance(applicationContext)
                // Elements/edges reference passive_screens via a subquery on
                // packageName — must delete them BEFORE the screens rows,
                // or that subquery finds nothing.
                db.passiveElementDao().deleteForApp(packageName)
                db.passiveEdgeDao().deleteForApp(packageName)
                db.passiveScreenDao().deleteForApp(packageName)
            }
            loadRows()
        }
    }

    // ── Forget everything: Phase-1-style 3-second delay + warning line ──

    private fun confirmForgetAll() {
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_screen_reading_confirm, null)
        val title = view.findViewById<TextView>(R.id.dialogTitle)
        val message = view.findViewById<TextView>(R.id.dialogMessage)
        val warning = view.findViewById<TextView>(R.id.dialogFinanceWarning)
        val cancelBtn = view.findViewById<TextView>(R.id.dialogCancel)
        val confirmBtn = view.findViewById<TextView>(R.id.dialogAllow)

        title.text = "Sab kuch bhula do?"
        message.text = "Jun ne har allowed app/surface mein jo bhi passively seekha hai — sab screens, buttons, aur transitions — permanently delete ho jaayenge."
        warning.visibility = View.VISIBLE
        warning.text = "⚠️ Ye action wapas nahi ho sakta. Allow/Deny settings nahi badlengi, sirf seekha hua data jaayega."

        val dialog = Dialog(this).apply {
            setContentView(view)
            setCancelable(true)
        }

        var remaining = 3
        confirmBtn.isEnabled = false
        confirmBtn.alpha = 0.5f
        confirmBtn.text = "Bhula do ($remaining)"
        val countdownJob = lifecycleScope.launch {
            while (remaining > 0) {
                delay(1000)
                remaining--
                if (remaining > 0) {
                    confirmBtn.text = "Bhula do ($remaining)"
                } else {
                    confirmBtn.text = "Bhula do"
                    confirmBtn.isEnabled = true
                    confirmBtn.alpha = 1f
                }
            }
        }

        cancelBtn.setOnClickListener {
            countdownJob.cancel()
            dialog.dismiss()
        }
        confirmBtn.setOnClickListener {
            if (!confirmBtn.isEnabled) return@setOnClickListener
            countdownJob.cancel()
            dialog.dismiss()
            forgetAll()
        }
        dialog.setOnDismissListener { countdownJob.cancel() }
        dialog.show()
    }

    private fun forgetAll() {
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                val db = AppDatabase.getInstance(applicationContext)
                db.passiveElementDao().deleteAll()
                db.passiveEdgeDao().deleteAll()
                db.passiveScreenDao().deleteAll()
            }
            loadRows()
        }
    }

    // ── Adapter ──────────────────────────────────────────────────────────

    private inner class ManageLearningAdapter : RecyclerView.Adapter<ManageLearningAdapter.RowVH>() {
        private var items: List<AppRow> = emptyList()

        fun submit(newItems: List<AppRow>) {
            items = newItems
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RowVH {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_manage_learning_app, parent, false)
            return RowVH(view)
        }

        override fun getItemCount(): Int = items.size

        override fun onBindViewHolder(holder: RowVH, position: Int) {
            holder.bind(items[position])
        }

        inner class RowVH(itemView: View) : RecyclerView.ViewHolder(itemView) {
            private val icon = itemView.findViewById<ImageView>(R.id.appIcon)
            private val name = itemView.findViewById<TextView>(R.id.appName)
            private val summary = itemView.findViewById<TextView>(R.id.appSummary)
            private val forgetBtn = itemView.findViewById<TextView>(R.id.forgetButton)

            fun bind(row: AppRow) {
                name.text = row.displayName
                if (row.icon != null) {
                    icon.visibility = View.VISIBLE
                    icon.setImageDrawable(row.icon)
                } else {
                    icon.visibility = View.GONE
                }
                summary.text = "${row.screenCount} screens seekhe, aakhri baar ${relativeTime(row.lastSeenAt)} use hua"
                forgetBtn.setOnClickListener { confirmForgetApp(row) }
            }
        }
    }
}
