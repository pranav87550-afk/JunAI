package com.junai.app.passive

import android.app.Dialog
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.Switch
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.junai.app.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.junai.app.AppDatabase

/**
 * Passive Learning — Phase 1: the "Screen Reading" tab.
 *
 * Lists every launcher-visible app plus four fixed system surfaces, each
 * with an Allow/Deny toggle backed by [AppLearningPermissionEntity].
 * Default is Deny for everything (ground rule 2) — nothing here writes a
 * row until the user explicitly flips something to Allow, and that flip
 * itself is gated behind a 3-second delayed confirmation (ground rule 4:
 * UX/copy is first-class, not an afterthought).
 *
 * JunAI's own package is hardcoded out of this list entirely (ground rule
 * 1) — it never appears, never gets a row, isn't a togglable option.
 */
class ScreenReadingActivity : AppCompatActivity() {

    companion object {
        const val SURFACE_HOME = "system:home"
        const val SURFACE_QUICK_SETTINGS = "system:quick_settings"
        const val SURFACE_NOTIFICATIONS = "system:notifications"
        const val SURFACE_RECENTS = "system:recents"

        private const val CONFIRM_DELAY_SECONDS = 3

        // Fallback keyword list — used when ApplicationInfo.category is
        // CATEGORY_UNDEFINED (very common; most developers never declare
        // android:appCategory), checked against app label + package name.
        private val FINANCE_KEYWORDS = listOf(
            "pay", "bank", "wallet", "upi", "finance", "loan", "credit",
            "emi", "invest", "mutual fund", "stocks", "trading", "insurance",
            "paytm", "phonepe", "gpay", "kotak", "hdfc", "icici", "sbi",
            "axis", "yesbank", "idfc", "rbl", "indusind", "razorpay"
        )
    }

    private sealed class Row {
        data class Header(val label: String) : Row()
        data class AppRow(
            val packageName: String,
            val displayName: String,
            val icon: Drawable?,
            val isFinance: Boolean,
            var allowed: Boolean
        ) : Row()
    }

    private val fullList = mutableListOf<Row>()
    private lateinit var adapter: ScreenReadingAdapter
    private lateinit var dao: AppLearningPermissionDao

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_screen_reading)

        dao = AppDatabase.getInstance(applicationContext).appLearningPermissionDao()

        findViewById<View>(R.id.backButton).setOnClickListener { finish() }

        val recycler = findViewById<RecyclerView>(R.id.appListRecycler)
        recycler.layoutManager = LinearLayoutManager(this)
        // Smooth scroll over a potentially long app list: fixed row size
        // avoids relayout measuring on every bind, and RecyclerView's own
        // view-recycling keeps this cheap regardless of list length.
        recycler.setHasFixedSize(true)
        adapter = ScreenReadingAdapter()
        recycler.adapter = adapter

        val searchInput = findViewById<EditText>(R.id.searchInput)
        searchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                applyFilter(s?.toString().orEmpty())
            }
        })

        loadRows()
    }

    private fun loadRows() {
        lifecycleScope.launch {
            val permissions = withContext(Dispatchers.IO) { dao.getAll() }
            val permMap = permissions.associateBy { it.packageName }

            val systemRows = listOf(
                SURFACE_HOME to "Home Screen / App Drawer",
                SURFACE_QUICK_SETTINGS to "Quick Settings",
                SURFACE_NOTIFICATIONS to "Notifications",
                SURFACE_RECENTS to "Recents"
            ).map { (pkg, label) ->
                Row.AppRow(
                    packageName = pkg,
                    displayName = label,
                    icon = null,
                    isFinance = false,
                    allowed = permMap[pkg]?.allowed ?: false
                )
            }

            val appRows = withContext(Dispatchers.IO) { loadLauncherApps(permMap) }

            fullList.clear()
            fullList.add(Row.Header("SYSTEM"))
            fullList.addAll(systemRows)
            fullList.add(Row.Header("APPS"))
            fullList.addAll(appRows)

            adapter.submit(fullList.toList())
        }
    }

    /** Launcher-visible apps only (not every PackageManager entry) — see design check-in. */
    private fun loadLauncherApps(permMap: Map<String, AppLearningPermissionEntity>): List<Row.AppRow> {
        val pm = packageManager
        val mainIntent = Intent(Intent.ACTION_MAIN, null).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
        }
        val resolved = pm.queryIntentActivities(mainIntent, 0)

        return resolved
            .asSequence()
            .map { it.activityInfo.applicationInfo }
            .distinctBy { it.packageName }
            .filter { it.packageName != packageName } // ground rule 1: JunAI is never in its own list
            .map { appInfo ->
                val label = appInfo.loadLabel(pm).toString()
                val icon = try { appInfo.loadIcon(pm) } catch (e: Exception) { null }
                Row.AppRow(
                    packageName = appInfo.packageName,
                    displayName = label,
                    icon = icon,
                    isFinance = isFinanceApp(appInfo, label),
                    allowed = permMap[appInfo.packageName]?.allowed ?: false
                )
            }
            .sortedBy { it.displayName.lowercase() }
            .toList()
    }

    private fun isFinanceApp(appInfo: ApplicationInfo, label: String): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (appInfo.category == ApplicationInfo.CATEGORY_FINANCE) return true
        }
        val haystack = (label + " " + appInfo.packageName).lowercase()
        return FINANCE_KEYWORDS.any { haystack.contains(it) }
    }

    private fun applyFilter(query: String) {
        if (query.isBlank()) {
            adapter.submit(fullList.toList())
            return
        }
        val q = query.trim().lowercase()
        val filtered = mutableListOf<Row>()
        var pendingHeader: Row.Header? = null
        for (row in fullList) {
            when (row) {
                is Row.Header -> pendingHeader = row
                is Row.AppRow -> {
                    if (row.displayName.lowercase().contains(q)) {
                        pendingHeader?.let {
                            if (filtered.lastOrNull() != it) filtered.add(it)
                        }
                        pendingHeader = null
                        filtered.add(row)
                    }
                }
            }
        }
        adapter.submit(filtered)
    }

    /** Persists the toggle and drives the row's checked state after a confirmed change. */
    private fun onAllowToggled(row: Row.AppRow, wantsAllowed: Boolean, switch: Switch) {
        if (!wantsAllowed) {
            // Turning OFF is always immediate, no confirmation (ground rule:
            // low friction to revoke). History is kept, not wiped here —
            // that's a separate action, deferred to Phase 9's "Manage
            // Learning" tab.
            row.allowed = false
            persist(row, allowed = false)
            return
        }

        // Turning ON requires the delayed-confirmation dialog.
        showAllowConfirmation(row, onConfirmed = {
            row.allowed = true
            persist(row, allowed = true)
        }, onCancelled = {
            switch.isChecked = false
        })
    }

    private fun persist(row: Row.AppRow, allowed: Boolean) {
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                dao.upsert(
                    AppLearningPermissionEntity(
                        packageName = row.packageName,
                        allowed = allowed,
                        allowedAt = System.currentTimeMillis(),
                        category = if (row.isFinance) "FINANCE" else null
                    )
                )
            }
        }
    }

    private fun showAllowConfirmation(row: Row.AppRow, onConfirmed: () -> Unit, onCancelled: () -> Unit) {
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_screen_reading_confirm, null)
        val title = view.findViewById<TextView>(R.id.dialogTitle)
        val message = view.findViewById<TextView>(R.id.dialogMessage)
        val financeWarning = view.findViewById<TextView>(R.id.dialogFinanceWarning)
        val cancelBtn = view.findViewById<TextView>(R.id.dialogCancel)
        val allowBtn = view.findViewById<TextView>(R.id.dialogAllow)

        title.text = "${row.displayName} — Screen Reading Allow?"
        message.text = "${row.displayName} ke liye screen-reading allow karoge? Jun is app ke screens, buttons, aur unke naam dekhega taaki future mein khud kaam kar sake. Kabhi bhi wapas Deny kar sakte ho, aur is app ka seekha hua sab kuch turant delete ho jaayega."

        if (row.isFinance) {
            financeWarning.visibility = View.VISIBLE
            financeWarning.text = "Ye ek financial app hai — screens/buttons yaad rakhe jaayenge, lekin koi bhi paisa bhejne wala step Jun kabhi khud confirm nahi karega, hamesha tumse poochega."
        } else {
            financeWarning.visibility = View.GONE
        }

        val dialog = Dialog(this).apply {
            setContentView(view)
            setCancelable(true)
        }

        var confirmed = false
        dialog.setOnCancelListener {
            if (!confirmed) onCancelled()
        }

        cancelBtn.setOnClickListener {
            confirmed = false
            dialog.dismiss()
            onCancelled()
        }

        // 3-second deliberate friction before Allow becomes tappable —
        // visibly greyed out, not hidden, per the spec.
        var remaining = CONFIRM_DELAY_SECONDS
        allowBtn.isEnabled = false
        allowBtn.text = "Allow ($remaining)"
        val countdownJob = lifecycleScope.launch {
            while (remaining > 0) {
                delay(1000)
                remaining--
                if (remaining > 0) {
                    allowBtn.text = "Allow ($remaining)"
                } else {
                    allowBtn.text = "Allow"
                    allowBtn.isEnabled = true
                    allowBtn.alpha = 1f
                }
            }
        }
        allowBtn.alpha = 0.5f

        allowBtn.setOnClickListener {
            if (!allowBtn.isEnabled) return@setOnClickListener
            confirmed = true
            countdownJob.cancel()
            dialog.dismiss()
            onConfirmed()
        }

        dialog.setOnDismissListener {
            countdownJob.cancel()
        }

        dialog.show()
    }

    // ── Adapter ──────────────────────────────────────────────────────────
    private inner class ScreenReadingAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

        private var items: List<Row> = emptyList()

        fun submit(newItems: List<Row>) {
            items = newItems
            notifyDataSetChanged()
        }

        override fun getItemViewType(position: Int): Int =
            if (items[position] is Row.Header) 0 else 1

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val inflater = LayoutInflater.from(parent.context)
            return if (viewType == 0) {
                HeaderVH(inflater.inflate(R.layout.item_screen_reading_header, parent, false))
            } else {
                AppVH(inflater.inflate(R.layout.item_screen_reading_app, parent, false))
            }
        }

        override fun getItemCount(): Int = items.size

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            when (val row = items[position]) {
                is Row.Header -> (holder as HeaderVH).bind(row)
                is Row.AppRow -> (holder as AppVH).bind(row)
            }
        }

        private inner class HeaderVH(itemView: View) : RecyclerView.ViewHolder(itemView) {
            private val label = itemView.findViewById<TextView>(R.id.headerLabel)
            fun bind(row: Row.Header) {
                label.text = row.label
            }
        }

        private inner class AppVH(itemView: View) : RecyclerView.ViewHolder(itemView) {
            private val icon = itemView.findViewById<ImageView>(R.id.appIcon)
            private val name = itemView.findViewById<TextView>(R.id.appName)
            private val subtitle = itemView.findViewById<TextView>(R.id.appSubtitle)
            private val toggle = itemView.findViewById<Switch>(R.id.appToggle)

            fun bind(row: Row.AppRow) {
                // Clear listener before setting checked state to avoid the
                // recycled view firing a spurious toggle callback.
                toggle.setOnCheckedChangeListener(null)

                name.text = row.displayName
                if (row.icon != null) {
                    icon.visibility = View.VISIBLE
                    icon.setImageDrawable(row.icon)
                } else {
                    icon.visibility = View.GONE
                }

                if (row.isFinance) {
                    subtitle.visibility = View.VISIBLE
                    subtitle.text = "Financial app"
                    subtitle.setTextColor(android.graphics.Color.parseColor("#FF6D00"))
                } else {
                    subtitle.visibility = View.GONE
                }

                toggle.isChecked = row.allowed
                updateToggleColor(toggle, row.allowed)

                toggle.setOnCheckedChangeListener { _, isChecked ->
                    updateToggleColor(toggle, isChecked)
                    onAllowToggled(row, isChecked, toggle)
                }
            }

            private fun updateToggleColor(switch: Switch, checked: Boolean) {
                if (checked) {
                    switch.thumbTintList = android.content.res.ColorStateList.valueOf(
                        android.graphics.Color.parseColor("#4CAF50"))
                    switch.trackTintList = android.content.res.ColorStateList.valueOf(
                        android.graphics.Color.parseColor("#804CAF50"))
                } else {
                    switch.thumbTintList = android.content.res.ColorStateList.valueOf(
                        android.graphics.Color.parseColor("#E53935"))
                    switch.trackTintList = android.content.res.ColorStateList.valueOf(
                        android.graphics.Color.parseColor("#80E53935"))
                }
            }
        }
    }
}
