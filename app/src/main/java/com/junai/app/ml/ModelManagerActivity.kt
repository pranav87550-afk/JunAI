package com.junai.app

import android.os.Bundle
import android.view.LayoutInflater
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Observer
import com.junai.app.ml.ModelCatalog
import com.junai.app.ml.ModelDownloadManager
import com.junai.app.ml.ModelStateStore

/**
 * Lets the user download the 3 on-device models (Embedding/FunctionGemma/
 * Qwen3) from Hugging Face at runtime, instead of them being bundled in
 * the APK. Reached via the drawer menu ("Models"). Only 3 items total,
 * so this deliberately skips RecyclerView/Adapter machinery — a plain
 * loop inflating item_model_download.xml once per model into a
 * ScrollView is simpler and has fewer moving parts to get wrong for a
 * list this short.
 */
class ModelManagerActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_model_manager)

        findViewById<Button>(R.id.backButton).setOnClickListener { finish() }

        val container = findViewById<android.widget.LinearLayout>(R.id.modelsContainer)
        val inflater = LayoutInflater.from(this)

        ModelCatalog.ALL.forEach { model ->
            val row = inflater.inflate(R.layout.item_model_download, container, false)

            val nameView = row.findViewById<TextView>(R.id.modelName)
            val descView = row.findViewById<TextView>(R.id.modelDescription)
            val statusView = row.findViewById<TextView>(R.id.modelStatus)
            val progressBar = row.findViewById<ProgressBar>(R.id.downloadProgress)
            val actionButton = row.findViewById<Button>(R.id.actionButton)

            nameView.text = model.displayName
            descView.text = model.description

            val approxMb = model.approxSizeBytes / (1024 * 1024)

            // Re-read on every observed status change rather than
            // caching — keeps this in lockstep with ModelStateStore's
            // "always derive, never cache" approach instead of
            // introducing its own local copy that could drift.
            ModelStateStore.observe(this, model.id).observe(this, Observer { info ->
                when (info.status) {
                    ModelStateStore.Status.NOT_DOWNLOADED -> {
                        statusView.text = "~${approxMb} MB · Not downloaded"
                        progressBar.visibility = android.view.View.GONE
                        actionButton.text = "Download"
                        actionButton.isEnabled = true
                        actionButton.setOnClickListener {
                            ModelDownloadManager.download(this, model.id)
                        }
                    }
                    ModelStateStore.Status.QUEUED -> {
                        statusView.text = "Queued…"
                        progressBar.visibility = android.view.View.GONE
                        actionButton.text = "Cancel"
                        actionButton.isEnabled = true
                        actionButton.setOnClickListener {
                            ModelDownloadManager.cancel(this, model.id)
                        }
                    }
                    ModelStateStore.Status.DOWNLOADING -> {
                        val doneMb = info.bytesDownloaded / (1024 * 1024)
                        val totalMb = if (info.bytesTotal > 0) info.bytesTotal / (1024 * 1024) else approxMb
                        statusView.text = "Downloading… ${doneMb}/${totalMb} MB"
                        progressBar.visibility = android.view.View.VISIBLE
                        progressBar.progress = info.progressPercent
                        actionButton.text = "Cancel"
                        actionButton.isEnabled = true
                        actionButton.setOnClickListener {
                            ModelDownloadManager.cancel(this, model.id)
                        }
                    }
                    ModelStateStore.Status.READY -> {
                        statusView.text = "Ready ✓"
                        progressBar.visibility = android.view.View.GONE
                        actionButton.text = "Downloaded"
                        actionButton.isEnabled = false
                        actionButton.setOnClickListener(null)
                    }
                    ModelStateStore.Status.FAILED -> {
                        statusView.text = "Download failed — check connection and retry"
                        progressBar.visibility = android.view.View.GONE
                        actionButton.text = "Retry"
                        actionButton.isEnabled = true
                        actionButton.setOnClickListener {
                            ModelDownloadManager.retry(this, model.id)
                        }
                    }
                }
            })

            container.addView(row)
        }
    }
}
