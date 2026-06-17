package com.junai.app

import android.graphics.Bitmap
import android.graphics.Color
import android.os.Bundle
import android.os.Environment
import android.widget.ImageButton
import android.widget.SeekBar
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class DrawActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_draw)

        val drawingView = findViewById<DrawingView>(R.id.drawingView)

        findViewById<ImageButton>(R.id.backButton).setOnClickListener {
            saveDrawing(drawingView)
            finish()
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

            // Gallery mein refresh karo
            android.media.MediaScannerConnection.scanFile(
                this, arrayOf(file.absolutePath), null, null
            )

            Toast.makeText(this, "Drawing saved to Pictures/JunAI! 🎨", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Save failed: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }
}
