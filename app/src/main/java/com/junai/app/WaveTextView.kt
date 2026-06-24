package com.junai.app

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.sin

/**
 * Shows status text as a row of words, each one continuously floating
 * up and down with a slight delay relative to its neighbours —
 * creates a gentle "wave" motion across the whole line instead of
 * static text.
 */
class WaveTextView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : LinearLayout(context, attrs) {

    private val handler = Handler(Looper.getMainLooper())
    private val startTime = System.currentTimeMillis()
    private var running = false
    private var currentText: String = ""

    private val amplitudePx = dp(4f)      // how far up/down each word bobs
    private val periodMs = 1400L          // time for one full bob cycle
    private val phaseStepRad = 0.9f       // wave offset between consecutive words

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }

    /** Rebuilds the word views only when the text actually changes. */
    fun setWaveText(text: String) {
        if (text == currentText) return
        currentText = text
        removeAllViews()

        val tokens = text.split(" ").filter { it.isNotEmpty() }
        tokens.forEachIndexed { index, token ->
            val word = TextView(context).apply {
                this.text = token
                setTextColor(0xFF888888.toInt())
                textSize = 13f
                tag = index
            }
            addView(word)

            if (index != tokens.lastIndex) {
                addView(TextView(context).apply {
                    this.text = " "
                    textSize = 13f
                })
            }
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        running = true
        handler.post(waveRunnable)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        running = false
        handler.removeCallbacks(waveRunnable)
    }

    private val waveRunnable = object : Runnable {
        override fun run() {
            if (!running) return
            val elapsed = System.currentTimeMillis() - startTime
            for (i in 0 until childCount) {
                val child = getChildAt(i)
                val wordIndex = child.tag as? Int ?: continue
                val phase = wordIndex * phaseStepRad
                val angle = (2 * Math.PI * (elapsed % periodMs) / periodMs) + phase
                child.translationY = (amplitudePx * sin(angle)).toFloat()
            }
            handler.postDelayed(this, 16)
        }
    }

    private fun dp(value: Float): Float = value * resources.displayMetrics.density
}
