package com.junai.app

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

class SplashActivity : AppCompatActivity() {

    private var progress = 0
    private lateinit var progressBar: ProgressBar
    private lateinit var progressText: TextView
    private lateinit var loadingStatus: TextView
    private val handler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_splash)

        progressBar = findViewById(R.id.progressBar)
        progressText = findViewById(R.id.progressText)
        loadingStatus = findViewById(R.id.loadingStatus)

        checkFirstLaunch()
    }

    private fun checkFirstLaunch() {
        val prefs = getSharedPreferences("jun_setup", MODE_PRIVATE)
        val isFirstLaunch = prefs.getBoolean("knowledge_imported", false).not()

        if (isFirstLaunch) {
            loadingStatus.text = "Setting up Jun Brain... 🧠"
            importDefaultKnowledge {
                prefs.edit().putBoolean("knowledge_imported", true).apply()
                startLoading()
            }
        } else {
            loadingStatus.text = "Loading Jun AI... 🚀"
            startLoading()
        }
    }

    private fun importDefaultKnowledge(onComplete: () -> Unit) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val json = assets.open("jun_knowledge.json")
                    .bufferedReader().use { it.readText() }
                val root = JSONObject(json)
                val array = root.getJSONArray("knowledge")
                val dao = AppDatabase.getInstance(this@SplashActivity).knowledgeDao()

                val total = array.length()
                for (i in 0 until total) {
                    val obj = array.getJSONObject(i)
                    val question = obj.getString("question").lowercase().trim()
                    val answer = obj.getString("answer")
                    val category = obj.optString("category", "General")

                    dao.insert(KnowledgeEntity(
                        question = question,
                        answer = answer,
                        category = category
                    ))

                    val prog = ((i + 1) * 60 / total)
                    withContext(Dispatchers.Main) {
                        progressBar.progress = prog
                        progressText.text = "$prog%"
                        loadingStatus.text = "Loading knowledge... (${ i + 1}/$total) 🧠"
                    }
                }

                withContext(Dispatchers.Main) {
                    loadingStatus.text = "Jun is ready! 🚀"
                    onComplete()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    loadingStatus.text = "Loading Jun AI... 🚀"
                    onComplete()
                }
            }
        }
    }

    private fun startLoading() {
        handler.postDelayed(object : Runnable {
            override fun run() {
                progress += 2
                progressBar.progress = progress
                progressText.text = "$progress%"

                if (progress < 100) {
                    handler.postDelayed(this, 40)
                } else {
                    startActivity(Intent(this@SplashActivity, MainActivity::class.java))
                    finish()
                }
            }
        }, 40)
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
    }
}
