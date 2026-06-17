package com.junai.app

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View

class DrawingView(context: Context, attrs: AttributeSet?) : View(context, attrs) {

    private val paths = mutableListOf<Pair<Path, Paint>>()
    private val undonePaths = mutableListOf<Pair<Path, Paint>>()
    private var currentPath = Path()
    private var currentPaint = createPaint(Color.BLACK)

    private fun createPaint(color: Int, strokeWidth: Float = 8f): Paint {
        return Paint().apply {
            this.color = color
            this.strokeWidth = strokeWidth
            this.isAntiAlias = true
            this.style = Paint.Style.STROKE
            this.strokeJoin = Paint.Join.ROUND
            this.strokeCap = Paint.Cap.ROUND
        }
    }

    fun setColor(color: Int) {
        currentPaint = createPaint(color)
    }

    fun setEraser() {
        currentPaint = createPaint(Color.WHITE, 24f)
    }

    fun setBrushSize(size: Float) {
    currentPaint = createPaint(currentPaint.color, size)
    }

    fun undo() {
        if (paths.isNotEmpty()) {
            undonePaths.add(paths.removeLast())
            invalidate()
        }
    }

    fun redo() {
        if (undonePaths.isNotEmpty()) {
            paths.add(undonePaths.removeLast())
            invalidate()
        }
    }

    fun getBitmap(): Bitmap {
    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    canvas.drawColor(Color.WHITE)
    for ((path, paint) in paths) {
        canvas.drawPath(path, paint)
    }
    canvas.drawPath(currentPath, currentPaint)
    return bitmap
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        for ((path, paint) in paths) {
            canvas.drawPath(path, paint)
        }
        canvas.drawPath(currentPath, currentPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val x = event.x
        val y = event.y
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                currentPath = Path()
                currentPath.moveTo(x, y)
                undonePaths.clear()
            }
            MotionEvent.ACTION_MOVE -> {
                currentPath.lineTo(x, y)
                invalidate()
            }
            MotionEvent.ACTION_UP -> {
                paths.add(Pair(currentPath, currentPaint))
                currentPath = Path()
            }
        }
        return true
    }
}
