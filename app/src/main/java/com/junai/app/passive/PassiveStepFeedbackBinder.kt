package com.junai.app.passive

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import androidx.lifecycle.LifecycleCoroutineScope
import com.junai.app.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Passive Learning — Phase 7: per-step thumbs up/down.
 *
 * Not a RecyclerView adapter of its own — this only knows how to inflate
 * and bind ONE [R.layout.item_passive_step_feedback] row to one executed
 * edge. Whatever renders Jun's autonomous-execution status view (still
 * pending integration, same as Phases 5/6/8) is expected to call
 * [inflateAndBind] once per step and drop the resulting [View] into its
 * own message/step list — this deliberately doesn't assume anything
 * about that list's shape.
 *
 * Deltas match Phase 4 exactly (same call, not a re-implementation):
 * thumbs-up -> [PassiveConfidenceScorer.recordThumbsUp], thumbs-down ->
 * [PassiveConfidenceScorer.recordThumbsDown].
 */
object PassiveStepFeedbackBinder {

    /**
     * @param stepLabel plain-language description of what this step did (e.g. "Contacts kholi")
     * @param edgeId the PassiveEdgeEntity.id this step actually executed
     * @param scope caller's lifecycleScope (or any CoroutineScope) — the rating write happens on Dispatchers.IO
     */
    fun inflateAndBind(
        parent: ViewGroup,
        context: Context,
        stepLabel: String,
        edgeId: Long,
        scope: LifecycleCoroutineScope
    ): View {
        val view = LayoutInflater.from(context).inflate(R.layout.item_passive_step_feedback, parent, false)
        bind(view, context, stepLabel, edgeId, scope)
        return view
    }

    fun bind(view: View, context: Context, stepLabel: String, edgeId: Long, scope: LifecycleCoroutineScope) {
        val label = view.findViewById<TextView>(R.id.stepLabel)
        val upButton = view.findViewById<ImageButton>(R.id.stepThumbsUp)
        val downButton = view.findViewById<ImageButton>(R.id.stepThumbsDown)

        label.text = stepLabel
        upButton.isEnabled = true
        downButton.isEnabled = true
        upButton.alpha = 1f
        downButton.alpha = 1f

        upButton.setOnClickListener {
            lockButtons(upButton, downButton, chosenUp = true)
            scope.launch(Dispatchers.IO) {
                PassiveConfidenceScorer.recordThumbsUp(context, edgeId)
            }
        }
        downButton.setOnClickListener {
            lockButtons(upButton, downButton, chosenUp = false)
            scope.launch(Dispatchers.IO) {
                PassiveConfidenceScorer.recordThumbsDown(context, edgeId)
            }
        }
    }

    /** Once rated, lock both buttons — a step should only ever contribute one delta, never let a mis-tap or a repeat tap double-count. */
    private fun lockButtons(upButton: ImageButton, downButton: ImageButton, chosenUp: Boolean) {
        upButton.isEnabled = false
        downButton.isEnabled = false
        upButton.alpha = if (chosenUp) 1f else 0.35f
        downButton.alpha = if (chosenUp) 0.35f else 1f
    }
}
