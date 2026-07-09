package com.junai.app.passive

import android.content.Context
import com.junai.app.AppDatabase
import com.junai.app.agent.action.MacroReplayEngine
import com.junai.app.agent.action.RecordingEngine
import com.junai.app.learning.RecordedMacroEntity
import com.junai.app.learning.RecordedStep

/**
 * Passive Learning — executes a path from [PassivePathFinder.PathResult.Found].
 *
 * Reuses [MacroReplayEngine.replay] WHOLESALE by packaging the path as a
 * throwaway, NEVER-PERSISTED [RecordedMacroEntity] — this is what the
 * cross-cutting guardrail ("Built on top of waitForQuiet(), HOME_SCREEN_SENTINEL,
 * alternate-candidate retry, and step_outcomes — not a parallel
 * reimplementation") means in practice here: every bit of replay
 * reliability machinery (settle-waiting, recovery-jump, step_outcomes
 * logging) applies to a passively-learned path exactly the same as it
 * does to an explicitly "sikhao"-taught macro, because it IS the same
 * code path underneath.
 *
 * TYPE edges are never executed. Phase 2 deliberately never captures
 * WHAT was typed into a field (only that a TYPE action happened there) —
 * retyping old observed text would be wrong even if it existed. So a
 * path that reaches a TYPE edge stops cleanly right before it and hands
 * control back to the user, exactly the "high confidence only grants
 * autonomy over navigation, never over committing" guardrail already
 * asks for elsewhere.
 */
object PassivePathExecutor {

    sealed class ExecutionResult {
        data class Completed(val stepsRun: List<PassiveEdgeEntity>) : ExecutionResult()
        data class StoppedForManualInput(val stepsRun: List<PassiveEdgeEntity>, val message: String) : ExecutionResult()
        data class Failed(val stepsRun: List<PassiveEdgeEntity>, val failedEdge: PassiveEdgeEntity, val message: String) : ExecutionResult()
        object NothingToRun : ExecutionResult()
    }

    suspend fun execute(context: Context, path: List<PassiveEdgeEntity>): ExecutionResult {
        if (path.isEmpty()) return ExecutionResult.NothingToRun

        val typeIndex = path.indexOfFirst { it.actionType == "TYPE" }
        val runnable = if (typeIndex >= 0) path.subList(0, typeIndex) else path
        if (runnable.isEmpty()) {
            return ExecutionResult.StoppedForManualInput(
                emptyList(),
                "Yahan text type karna hoga khud — main automatically type nahi karti."
            )
        }

        val db = AppDatabase.getInstance(context)
        val recordedSteps = mutableListOf<RecordedStep>()
        for (edge in runnable) {
            val elements = db.passiveElementDao().forScreen(edge.fromScreenId)
            val element = elements.find { it.identifier() == edge.elementIdentifier }
                ?: return ExecutionResult.Failed(emptyList(), edge, "Wo button ab nahi mila — shayad app update ho gayi hai.")
            val screen = db.passiveScreenDao().get(edge.fromScreenId)
            recordedSteps.add(toRecordedStep(edge, element, screen?.packageName))
        }

        val tempMacro = RecordedMacroEntity(
            id = 0,
            triggerPhrase = "__passive_path__",   // never saved to recorded_macros — see doc comment
            displayPhrase = "__passive_path__",
            stepsJson = RecordingEngine.serializeSteps(recordedSteps),
            stepCount = recordedSteps.size,
            createdAt = System.currentTimeMillis()
        )

        val ranEdges = mutableListOf<PassiveEdgeEntity>()
        var failedEdge: PassiveEdgeEntity? = null

        val resultMessage = MacroReplayEngine.replay(context, tempMacro) { index, success ->
            val edge = runnable[index]
            if (success) {
                ranEdges.add(edge)
                PassiveConfidenceScorer.recordSuccess(context, edge.id)
            } else {
                failedEdge = edge
                PassiveConfidenceScorer.recordFailure(context, edge.id)
            }
        }

        val blocked = failedEdge
        return when {
            blocked != null -> ExecutionResult.Failed(ranEdges, blocked, resultMessage)
            typeIndex >= 0 -> ExecutionResult.StoppedForManualInput(ranEdges, "Yahan tak pahucha diya — ab text type karna hoga khud.")
            else -> ExecutionResult.Completed(ranEdges)
        }
    }

    private fun toRecordedStep(edge: PassiveEdgeEntity, element: PassiveElementEntity, packageName: String?): RecordedStep {
        val actionType = when (edge.actionType) {
            "CLICK" -> "TAP"
            "LONG_CLICK" -> "LONG_PRESS"
            "SCROLL" -> "SWIPE"
            else -> "TAP"
        }
        return RecordedStep(
            actionType = actionType,
            packageName = packageName,
            resourceId = element.resourceId,
            text = element.text,
            contentDescription = element.contentDescription,
            className = element.className,
            boundsLeft = element.boundsLeft,
            boundsTop = element.boundsTop,
            boundsRight = element.boundsRight,
            boundsBottom = element.boundsBottom,
            // Known limitation: Phase 2 only ever captured THAT a scroll
            // happened, not its direction — so this defaults to forward
            // (the overwhelmingly common case: revealing more content
            // below/ahead). Worth capturing properly in a future capture-
            // side improvement, not something execution can recover.
            scrollForward = if (edge.actionType == "SCROLL") true else null
        )
    }
}
