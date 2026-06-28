package com.junai.app

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.BatteryManager
import android.widget.Toast
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object CommandExecutor {

    // TTS removed — routed through FloatingBotService's single shared instance
    private var onSpeak: ((String) -> Unit)? = null
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())

    private val packageMap = mapOf(
        "youtube" to "com.google.android.youtube",
        "instagram" to "com.instagram.android",
        "whatsapp" to "com.whatsapp",
        "facebook" to "com.facebook.katana",
        "snapchat" to "com.snapchat.android",
        "telegram" to "org.telegram.messenger",
        "chrome" to "com.android.chrome",
        "gmail" to "com.google.android.gm",
        "netflix" to "com.netflix.mediaclient",
        "discord" to "com.discord",
        "amazon" to "com.amazon.mShop.android.shopping",
        "flipkart" to "com.flipkart.android",
        "paytm" to "net.one97.paytm",
        "gpay" to "com.google.android.apps.nbu.paisa.user",
        "hotspot" to "in.startv.hotstar",
        "jiohotstar" to "in.startv.hotstar",
        "drive" to "com.google.android.apps.docs",
        "meet" to "com.google.android.apps.tachyon",
        "threads" to "com.instagram.barcelona",
        "truecaller" to "com.truecaller",
        "deepseek" to "com.deepseek.app",
        "chatgpt" to "com.openai.chatgpt",
        "perplexity" to "ai.perplexity.app.android",
        "meesho" to "com.meesho.supply",
        "mx player" to "com.mxtech.videoplayer.ad",
        "vidmate" to "com.vidmate.yt",
        "claude" to "com.anthropic.claude",
        "maps" to "com.google.android.apps.maps",
        "photos" to "com.google.android.apps.photos",
        "play store" to "com.android.vending",
        "spotify" to "com.spotify.music"
    )

    fun init(context: Context, speakCallback: ((String) -> Unit)? = null) {
        onSpeak = speakCallback
    }

    fun execute(context: Context, text: String) {
        val result = IntentDetector.detect(text)
        val target = result.params["target"] ?: ""

        when (result.intent) {
            IntentDetector.Intent.GREET -> {
                respond(context, listOf(
                    "Hello! 👋 Main Jun hun!",
                    "Hi! Kya haal hai? 😊",
                    "Namaste! 🙏"
                ).random())
            }

            IntentDetector.Intent.HOW_ARE_YOU -> {
                respond(context, "Main bilkul theek hun! Aur tum? 😊")
            }

            IntentDetector.Intent.THANK -> {
                respond(context, "Koi baat nahi! 😊")
            }

            IntentDetector.Intent.OPEN_APP -> {
                if (target.isNotEmpty()) openApp(context, target)
                else respond(context, "Konsa app open karun? 🤔")
            }

            IntentDetector.Intent.CALL_CONTACT -> {
                if (target.isNotEmpty()) makeCall(context, target)
                else respond(context, "Kisko call karun? 📞")
            }

            IntentDetector.Intent.SHOW_NOTES            -> launchActivity(context, NotesActivity::class.java)
            IntentDetector.Intent.SHOW_TODO             -> launchActivity(context, TodoActivity::class.java)
            IntentDetector.Intent.SHOW_CALCULATOR       -> launchActivity(context, CalculatorActivity::class.java)
            IntentDetector.Intent.SHOW_DRAW             -> launchActivity(context, DrawActivity::class.java)
            IntentDetector.Intent.SHOW_TRANSLATOR       -> launchActivity(context, TranslatorActivity::class.java)
            IntentDetector.Intent.SHOW_REMINDER         -> launchActivity(context, ReminderActivity::class.java)
            IntentDetector.Intent.SHOW_SETTINGS         -> launchActivity(context, SettingsActivity::class.java)
            IntentDetector.Intent.SHOW_MUSIC            -> launchActivity(context, MusicHomeActivity::class.java)
            IntentDetector.Intent.SHOW_UNANSWERED       -> launchActivity(context, UnansweredActivity::class.java)
            IntentDetector.Intent.SHOW_VOICE_COMMANDS   -> launchActivity(context, VoiceCommandsActivity::class.java)
            IntentDetector.Intent.SHOW_DATA_MANAGEMENT  -> launchActivity(context, DataManagementActivity::class.java)

            IntentDetector.Intent.PLAY_MUSIC -> {
                launchActivity(context, MusicHomeActivity::class.java)
                respond(context, "Music open kar rahi hun! 🎵")
            }

            IntentDetector.Intent.PAUSE_MUSIC -> {
                sendMusicAction(context, "PAUSE")
                respond(context, "Music pause! ⏸️")
            }

            IntentDetector.Intent.NEXT_SONG -> {
                sendMusicAction(context, "NEXT")
                respond(context, "Next song! ⏭️")
            }

            IntentDetector.Intent.PREV_SONG -> {
                sendMusicAction(context, "PREV")
                respond(context, "Previous song! ⏮️")
            }

            IntentDetector.Intent.SET_REMINDER -> {
                launchActivity(context, ReminderActivity::class.java)
                respond(context, "Reminder screen open kar rahi hun! ⏰")
            }

            IntentDetector.Intent.CREATE_NOTE -> {
                launchActivity(context, NotesActivity::class.java)
                respond(context, "Notes screen open kar rahi hun! 📝")
            }

            IntentDetector.Intent.TELL_TIME -> {
                val time = SimpleDateFormat("hh:mm a", Locale.getDefault()).format(Date())
                respond(context, "Abhi time hai: $time ⏰")
            }

            IntentDetector.Intent.TELL_DATE -> {
                val date = SimpleDateFormat("dd MMMM yyyy, EEEE", Locale.getDefault()).format(Date())
                respond(context, "Aaj ki date hai: $date 📅")
            }

            IntentDetector.Intent.TELL_BATTERY -> {
                val bm = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
                val level = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
                val charging = bm.isCharging
                val status = if (charging) "⚡ Charging" else "🔋 Not charging"
                respond(context, "Battery: $level% — $status")
            }

            IntentDetector.Intent.TELL_JOKE -> {
                val jokes = listOf(
                    "Maine ek AI se pucha — 'Kya tum insaan ban sakte ho?' Usne bola — 'Haan, bas ek update aur!' 😂",
                    "Phone low battery pe tha... Jun boli — 'Main bhi thak jaati hun kabhi kabhi!' 🔋😄"
                )
                respond(context, jokes.random())
            }

            IntentDetector.Intent.FLIP_COIN -> {
                val result2 = if ((0..1).random() == 0) "Heads! 🪙" else "Tails! 🪙"
                respond(context, "Coin toss result: $result2")
            }

            IntentDetector.Intent.ROLL_DICE -> {
                val result3 = (1..6).random()
                respond(context, "Dice result: $result3 🎲")
            }

            else -> {
    // UNKNOWN — knowledge search background mein
    CoroutineScope(Dispatchers.IO).launch {
        try {
            val repo = LearningRepository(context)
            val searchResult = repo.findAnswer(text)
            val response = when {
                searchResult.answer != null && searchResult.confidence >= 70f -> searchResult.answer
                else -> "Samajh nahi paayi 😅 App mein try karo!"
            }
            mainHandler.post { respond(context, response) }
        } catch (e: Exception) {
            mainHandler.post { respond(context, "Kuch gadbad ho gayi 😅") }
        }
    }
            }
        }
    }

    // ──────────────────────────────────────────────────────────
    // HELPERS
    // ──────────────────────────────────────────────────────────
    private fun respond(context: Context, text: String) {
        Toast.makeText(context, text, Toast.LENGTH_LONG).show()
        onSpeak?.invoke(text)
    }

    private fun launchActivity(context: Context, cls: Class<*>) {
        val intent = Intent(context, cls).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    private fun sendMusicAction(context: Context, action: String) {
        val intent = Intent(context, MusicService::class.java).apply {
            this.action = action
        }
        context.startService(intent)
    }

    private fun openApp(context: Context, appName: String) {
        val lower = appName.lowercase().trim()
        val pm = context.packageManager

        val pkgName = packageMap[lower]
        if (pkgName != null) {
            try {
                val launchIntent = pm.getLaunchIntentForPackage(pkgName)
                if (launchIntent != null) {
                    launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(launchIntent)
                    respond(context, "Opening $appName ✅")
                    return
                }
            } catch (e: Exception) { e.printStackTrace() }
        }

        val installedApps = pm.getInstalledApplications(PackageManager.GET_META_DATA)
        val matched = installedApps.firstOrNull {
            pm.getApplicationLabel(it).toString().lowercase().contains(lower)
        }
        if (matched != null) {
            val launchIntent = pm.getLaunchIntentForPackage(matched.packageName)
            if (launchIntent != null) {
                launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(launchIntent)
                respond(context, "Opening $appName ✅")
                return
            }
        }

        try {
            val marketIntent = Intent(Intent.ACTION_VIEW, Uri.parse("market://search?q=$appName")).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(marketIntent)
            respond(context, "'$appName' not found. Opening Play Store... 🔍")
        } catch (e: Exception) {
            respond(context, "App '$appName' not found.")
        }
    }

    private fun makeCall(context: Context, name: String) {
        val permission = android.Manifest.permission.CALL_PHONE
        val contactPermission = android.Manifest.permission.READ_CONTACTS

        if (ContextCompat.checkSelfPermission(context, permission) != PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, contactPermission) != PackageManager.PERMISSION_GRANTED) {
            respond(context, "Call & contacts permission chahiye!")
            return
        }

        val cursor = context.contentResolver.query(
            android.provider.ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(
                android.provider.ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                android.provider.ContactsContract.CommonDataKinds.Phone.NUMBER
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
            respond(context, "Calling $name... 📞")
            val callIntent = Intent(Intent.ACTION_CALL).apply {
                data = Uri.parse("tel:$number")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(callIntent)
        } else {
            respond(context, "Contact '$name' nahi mila! 😕")
        }
    }
}
