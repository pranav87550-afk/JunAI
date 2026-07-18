package com.junai.app

import android.os.Bundle
import android.view.LayoutInflater
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Observer
import androidx.lifecycle.lifecycleScope
import com.junai.app.ml.ModelCatalog
import com.junai.app.ml.ModelDownloadManager
import com.junai.app.ml.ModelStateStore
import kotlinx.coroutines.launch

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

            ModelStateStore.observe(this, model.id).observe(this, Observer { info ->
                when (info.status) {
                    ModelStateStore.Status.NOT_DOWNLOADED -> {
                        statusView.text = "~${approxMb} MB · Not downloaded"
                        progressBar.visibility = android.view.View.GONE
                        actionButton.text = "Download"
                        actionButton.isEnabled = true
                        actionButton.setOnClickListener {
                            try {
                                ModelDownloadManager.download(this, model.id)
                            } catch (e: Exception) {
                                android.widget.Toast.makeText(
                                    this, "Download start failed: ${e.javaClass.simpleName}: ${e.message}",
                                    android.widget.Toast.LENGTH_LONG
                                ).show()
                                android.util.Log.e("ModelManagerActivity", "download() failed", e)
                            }
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

        // TEMPORARY TEST HOOK (Piece 3 of the GGUF migration) — lets us
        // confirm GGUFChatEngine actually loads + generates on a real
        // device before it's wired into the main chat router. Remove
        // once that wiring happens (GGUFChatEngine replaces/joins
        // ChatEngine in the router) and this becomes redundant.
        val testButton = Button(this).apply { text = "Test GGUF Chat" }
        val testResultView = TextView(this).apply {
            setPadding(24, 24, 24, 24)
            setTextColor(android.graphics.Color.WHITE)
        }
        testButton.setOnClickListener {
            if (!ModelDownloadManager.isDownloaded(this, ModelCatalog.ModelId.QWEN3_CHAT_GGUF)) {
                android.widget.Toast.makeText(this, "Download the GGUF model first", android.widget.Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            testButton.isEnabled = false
            testResultView.text = "Loading model…"
            lifecycleScope.launch {
                try {
                    com.junai.app.ml.GGUFChatEngine.init(this@ModelManagerActivity)
                    if (!com.junai.app.ml.GGUFChatEngine.isReady()) {
                        testResultView.text = "Failed to load model — check Logcat for GGUFChatEngine errors"
                        return@launch
                    }
                    testResultView.text = "Model loaded. Generating…"
                    val response = com.junai.app.ml.GGUFChatEngine.tryChat("Hello! Who are you?")
                    testResultView.text = response?.answer?.takeIf { it.isNotBlank() } ?: "Generation failed or timed out — check Logcat"
                } catch (e: Exception) {
                    testResultView.text = "Exception: ${e.javaClass.simpleName}: ${e.message}"
                    android.util.Log.e("ModelManagerActivity", "GGUF test crashed", e)
                } finally {
                    testButton.isEnabled = true
                }
            }
        }
        container.addView(testButton)
        container.addView(testResultView)
    }
}
