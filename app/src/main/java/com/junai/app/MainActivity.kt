package com.junai.app

import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.EditText
import android.widget.ImageButton
import android.widget.TextView
import androidx.core.view.GravityCompat
import androidx.drawerlayout.widget.DrawerLayout
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

class MainActivity : AppCompatActivity() {

    private lateinit var drawerLayout: DrawerLayout
    private lateinit var chatAdapter: ChatAdapter
    private val messages = mutableListOf<ChatMessage>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        drawerLayout = findViewById(R.id.drawerLayout)

        // Menu button
        findViewById<ImageButton>(R.id.menuButton).setOnClickListener {
            if (drawerLayout.isDrawerOpen(GravityCompat.START)) {
                drawerLayout.closeDrawer(GravityCompat.START)
            } else {
                drawerLayout.openDrawer(GravityCompat.START)
            }
        }

        // RecyclerView setup
        val recyclerView = findViewById<RecyclerView>(R.id.chatRecyclerView)
        chatAdapter = ChatAdapter(messages)
        recyclerView.adapter = chatAdapter
        recyclerView.layoutManager = LinearLayoutManager(this).also {
            it.stackFromEnd = true
        }

        // Typing indicator
        val typingIndicator = findViewById<TextView>(R.id.typingIndicator)

        // Send button
        val messageInput = findViewById<EditText>(R.id.messageInput)
        findViewById<ImageButton>(R.id.sendButton).setOnClickListener {
            val text = messageInput.text.toString().trim()
            if (text.isEmpty()) return@setOnClickListener

            // Add user message
            chatAdapter.addMessage(ChatMessage(text, isUser = true))
            messageInput.setText("")
            recyclerView.scrollToPosition(messages.size - 1)

            // Show typing indicator
            typingIndicator.visibility = android.view.View.VISIBLE

            // Reply after delay
            Handler(Looper.getMainLooper()).postDelayed({
                typingIndicator.visibility = android.view.View.GONE
                chatAdapter.addMessage(ChatMessage("In development", isUser = false))
                recyclerView.scrollToPosition(messages.size - 1)
            }, 1000)
        }
    }

    override fun onBackPressed() {
        if (drawerLayout.isDrawerOpen(GravityCompat.START)) {
            drawerLayout.closeDrawer(GravityCompat.START)
        } else {
            super.onBackPressed()
        }
    }
}
