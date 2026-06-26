package com.junai.app

import android.animation.ObjectAnimator
import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.view.View
import android.widget.LinearLayout
import java.util.Locale

/**
 * Wraps TextToSpeech init, settings, and playback.
 * Pass the speaking indicator layout and its three dot Views so this helper
 * can show/hide the animation without touching MainActivity directly.
 */
class TtsHelper(
    private val context: Context,
    private val speakingIndicator: LinearLayout,
    private val dot1: View,
    private val dot2: View,
    private val dot3: View,
    private val onReady: () -> Unit
) : TextToSpeech.OnInitListener {

    val tts: TextToSpeech = TextToSpeech(context, this)
    var isReady = false
        private set

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts.language = Locale.getDefault()
            isReady = true
            applySettings()
            onReady()
        }
    }

    fun applySettings() {
        val prefs = context.getSharedPreferences("settings_prefs", Context.MODE_PRIVATE)
        tts.setPitch(prefs.getFloat("voice_pitch", 1.0f))
        tts.setSpeechRate(prefs.getFloat("voice_speed", 1.0f))
    }

    fun speak(text: String) {
        if (!isReady) return
        applySettings()

        speakingIndicator.visibility = View.VISIBLE
        animateDot(dot1, 0)
        animateDot(dot2, 150)
        animateDot(dot3, 300)

        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) {
                (context as? android.app.Activity)?.runOnUiThread {
                    speakingIndicator.visibility = View.GONE
                    dot1.clearAnimation()
                    dot2.clearAnimation()
                    dot3.clearAnimation()
                }
            }
            override fun onError(utteranceId: String?) {
                (context as? android.app.Activity)?.runOnUiThread {
                    speakingIndicator.visibility = View.GONE
                }
            }
        })

        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "JUN_TTS")
    }

    fun shutdown() {
        tts.stop()
        tts.shutdown()
    }

    private fun animateDot(dot: View, delay: Long) {
        val animator = ObjectAnimator.ofFloat(dot, "alpha", 0.2f, 1f)
        animator.duration = 400
        animator.repeatMode = ObjectAnimator.REVERSE
        animator.repeatCount = ObjectAnimator.INFINITE
        animator.startDelay = delay
        animator.start()
    }
}
