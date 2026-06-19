package com.junai.app

import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.Switch
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class MiniJunSettingsActivity : AppCompatActivity() {

    companion object {
        const val OVERLAY_REQUEST_CODE = 1001
        const val PREFS_NAME           = "mini_jun_prefs"
        const val KEY_MINI_JUN_ENABLED = "mini_jun_enabled"
        const val KEY_RANDOM_EYE       = "random_eye_enabled"
        const val KEY_TOUCH_EYE        = "touch_eye_enabled"
    }

    private lateinit var miniJunSwitch:   Switch
    private lateinit var roamSwitch:      Switch
    private lateinit var randomEyeSwitch: Switch
    private lateinit var touchEyeSwitch:  Switch

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_mini_jun_settings)

        miniJunSwitch   = findViewById(R.id.miniJunSwitch)
        roamSwitch      = findViewById(R.id.roamSwitch)
        randomEyeSwitch = findViewById(R.id.randomEyeSwitch)
        touchEyeSwitch  = findViewById(R.id.touchEyeSwitch)

        // Load saved state
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        miniJunSwitch.isChecked   = prefs.getBoolean(KEY_MINI_JUN_ENABLED, false)
        randomEyeSwitch.isChecked = prefs.getBoolean(KEY_RANDOM_EYE, false)
        touchEyeSwitch.isChecked  = false // always off — coming soon

        updateSwitchColor(miniJunSwitch,   miniJunSwitch.isChecked)
        updateSwitchColor(roamSwitch,      false)
        updateSwitchColor(randomEyeSwitch, randomEyeSwitch.isChecked)
        updateSwitchColor(touchEyeSwitch,  false)

        findViewById<Button>(R.id.backButton).setOnClickListener { finish() }

        // ── Mini Jun ON/OFF ───────────────────────────────────
        miniJunSwitch.setOnCheckedChangeListener { _, isChecked ->
            updateSwitchColor(miniJunSwitch, isChecked)
            if (isChecked) {
                handleBotEnable()
            } else {
                stopBotService()
                saveBoolean(KEY_MINI_JUN_ENABLED, false)
            }
        }

        // ── Roam — Coming Soon ────────────────────────────────
        roamSwitch.setOnCheckedChangeListener { _, isChecked ->
            updateSwitchColor(roamSwitch, isChecked)
            if (isChecked) {
                roamSwitch.isChecked = false
                updateSwitchColor(roamSwitch, false)
                Toast.makeText(this, "Coming Soon!", Toast.LENGTH_SHORT).show()
            }
        }

        // ── Random Eye Movement ───────────────────────────────
        randomEyeSwitch.setOnCheckedChangeListener { _, isChecked ->
            updateSwitchColor(randomEyeSwitch, isChecked)
            saveBoolean(KEY_RANDOM_EYE, isChecked)
            notifyBotEyeMode()
        }

        // ── Touch Tracking — Coming Soon ──────────────────────
        touchEyeSwitch.setOnCheckedChangeListener { _, isChecked ->
            updateSwitchColor(touchEyeSwitch, isChecked)
            if (isChecked) {
                touchEyeSwitch.isChecked = false
                updateSwitchColor(touchEyeSwitch, false)
                Toast.makeText(this, "Coming Soon!", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (miniJunSwitch.isChecked && hasOverlayPermission()) {
            startBotService()
            saveBoolean(KEY_MINI_JUN_ENABLED, true)
        }
    }

    // ──────────────────────────────────────────────────────────
    // NOTIFY BOT — eye mode change
    // ──────────────────────────────────────────────────────────
    private fun notifyBotEyeMode() {
        if (miniJunSwitch.isChecked && hasOverlayPermission()) {
            stopBotService()
            startBotService()
        }
    }

    // ──────────────────────────────────────────────────────────
    // OVERLAY PERMISSION
    // ──────────────────────────────────────────────────────────
    private fun hasOverlayPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
            Settings.canDrawOverlays(this) else true
    }

    private fun handleBotEnable() {
        if (hasOverlayPermission()) {
            startBotService()
            saveBoolean(KEY_MINI_JUN_ENABLED, true)
        } else {
            Toast.makeText(this,
                "Jun ko screen pe dikhne ke liye permission chahiye",
                Toast.LENGTH_LONG).show()
            requestOverlayPermission()
        }
    }

    private fun requestOverlayPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            @Suppress("DEPRECATION")
            startActivityForResult(intent, OVERLAY_REQUEST_CODE)
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == OVERLAY_REQUEST_CODE) {
            if (hasOverlayPermission()) {
                startBotService()
                saveBoolean(KEY_MINI_JUN_ENABLED, true)
            } else {
                miniJunSwitch.isChecked = false
                updateSwitchColor(miniJunSwitch, false)
                Toast.makeText(this,
                    "Permission nahi mili — Jun show nahi hoga",
                    Toast.LENGTH_SHORT).show()
            }
        }
    }

    // ──────────────────────────────────────────────────────────
    // SERVICE
    // ──────────────────────────────────────────────────────────
    private fun startBotService() {
        val intent = Intent(this, FloatingBotService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            startForegroundService(intent) else startService(intent)
    }

    private fun stopBotService() {
        stopService(Intent(this, FloatingBotService::class.java))
    }

    // ──────────────────────────────────────────────────────────
    // HELPERS
    // ──────────────────────────────────────────────────────────
    private fun saveBoolean(key: String, value: Boolean) {
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            .edit().putBoolean(key, value).apply()
    }

    private fun updateSwitchColor(switch: Switch, isChecked: Boolean) {
        val thumbColor = if (isChecked) Color.parseColor("#2E7D32")
                         else           Color.parseColor("#E53935")
        val trackColor = if (isChecked) Color.parseColor("#1B5E20")
                         else           Color.parseColor("#4A1010")
        switch.thumbTintList = ColorStateList.valueOf(thumbColor)
        switch.trackTintList = ColorStateList.valueOf(trackColor)
    }
}
