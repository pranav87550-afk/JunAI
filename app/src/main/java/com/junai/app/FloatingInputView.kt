package com.junai.app

import android.animation.ObjectAnimator
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.animation.OvershootInterpolator
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout

class FloatingInputView(context: Context) : FrameLayout(context) {

    var onSubmit: ((String) -> Unit)? = null
    var onDismiss: (() -> Unit)? = null

    private val editText: EditText
    private val card: LinearLayout

    init {
        setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_DOWN) {
                dismiss()
            }
            true
        }

        card = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val pad = dp(10)
            setPadding(pad, pad, pad, pad)
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(28).toFloat()
                setColor(Color.parseColor("#1A1A1F"))
                setStroke(dp(2), Color.parseColor("#FF4444"))
            }
            // Block touches on the card from dismissing
            setOnTouchListener { _, _ -> true }
        }

        editText = EditText(context).apply {
            hint = "Jun se kuch kaho..."
            setHintTextColor(Color.parseColor("#888888"))
            setTextColor(Color.WHITE)
            background = null
            inputType = InputType.TYPE_CLASS_TEXT
            imeOptions = EditorInfo.IME_ACTION_SEND
            textSize = 15f
            maxLines = 1
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = dp(8)
                marginEnd = dp(8)
            }
            setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_SEND) {
                    submitText()
                    true
                } else false
            }
        }

        val sendBtn = ImageButton(context).apply {
            setImageResource(R.drawable.ic_send)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#FF4444"))
            }
            val size = dp(40)
            layoutParams = LinearLayout.LayoutParams(size, size)
            setPadding(dp(8), dp(8), dp(8), dp(8))
            setOnClickListener { submitText() }
        }

        card.addView(editText)
        card.addView(sendBtn)

        addView(card, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.CENTER
        ).apply {
            width = dp(280)
        })

        card.alpha = 0f
        card.scaleX = 0.7f
        card.scaleY = 0.7f
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun submitText() {
        val text = editText.text.toString().trim()
        if (text.isNotEmpty()) {
            onSubmit?.invoke(text)
        }
        dismiss()
    }

    fun show() {
        visibility = View.VISIBLE
        card.alpha = 0f
        card.scaleX = 0.7f
        card.scaleY = 0.7f
        card.animate()
            .alpha(1f)
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(220)
            .setInterpolator(OvershootInterpolator(2f))
            .withEndAction {
                editText.requestFocus()
                val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE)
                        as android.view.inputmethod.InputMethodManager
                imm.showSoftInput(editText, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
            }
            .start()
    }

    fun dismiss() {
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE)
                as android.view.inputmethod.InputMethodManager
        imm.hideSoftInputFromWindow(editText.windowToken, 0)

        card.animate()
            .alpha(0f)
            .scaleX(0.7f)
            .scaleY(0.7f)
            .setDuration(150)
            .withEndAction {
                visibility = View.GONE
                editText.setText("")
                onDismiss?.invoke()
            }
            .start()
    }
}
