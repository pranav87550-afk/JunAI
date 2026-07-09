package com.junai.app.passive

import android.content.Context
import com.junai.app.AppDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/**
 * Passive Learning — Phase 6: ties [PassiveHelpPopupOverlay] to
 * [PassivePathFinder]'s LowConfidence result and to [PassiveCaptureEngine]'s
 * existing capture pipeline.
 *
 * Like Phases 5/8, [handleLowConfidence] is infrastructure the eventual
 * chat/execution integration calls into — see [PassivePathFinder]'s own
 * doc comment for the same caveat.
 */
object PassiveHelpCoordinator {

    private const val REPEAT_CAP = 3
    /** Above the Phase 4 autonomous threshold (50) — a direct human demonstration is stronger evidence than an inferred success, so it's seeded higher, not just nudged. Worth tuning at integration time, per the spec's own note. */
    private const val SEED_CONFIDENCE = 70
    private const val DEMONSTRATION_TIMEOUT_MS = 30_000L

    private const val REPEATED_CONFUSION_MESSAGE =
        "Ye baar-baar confuse ho raha hai — 'sikhao' bol ke seedha train kar do, zyada reliable rahega."

    /** Session-scoped, same spirit as Phase 8's debounce — resets on process restart, which is fine here (a fresh session earning back a few popups is harmless). */
    private val attemptCounts = ConcurrentHashMap<String, Int>()
    private val escalated = ConcurrentHashMap.newKeySet<String>()

    @Volatile private var waitingForKey: String? = null
    private var timeoutJob: Job? = null

    /**
     * @return a message to surface in chat if the popup was skipped
     *   (repeat cap already hit for this edge — defers to "sikhao"
     *   instead), or null if the overlay was shown and the caller doesn't
     *   need to say anything else right now.
     */
    fun handleLowConfidence(context: Context, result: PassivePathFinder.PathResult.LowConfidence): String? {
        val edge = result.blockedEdge
        val key = keyOf(edge)

        if (key in escalated) return REPEATED_CONFUSION_MESSAGE

        val count = (attemptCounts[key] ?: 0) + 1
        attemptCounts[key] = count

        if (count > REPEAT_CAP) {
            escalated.add(key)
            stopWaiting()
            return REPEATED_CONFUSION_MESSAGE
        }

        showAndListen(context, edge)
        return null
    }

    private fun showAndListen(context: Context, edge: PassiveEdgeEntity) {
        val key = keyOf(edge)
        timeoutJob?.cancel()
        waitingForKey = key

        // Reuses PassiveCaptureEngine's existing pendingEdge capture path
        // (see its doc comment) rather than forking a second one — this
        // listener just watches for a WRITE matching the exact edge we're
        // stuck on, which is the recording-derived capture Phase 2 already
        // does for every click/type/scroll in an Allowed app.
        PassiveCaptureEngine.onEdgeResolved = { resolvedEdge ->
            if (keyOf(resolvedEdge) == waitingForKey) {
                CoroutineScope(Dispatchers.IO).launch { seedFromDemonstration(context, resolvedEdge) }
            }
        }

        PassiveHelpPopupOverlay.show(
            context,
            "Yahan se aage badhne ke liye main sure nahi hoon konsa button sahi hai — tum dikha doge?"
        ) {
            stopWaiting()
        }

        timeoutJob = CoroutineScope(Dispatchers.Main).launch {
            delay(DEMONSTRATION_TIMEOUT_MS)
            stopWaiting()
        }
    }

    private suspend fun seedFromDemonstration(context: Context, edge: PassiveEdgeEntity) {
        val dao = AppDatabase.getInstance(context).passiveEdgeDao()
        // maxOf, not a flat overwrite — if it had somehow already earned a
        // higher confidence than the seed, a direct demonstration
        // shouldn't ever LOWER it.
        val boosted = edge.copy(confidence = maxOf(edge.confidence, SEED_CONFIDENCE), consecutiveFailures = 0)
        dao.update(boosted)
        attemptCounts.remove(keyOf(edge))  // resolved on its own now — no longer "confused"

        withContext(Dispatchers.Main) { stopWaiting() }
    }

    private fun stopWaiting() {
        PassiveCaptureEngine.onEdgeResolved = null
        waitingForKey = null
        timeoutJob?.cancel()
        timeoutJob = null
        PassiveHelpPopupOverlay.hide()
    }

    private fun keyOf(edge: PassiveEdgeEntity) = "${edge.fromScreenId}|${edge.elementIdentifier}|${edge.actionType}"
}
