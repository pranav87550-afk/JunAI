package com.junai.app

import android.graphics.Bitmap
import android.graphics.Color
import android.os.Bundle
import android.os.Environment
import android.widget.ImageButton
import android.widget.SeekBar
import android.widget.Switch
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class DrawActivity : AppCompatActivity() {

    private val DRAW_PREFS = "draw_prefs"
    private val KEY_AUTO_SAVE = "auto_save_enabled"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_draw)

        val drawingView = findViewById<DrawingView>(R.id.drawingView)
        val prefs = getSharedPreferences(DRAW_PREFS, MODE_PRIVATE)

        // Auto-save toggle
        val autoSaveSwitch = findViewById<Switch>(R.id.autoSaveSwitch)
        autoSaveSwitch.isChecked = prefs.getBoolean(KEY_AUTO_SAVE, false)
        autoSaveSwitch.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean(KEY_AUTO_SAVE, isChecked).apply()
            val msg = if (isChecked) "Auto-save enabled" else "Auto-save disabled"
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
        }

        // Back button
        findViewById<ImageButton>(R.id.backButton).setOnClickListener {
            val autoSave = prefs.getBoolean(KEY_AUTO_SAVE, false)
            if (autoSave) {
                saveDrawing(drawingView)
                finish()
            } else {
                AlertDialog.Builder(this)
                    .setTitle("Save Drawing?")
                    .setMessage("Auto-save is off. Do you want to save before leaving?")
                    .setPositiveButton("Save") { _, _ ->
                        saveDrawing(drawingView)
                        finish()
                    }
                    .setNegativeButton("Discard") { _, _ -> finish() }
                    .setNeutralButton("Cancel", null)
                    .show()
            }
        }

        // Manual save button
        findViewById<ImageButton>(R.id.saveButton).setOnClickListener {
            saveDrawing(drawingView)
        }

        findViewById<ImageButton>(R.id.undoButton).setOnClickListener { drawingView.undo() }
        findViewById<ImageButton>(R.id.redoButton).setOnClickListener { drawingView.redo() }
        findViewById<ImageButton>(R.id.eraserButton).setOnClickListener { drawingView.setEraser() }

        findViewById<SeekBar>(R.id.brushSizeSeekBar).setOnSeekBarChangeListener(
            object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    drawingView.setBrushSize(progress.toFloat().coerceAtLeast(5f))
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                override fun onStopTrackingTouch(seekBar: SeekBar?) {}
            }
        )

        findViewById<android.view.View>(R.id.colorRed).setOnClickListener { drawingView.setColor(Color.parseColor("#E53935")) }
        findViewById<android.view.View>(R.id.colorOrange).setOnClickListener { drawingView.setColor(Color.parseColor("#FF9800")) }
        findViewById<android.view.View>(R.id.colorWhite).setOnClickListener { drawingView.setColor(Color.WHITE) }
        findViewById<android.view.View>(R.id.colorBlack).setOnClickListener { drawingView.setColor(Color.BLACK) }
        findViewById<android.view.View>(R.id.colorNavy).setOnClickListener { drawingView.setColor(Color.parseColor("#1A237E")) }
        findViewById<android.view.View>(R.id.colorBlue).setOnClickListener { drawingView.setColor(Color.parseColor("#1565C0")) }
        findViewById<android.view.View>(R.id.colorTeal).setOnClickListener { drawingView.setColor(Color.parseColor("#00897B")) }
    }

    private fun saveDrawing(drawingView: DrawingView) {
        try {
            val bitmap = drawingView.getBitmap()
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            val filename = "JunAI_Drawing_$timestamp.png"

            val picturesDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
            val junaiDir = File(picturesDir, "JunAI")
            if (!junaiDir.exists()) junaiDir.mkdirs()

            val file = File(junaiDir, filename)
            FileOutputStream(file).use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            }

            android.media.MediaScannerConnection.scanFile(
                this, arrayOf(file.absolutePath), null, null
            )

            Toast.makeText(this, "Drawing saved to Pictures/JunAI!", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Save failed: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }
}
