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
        const val KEY_ROAMING          = "roaming_enabled"
        const val KEY_APP_SENSE        = "app_sense_enabled"
    }

    private lateinit var miniJunSwitch:   Switch
    private lateinit var roamSwitch:      Switch
    private lateinit var randomEyeSwitch: Switch
    private lateinit var appSenseSwitch:  Switch

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_mini_jun_settings)

        miniJunSwitch   = findViewById(R.id.miniJunSwitch)
        roamSwitch      = findViewById(R.id.roamSwitch)
        randomEyeSwitch = findViewById(R.id.randomEyeSwitch)
        appSenseSwitch  = findViewById(R.id.appSenseSwitch)

        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        miniJunSwitch.isChecked   = prefs.getBoolean(KEY_MINI_JUN_ENABLED, false)
        roamSwitch.isChecked      = prefs.getBoolean(KEY_ROAMING, false)
        randomEyeSwitch.isChecked = prefs.getBoolean(KEY_RANDOM_EYE, false)
        appSenseSwitch.isChecked  = prefs.getBoolean(KEY_APP_SENSE, false)

        updateSwitchColor(miniJunSwitch,   miniJunSwitch.isChecked)
        updateSwitchColor(roamSwitch,      roamSwitch.isChecked)
        updateSwitchColor(randomEyeSwitch, randomEyeSwitch.isChecked)
        updateSwitchColor(appSenseSwitch,  appSenseSwitch.isChecked)

        findViewById<Button>(R.id.backButton).setOnClickListener { finish() }

        // ── Mini Jun ON/OFF ───────────────────────────────────
        miniJunSwitch.setOnCheckedChangeListener { _, isChecked ->
            updateSwitchColor(miniJunSwitch, isChecked)
            if (isChecked) handleBotEnable()
            else {
                stopBotService()
                saveBoolean(KEY_MINI_JUN_ENABLED, false)
            }
        }

        // ── Roaming ───────────────────────────────────────────
        roamSwitch.setOnCheckedChangeListener { _, isChecked ->
            updateSwitchColor(roamSwitch, isChecked)
            saveBoolean(KEY_ROAMING, isChecked)
            if (isChecked && randomEyeSwitch.isChecked) {
                randomEyeSwitch.isChecked = false
                updateSwitchColor(randomEyeSwitch, false)
                saveBoolean(KEY_RANDOM_EYE, false)
            }
            notifyServicePrefsChanged()
        }

        // ── Random Eye Movement ───────────────────────────────
        randomEyeSwitch.setOnCheckedChangeListener { _, isChecked ->
            updateSwitchColor(randomEyeSwitch, isChecked)
            if (isChecked && roamSwitch.isChecked) {
                roamSwitch.isChecked = false
                updateSwitchColor(roamSwitch, false)
                saveBoolean(KEY_ROAMING, false)
            }
            saveBoolean(KEY_RANDOM_EYE, isChecked)
            notifyServicePrefsChanged()
        }

        // ── App Sense ─────────────────────────────────────────
        appSenseSwitch.setOnCheckedChangeListener { _, isChecked ->
            updateSwitchColor(appSenseSwitch, isChecked)
            if (isChecked) {
                if (!AppSenseManager.hasUsagePermission(this)) {
                    // Permission nahi hai — directly settings pe bhejo
                    Toast.makeText(this,
                        "Please enable Usage Access for JunAI",
                        Toast.LENGTH_LONG).show()
                    openUsageAccessSettings()
                    // Switch wapas off — onResume mein check karenge
                    appSenseSwitch.isChecked = false
                    updateSwitchColor(appSenseSwitch, false)
                    return@setOnCheckedChangeListener
                }
            }
            saveBoolean(KEY_APP_SENSE, isChecked)
            notifyServicePrefsChanged()
        }
    }

    override fun onResume() {
        super.onResume()
        // Check agar user usage settings se wapas aaya
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        if (!prefs.getBoolean(KEY_APP_SENSE, false) &&
            AppSenseManager.hasUsagePermission(this)) {
            // Permission mil gayi — switch ON karo
            appSenseSwitch.isChecked = true
            updateSwitchColor(appSenseSwitch, true)
            saveBoolean(KEY_APP_SENSE, true)
            notifyServicePrefsChanged()
        }

        if (miniJunSwitch.isChecked && hasOverlayPermission()) {
            startBotServiceIfNeeded()
        }
    }

    // ──────────────────────────────────────────────────────────
    // USAGE ACCESS SETTINGS — direct redirect
    // ──────────────────────────────────────────────────────────
    private fun openUsageAccessSettings() {
        try {
            val intent = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS).apply {
                // Try to open directly to JunAI's entry
                data = Uri.parse("package:$packageName")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
        } catch (e: Exception) {
            // Fallback — some devices don't support package URI
            try {
                startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                })
            } catch (ex: Exception) {
                Toast.makeText(this,
                    "Please go to Settings → Apps → Special access → Usage access → JunAI",
                    Toast.LENGTH_LONG).show()
            }
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
            startBotServiceIfNeeded()
            saveBoolean(KEY_MINI_JUN_ENABLED, true)
        } else {
            Toast.makeText(this,
                "Jun needs overlay permission to appear on screen",
                Toast.LENGTH_LONG).show()
            requestOverlayPermission()
        }
    }

    private fun requestOverlayPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            @Suppress("DEPRECATION")
            startActivityForResult(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")),
                OVERLAY_REQUEST_CODE
            )
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == OVERLAY_REQUEST_CODE) {
            if (hasOverlayPermission()) {
                startBotServiceIfNeeded()
                saveBoolean(KEY_MINI_JUN_ENABLED, true)
            } else {
                miniJunSwitch.isChecked = false
                updateSwitchColor(miniJunSwitch, false)
                Toast.makeText(this,
                    "Permission denied — Jun won't appear",
                    Toast.LENGTH_SHORT).show()
            }
        }
    }

    // ──────────────────────────────────────────────────────────
    // SERVICE
    // ──────────────────────────────────────────────────────────
    private var serviceStarted = false

    private fun startBotServiceIfNeeded() {
        if (serviceStarted) return
        val intent = Intent(this, FloatingBotService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            startForegroundService(intent) else startService(intent)
        serviceStarted = true
    }

    private fun stopBotService() {
        stopService(Intent(this, FloatingBotService::class.java))
        serviceStarted = false
    }

    private fun notifyServicePrefsChanged() {
        if (!miniJunSwitch.isChecked || !hasOverlayPermission()) return
        startService(Intent(this, FloatingBotService::class.java).apply {
            action = FloatingBotService.ACTION_RELOAD_PREFS
        })
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
