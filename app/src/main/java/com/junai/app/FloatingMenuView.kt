package com.junai.app

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.content.Context
import android.graphics.Color
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.animation.OvershootInterpolator
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout

class FloatingMenuView(context: Context) : FrameLayout(context) {

    enum class MenuAction { OPEN_APP, MESSAGE, SPEAK, BACK, HIDE }

    var onActionSelected: ((MenuAction) -> Unit)? = null
    var onDismiss: (() -> Unit)? = null

    private val container: LinearLayout
    private val icons = mutableListOf<ImageView>()
    private var isShowing = false

    private val iconSizeDp = 56
    private val iconSpacingDp = 14

    init {
        container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }
        addView(container, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.CENTER
        ))

        buildIcon(R.drawable.ic_bot_hide,    MenuAction.HIDE)
        buildIcon(R.drawable.ic_bot_message, MenuAction.MESSAGE)
        buildIcon(R.drawable.ic_bot_speak,   MenuAction.SPEAK)
        buildIcon(R.drawable.ic_bot_home,    MenuAction.OPEN_APP)
        buildIcon(R.drawable.ic_bot_back,    MenuAction.BACK)

        // Background — fully transparent, click outside to dismiss
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
                MotionEvent.ACTION_DOWN -> {
                    pressEffect(v, true)
                    true
                }
                MotionEvent.ACTION_UP -> {
                    pressEffect(v, false)
                    v.performClick()
                    onActionSelected?.invoke(action)
                    hideMenu()
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    pressEffect(v, false)
                    true
                }
                else -> false
            }
        }
        icon.setOnClickListener { /* handled via touch for press effect */ }

        icons.add(icon)
        container.addView(icon)
    }

    // ──────────────────────────────────────────────────────────
    // PRESS EFFECT — crystal squeeze + glow pulse
    // ──────────────────────────────────────────────────────────
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
    // SHOW — staggered slide + fade + scale in
    // ──────────────────────────────────────────────────────────
    fun showMenu() {
        if (isShowing) return
        isShowing = true
        visibility = View.VISIBLE

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

    // ──────────────────────────────────────────────────────────
    // HIDE — reverse staggered animation
    // ──────────────────────────────────────────────────────────
    fun hideMenu() {
        if (!isShowing) return
        isShowing = false

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
}
