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
    private var soundLoaded: Boolean = false  // track if sound is ready

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_splash)

        showPendingCrashLogIfAny()

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
        // Fix: null-safe build, no !! force unwrap
        soundPool = SoundPool.Builder()
            .setMaxStreams(1)
            .setAudioAttributes(attrs)
            .build()
            .also { pool ->
                // Fix: wait for load complete before marking ready
                pool.setOnLoadCompleteListener { _, _, status ->
                    soundLoaded = (status == 0)
                }
                swipeSoundId = pool.load(this, R.raw.swipe_sound, 1)
            }
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
                    loadingStatus.setWaveText("Jun is ready! 🚀")
                    handler.postDelayed({ goToMainActivity() }, 550)
                }
            }
        }, 40)
    }

    /**
     * Fix: Sound aur swipe animation ek saath start hote hain.
     *
     * Pehle: play() → startActivity() → finish() immediately
     * Sound stream Activity destroy hone se cut off ho jaata tha.
     *
     * Ab: play() aur slide animation simultaneously trigger hote hain.
     * Activity 400ms baad finish hoti hai — itne mein sound (~300ms) complete
     * ho jaata hai. SoundPool bhi tabhi release hota hai.
     */
    private fun goToMainActivity() {
        pulseAnimator?.cancel()

        // Sound aur transition ek saath
        if (soundLoaded) {
            soundPool?.play(swipeSoundId, 0.8f, 0.8f, 1, 0, 1f)
        }
        startActivity(Intent(this, MainActivity::class.java))
        overridePendingTransition(R.anim.splash_slide_up_enter, R.anim.slide_up_exit)

        // Finish slightly after so sound isn't cut off by onDestroy
        handler.postDelayed({ finish() }, 400)
    }

    /**
     * Shows crash-log and/or breadcrumb trail from the previous run, if
     * either exists — this is a TEMPORARY debugging aid while chasing a
     * specific native-crash bug (see Breadcrumb.kt's doc comment for
     * why breadcrumbs exist at all). It'll pop up on every launch as
     * long as breadcrumbs.txt has content, which it always will after
     * any session that touched an engine — noisy by design for now.
     * Once the underlying crash is found and fixed, this should go back
     * to ONLY showing on an actual last_crash.txt, not unconditionally.
     */
    private fun showPendingCrashLogIfAny() {
        val crashFile = java.io.File(filesDir, JunApplication.CRASH_LOG_FILENAME)
        val hadCrash = crashFile.exists()
        val crashLog = if (hadCrash) (try { crashFile.readText() } catch (e: Exception) { null }) else null
        if (hadCrash) crashFile.delete()

        val breadcrumbs = com.junai.app.ml.Breadcrumb.readPreviousSession(this)

        if (crashLog == null && breadcrumbs == null) return

        val combined = buildString {
            if (crashLog != null) {
                append("=== CRASH (caught) ===\n")
                append(crashLog)
                append("\n\n")
            }
            append("=== BREADCRUMBS (last session) ===\n")
            append("If this ends abruptly with no matching \"returned OK\" line ")
            append("right after it, that call is what likely crashed natively.\n\n")
            append(breadcrumbs ?: "(none)")
        }

        val textView = TextView(this).apply {
            text = combined
            setTextIsSelectable(true)
            setPadding(40, 24, 40, 24)
            textSize = 12f
        }
        val scrollView = android.widget.ScrollView(this).apply { addView(textView) }

        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(if (hadCrash) "Jun crashed last time — here's why" else "Last session trail")
            .setView(scrollView)
            .setPositiveButton("OK", null)
            .setCancelable(false)
            .show()
    }

    override fun onDestroy() {
        super.onDestroy()
        pulseAnimator?.cancel()
        handler.removeCallbacksAndMessages(null)
        soundPool?.release()
        soundPool = null
    }
}
