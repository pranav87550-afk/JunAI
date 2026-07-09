package com.junai.app.passive

import android.content.Context
import com.junai.app.AppDatabase

/**
 * Passive Learning — Phase 5, path-finder half (see [PassiveIntentMatcher]
 * for the target-word-matching half — kept in its own file on purpose).
 *
 * The spec calls this "the hardest and most ambitious part," and is
 * explicit about a limitation to keep expectations set correctly:
 *
 * This does NOT compose never-before-seen sequences from unrelated
 * observed fragments. If "search a contact" and "dial a number" were
 * each observed separately but never observed TOGETHER as one flow, this
 * does not invent that connection. That's guaranteed by construction
 * here, not bolted on as an extra check — [bfs] only ever walks real,
 * captured [PassiveEdgeEntity] rows; it never synthesizes an edge that
 * wasn't actually observed.
 *
 * Scope note: routing is intentionally scoped to a single app's own
 * screens (edges whose fromScreenId belongs to that app) — cross-app
 * hops (e.g. via the home screen) aren't modeled as one continuous route
 * in v1.
 */
object PassivePathFinder {

    private const val MAX_HOPS = 8   // sane cap — normal in-app navigation shouldn't need more than this

    sealed class PathResult {
        /** [steps] is empty when the target is already on the current screen — nothing to navigate, just act. */
        data class Found(val steps: List<PassiveEdgeEntity>, val targetElement: PassiveElementEntity) : PathResult()

        /**
         * A route exists in the raw graph, but it needs at least one edge
         * that isn't yet confirmed+high-confidence — this is exactly the
         * "path-finder treats the edge as unknown" case the spec says
         * should trigger Phase 6's help-popup instead of guessing.
         */
        data class LowConfidence(
            val confirmedPrefix: List<PassiveEdgeEntity>,
            val blockedEdge: PassiveEdgeEntity,
            val targetElement: PassiveElementEntity
        ) : PathResult()

        object NoCurrentScreen : PathResult()   // don't even know where the user is right now
        object NoMatch : PathResult()           // intent didn't match anything learned for this app
        object NoPathFound : PathResult()        // target matched, but no route exists in the graph at all — not even an unconfirmed one
    }

    /**
     * [packageName] is the surface key (real package, or one of
     * ScreenReadingActivity's system:* pseudo-ids) — same key everything
     * else in Phase 2/3 uses.
     */
    suspend fun findPath(context: Context, packageName: String, intentText: String): PathResult {
        val currentScreenId = PassiveCaptureEngine.currentScreen() ?: return PathResult.NoCurrentScreen

        val db = AppDatabase.getInstance(context)
        val elements = db.passiveElementDao().forApp(packageName)
        val match = PassiveIntentMatcher.findTarget(intentText, elements) ?: return PathResult.NoMatch
        val targetScreenId = match.element.screenId

        if (targetScreenId == currentScreenId) {
            return PathResult.Found(emptyList(), match.element)
        }

        val allEdges = db.passiveEdgeDao().forApp(packageName).filter { it.toScreenId != null }

        val strictEdges = allEdges.filter { it.isConfirmed && PassiveConfidenceScorer.isAutonomous(it) }
        val strictPath = bfs(currentScreenId, targetScreenId, strictEdges)
        if (strictPath != null) return PathResult.Found(strictPath, match.element)

        // No fully-trusted route — check if a route exists in the raw
        // graph at all, so we can tell "genuinely don't know the way"
        // (NoPathFound) apart from "know a way, but part of it is
        // unconfirmed/low-confidence" (LowConfidence, Phase 6's job).
        val fullPath = bfs(currentScreenId, targetScreenId, allEdges) ?: return PathResult.NoPathFound

        val confirmedPrefix = fullPath.takeWhile { it.isConfirmed && PassiveConfidenceScorer.isAutonomous(it) }
        val blockedEdge = fullPath.getOrNull(confirmedPrefix.size) ?: fullPath.last()
        return PathResult.LowConfidence(confirmedPrefix, blockedEdge, match.element)
    }

    /** Plain BFS over [edges] (already pre-filtered by the caller) from [start] to [target]. Returns the edge sequence, or null if unreachable within [MAX_HOPS]. */
    private fun bfs(start: String, target: String, edges: List<PassiveEdgeEntity>): List<PassiveEdgeEntity>? {
        if (edges.isEmpty()) return null
        val byFrom = edges.groupBy { it.fromScreenId }

        val visited = mutableSetOf(start)
        val queue = ArrayDeque<Pair<String, List<PassiveEdgeEntity>>>()
        queue.add(start to emptyList())

        while (queue.isNotEmpty()) {
            val (screenId, pathSoFar) = queue.removeFirst()
            if (pathSoFar.size >= MAX_HOPS) continue

            for (edge in byFrom[screenId].orEmpty()) {
                val next = edge.toScreenId ?: continue
                if (next in visited) continue
                val newPath = pathSoFar + edge
                if (next == target) return newPath
                visited.add(next)
                queue.add(next to newPath)
            }
        }
        return null
    }
}
