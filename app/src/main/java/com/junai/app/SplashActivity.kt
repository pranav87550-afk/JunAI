package com.junai.app

import android.animation.Animator
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.content.Intent
import android.media.AudioAttributes
import android.media.SoundPool
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.animation.OvershootInterpolator
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

class SplashActivity : AppCompatActivity() {

    private var progress = 0
    private lateinit var splashLogo: ImageView
    private lateinit var progressBar: CodeStreamProgressView
    private lateinit var progressText: TextView
    private lateinit var loadingStatus: WaveTextView
    private val handler = Handler(Looper.getMainLooper())
    private var pulseAnimator: AnimatorSet? = null

    private var soundPool: SoundPool? = null
    private var swipeSoundId: Int = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_splash)

        splashLogo = findViewById(R.id.splashLogo)
        progressBar = findViewById(R.id.progressBar)
        progressText = findViewById(R.id.progressText)
        loadingStatus = findViewById(R.id.loadingStatus)

        initSwipeSound()

        // Hide bottom UI until entrance animation finishes
        progressBar.alpha = 0f
        progressText.alpha = 0f
        loadingStatus.alpha = 0f

        playEntranceAnimation()
    }

    private fun initSwipeSound() {
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        soundPool = SoundPool.Builder()
            .setMaxStreams(1)
            .setAudioAttributes(attrs)
            .build()
        swipeSoundId = soundPool!!.load(this, R.raw.swipe_sound, 1)
    }

    /** Logo fades + scales in with a slight overshoot. */
    private fun playEntranceAnimation() {
        splashLogo.alpha = 0f
        splashLogo.scaleX = 0.82f
        splashLogo.scaleY = 0.82f

        val fade = ObjectAnimator.ofFloat(splashLogo, View.ALPHA, 0f, 1f).apply { duration = 650 }
        val scaleX = ObjectAnimator.ofFloat(splashLogo, View.SCALE_X, 0.82f, 1f).apply { duration = 650 }
        val scaleY = ObjectAnimator.ofFloat(splashLogo, View.SCALE_Y, 0.82f, 1f).apply { duration = 650 }

        AnimatorSet().apply {
            playTogether(fade, scaleX, scaleY)
            interpolator = OvershootInterpolator(1.1f)
            addListener(object : Animator.AnimatorListener {
                override fun onAnimationStart(animation: Animator) {}
                override fun onAnimationCancel(animation: Animator) {}
                override fun onAnimationRepeat(animation: Animator) {}
                override fun onAnimationEnd(animation: Animator) {
                    fadeInBottomUi()
                    startLogoPulse()
                    checkFirstLaunch()
                }
            })
            start()
        }
    }

    private fun fadeInBottomUi() {
        listOf(progressBar, progressText, loadingStatus).forEach {
            ObjectAnimator.ofFloat(it, View.ALPHA, 0f, 1f).apply {
                duration = 350
                start()
            }
        }
    }

    /** Subtle breathing glow on the logo while loading. */
    private fun startLogoPulse() {
        val pulseUp = ObjectAnimator.ofFloat(splashLogo, View.SCALE_X, 1f, 1.035f)
        val pulseUpY = ObjectAnimator.ofFloat(splashLogo, View.SCALE_Y, 1f, 1.035f)
        val pulseDown = ObjectAnimator.ofFloat(splashLogo, View.SCALE_X, 1.035f, 1f)
        val pulseDownY = ObjectAnimator.ofFloat(splashLogo, View.SCALE_Y, 1.035f, 1f)

        listOf(pulseUp, pulseUpY, pulseDown, pulseDownY).forEach { it.duration = 1100 }

        pulseAnimator = AnimatorSet().apply {
            playSequentially(
                AnimatorSet().apply { playTogether(pulseUp, pulseUpY) },
                AnimatorSet().apply { playTogether(pulseDown, pulseDownY) }
            )
            addListener(object : Animator.AnimatorListener {
                override fun onAnimationStart(animation: Animator) {}
                override fun onAnimationCancel(animation: Animator) {}
                override fun onAnimationRepeat(animation: Animator) {}
                override fun onAnimationEnd(animation: Animator) {
                    if (!isFinishing) startLogoPulse()
                }
            })
            start()
        }
    }

    private fun checkFirstLaunch() {
        val prefs = getSharedPreferences("jun_setup", MODE_PRIVATE)
        val isFirstLaunch = prefs.getBoolean("knowledge_imported", false).not()

        if (isFirstLaunch) {
            loadingStatus.setWaveText("Setting up Jun Brain... 🧠")
            importDefaultKnowledge {
                prefs.edit().putBoolean("knowledge_imported", true).apply()
                startLoading()
            }
        } else {
            loadingStatus.setWaveText("Loading Jun AI... 🚀")
            startLoading()
        }
    }

    private fun importDefaultKnowledge(onComplete: () -> Unit) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val json = assets.open("jun_knowledge.json")
                    .bufferedReader().use { it.readText() }
                val root = JSONObject(json)
                val array = root.getJSONArray("knowledge")
                val dao = AppDatabase.getInstance(this@SplashActivity).knowledgeDao()

                val total = array.length()
                for (i in 0 until total) {
                    val obj = array.getJSONObject(i)
                    val question = obj.getString("question").lowercase().trim()
                    val answer = obj.getString("answer")
                    val category = obj.optString("category", "General")

                    dao.insert(KnowledgeEntity(
                        question = question,
                        answer = answer,
                        category = category
                    ))

                    val prog = ((i + 1) * 60 / total)
                    withContext(Dispatchers.Main) {
                        progressBar.progress = prog
                        progressText.text = "$prog%"
                        loadingStatus.setWaveText("Loading knowledge... (${i + 1}/$total) 🧠")
                    }
                }

                withContext(Dispatchers.Main) {
                    loadingStatus.setWaveText("Jun is ready! 🚀")
                    onComplete()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    loadingStatus.setWaveText("Loading Jun AI... 🚀")
                    onComplete()
                }
            }
        }
    }

    private fun startLoading() {
        handler.postDelayed(object : Runnable {
            override fun run() {
                progress += 2
                progressBar.progress = progress
                progressText.text = "$progress%"

                if (progress < 100) {
                    handler.postDelayed(this, 40)
                } else {
                    // Show the fully-loaded splash for a beat before swiping away
                    loadingStatus.setWaveText("Jun is ready! 🚀")
                    handler.postDelayed({ goToMainActivity() }, 550)
                }
            }
        }, 40)
    }

    /** Plays the swipe sound and slides the whole splash screen up to reveal MainActivity. */
    private fun goToMainActivity() {
        pulseAnimator?.cancel()
        soundPool?.play(swipeSoundId, 0.8f, 0.8f, 1, 0, 1f)
        startActivity(Intent(this, MainActivity::class.java))
        overridePendingTransition(R.anim.splash_slide_up_enter, R.anim.slide_up_exit)
        finish()
    }

    override fun onDestroy() {
        super.onDestroy()
        pulseAnimator?.cancel()
        soundPool?.release()
        soundPool = null
        handler.removeCallbacksAndMessages(null)
    }
}
