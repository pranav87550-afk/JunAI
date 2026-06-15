package com.junai.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class ChatMessage(
    val text: String,
    val isUser: Boolean,
    val timestamp: Long = System.currentTimeMillis()
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

    companion object {
        const val TYPE_USER = 1
        const val TYPE_JUN = 2
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
            holder.messageText.text = message.text
            holder.timeText.text = time

            // Get previous user message as question
            val question = if (position > 0 && messages[position - 1].isUser)
                messages[position - 1].text else ""

            // Copy button
            holder.copyButton.setOnClickListener {
                val clipboard = it.context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("JunAI", message.text))
                android.widget.Toast.makeText(it.context, "Copied!", android.widget.Toast.LENGTH_SHORT).show()
            }

            // Speak button
            holder.speakButton.setOnClickListener {
                actionListener?.onSpeak(message.text)
            }

            // Thumbs up
            holder.thumbsUpButton.setOnClickListener {
                actionListener?.onThumbsUp(message.text, question)
                android.widget.Toast.makeText(it.context, "👍 Good answer!", android.widget.Toast.LENGTH_SHORT).show()
            }

            // Thumbs down
            holder.thumbsDownButton.setOnClickListener {
                actionListener?.onThumbsDown(message.text, question)
                android.widget.Toast.makeText(it.context, "👎 Noted!", android.widget.Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun getItemCount() = messages.size

    class UserViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val messageText: TextView = view.findViewById(R.id.messageText)
        val timeText: TextView = view.findViewById(R.id.timeText)
    }

    class JunViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val messageText: TextView = view.findViewById(R.id.messageText)
        val timeText: TextView = view.findViewById(R.id.timeText)
        val copyButton: ImageButton = view.findViewById(R.id.copyButton)
        val speakButton: ImageButton = view.findViewById(R.id.speakButton)
        val thumbsUpButton: ImageButton = view.findViewById(R.id.thumbsUpButton)
        val thumbsDownButton: ImageButton = view.findViewById(R.id.thumbsDownButton)
    }

    fun addMessage(message: ChatMessage) {
        messages.add(message)
        notifyItemInserted(messages.size - 1)
    }
}
