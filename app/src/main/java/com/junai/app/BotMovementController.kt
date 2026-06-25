package com.junai.app

import android.animation.ValueAnimator
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.WindowManager
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.DecelerateInterpolator
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Owns every animation that moves the floating bot around the screen:
 * idle bobbing, free-roaming, edge-snap on drag release, and the
 * "move to center / move back" animation used when the orbit menu opens.
 *
 * This class mutates [botParams] directly (same instance the service
 * holds) and calls [windowManager.updateViewLayout] itself, so the
 * service does not need to duplicate any of that logic — it only needs
 * to call the public functions below and read/write [roamingEnabled].
 *
 * @param isInterrupted returns true while the bot is being dragged, or
 *        the menu / input overlay is open — movement must pause.
 * @param isOverlayBusy returns true only when the menu / input overlay
 *        is open (used by [startBobbing]'s entry guard, which — exactly
 *        like the original code — does not also check dragging there).
 * @param onPositionUpdate called after every position change so the
 *        service can reposition the bubble / listening overlay.
 */
class BotMovementController(
    private val context: Context,
    private val windowManager: WindowManager,
    private val botView: FloatingBotView,
    private val botParams: WindowManager.LayoutParams,
    private val screenWidth: Int,
    private val screenHeight: Int,
    private val botSizePx: Int,
    private val isInterrupted: () -> Boolean,
    private val isOverlayBusy: () -> Boolean,
    private val onPositionUpdate: () -> Unit
) {

    companion object {
        const val SNAP_ANIM_MS         = 320L
        const val MENU_CENTER_ANIM_MS  = 260L
    }

    /** Whether the bot should be free-roaming instead of bobbing in place. */
    var roamingEnabled = false

    // ── Bobbing ──
    private var bobbingAnimator: ValueAnimator? = null
    private var bobbingBaseY = 0

    // ── Roaming ──
    private var roamAnimX: ValueAnimator? = null
    private var roamAnimY: ValueAnimator? = null
    private val roamHandler = Handler(Looper.getMainLooper())
    private var roamRunnable: Runnable? = null
    var currentRoamX = 0f
    var currentRoamY = 0f
    private var targetRoamX = 0f
    private var targetRoamY = 0f

    // ── Menu center-move ──
    private var preMenuBotX = 0
    private var preMenuBotY = 0
    private var centerMoveAnimator: ValueAnimator? = null

    // ──────────────────────────────────────────────────────────
    // BOBBING
    // ──────────────────────────────────────────────────────────
    fun startBobbing() {
        if (isOverlayBusy()) return
        bobbingAnimator?.cancel()
        bobbingAnimator = ValueAnimator.ofFloat(-12f, 12f).apply {
            duration     = 1800
            repeatCount  = ValueAnimator.INFINITE
            repeatMode   = ValueAnimator.REVERSE
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener {
                if (!roamingEnabled && !isInterrupted()) {
                    botParams.y = bobbingBaseY + (it.animatedValue as Float).toInt()
                    try { windowManager.updateViewLayout(botView, botParams) } catch (e: Exception) { }
                    onPositionUpdate()
                }
            }
            start()
        }
    }

    fun stopBobbing() {
        bobbingAnimator?.cancel()
        bobbingAnimator = null
        bobbingBaseY    = botParams.y
    }

    /** Call this after the service moves the bot directly (e.g. drag) so bobbing resumes from the right Y. */
    fun syncBobbingBase() { bobbingBaseY = botParams.y }

    // ──────────────────────────────────────────────────────────
    // ROAMING
    // ──────────────────────────────────────────────────────────
    fun startRoaming()  { stopBobbing(); roamToNextTarget() }
    fun resumeRoaming() { roamToNextTarget() }

    fun pauseRoaming() {
        roamAnimX?.cancel(); roamAnimY?.cancel()
        roamRunnable?.let { roamHandler.removeCallbacks(it) }
    }

    fun stopRoaming() {
        roamAnimX?.cancel(); roamAnimY?.cancel()
        roamRunnable?.let { roamHandler.removeCallbacks(it) }
    }

    /** Call this after the service moves the bot directly (e.g. drag) so roaming resumes from the right spot. */
    fun syncRoamPosition() {
        currentRoamX = botParams.x.toFloat()
        currentRoamY = botParams.y.toFloat()
    }

    private fun roamToNextTarget() {
        if (!roamingEnabled || isInterrupted()) return

        val margin = if (Random.nextFloat() < 0.3f) botSizePx * 0.05f else botSizePx * 0.15f
        targetRoamX = Random.nextFloat() * (screenWidth  - botSizePx - margin * 2) + margin
        targetRoamY = Random.nextFloat() * (screenHeight - botSizePx - margin * 2) + margin

        val dx       = targetRoamX - currentRoamX
        val dy       = targetRoamY - currentRoamY
        val distance = sqrt(dx * dx + dy * dy)
        val speed    = 280f * context.resources.displayMetrics.density
        val duration = ((distance / speed) * 1000f).toLong().coerceIn(1000, 4200)

        botView.setRoamDirection(dx, dy)
        val fromX = currentRoamX
        val fromY = currentRoamY

        roamAnimX = ValueAnimator.ofFloat(fromX, targetRoamX).apply {
            this.duration = duration
            interpolator  = AccelerateDecelerateInterpolator()
            addUpdateListener {
                if (!isInterrupted()) {
                    currentRoamX = it.animatedValue as Float
                    botParams.x  = currentRoamX.toInt()
                    try { windowManager.updateViewLayout(botView, botParams) } catch (e: Exception) { }
                    onPositionUpdate()
                }
            }
            start()
        }

        roamAnimY = ValueAnimator.ofFloat(fromY, targetRoamY).apply {
            this.duration = duration
            startDelay    = 60
            interpolator  = AccelerateDecelerateInterpolator()
            addUpdateListener {
                if (!isInterrupted()) {
                    currentRoamY = it.animatedValue as Float
                    botParams.y  = currentRoamY.toInt()
                    try { windowManager.updateViewLayout(botView, botParams) } catch (e: Exception) { }
                    onPositionUpdate()
                }
            }
            doOnEnd {
                if (!isInterrupted()) {
                    botView.clearRoamDirection()
                    val pause = Random.nextLong(500, 2000)
                    roamRunnable = Runnable { roamToNextTarget() }
                    roamHandler.postDelayed(roamRunnable!!, pause)
                }
            }
            start()
        }
    }

    // ──────────────────────────────────────────────────────────
    // EDGE SNAP — on drag release when not roaming
    // ──────────────────────────────────────────────────────────
    fun snapToEdge() {
        val midX    = botParams.x + botSizePx / 2
        val targetX = if (midX < screenWidth / 2)
            -botSizePx / 8 else screenWidth - botSizePx + botSizePx / 8
        val startX  = botParams.x

        ValueAnimator.ofInt(startX, targetX).apply {
            duration     = SNAP_ANIM_MS
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                botParams.x = it.animatedValue as Int
                try { windowManager.updateViewLayout(botView, botParams) } catch (e: Exception) { }
                onPositionUpdate()
            }
            doOnEnd { bobbingBaseY = botParams.y; startBobbing() }
            start()
        }
    }

    // ──────────────────────────────────────────────────────────
    // MENU CENTER-MOVE — bot hops to screen center so the orbit
    // menu always has full room to open, then hops back after.
    // ──────────────────────────────────────────────────────────
    fun moveToCenter(onArrived: () -> Unit) {
        centerMoveAnimator?.cancel()
        preMenuBotX = botParams.x
        preMenuBotY = botParams.y
        val targetX = (screenWidth  - botSizePx) / 2
        val targetY = (screenHeight - botSizePx) / 2
        animateTo(targetX, targetY, onArrived)
    }

    fun moveBackFromCenter(onArrived: () -> Unit) {
        centerMoveAnimator?.cancel()
        animateTo(preMenuBotX, preMenuBotY) {
            currentRoamX = botParams.x.toFloat()
            currentRoamY = botParams.y.toFloat()
            bobbingBaseY = botParams.y
            onArrived()
        }
    }

    private fun animateTo(targetX: Int, targetY: Int, onArrived: () -> Unit) {
        val startX = botParams.x
        val startY = botParams.y

        centerMoveAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration     = MENU_CENTER_ANIM_MS
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                val f = it.animatedValue as Float
                botParams.x = (startX + (targetX - startX) * f).toInt()
                botParams.y = (startY + (targetY - startY) * f).toInt()
                try { windowManager.updateViewLayout(botView, botParams) } catch (e: Exception) { }
                onPositionUpdate()
            }
            doOnEnd { onArrived() }
            start()
        }
    }

    private fun ValueAnimator.doOnEnd(action: () -> Unit) {
        addListener(object : android.animation.Animator.AnimatorListener {
            override fun onAnimationEnd(a: android.animation.Animator)    = action()
            override fun onAnimationStart(a: android.animation.Animator)  {}
            override fun onAnimationCancel(a: android.animation.Animator) {}
            override fun onAnimationRepeat(a: android.animation.Animator) {}
        })
    }
}
