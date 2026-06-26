package com.junai.app

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.view.WindowManager
import androidx.core.content.ContextCompat

/**
 * Handles all Speech-to-Text logic and the listening overlay view
 * for FloatingBotService.
 */
class BotSpeechController(
    private val service: FloatingBotService,
    private val botView: FloatingBotView,
    private val listeningView: ListeningOverlayView,
    private val listeningParams: WindowManager.LayoutParams,
    private val windowManager: WindowManager,
    private val mainHandler: Handler,
    private val onShowBubble: (text: String, durationMs: Long, speak: Boolean) -> Unit,
    private val onExecuteCommand: (text: String) -> Unit,
    private val onListeningParamsPositionChanged: () -> Unit
) {

    var listeningAttached = false
        private set

    private var speechRecognizer: SpeechRecognizer? = null

    // ──────────────────────────────────────────────────────────
    // LISTENING OVERLAY
    // ──────────────────────────────────────────────────────────

    fun showListeningUI(botX: Int, botY: Int) {
        if (!listeningAttached) {
            try {
                listeningParams.x = botX
                listeningParams.y = botY
                windowManager.addView(listeningView, listeningParams)
                listeningAttached = true
            } catch (e: Exception) { return }
        }
        listeningView.start()
    }

    fun detachListening() {
        if (!listeningAttached) return
        listeningView.stop()
        mainHandler.postDelayed({
            if (listeningAttached) {
                try { windowManager.removeView(listeningView) } catch (e: Exception) { }
                listeningAttached = false
            }
        }, 200)
    }

    fun updateListeningPosition(botX: Int, botY: Int) {
        if (!listeningAttached) return
        listeningParams.x = botX
        listeningParams.y = botY
        try { windowManager.updateViewLayout(listeningView, listeningParams) } catch (e: Exception) { }
    }

    // ──────────────────────────────────────────────────────────
    // BACKGROUND STT
    // ──────────────────────────────────────────────────────────

    fun startBackgroundSTT(botX: Int, botY: Int) {
        if (ContextCompat.checkSelfPermission(
                service, android.Manifest.permission.RECORD_AUDIO
            ) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            service.startActivity(Intent(service, VoicePermissionActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
            return
        }

        if (!SpeechRecognizer.isRecognitionAvailable(service)) {
            onShowBubble("Voice support not available 😅", 2200, true)
            return
        }

        botView.expression = BotExpression.LISTENING
        showListeningUI(botX, botY)

        speechRecognizer?.destroy()
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(service).apply {
            setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {}
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {
                    mainHandler.post { botView.expression = BotExpression.THINKING }
                }
                override fun onError(error: Int) {
                    mainHandler.post {
                        botView.expression = BotExpression.NEURAL
                        detachListening()
                        onShowBubble("Couldn't hear you 😅", 2200, true)
                    }
                }
                override fun onResults(results: Bundle?) {
                    val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    mainHandler.post {
                        botView.expression = BotExpression.NEURAL
                        detachListening()
                        val spoken = matches?.firstOrNull()
                        if (!spoken.isNullOrEmpty()) showRecognizedTextThenExecute(spoken)
                    }
                }
                override fun onPartialResults(partialResults: Bundle?) {}
                override fun onEvent(eventType: Int, params: Bundle?) {}
            })

            try {
                startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, java.util.Locale.getDefault())
                    putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, service.packageName)
                })
            } catch (e: Exception) {
                onShowBubble("STT failed to start 😅", 2000, true)
            }
        }
    }

    fun showRecognizedTextThenExecute(text: String) {
        onShowBubble("\"$text\"", 1300, false)
        mainHandler.postDelayed({ onExecuteCommand(text) }, 1300)
    }

    fun destroy() {
        speechRecognizer?.destroy()
        speechRecognizer = null
    }
}
