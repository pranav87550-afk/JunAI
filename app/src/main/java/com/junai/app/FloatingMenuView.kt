package com.junai.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout

class FloatingMenuView(context: Context) : FrameLayout(context) {

    enum class MenuAction { OPEN_APP, MESSAGE, SPEAK, BACK, HIDE }

    var onActionSelected: ((MenuAction) -> Unit)? = null
    var onDismiss: (() -> Unit)? = null

    private val container: LinearLayout
    private val lineView: ConnectorLineView
    private val icons = mutableListOf<ImageView>()
    private var isShowing = false

    private val iconSizeDp = 56
    private val iconSpacingDp = 16

    init {
        // Connector line — drawn behind icons
        lineView = ConnectorLineView(context)
        addView(lineView, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        ))

        container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }
        addView(container, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        ))

        buildIcon(R.drawable.ic_bot_hide,    MenuAction.HIDE)
        buildIcon(R.drawable.ic_bot_message, MenuAction.MESSAGE)
        buildIcon(R.drawable.ic_bot_speak,   MenuAction.SPEAK)
        buildIcon(R.drawable.ic_bot_home,    MenuAction.OPEN_APP)
        buildIcon(R.drawable.ic_bot_back,    MenuAction.BACK)

        setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_DOWN) {
                hideMenu()
                onDismiss?.invoke()
            }
            true
        }
        visibility = View.GONE
    }

    private fun dpToPx(dp: Int): Int =
        (dp * resources.displayMetrics.density).toInt()

    private fun buildIcon(resId: Int, action: MenuAction) {
        val size = dpToPx(iconSizeDp)
        val icon = ImageView(context).apply {
            setImageResource(resId)
            layoutParams = LinearLayout.LayoutParams(size, size).apply {
                topMargin = if (icons.isEmpty()) 0 else dpToPx(iconSpacingDp)
            }
            scaleType = ImageView.ScaleType.FIT_CENTER
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

        icons.add(icon)
        container.addView(icon)
    }

    private fun pressEffect(view: View, pressed: Boolean) {
        val targetScale = if (pressed) 0.82f else 1f
        val targetAlpha = if (pressed) 0.7f else 1f

        view.animate()
            .scaleX(targetScale)
            .scaleY(targetScale)
            .alpha(targetAlpha)
            .setDuration(if (pressed) 90 else 160)
            .setInterpolator(if (pressed) DecelerateInterpolator() else OvershootInterpolator(3f))
            .start()
    }

    // ──────────────────────────────────────────────────────────
    // POSITION — left or right of bot, vertically centered on it
    // ──────────────────────────────────────────────────────────
    fun positionRelativeToBot(botX: Int, botY: Int, botSize: Int, screenWidth: Int) {
        val iconSize  = dpToPx(iconSizeDp)
        val spacing   = dpToPx(iconSpacingDp)
        val menuWidth = iconSize
        val menuHeight = iconSize * icons.size + spacing * (icons.size - 1)

        val botCenterX = botX + botSize / 2
        val botCenterY = botY + botSize / 2
        val gap = dpToPx(20)

        // Decide left or right based on bot position
        val openRight = botCenterX < screenWidth / 2

        val menuLeft = if (openRight) {
            botX + botSize + gap
        } else {
            botX - gap - menuWidth
        }
        val menuTop = (botCenterY - menuHeight / 2).coerceAtLeast(dpToPx(40))

        val containerParams = container.layoutParams as FrameLayout.LayoutParams
        containerParams.leftMargin = menuLeft
        containerParams.topMargin  = menuTop
        container.layoutParams = containerParams

        // Position connector line from bot edge to menu
        val lineStartX = if (openRight) botX + botSize else botX
        val lineEndX   = if (openRight) menuLeft else menuLeft + menuWidth
        lineView.setLine(lineStartX, botCenterY, lineEndX, botCenterY)
    }

    // ──────────────────────────────────────────────────────────
    // SHOW
    // ──────────────────────────────────────────────────────────
    fun showMenu() {
        if (isShowing) return
        isShowing = true
        visibility = View.VISIBLE
        lineView.animateLine()

        icons.forEachIndexed { index, icon ->
            icon.alpha = 0f
            icon.scaleX = 0.3f
            icon.scaleY = 0.3f
            icon.translationY = 30f

            icon.animate()
                .alpha(1f)
                .scaleX(1f)
                .scaleY(1f)
                .translationY(0f)
                .setStartDelay((index * 50).toLong())
                .setDuration(280)
                .setInterpolator(OvershootInterpolator(2.2f))
                .start()
        }
    }

    fun hideMenu() {
        if (!isShowing) return
        isShowing = false
        lineView.hideLine()

        icons.forEachIndexed { index, icon ->
            icon.animate()
                .alpha(0f)
                .scaleX(0.3f)
                .scaleY(0.3f)
                .translationY(20f)
                .setStartDelay(((icons.size - 1 - index) * 30).toLong())
                .setDuration(180)
                .setInterpolator(DecelerateInterpolator())
                .withEndAction {
                    if (index == 0) visibility = View.GONE
                }
                .start()
        }
    }

    fun isMenuShowing() = isShowing

    // ──────────────────────────────────────────────────────────
    // CONNECTOR LINE — simple animated line view
    // ──────────────────────────────────────────────────────────
    private class ConnectorLineView(context: Context) : View(context) {
        private var startX = 0f
        private var startY = 0f
        private var endX   = 0f
        private var endY   = 0f
        private var progress = 0f

        private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#FF4444")
            strokeWidth = 3f
            style = Paint.Style.STROKE
            alpha = 180
        }

        fun setLine(sx: Int, sy: Int, ex: Int, ey: Int) {
            startX = sx.toFloat(); startY = sy.toFloat()
            endX   = ex.toFloat(); endY   = ey.toFloat()
            invalidate()
        }

        fun animateLine() {
            progress = 0f
            android.animation.ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 250
                addUpdateListener {
                    progress = it.animatedValue as Float
                    invalidate()
                }
                start()
            }
        }

        fun hideLine() {
            android.animation.ValueAnimator.ofFloat(1f, 0f).apply {
                duration = 150
                addUpdateListener {
                    progress = it.animatedValue as Float
                    invalidate()
                }
                start()
            }
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            if (progress <= 0f) return
            val currentEndX = startX + (endX - startX) * progress
            val currentEndY = startY + (endY - startY) * progress
            canvas.drawLine(startX, startY, currentEndX, currentEndY, linePaint)
        }
    }
}
