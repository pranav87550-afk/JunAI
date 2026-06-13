package com.junai.app

import android.os.Bundle
import android.widget.Button
import android.widget.Switch
import androidx.appcompat.app.AppCompatActivity

class MiniJunSettingsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_mini_jun_settings)

        findViewById<Button>(R.id.backButton).setOnClickListener {
            finish()
        }

        val miniJunSwitch = findViewById<Switch>(R.id.miniJunSwitch)
        val roamSwitch = findViewById<Switch>(R.id.roamSwitch)

        // Set initial colors
        updateSwitchColor(miniJunSwitch, miniJunSwitch.isChecked)
        updateSwitchColor(roamSwitch, roamSwitch.isChecked)

        miniJunSwitch.setOnCheckedChangeListener { _, isChecked ->
            updateSwitchColor(miniJunSwitch, isChecked)
        }

        roamSwitch.setOnCheckedChangeListener { _, isChecked ->
            updateSwitchColor(roamSwitch, isChecked)
        }
    }

    private fun updateSwitchColor(switch: Switch, isChecked: Boolean) {
        if (isChecked) {
            switch.thumbTint = android.content.res.ColorStateList.valueOf(
                android.graphics.Color.parseColor("#2E7D32"))
            switch.trackTint = android.content.res.ColorStateList.valueOf(
                android.graphics.Color.parseColor("#1B5E20"))
        } else {
            switch.thumbTint = android.content.res.ColorStateList.valueOf(
                android.graphics.Color.parseColor("#E53935"))
            switch.trackTint = android.content.res.ColorStateList.valueOf(
                android.graphics.Color.parseColor("#4A1010"))
        }
    }
}
