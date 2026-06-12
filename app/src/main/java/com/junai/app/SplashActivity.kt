package com.junai.app

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class SplashActivity : AppCompatActivity() {

    private var progress = 0
    private lateinit var progressBar: ProgressBar
    private lateinit var progressText: TextView
    private val handler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_splash)

        progressBar = findViewById(R.id.progressBar)
        progressText = findViewById(R.id.progressText)

        startLoading()
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
