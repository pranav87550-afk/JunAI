package com.junai.app

import android.graphics.Color
import android.os.Bundle
import android.widget.ImageButton
import android.widget.SeekBar
import androidx.appcompat.app.AppCompatActivity

class DrawActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_draw)

        val drawingView = findViewById<DrawingView>(R.id.drawingView)

        findViewById<ImageButton>(R.id.backButton).setOnClickListener { finish() }
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
}
