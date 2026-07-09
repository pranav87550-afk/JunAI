package com.junai.app.passive

import android.content.Context
import com.junai.app.AppDatabase

/**
 * Passive Learning — Phase 4: confidence scoring.
 *
 * Nothing in the app calls this yet — there's no autonomous execution
 * (Phase 5), no help-popup (Phase 6), and no per-step thumbs UI (Phase 7)
 * built yet. This is the scoring mechanism those phases will call into,
 * built now per the suggested build order so it exists and is stable
 * before anything depends on it.
 *
 * All deltas match the spec exactly:
 *   +10  success / thumbs-up
 *   -12  failure / thumbs-down
 *   confidence is clamped to [0, 100]
 *   3 consecutive failures -> steeper drop + a message suggesting
 *     "sikhao" instead of continuing to guess (see [RecordOutcomeResult]).
 */
object PassiveConfidenceScorer {

    private const val SUCCESS_DELTA = 10
    private const val FAILURE_DELTA = -12
    private const val REPEATED_FAILURE_THRESHOLD = 3
    private const val REPEATED_FAILURE_EXTRA_DROP = 20  // on top of the normal -12, once the streak hits the threshold
    private const val AUTONOMOUS_THRESHOLD = 50

    /** Whether the path-finder (Phase 5) should execute this edge without asking, per the spec's threshold. */
    fun isAutonomous(edge: PassiveEdgeEntity): Boolean = edge.confidence >= AUTONOMOUS_THRESHOLD

    sealed class RecordOutcomeResult {
        data class Updated(val edge: PassiveEdgeEntity) : RecordOutcomeResult()
        /** Streak hit [REPEATED_FAILURE_THRESHOLD] — Phase 6/5 should surface the "sikhao" suggestion and stop auto-retrying this edge for the session. */
        data class RepeatedFailure(val edge: PassiveEdgeEntity) : RecordOutcomeResult()
        object NotFound : RecordOutcomeResult()
    }

    /** A confirmed-successful autonomous execution, or an explicit thumbs-up — same delta by design (spec: "equal weight by default"). */
    suspend fun recordSuccess(context: Context, edgeId: Long): RecordOutcomeResult =
        adjust(context, edgeId, SUCCESS_DELTA, isFailure = false)

    /** A failed execution attempt, or an explicit thumbs-down. */
    suspend fun recordFailure(context: Context, edgeId: Long): RecordOutcomeResult =
        adjust(context, edgeId, FAILURE_DELTA, isFailure = true)

    /** Alias kept separate from recordSuccess/recordFailure so Phase 7's call sites read clearly, even though the underlying deltas are identical today. */
    suspend fun recordThumbsUp(context: Context, edgeId: Long): RecordOutcomeResult = recordSuccess(context, edgeId)
    suspend fun recordThumbsDown(context: Context, edgeId: Long): RecordOutcomeResult = recordFailure(context, edgeId)

    private suspend fun adjust(context: Context, edgeId: Long, delta: Int, isFailure: Boolean): RecordOutcomeResult {
        val dao = AppDatabase.getInstance(context).passiveEdgeDao()
        val existing = dao.getById(edgeId) ?: return RecordOutcomeResult.NotFound

        val streak = if (isFailure) existing.consecutiveFailures + 1 else 0
        val hitThreshold = isFailure && streak == REPEATED_FAILURE_THRESHOLD  // exactly at the 3rd in a row, not every failure after
        val extraDrop = if (hitThreshold) REPEATED_FAILURE_EXTRA_DROP else 0

        val newConfidence = (existing.confidence + delta - extraDrop).coerceIn(0, 100)
        val updated = existing.copy(confidence = newConfidence, consecutiveFailures = streak)
        dao.update(updated)

        return if (hitThreshold) RecordOutcomeResult.RepeatedFailure(updated) else RecordOutcomeResult.Updated(updated)
    }

    // ── Decay (runs from PassiveCaptureEngine's periodic loop) ─────────

    private const val DECAY_UNUSED_AFTER_MS = 7L * 24 * 60 * 60 * 1000L  // a week of not coming up at all
    private const val DECAY_AMOUNT = 2
    private const val DECAY_FLOOR = 20  // decay alone never pushes an edge below this — only explicit failures can

    /** Called once a day from PassiveCaptureEngine.expiryLoop — gentle, not urgent, so it piggybacks on that existing daily pass rather than its own loop. */
    suspend fun decayUnusedEdges(context: Context) {
        val dao = AppDatabase.getInstance(context).passiveEdgeDao()
        val cutoff = System.currentTimeMillis() - DECAY_UNUSED_AFTER_MS
        dao.decayStale(cutoff, DECAY_AMOUNT, DECAY_FLOOR)
    }
}
