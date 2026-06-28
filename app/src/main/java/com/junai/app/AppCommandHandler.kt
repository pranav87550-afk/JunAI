package com.junai.app

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.ContactsContract
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

/**
 * Handles device-level commands: launching apps and making phone calls.
 * Requires an Activity reference for permissions, startActivity, and contentResolver.
 */
class AppCommandHandler(
    private val activity: Activity,
    private val chatAdapter: ChatAdapter,
    private val messages: MutableList<ChatMessage>,
    private val onSaveChat: () -> Unit
) {
    private val packageMap = mapOf(
        "youtube"     to "com.google.android.youtube",
        "instagram"   to "com.instagram.android",
        "whatsapp"    to "com.whatsapp",
        "facebook"    to "com.facebook.katana",
        "snapchat"    to "com.snapchat.android",
        "telegram"    to "org.telegram.messenger",
        "chrome"      to "com.android.chrome",
        "gmail"       to "com.google.android.gm",
        "netflix"     to "com.netflix.mediaclient",
        "discord"     to "com.discord",
        "amazon"      to "com.amazon.mShop.android.shopping",
        "flipkart"    to "com.flipkart.android",
        "paytm"       to "net.one97.paytm",
        "gpay"        to "com.google.android.apps.nbu.paisa.user",
        "hotspot"     to "in.startv.hotstar",
        "jiohotstar"  to "in.startv.hotstar",
        "drive"       to "com.google.android.apps.docs",
        "meet"        to "com.google.android.apps.tachyon",
        "threads"     to "com.instagram.barcelona",
        "truecaller"  to "com.truecaller",
        "deepseek"    to "com.deepseek.app",
        "chatgpt"     to "com.openai.chatgpt",
        "perplexity"  to "ai.perplexity.app.android",
        "meesho"      to "com.meesho.supply",
        "mx player"   to "com.mxtech.videoplayer.ad",
        "vidmate"     to "com.vidmate.yt",
        "claude"      to "com.anthropic.claude",
        "maps"        to "com.google.android.apps.maps",
        "photos"      to "com.google.android.apps.photos",
        "play store"  to "com.android.vending",
        "spotify"     to "com.spotify.music"
    )

    fun openApp(appName: String) {
        val lower = appName.lowercase().trim()
        val pm = activity.packageManager

        // 1. Known package map
        packageMap[lower]?.let { pkg ->
            try {
                val intent = pm.getLaunchIntentForPackage(pkg)
                if (intent != null) {
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    activity.startActivity(intent)
                    reply("Opening $appName ✅")
                    return
                }
            } catch (e: Exception) { e.printStackTrace() }
        }

        // 2. Fuzzy match installed apps
        val matched = pm.getInstalledApplications(PackageManager.GET_META_DATA)
            .firstOrNull { pm.getApplicationLabel(it).toString().lowercase().contains(lower) }
        matched?.let {
            val intent = pm.getLaunchIntentForPackage(it.packageName)
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                activity.startActivity(intent)
                reply("Opening $appName ✅")
                return
            }
        }

        // 3. Fallback to Play Store search
        try {
            activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("market://search?q=$appName")))
            reply("'$appName' not found. Opening Play Store... 🔍")
        } catch (e: Exception) {
            reply("App '$appName' not found.")
        }
    }

    fun makeCall(name: String) {
        val callPerm    = Manifest.permission.CALL_PHONE
        val contactPerm = Manifest.permission.READ_CONTACTS

        if (ContextCompat.checkSelfPermission(activity, callPerm) != PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(activity, contactPerm) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(activity, arrayOf(callPerm, contactPerm), 200)
            reply("Please grant call & contacts permission!")
            return
        }

        val cursor = activity.contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                ContactsContract.CommonDataKinds.Phone.NUMBER
            ),
            null, null, null
        )

        var number: String? = null
        cursor?.use {
            while (it.moveToNext()) {
                val contactName = it.getString(0) ?: continue
                if (contactName.lowercase().contains(name.lowercase())) {
                    number = it.getString(1)
                    break
                }
            }
        }

        if (number != null) {
            reply("Calling $name... 📞")
            activity.startActivity(Intent(Intent.ACTION_CALL).apply {
                data = Uri.parse("tel:$number")
            })
        } else {
            reply("Contact '$name' nahi mila! 😕")
        }
    }

    fun sendMessage(contactName: String) {
        val contactPerm = Manifest.permission.READ_CONTACTS

        if (ContextCompat.checkSelfPermission(activity, contactPerm) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(activity, arrayOf(contactPerm), 201)
            reply("Please grant contacts permission! 📋")
            return
        }

        if (contactName.isEmpty()) {
            reply("Kisko message karun? Naam batao! 🤔")
            return
        }

        val cursor = activity.contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                ContactsContract.CommonDataKinds.Phone.NUMBER
            ),
            null, null, null
        )

        var number: String? = null
        cursor?.use {
            while (it.moveToNext()) {
                val name = it.getString(0) ?: continue
                if (name.lowercase().contains(contactName.lowercase())) {
                    number = it.getString(1)
                    break
                }
            }
        }

        if (number != null) {
            reply("Opening WhatsApp for $contactName... 💬")
            try {
                // Try WhatsApp directly
                val whatsappIntent = Intent(Intent.ACTION_VIEW).apply {
                    data = Uri.parse("https://wa.me/${number!!.replace(" ", "").replace("-", "")}")
                    setPackage("com.whatsapp")
                }
                activity.startActivity(whatsappIntent)
            } catch (e: Exception) {
                // Fallback to SMS
                activity.startActivity(Intent(Intent.ACTION_SENDTO).apply {
                    data = Uri.parse("smsto:$number")
                })
            }
        } else {
            reply("Contact '$contactName' nahi mila! 😕")
        }
    }

    private fun reply(text: String) {
        chatAdapter.addMessage(ChatMessage(text, isUser = false))
        onSaveChat()
    }
}
