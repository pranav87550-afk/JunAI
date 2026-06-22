package com.junai.app

import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build

object AppSenseManager {

    // App package → list of messages
    private val appMessages = mapOf(
        // Social
        "com.instagram.android" to listOf(
            "Watching reels? 🎬",
            "Chatting with someone? 💬"
        ),
        "com.snapchat.android" to listOf(
            "Snap time! 📱",
            "This filter looks great! 🎭"
        ),

        // Video
        "com.google.android.youtube" to listOf(
            "Watching shorts? ▶️",
            "Interesting! 🍿"
        ),

        // Browser / Search
        "com.android.chrome" to listOf(
            "Looking for something? 🌐",
            "Can I see 👀 or not 🙈"
        ),
        "com.google.android.googlequicksearchbox" to listOf(
            "Searching something? 🔍",
            "Need help? 😊"
        ),

        // Shopping
        "com.amazon.mShop.android.shopping" to listOf(
            "Shopping? 🛒",
            "Looks good! 👀"
        ),
        "com.flipkart.android" to listOf(
            "Shopping? 🛒",
            "Looks good! 👀"
        ),
        "com.meesho.supply" to listOf(
            "Shopping? 🛒",
            "Looks good! 👀"
        ),

        // Grocery
        "com.zeptoconsumerapp" to listOf(
            "Grocery shopping? 🛍️",
            "Party time! 🎉"
        ),
        "com.blinkit.consumer" to listOf(
            "Grocery shopping? 🛍️",
            "Party time! 🎉"
        ),

        // Food
        "com.dominos.dominos" to listOf(
            "Pizza hunt! 🍕",
            "Party time! 🎉"
        ),
        "com.swiggy.android" to listOf(
            "Hungry? 🍔",
            "Good choice! 😋"
        ),
        "com.zomato.android" to listOf(
            "Ordering food? 🍽️",
            "Yum! 😋"
        ),

        // Camera / Photos
        "com.android.camera" to listOf(
            "Say cheese! 📸",
            "Selfie time! 🤳"
        ),
        "com.android.camera2" to listOf(
            "Say cheese! 📸",
            "Selfie time! 🤳"
        ),
        "com.google.android.GoogleCamera" to listOf(
            "Say cheese! 📸",
            "Selfie time! 🤳"
        ),
        "com.android.gallery3d" to listOf(
            "So many memories! 🖼️",
            "Nice pic! 😚"
        ),
        "com.google.android.apps.photos" to listOf(
            "So many memories! 🖼️",
            "Nice pic! 😚"
        ),

        // Music
        "com.spotify.music" to listOf(
            "Vibing to music? 🎵",
            "Good taste! 🎶"
        ),

        // Games
        "com.pubg.imobile" to listOf(
            "Gaming time? 🎮",
            "Win this one! 🏆"
        ),
        "com.dts.freefireth" to listOf(
            "Gaming time? 🎮",
            "Win this one! 🏆"
        )
    )

    private var lastDetectedPackage = ""
    private var lastMessageTime = 0L
    private val MESSAGE_COOLDOWN_MS = 45_000L // 45 seconds between messages

    fun getForegroundApp(context: Context): String? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP_MR1) return null

        return try {
            val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
            val now = System.currentTimeMillis()
            val stats = usm.queryUsageStats(
                UsageStatsManager.INTERVAL_DAILY,
                now - 5000,
                now
            )
            stats?.maxByOrNull { it.lastTimeUsed }?.packageName
        } catch (e: Exception) {
            null
        }
    }

    fun hasUsagePermission(context: Context): Boolean {
        return try {
            val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
            val stats = usm.queryUsageStats(
                UsageStatsManager.INTERVAL_DAILY,
                System.currentTimeMillis() - 5000,
                System.currentTimeMillis()
            )
            stats != null && stats.isNotEmpty()
        } catch (e: Exception) {
            false
        }
    }

    // Returns message if new app detected and cooldown passed
    fun getMessageForApp(packageName: String): String? {
        val now = System.currentTimeMillis()

        // Skip Jun itself
        if (packageName.contains("junai")) return null

        // Same app — cooldown check
        if (packageName == lastDetectedPackage) {
            if (now - lastMessageTime < MESSAGE_COOLDOWN_MS) return null
        }

        val messages = appMessages[packageName] ?: return null

        lastDetectedPackage = packageName
        lastMessageTime = now

        return messages.random()
    }
}
