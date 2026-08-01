package com.junai.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.LeadingMarginSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Renders Qwen3's raw Markdown-ish output as real formatting instead of
 * leaving literal '**'/'#'/'-' characters on screen. Deliberately a
 * small hand-rolled parser (not a full Markdown library) — it only
 * covers the patterns Jun actually emits: **bold**, "# Heading" /
 * "## Heading" lines, and "- item" / "* item" bullet lines. Paragraph
 * spacing otherwise comes from the model's own blank lines, which
 * TextView already renders as a gap; lineSpacingExtra in the layout XML
 * adds the rest of the breathing room.
 *
 * Safe on partial/mid-stream text (used during the reveal animation):
 * an unclosed "**" or a bullet line with no following content just
 * doesn't get styled yet, and resolves itself as more text arrives.
 */
private val boldMarkdownRegex = Regex("\\*\\*(.+?)\\*\\*")
private val headerLineRegex = Regex("^(#{1,3})\\s+(.*)$")
private val bulletLineRegex = Regex("^[-*](?!\\*)\\s+(.*)$")
private const val BULLET_INDENT_PX = 36

private fun appendInlineBold(builder: SpannableStringBuilder, line: String) {
    if (!line.contains("**")) {
        builder.append(line)
        return
    }
    var lastEnd = 0
    for (match in boldMarkdownRegex.findAll(line)) {
        builder.append(line, lastEnd, match.range.first)
        val boldText = match.groupValues[1]
        val start = builder.length
        builder.append(boldText)
        builder.setSpan(StyleSpan(Typeface.BOLD), start, builder.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        lastEnd = match.range.last + 1
    }
    builder.append(line, lastEnd, line.length)
}

private fun renderMarkdown(raw: String): CharSequence {
    if (raw.isBlank()) return raw
    // Safety net alongside the SYSTEM_INSTRUCTION rule against LaTeX
    // (GGUFChatEngine.kt) — a 0.6B model doesn't always obey prompt
    // rules perfectly, and unrendered "$ A = P(1+r/n)^{nt} $" reads as
    // broken/buggy rather than just plain. Strips the $ delimiters and
    // ^{...}/_{...} exponent-braces so what's left is at least readable
    // plain text instead of raw LaTeX syntax, same "can't guarantee the
    // model followed the rule, so clean up after it" reasoning as
    // stripLeakedContextBrackets() in ChatIntentHandler.kt.
    val delatexed = raw
        .replace(Regex("\\$([^$]+)\\$")) { it.groupValues[1] }
        .replace(Regex("\\^\\{([^}]+)\\}"), "^$1")
        .replace(Regex("_\\{([^}]+)\\}"), "_$1")
    val builder = SpannableStringBuilder()
    val lines = delatexed.split("\n")

    lines.forEachIndexed { index, line ->
        val headerMatch = headerLineRegex.find(line)
        val bulletMatch = bulletLineRegex.find(line)

        when {
            headerMatch != null -> {
                val start = builder.length
                appendInlineBold(builder, headerMatch.groupValues[2])
                builder.setSpan(StyleSpan(Typeface.BOLD), start, builder.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                builder.setSpan(RelativeSizeSpan(1.1f), start, builder.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            bulletMatch != null -> {
                val start = builder.length
                builder.append("•  ")
                appendInlineBold(builder, bulletMatch.groupValues[1])
                builder.setSpan(
                    LeadingMarginSpan.Standard(0, BULLET_INDENT_PX),
                    start, builder.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
            }
            else -> appendInlineBold(builder, line)
        }

        if (index != lines.lastIndex) builder.append("\n")
    }

    return builder
}

data class ChatMessage(
    val text: String,
    val isUser: Boolean,
    val timestamp: Long = System.currentTimeMillis(),
    // isThinking=true while Qwen3 is still streaming — the bubble shows
    // "Jun is thinking…" as its main text and force-expands the
    // thinkingText dropdown (live, not click-to-open) so the user has
    // something to read while waiting. Once the final answer arrives,
    // isThinking flips to false and `text` becomes the real answer —
    // thinkingText is kept around so the dropdown still works
    // afterwards, just collapsed-by-default like Claude's "Show
    // thinking", toggled by tapping thinkingHeader.
    val isThinking: Boolean = false,
    val thinkingText: String = "",
    // True for non-answer status bubbles Jun shows in the chat stream —
    // e.g. "Jun response is interrupted". These aren't a real answer, so
    // copy/speak/thumbs-up/thumbs-down don't make sense on them the way
    // they do on an actual reply. Default false so every existing call
    // site (real Jun answers) is completely untouched.
    val isSystem: Boolean = false
)

interface ChatActionListener {
    fun onSpeak(text: String)
    fun onThumbsUp(text: String, question: String)
    fun onThumbsDown(text: String, question: String)
}

class ChatAdapter(
    private val messages: MutableList<ChatMessage>,
    private val actionListener: ChatActionListener? = null
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    // Fired whenever a Jun (bot) reply is added — lets MainActivity know
    // it's safe to re-enable the send button.
    var onBotMessageAdded: (() -> Unit)? = null

    // Drives the animated "Jun is thinking." → ".." → "..." dots.
    // One shared Handler; each JunViewHolder tracks its own Runnable so
    // recycled views don't keep an old animation running against a
    // view that's now bound to a different message.
    private val thinkingHandler = android.os.Handler(android.os.Looper.getMainLooper())

    // Which finished (isThinking=false) messages currently have their
    // thinking dropdown expanded — keyed by timestamp (stable id, see
    // getItemId). While a message isThinking=true its dropdown is always
    // force-expanded regardless of this set (see onBindViewHolder).
    private val expandedThinkingIds = mutableSetOf<Long>()

    companion object {
        const val TYPE_USER = 1
        const val TYPE_JUN = 2
        const val MAX_MESSAGES = 200
    }

    override fun getItemViewType(position: Int): Int {
        return if (messages[position].isUser) TYPE_USER else TYPE_JUN
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        return if (viewType == TYPE_USER) {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_message_user, parent, false)
            UserViewHolder(view)
        } else {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_message_jun, parent, false)
            JunViewHolder(view)
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val message = messages[position]
        val time = SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date(message.timestamp))

        if (holder is UserViewHolder) {
            holder.messageText.text = message.text
            holder.timeText.text = time
        } else if (holder is JunViewHolder) {
            if (message.isThinking) {
                startThinkingAnimation(holder)
            } else {
                stopThinkingAnimation(holder)
                holder.messageText.text = renderMarkdown(message.text)
            }
            holder.timeText.text = time

            val question = if (position > 0 && messages[position - 1].isUser)
                messages[position - 1].text else ""

            // Thinking dropdown: hidden entirely if there's no thinking
            // text at all. While isThinking=true, force-expanded (live —
            // grows as more streams in, never click-to-open, so the user
            // always sees current progress). Once finished, becomes a
            // normal tap-to-expand/collapse toggle.
            if (message.thinkingText.isBlank()) {
                holder.thinkingHeader.visibility = View.GONE
                holder.thinkingContent.visibility = View.GONE
            } else {
                holder.thinkingHeader.visibility = View.VISIBLE
                val expanded = message.isThinking || expandedThinkingIds.contains(message.timestamp)
                holder.thinkingHeader.text = if (expanded) "🤔 Thinking ▾" else "🤔 Thinking ▸"
                holder.thinkingContent.visibility = if (expanded) View.VISIBLE else View.GONE
                holder.thinkingContent.text = renderMarkdown(message.thinkingText)
                if (message.isThinking) {
                    // No click-toggle while still streaming — it's
                    // always expanded, nothing to toggle yet.
                    holder.thinkingHeader.setOnClickListener(null)
                } else {
                    holder.thinkingHeader.setOnClickListener {
                        if (expandedThinkingIds.contains(message.timestamp)) {
                            expandedThinkingIds.remove(message.timestamp)
                        } else {
                            expandedThinkingIds.add(message.timestamp)
                        }
                        notifyItemChanged(position)
                    }
                }
            }

            // Action buttons only make sense once there's a real answer —
            // hide them entirely while still thinking, same reasoning as
            // not auto-speaking a mid-stream partial answer. Also hidden
            // for isSystem status bubbles (e.g. "Jun response is
            // interrupted") since there's nothing to copy/speak/rate.
            val actionButtonsVisibility = if (message.isThinking || message.isSystem) View.GONE else View.VISIBLE
            holder.copyButton.visibility = actionButtonsVisibility
            holder.speakButton.visibility = actionButtonsVisibility
            holder.thumbsUpButton.visibility = actionButtonsVisibility
            holder.thumbsDownButton.visibility = actionButtonsVisibility

            holder.copyButton.setOnClickListener {
                val clipboard = it.context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("JunAI", message.text))
                android.widget.Toast.makeText(it.context, "Copied!", android.widget.Toast.LENGTH_SHORT).show()
            }

            holder.speakButton.setOnClickListener {
                actionListener?.onSpeak(message.text)
            }

            holder.thumbsUpButton.setOnClickListener {
                actionListener?.onThumbsUp(message.text, question)
                android.widget.Toast.makeText(it.context, "👍 Good answer!", android.widget.Toast.LENGTH_SHORT).show()
            }

            holder.thumbsDownButton.setOnClickListener {
                actionListener?.onThumbsDown(message.text, question)
                android.widget.Toast.makeText(it.context, "👎 Noted!", android.widget.Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun getItemCount() = messages.size

    // DiffUtil for better performance
    override fun getItemId(position: Int): Long {
        return messages[position].timestamp
    }

    // Cycles "Jun is thinking" → "Jun is thinking." → ".." → "..." → back
    // to no dots, every 400ms, until stopThinkingAnimation() is called
    // (message finishes) or the view gets recycled.
    private fun startThinkingAnimation(holder: JunViewHolder) {
        stopThinkingAnimation(holder)
        var dotCount = 0
        val runnable = object : Runnable {
            override fun run() {
                holder.messageText.text = "Jun is thinking" + ".".repeat(dotCount)
                dotCount = (dotCount + 1) % 4
                thinkingHandler.postDelayed(this, 400)
            }
        }
        holder.thinkingRunnable = runnable
        thinkingHandler.post(runnable)
    }

    private fun stopThinkingAnimation(holder: JunViewHolder) {
        holder.thinkingRunnable?.let { thinkingHandler.removeCallbacks(it) }
        holder.thinkingRunnable = null
    }

    override fun onViewRecycled(holder: RecyclerView.ViewHolder) {
        super.onViewRecycled(holder)
        if (holder is JunViewHolder) stopThinkingAnimation(holder)
    }

    class UserViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val messageText: TextView = view.findViewById(R.id.messageText)
        val timeText: TextView = view.findViewById(R.id.timeText)
    }

    class JunViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val messageText: TextView = view.findViewById(R.id.messageText)
        val timeText: TextView = view.findViewById(R.id.timeText)
        val thinkingHeader: TextView = view.findViewById(R.id.thinkingHeader)
        val thinkingContent: TextView = view.findViewById(R.id.thinkingContent)
        val copyButton: ImageButton = view.findViewById(R.id.copyButton)
        // Tracks the currently-running dots-animation Runnable for this
        // recycled view, if any — see startThinkingAnimation/stopThinkingAnimation.
        var thinkingRunnable: Runnable? = null
        val speakButton: ImageButton = view.findViewById(R.id.speakButton)
        val thumbsUpButton: ImageButton = view.findViewById(R.id.thumbsUpButton)
        val thumbsDownButton: ImageButton = view.findViewById(R.id.thumbsDownButton)
    }

    fun addMessage(message: ChatMessage) {
        // Max 200 messages limit
        if (messages.size >= MAX_MESSAGES) {
            messages.removeAt(0)
            notifyItemRemoved(0)
        }
        messages.add(message)
        notifyItemInserted(messages.size - 1)

        if (!message.isUser) {
            onBotMessageAdded?.invoke()
        }
    }

    /** Index of the most recently added message, or -1 if empty. Callers
     * use this right after addMessage() to remember which position to
     * later update via updateMessageAt() — e.g. the Qwen3 "thinking"
     * bubble that gets rewritten in place as streaming progresses. */
    fun lastIndex(): Int = messages.size - 1

    /**
     * Rewrites the message at `index` in place (e.g. a "thinking" bubble
     * being updated as more of Qwen3's stream arrives, or finally
     * becoming the real answer) and notifies just that item changed —
     * no insert/remove, so the message's position in the list is
     * unaffected. No-op if index is out of range (defensive — the
     * message list has a MAX_MESSAGES cap that can shift things).
     */
    fun updateMessageAt(index: Int, message: ChatMessage) {
        if (index !in messages.indices) return
        messages[index] = message
        notifyItemChanged(index)
    }

    fun updateMessages(newMessages: List<ChatMessage>) {
        val diffResult = DiffUtil.calculateDiff(object : DiffUtil.Callback() {
            override fun getOldListSize() = messages.size
            override fun getNewListSize() = newMessages.size
            override fun areItemsTheSame(oldPos: Int, newPos: Int) =
                messages[oldPos].timestamp == newMessages[newPos].timestamp
            override fun areContentsTheSame(oldPos: Int, newPos: Int) =
                messages[oldPos] == newMessages[newPos]
        })
        messages.clear()
        messages.addAll(newMessages)
        diffResult.dispatchUpdatesTo(this)
    }
}
