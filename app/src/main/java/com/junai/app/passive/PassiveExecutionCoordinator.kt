package com.junai.app.passive

import android.content.Context
import com.junai.app.AppDatabase

/**
 * Passive Learning — the master entrypoint. Everything else in this
 * package (PassiveIntentMatcher, PassivePathFinder, PassivePathExecutor,
 * PassiveHelpCoordinator, PassiveLowKnowledgeAdvisor, PassiveConfidenceScorer)
 * is infrastructure this one function composes — see ChatIntentHandler's
 * UNKNOWN-intent branch for the actual call site.
 */
object PassiveExecutionCoordinator {

    sealed class Outcome {
        data class Executed(val message: String, val stepsRun: List<PassiveEdgeEntity>) : Outcome()
        data class NeedsManualInput(val message: String) : Outcome()
        data class Failed(val message: String) : Outcome()
        /** Overlay is showing — [message], if non-null, is the "stop asking, sikhao instead" line from hitting the repeat cap. */
        data class HelpPopupShown(val message: String?) : Outcome()
        data class LowKnowledgeDisclosure(val message: String) : Outcome()
        object NoMatch : Outcome()
        object NoPathFound : Outcome()
        /** Nothing to do here — app isn't Allowed, or we don't know what screen the user is even on. Caller should fall through to its normal UNKNOWN handling. */
        object NotApplicable : Outcome()
    }

    /**
     * @param appDisplayName only needed for the Phase 8 disclosure line — pass the app's label if you have it handy, package name is a fine fallback.
     * @param isNewTaskAttempt see PassiveLowKnowledgeAdvisor's doc comment — this function doesn't try to infer it itself.
     */
    suspend fun handle(
        context: Context,
        intentText: String,
        appDisplayName: String? = null,
        isNewTaskAttempt: Boolean = true
    ): Outcome {
        val packageName = resolveTargetPackage(context) ?: return Outcome.NotApplicable

        PassiveLowKnowledgeAdvisor.maybeDisclose(
            context, packageName, appDisplayName ?: packageName, isNewTaskAttempt
        )?.let { return Outcome.LowKnowledgeDisclosure(it) }

        return when (val result = PassivePathFinder.findPath(context, packageName, intentText)) {
            is PassivePathFinder.PathResult.Found -> runPath(context, result.steps)

            is PassivePathFinder.PathResult.LowConfidence ->
                Outcome.HelpPopupShown(PassiveHelpCoordinator.handleLowConfidence(context, result))

            PassivePathFinder.PathResult.NoMatch -> Outcome.NoMatch
            PassivePathFinder.PathResult.NoPathFound -> Outcome.NoPathFound
            PassivePathFinder.PathResult.NoCurrentScreen -> Outcome.NotApplicable
        }
    }

    private suspend fun runPath(context: Context, steps: List<PassiveEdgeEntity>): Outcome {
        return when (val result = PassivePathExecutor.execute(context, steps)) {
            is PassivePathExecutor.ExecutionResult.Completed ->
                Outcome.Executed("Ho gaya! 👍", result.stepsRun)
            is PassivePathExecutor.ExecutionResult.StoppedForManualInput ->
                Outcome.NeedsManualInput(result.message)
            is PassivePathExecutor.ExecutionResult.Failed ->
                Outcome.Failed(result.message)
            PassivePathExecutor.ExecutionResult.NothingToRun ->
                Outcome.Executed("Wo already yahin hai.", emptyList())
        }
    }

    /** Only attempts passive execution if there's a known current screen AND that app is Allowed — both checked here so the caller doesn't need to know about the permission gate at all. */
    private suspend fun resolveTargetPackage(context: Context): String? {
        val currentScreen = PassiveCaptureEngine.currentScreen() ?: return null
        val packageName = currentScreen.substringBefore("::").takeIf { it.isNotBlank() } ?: return null
        val allowed = AppDatabase.getInstance(context).appLearningPermissionDao().isAllowed(packageName) ?: false
        return packageName.takeIf { allowed }
    }
}
