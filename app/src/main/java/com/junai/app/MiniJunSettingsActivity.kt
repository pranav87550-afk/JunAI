package com.junai.app

import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Bundle
import android.widget.Button
import android.widget.Switch
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.CompoundButtonCompat

class MiniJunSettingsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_mini_jun_settings)

        findViewById<Button>(R.id.backButton).setOnClickListener {
            finish()
        }

        val miniJunSwitch = findViewById<Switch>(R.id.miniJunSwitch)
        val roamSwitch = findViewById<Switch>(R.id.roamSwitch)

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
        val color = if (isChecked) Color.parseColor("#2E7D32")
                    else Color.parseColor("#E53935")
        switch.thumbTintList = ColorStateList.valueOf(color)
        switch.trackTintList = ColorStateList.valueOf(
            if (isChecked) Color.parseColor("#1B5E20")
            else Color.parseColor("#4A1010"))
    }
}
