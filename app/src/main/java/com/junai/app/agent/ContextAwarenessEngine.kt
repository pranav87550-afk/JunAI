package com.junai.app.agent

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import com.junai.app.AppSenseManager
import com.junai.app.ConversationContext
import com.junai.app.memory.MemoryRepository
import java.util.Calendar

data class CurrentContext(
    val currentApp: String,
    val timeOfDay: String,
    val batteryLevel: Int,
    val isCharging: Boolean,
    val networkAvailable: Boolean,
    val isOnWifi: Boolean,
    val recentMemories: List<String>,   // blended: MemoryRepository + ConversationContext
    val currentTask: String?,           // from MultiStepTaskManager — null if nothing paused
    val userMood: String? = null        // Phase 13 EmotionalMemory was skipped — always null for now
)

/**
 * ContextAwarenessEngine — gathers full situational context before every
 * agent decision, per spec. Extends (never replaces) the existing
 * AppSenseManager and ConversationContext: this object reads from both
 * rather than duplicating their logic.
 *
 * Note on currentTask: this checks MultiStepTaskManager for a *paused*
 * task, i.e. "is there unfinished business from before this session?"
 * While AgentEngine is actively running a task, it already holds that
 * state in memory and doesn't need to query this — this field exists for
 * the case where the app restarted mid-workflow.
 *
 * Note on userMood: Phase 13 (EmotionalMemory) was intentionally skipped —
 * confirmed earlier in this build, it has zero hard dependents. The field
 * is kept (always null today) so nothing downstream needs to change if
 * that phase is ever added later.
 */
object ContextAwarenessEngine {

    suspend fun gatherContext(
        context: Context,
        memoryRepository: MemoryRepository,
        multiStepTaskManager: MultiStepTaskManager
    ): CurrentContext {
        val currentApp = AppSenseManager.getForegroundApp(context) ?: "unknown"
        val (batteryLevel, isCharging) = getBatteryInfo(context)
        val (networkAvailable, isOnWifi) = getNetworkInfo(context)

        // Blend long-term-memory summaries with the live conversation window —
        // both are "recent context", just from two different existing systems.
        val memorySummaries = memoryRepository.getShortTermMemories().take(5).map { it.summary }
        val conversationSnippets = ConversationContext.instance.getRecentUserMessages(3)
        val recentMemories = (memorySummaries + conversationSnippets).distinct().take(8)

        val currentTask = multiStepTaskManager.getResumableTask()?.params?.rawGoal

        return CurrentContext(
            currentApp = currentApp,
            timeOfDay = getTimeOfDay(),
            batteryLevel = batteryLevel,
            isCharging = isCharging,
            networkAvailable = networkAvailable,
            isOnWifi = isOnWifi,
            recentMemories = recentMemories,
            currentTask = currentTask,
            userMood = null
        )
    }

    private fun getBatteryInfo(context: Context): Pair<Int, Boolean> {
        return try {
            val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
            val pct = if (level >= 0 && scale > 0) (level * 100 / scale) else -1
            val status = intent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
            val charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
            pct to charging
        } catch (e: Exception) {
            -1 to false
        }
    }

    private fun getNetworkInfo(context: Context): Pair<Boolean, Boolean> {
        return try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val network = cm.activeNetwork ?: return false to false
            val capabilities = cm.getNetworkCapabilities(network) ?: return false to false
            val hasInternet = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            val isWifi = capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
            hasInternet to isWifi
        } catch (e: Exception) {
            false to false
        }
    }

    private fun getTimeOfDay(): String {
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        return when (hour) {
            in 5..11 -> "morning"
            in 12..16 -> "afternoon"
            in 17..20 -> "evening"
            else -> "night"
        }
    }
}
