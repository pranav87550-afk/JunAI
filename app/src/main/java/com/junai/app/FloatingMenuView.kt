package com.junai.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.view.MotionEvent
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * Circular "orbit" action menu for the floating bot.
 * Eight action icons are arranged evenly around the bot in a ring
 * and slowly rotate in a continuous loop while the menu is open.
 * Each icon counter-rotates so its glyph always stays upright.
 */
class FloatingMenuView(context: Context) : FrameLayout(context) {

    enum class MenuAction {
        OPEN_APP, MESSAGE, SPEAK, BACK, HIDE, ROAM, EYE_MOVEMENT, HELP
    }

    var onActionSelected: ((MenuAction) -> Unit)? = null
    var onDismiss: (() -> Unit)? = null

    // ── Sizing ──
    private val iconSizeDp     = 56          // slightly bigger icons
    private val orbitRadiusDp  = 110         // larger orbit radius (was 86)
    private val orbitDiameterDp = orbitRadiusDp * 2 + iconSizeDp + 24

    // ── Gap from center so spokes don't draw over the bot face ──
    private val spokeGapDp = 42             // don't draw spokes inside this radius

    // ── Views ──
    private val orbitContainer: FrameLayout
    private val spokeView: SpokeLineView

    private data class Slot(val view: View, val angleDeg: Float)
    private val slots = mutableListOf<Slot>()

    private var isShowing = false
    private var orbitAnimator: android.animation.ValueAnimator? = null

    init {
        val orbitSizePx = dpToPx(orbitDiameterDp)

        orbitContainer = FrameLayout(context)
        addView(orbitContainer, FrameLayout.LayoutParams(orbitSizePx, orbitSizePx))

        spokeView = SpokeLineView(context, dpToPx(spokeGapDp).toFloat())
        orbitContainer.addView(spokeView, FrameLayout.LayoutParams(orbitSizePx, orbitSizePx))

        // Eight actions, evenly spaced 45° apart
        buildIcon(R.drawable.ic_bot_hide,    MenuAction.HIDE,          -90f)
        buildIcon(R.drawable.ic_bot_speak,   MenuAction.SPEAK,         -45f)
        buildIcon(R.drawable.ic_bot_home,    MenuAction.OPEN_APP,        0f)
        buildIcon(R.drawable.ic_bot_help,    MenuAction.HELP,           45f)
        buildIcon(R.drawable.ic_bot_back,    MenuAction.BACK,           90f)
        buildIcon(R.drawable.ic_bot_eyes,    MenuAction.EYE_MOVEMENT,  135f)
        buildIcon(R.drawable.ic_bot_roam,    MenuAction.ROAM,          180f)
        buildIcon(R.drawable.ic_bot_message, MenuAction.MESSAGE,       225f)

        setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_DOWN) {
                hideMenu()
                onDismiss?.invoke()
            }
            true
        }
        visibility = View.GONE
    }

    private fun dpToPx(dp: Int): Int = (dp * resources.displayMetrics.density).toInt()

    private fun buildIcon(resId: Int, action: MenuAction, angleDeg: Float) {
        val size = dpToPx(iconSizeDp)
        val icon = IconButtonView(context, resId).apply {
            alpha = 0f
            scaleX = 0.3f
            scaleY = 0.3f
            isClickable = true
            isFocusable = true
        }

        icon.setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> { pressEffect(v, true); true }
                MotionEvent.ACTION_UP -> {
                    pressEffect(v, false)
                    v.performClick()
                    onActionSelected?.invoke(action)
                    hideMenu()
                    true
                }
                MotionEvent.ACTION_CANCEL -> { pressEffect(v, false); true }
                else -> false
            }
        }
        icon.setOnClickListener { }

        orbitContainer.addView(icon, FrameLayout.LayoutParams(size, size))
        slots.add(Slot(icon, angleDeg))
    }

    private fun pressEffect(view: View, pressed: Boolean) {
        val targetScale = if (pressed) 0.82f else 1f
        val targetAlpha = if (pressed) 0.7f else 1f
        view.animate()
            .scaleX(targetScale).scaleY(targetScale).alpha(targetAlpha)
            .setDuration(if (pressed) 90 else 160)
            .setInterpolator(if (pressed) DecelerateInterpolator() else OvershootInterpolator(3f))
            .start()
    }

    fun positionRelativeToBot(botX: Int, botY: Int, botSize: Int, screenWidth: Int) {
        val orbitSizePx = dpToPx(orbitDiameterDp)
        val botCenterX = botX + botSize / 2
        val botCenterY = botY + botSize / 2

        val params = orbitContainer.layoutParams as FrameLayout.LayoutParams
        params.leftMargin = botCenterX - orbitSizePx / 2
        params.topMargin  = botCenterY - orbitSizePx / 2
        orbitContainer.layoutParams = params

        placeIconsAtBaseAngles()
    }

    private fun placeIconsAtBaseAngles() {
        val center = dpToPx(orbitDiameterDp) / 2f
        val radiusPx = dpToPx(orbitRadiusDp).toFloat()
        val targets = mutableListOf<Pair<Float, Float>>()

        slots.forEach { slot ->
            val rad = Math.toRadians(slot.angleDeg.toDouble())
            val cx = center + radiusPx * cos(rad).toFloat()
            val cy = center + radiusPx * sin(rad).toFloat()
            val half = slot.view.layoutParams.width / 2f

            val lp = slot.view.layoutParams as FrameLayout.LayoutParams
            lp.leftMargin = (cx - half).toInt()
            lp.topMargin  = (cy - half).toInt()
            slot.view.layoutParams = lp

            targets.add(Pair(cx, cy))
        }
        spokeView.setSpokes(center, center, targets)
    }

    fun showMenu() {
        if (isShowing) return
        isShowing = true
        visibility = View.VISIBLE
        spokeView.animateIn()

        slots.forEachIndexed { index, slot ->
            slot.view.alpha = 0f
            slot.view.scaleX = 0.3f
            slot.view.scaleY = 0.3f
            slot.view.animate()
                .alpha(1f).scaleX(1f).scaleY(1f)
                .setStartDelay((index * 40).toLong())
                .setDuration(280)
                .setInterpolator(OvershootInterpolator(2.2f))
                .start()
        }
        startOrbitRotation()
    }

    fun hideMenu() {
        if (!isShowing) return
        isShowing = false
        spokeView.animateOut()
        stopOrbitRotation()

        slots.forEachIndexed { index, slot ->
            slot.view.animate()
                .alpha(0f).scaleX(0.3f).scaleY(0.3f)
                .setStartDelay((index * 20).toLong())
                .setDuration(160)
                .setInterpolator(DecelerateInterpolator())
                .withEndAction { if (index == slots.size - 1) visibility = View.GONE }
                .start()
        }
    }

    fun isMenuShowing() = isShowing

    private fun startOrbitRotation() {
        orbitAnimator?.cancel()
        orbitAnimator = android.animation.ValueAnimator.ofFloat(0f, 360f).apply {
            duration = 24000
            repeatCount = android.animation.ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener {
                val angle = it.animatedValue as Float
                orbitContainer.rotation = angle
                slots.forEach { slot -> slot.view.rotation = -angle }
            }
            start()
        }
    }

    private fun stopOrbitRotation() {
        orbitAnimator?.cancel()
        orbitAnimator = null
        orbitContainer.rotation = 0f
        slots.forEach { it.view.rotation = 0f }
    }

    // ──────────────────────────────────────────────────────────
    // DOTTED SPOKE LINES — center to each icon, with gap near bot
    // ──────────────────────────────────────────────────────────
    private class SpokeLineView(context: Context, private val gapRadius: Float) : View(context) {
        private var centerX = 0f
        private var centerY = 0f
        private var targets: List<Pair<Float, Float>> = emptyList()
        private var progress = 0f

        private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#E53935")
            strokeWidth = 2.2f * context.resources.displayMetrics.density
            style = Paint.Style.STROKE
            alpha = 180
            pathEffect = DashPathEffect(floatArrayOf(6f, 7f), 0f)
        }

        fun setSpokes(cx: Float, cy: Float, points: List<Pair<Float, Float>>) {
            centerX = cx; centerY = cy; targets = points
            invalidate()
        }

        fun animateIn() {
            progress = 0f
            android.animation.ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 280
                addUpdateListener { progress = it.animatedValue as Float; invalidate() }
                start()
            }
        }

        fun animateOut() {
            android.animation.ValueAnimator.ofFloat(1f, 0f).apply {
                duration = 160
                addUpdateListener { progress = it.animatedValue as Float; invalidate() }
                start()
            }
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            if (progress <= 0f || targets.isEmpty()) return
            targets.forEach { (tx, ty) ->
                // Full line endpoint (animated by progress)
                val ex = centerX + (tx - centerX) * progress
                val ey = centerY + (ty - centerY) * progress

                // Compute start point offset by gapRadius from center
                val dx = tx - centerX
                val dy = ty - centerY
                val dist = hypot(dx, dy)
                val startX = centerX + dx / dist * gapRadius
                val startY = centerY + dy / dist * gapRadius

                // Only draw if endpoint is beyond the gap
                if (hypot(ex - centerX, ey - centerY) > gapRadius) {
                    canvas.drawLine(startX, startY, ex, ey, dotPaint)
                }
            }
        }
    }

    // ──────────────────────────────────────────────────────────
    // ICON BUTTON — dark circle bg + red glow ring + white icon
    // ──────────────────────────────────────────────────────────
    private inner class IconButtonView(context: Context, resId: Int) : FrameLayout(context) {

        private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#1A1A22")
            style = Paint.Style.FILL
        }
        private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#E53935")
            style = Paint.Style.STROKE
            strokeWidth = 2.5f * resources.displayMetrics.density
        }
        private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#55E53935")
            style = Paint.Style.STROKE
            strokeWidth = 7f * resources.displayMetrics.density
        }

        init {
            setWillNotDraw(false)
            val icon = ImageView(context).apply {
                setImageResource(resId)
                scaleType = ImageView.ScaleType.CENTER_INSIDE
                val pad = dpToPx(10)
                setPadding(pad, pad, pad, pad)
            }
            addView(icon, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        }

        override fun onDraw(canvas: Canvas) {
            val cx = width / 2f
            val cy = height / 2f
            val r  = (width / 2f) - ringPaint.strokeWidth

            // Outer soft glow
            canvas.drawCircle(cx, cy, r, glowPaint)
            // Dark fill
            canvas.drawCircle(cx, cy, r, bgPaint)
            // Crisp red ring
            canvas.drawCircle(cx, cy, r, ringPaint)

            super.onDraw(canvas)
        }

        private fun dpToPx(dp: Int): Int = (dp * resources.displayMetrics.density).toInt()
    }
}
