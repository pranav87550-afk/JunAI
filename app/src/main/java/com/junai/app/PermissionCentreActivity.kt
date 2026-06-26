package com.junai.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

class PermissionCentreActivity : AppCompatActivity() {

    data class PermissionItem(
        val title: String,
        val reason: String,
        val permission: String?,
        val specialAction: String? = null,
        val iconRes: Int = R.drawable.ic_mic
    )

    private val permissions = listOf(
        PermissionItem("Microphone", "Required for voice input and STT (Speech-to-Text) in chat", Manifest.permission.RECORD_AUDIO, iconRes = R.drawable.ic_mic),
        PermissionItem("Phone Calls", "Required to make calls directly from Jun AI", Manifest.permission.CALL_PHONE, iconRes = R.drawable.ic_send),
        PermissionItem("Contacts", "Required to read contacts for calling features", Manifest.permission.READ_CONTACTS, iconRes = R.drawable.ic_menu),
        PermissionItem("Notifications", "Required to show reminders and alarm notifications",
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) Manifest.permission.POST_NOTIFICATIONS else null, iconRes = R.drawable.ic_calendar),
        PermissionItem("Storage (Media Audio)", "Required to read and play music files from your device",
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) Manifest.permission.READ_MEDIA_AUDIO
            else Manifest.permission.READ_EXTERNAL_STORAGE, iconRes = R.drawable.ic_note_doc),
        PermissionItem("Overlay (Draw over apps)", "Required for Mini-Jun floating assistant", null, "OVERLAY", R.drawable.ic_bot_hide),
        PermissionItem("Modify System Settings", "Required to set ringtone and alarm tone from Music Player", null, "WRITE_SETTINGS", R.drawable.ic_edit),
        PermissionItem("Exact Alarms", "Required to fire reminders at the exact scheduled time",
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) Manifest.permission.SCHEDULE_EXACT_ALARM else null, iconRes = R.drawable.ic_calendar),
        PermissionItem("Internet", "Required for AI chat responses and translation", Manifest.permission.INTERNET, iconRes = R.drawable.ic_send)
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_permission_centre)

        findViewById<LinearLayout>(R.id.backButton).setOnClickListener { finish() }

        val container = findViewById<LinearLayout>(R.id.permissionsContainer)

        permissions.forEach { item ->
            val row = layoutInflater.inflate(R.layout.item_permission, container, false)

            row.findViewById<TextView>(R.id.permissionTitle).text = item.title
            row.findViewById<TextView>(R.id.permissionReason).text = item.reason

            // Set icon with blue tint
            val iconView = row.findViewById<android.widget.ImageView>(R.id.permissionIcon)
            iconView.setImageResource(item.iconRes)
            iconView.setColorFilter(android.graphics.Color.parseColor("#1E88E5"))

            val switch = row.findViewById<Switch>(R.id.permissionSwitch)
            switch.isChecked = isGranted(item)
            switch.isEnabled = !isAlwaysGranted(item)

            fun updateToggleColor(checked: Boolean) {
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
            updateToggleColor(switch.isChecked)

            switch.setOnCheckedChangeListener { _, isChecked ->
                updateToggleColor(isChecked)
                if (isChecked) {
                    requestPermission(item)
                } else {
                    Toast.makeText(this, "To revoke, go to App Settings", Toast.LENGTH_SHORT).show()
                    openAppSettings()
                    switch.isChecked = true
                    updateToggleColor(true)
                }
            }

            container.addView(row)
        }
    }

    override fun onResume() {
        super.onResume()
        // Refresh all switches when returning from settings
        val container = findViewById<LinearLayout>(R.id.permissionsContainer)
        permissions.forEachIndexed { index, item ->
            val row = container.getChildAt(index) ?: return@forEachIndexed
            val switch = row.findViewById<Switch>(R.id.permissionSwitch)
            val checked = isGranted(item)
            switch.isChecked = checked
            if (checked) {
                switch.thumbTintList = android.content.res.ColorStateList.valueOf(android.graphics.Color.parseColor("#4CAF50"))
                switch.trackTintList = android.content.res.ColorStateList.valueOf(android.graphics.Color.parseColor("#804CAF50"))
            } else {
                switch.thumbTintList = android.content.res.ColorStateList.valueOf(android.graphics.Color.parseColor("#E53935"))
                switch.trackTintList = android.content.res.ColorStateList.valueOf(android.graphics.Color.parseColor("#80E53935"))
            }
        }
    }

    private fun isGranted(item: PermissionItem): Boolean {
        return when (item.specialAction) {
            "OVERLAY" -> Settings.canDrawOverlays(this)
            "WRITE_SETTINGS" -> Settings.System.canWrite(this)
            else -> {
                val perm = item.permission ?: return true
                ContextCompat.checkSelfPermission(this, perm) == PackageManager.PERMISSION_GRANTED
            }
        }
    }

    private fun isAlwaysGranted(item: PermissionItem): Boolean {
        // INTERNET is always granted — no runtime request needed
        return item.permission == Manifest.permission.INTERNET
    }

    private fun requestPermission(item: PermissionItem) {
        when (item.specialAction) {
            "OVERLAY" -> {
                val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName"))
                startActivity(intent)
            }
            "WRITE_SETTINGS" -> {
                val intent = Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS,
                    Uri.parse("package:$packageName"))
                startActivity(intent)
            }
            else -> {
                val perm = item.permission ?: return
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    requestPermissions(arrayOf(perm), 100)
                }
            }
        }
    }

    private fun openAppSettings() {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.parse("package:$packageName"))
        startActivity(intent)
    }
}
