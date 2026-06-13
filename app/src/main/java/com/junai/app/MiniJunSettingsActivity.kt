package com.junai.app

import android.os.Bundle
import android.widget.Button
import androidx.appcompat.app.AppCompatActivity

class MiniJunSettingsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_mini_jun_settings)

        findViewById<Button>(R.id.backButton).setOnClickListener {
            finish()
        }
    }
}
