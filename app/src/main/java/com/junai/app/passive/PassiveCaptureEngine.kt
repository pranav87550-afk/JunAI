package com.junai.app.passive

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.junai.app.AppDatabase
import com.junai.app.agent.action.RecordingEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.security.MessageDigest

/**
 * Passive Learning — Phase 2/3: the capture engine.
 *
 * Registered as a [PassiveEventListener] (see JunAccessibilityService's
 * dispatch loop) rather than living inline in onAccessibilityEvent — the
 * Phase 2 design check-in's choice of "separate hook/callback
 * registration" for extra isolation (ground rule: a bug in here can never
 * take down recording/replay).
 *
 * Gate order on every single event, cheapest check first:
 *   1. Rate cap (~10/sec) — see [allowEvent].
 *   2. JunAI self-exclusion — hardcoded, not a DB check (ground rule 1).
 *   3. Allow-list check against the in-memory cache of
 *      [AppLearningPermissionDao.getAllAllowed] (ground rule 2 — default
 *      Deny; the cache only ever contains packages explicitly Allowed).
 *
 * Writes are queued in memory and flushed periodically (not per-event —
 * see [flushLoop]), and everything DB-facing runs on Dispatchers.IO.
 *
 * Phase 3 additions on top of the Phase 2 skeleton: [computeFingerprint]
 * is now hierarchy-aware instead of a sorted flat list, every screen/edge
 * write carries [appVersionOf] so a layout update doesn't get silently
 * misapplied, and [expiryLoop] runs the 30-day auto-expiry pass.
 */
object PassiveCaptureEngine : PassiveEventListener {

    private const val MAX_EVENTS_PER_SECOND = 10
    private const val PERMISSION_REFRESH_MS = 30_000L
    private const val FLUSH_INTERVAL_MS = 3_000L
    private const val EXPIRY_CHECK_INTERVAL_MS = 24 * 60 * 60 * 1000L   // once a day is plenty for a 30-day window
    private const val EXPIRY_MAX_AGE_MS = 30L * 24 * 60 * 60 * 1000L    // Phase 3: 30-day auto-expiry
    private const val EXPIRY_INITIAL_DELAY_MS = 60_000L                 // don't compete with app startup

    // ── Lifecycle ──────────────────────────────────────────────────────

    @Volatile private var appContext: Context? = null
    @Volatile private var junPackageName: String = ""
    @Volatile private var launcherPackage: String? = null
    @Volatile private var started = false

    /** Call once, e.g. from JunAccessibilityService.onServiceConnected(). Safe to call repeatedly. */
    fun init(context: Context) {
        if (started) return
        started = true
        appContext = context.applicationContext
        junPackageName = context.packageName
        launcherPackage = try {
            val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
            context.packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)
                ?.activityInfo?.packageName
        } catch (e: Exception) { null }

        val dao = AppDatabase.getInstance(context.applicationContext).appLearningPermissionDao()
        CoroutineScope(Dispatchers.IO).launch {
            while (true) {
                try {
                    allowedSurfaces = dao.getAllAllowed().map { it.packageName }.toSet()
                } catch (e: Exception) {
                    // Never let a transient DB hiccup crash this loop — just try again next cycle.
                }
                kotlinx.coroutines.delay(PERMISSION_REFRESH_MS)
            }
        }
        CoroutineScope(Dispatchers.IO).launch { flushLoop() }
        CoroutineScope(Dispatchers.IO).launch { expiryLoop(context.applicationContext) }
    }

    // ── Permission cache ──────────────────────────────────────────────

    @Volatile private var allowedSurfaces: Set<String> = emptySet()

    // ── App-version cache (Phase 3) ──────────────────────────────────

    private val appVersionCache = java.util.concurrent.ConcurrentHashMap<String, Long>()

    /** 0L for system pseudo-surfaces (not a real installed package) or if lookup fails — never crashes the caller. */
    private fun appVersionOf(surfaceKey: String): Long {
        if (surfaceKey.startsWith("system:")) return 0L
        appVersionCache[surfaceKey]?.let { return it }
        val context = appContext ?: return 0L
        val version = try {
            val info = context.packageManager.getPackageInfo(surfaceKey, 0)
            if (android.os.Build.VERSION.SDK_INT >= 28) info.longVersionCode else @Suppress("DEPRECATION") info.versionCode.toLong()
        } catch (e: Exception) {
            0L
        }
        appVersionCache[surfaceKey] = version
        return version
    }

    // ── Rate cap ──────────────────────────────────────────────────────

    private var windowStartMs = 0L
    private var eventsThisWindow = 0

    private fun allowEvent(): Boolean {
        val now = System.currentTimeMillis()
        if (now - windowStartMs >= 1000L) {
            windowStartMs = now
            eventsThisWindow = 0
        }
        eventsThisWindow++
        return eventsThisWindow <= MAX_EVENTS_PER_SECOND
    }

    // ── Per-surface capture state (single foreground surface at a time) ─

    private data class PendingEdge(
        val fromScreenId: String,
        val elementIdentifier: String,
        val actionType: String,
        val capturedAt: Long
    )

    @Volatile private var currentScreenId: String? = null
    @Volatile private var pendingEdge: PendingEdge? = null

    /** Phase 5 — the path-finder needs to know where the user currently is. Read-only from outside. */
    fun currentScreen(): String? = currentScreenId

    /**
     * Phase 6 — fired right after ANY edge is written during a flush (see
     * flushBatch's EdgeObserved branch), success or reinforcement alike.
     * The help-popup coordinator uses this to detect exactly when a
     * user's demonstration tap (after a low-confidence popup) resolves
     * into an edge, WITHOUT forking a second capture pipeline — this is
     * the same pendingEdge mechanism Phase 2 already has, just observed
     * from outside.
     */
    @Volatile var onEdgeResolved: ((PassiveEdgeEntity) -> Unit)? = null

    /** Pending edges older than this are considered abandoned (e.g. user left the app entirely) and dropped, never written half-resolved. */
    private const val EDGE_RESOLUTION_WINDOW_MS = 5_000L

    // ── Write queue ───────────────────────────────────────────────────

    private data class CapturedElement(
        val resourceId: String?,
        val text: String?,
        val contentDescription: String?,
        val stateDescription: String?,
        val className: String?,
        val bounds: android.graphics.Rect,
        val clickable: Boolean,
        val scrollable: Boolean,
        val editable: Boolean,
        val depth: Int
    )

    private sealed class PendingOp {
        data class ScreenSeen(
            val screenId: String,
            val packageName: String,
            val fingerprint: String,
            val elements: List<CapturedElement>,
            val at: Long,
            val appVersion: Long
        ) : PendingOp()

        data class EdgeObserved(
            val fromScreenId: String,
            val elementIdentifier: String,
            val actionType: String,
            val toScreenId: String,
            val at: Long,
            val appVersion: Long
        ) : PendingOp()
    }

    private val writeQueue = java.util.Collections.synchronizedList(mutableListOf<PendingOp>())

    // ── PassiveEventListener ──────────────────────────────────────────

    override fun onEvent(event: AccessibilityEvent, eventPackage: String?, rootProvider: () -> AccessibilityNodeInfo?) {
        val context = appContext ?: return
        if (!allowEvent()) return  // over budget for this second — drop, don't queue

        // Ground rule 1 — hardcoded, never a DB lookup, never toggleable.
        if (eventPackage.isNullOrBlank() || eventPackage == junPackageName) return

        val surfaceKey = resolveSurfaceKey(eventPackage, event)
        if (!allowedSurfaces.contains(surfaceKey)) {
            // Not Allowed — nothing captured, and if we were mid-app on a
            // now-denied surface, don't carry stale state into whatever
            // becomes current next.
            if (currentScreenId?.startsWith("$surfaceKey::") != true) return
            currentScreenId = null
            pendingEdge = null
            return
        }

        // Action capture — must happen using event.source (the OLD screen,
        // before whatever this action leads to), not rootProvider().
        when (event.eventType) {
            AccessibilityEvent.TYPE_VIEW_CLICKED -> captureAction(event, "CLICK")
            AccessibilityEvent.TYPE_VIEW_CONTEXT_CLICKED -> captureAction(event, "LONG_CLICK")
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED -> captureAction(event, "TYPE")
            AccessibilityEvent.TYPE_VIEW_SCROLLED -> captureAction(event, "SCROLL")
        }

        // Screen observation — every event that passes the gates above is
        // a chance to (re)confirm the current screen, same "not filtered
        // by event type" reasoning as the existing publishScreenSnapshot().
        val root = rootProvider() ?: return
        observeScreen(root, surfaceKey)
    }

    private fun captureAction(event: AccessibilityEvent, actionType: String) {
        val fromScreen = currentScreenId ?: return  // no known screen yet to attribute this action to
        val node = event.source ?: return
        try {
            if (RecordingEngine.isSensitive(node)) return
            val identifier = elementIdentifierOf(node)
            pendingEdge = PendingEdge(fromScreen, identifier, actionType, System.currentTimeMillis())
        } finally {
            node.recycle()
        }
    }

    private fun observeScreen(root: AccessibilityNodeInfo, surfaceKey: String) {
        val elements = mutableListOf<CapturedElement>()
        collectElements(root, elements, depth = 0)
        val fingerprint = computeFingerprint(surfaceKey, elements)
        val screenId = "$surfaceKey::$fingerprint"
        val now = System.currentTimeMillis()
        val version = appVersionOf(surfaceKey)

        writeQueue.add(PendingOp.ScreenSeen(screenId, surfaceKey, fingerprint, elements.toList(), now, version))

        val pending = pendingEdge
        if (pending != null) {
            val expired = now - pending.capturedAt > EDGE_RESOLUTION_WINDOW_MS
            if (expired) {
                pendingEdge = null
            } else if (screenId != pending.fromScreenId) {
                // A genuinely different screen appeared after the action — resolved.
                writeQueue.add(PendingOp.EdgeObserved(pending.fromScreenId, pending.elementIdentifier, pending.actionType, screenId, now, version))
                pendingEdge = null
            }
        }

        currentScreenId = screenId
    }

    private fun collectElements(node: AccessibilityNodeInfo, out: MutableList<CapturedElement>, depth: Int) {
        if (depth > 40) return  // same hard cap as the existing snapshot walk
        if (out.size >= 150) return  // bound a single screen's element count — very dense screens don't need every leaf to fingerprint usefully

        if (!RecordingEngine.isSensitive(node)) {
            val (labelText, labelDesc) = RecordingEngine.labelOf(node)
            val stateDesc = if (android.os.Build.VERSION.SDK_INT >= 30) node.stateDescription?.toString() else null
            val interactive = node.isClickable || node.isScrollable || node.isEditable
            if (interactive || !labelText.isNullOrBlank() || !labelDesc.isNullOrBlank()) {
                out.add(
                    CapturedElement(
                        resourceId = node.viewIdResourceName,
                        text = labelText,
                        contentDescription = labelDesc,
                        stateDescription = stateDesc,
                        className = node.className?.toString(),
                        bounds = RecordingEngine.boundsOf(node),
                        clickable = node.isClickable,
                        scrollable = node.isScrollable,
                        editable = node.isEditable,
                        depth = depth
                    )
                )
            }
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            try {
                collectElements(child, out, depth + 1)
            } finally {
                child.recycle()
            }
        }
    }

    private fun elementIdentifierOf(node: AccessibilityNodeInfo): String {
        node.viewIdResourceName?.let { return "id:$it" }
        val (text, desc) = RecordingEngine.labelOf(node)
        if (!text.isNullOrBlank()) return "text:$text"
        if (!desc.isNullOrBlank()) return "desc:$desc"
        val b = RecordingEngine.boundsOf(node)
        return "pos:${node.className}@${b.left},${b.top}"
    }

    /**
     * Phase 3 — "what counts as the same screen" (the hard part, per the
     * spec). Two changes from the Phase 2 version:
     *   1. NOT sorted anymore — traversal order (depth-first) is kept, and
     *      each entry is tagged with its depth. That combination is what
     *      "hierarchy position," not just "which resourceIds exist
     *      somewhere," actually means here.
     *   2. Every captured element counts now (not just clickable/
     *      scrollable/editable) — a screen's static structure (labels,
     *      containers) is part of its shape too, not only its interactive
     *      surface.
     * Text/content values are still deliberately excluded — a chat list
     * with different messages today than yesterday must stay "the same
     * screen," per the spec's own example.
     */
    private fun computeFingerprint(surfaceKey: String, elements: List<CapturedElement>): String {
        val shape = elements.joinToString(",") { "${it.depth}:${it.resourceId ?: it.className ?: "?"}" }
        val countBucket = when (elements.size) {
            in 0..5 -> "xs"
            in 6..15 -> "s"
            in 16..40 -> "m"
            else -> "l"
        }
        val raw = surfaceKey + "|" + countBucket + "|" + shape
        val digest = MessageDigest.getInstance("MD5").digest(raw.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }

    /**
     * Real apps map to their own package name. System surfaces map to the
     * four pseudo-ids from Phase 1. NOTE: Quick Settings vs Notifications
     * both commonly run under "com.android.systemui" on stock Android —
     * this heuristic on event.className is a best-effort split, worth
     * revisiting once there's real on-device data to check it against
     * (same iterative spirit as the other OEM-specific fixes already in
     * this codebase).
     */
    private fun resolveSurfaceKey(rawPackage: String, event: AccessibilityEvent): String {
        if (rawPackage == launcherPackage) return ScreenReadingActivity.SURFACE_HOME
        if (rawPackage == "com.android.systemui") {
            val cls = event.className?.toString()?.lowercase() ?: ""
            return when {
                cls.contains("recent") -> ScreenReadingActivity.SURFACE_RECENTS
                cls.contains("notification") -> ScreenReadingActivity.SURFACE_NOTIFICATIONS
                else -> ScreenReadingActivity.SURFACE_QUICK_SETTINGS
            }
        }
        return rawPackage
    }

    // ── Flush ─────────────────────────────────────────────────────────

    /**
     * Phase 3 — 30-day auto-expiry: a lightweight periodic pass (once a
     * day, not on every write, per the spec), not a per-event cost.
     * Elements/edges are cleaned up as orphans right after their parent
     * screens expire, same ordering concern as the Manage Learning
     * forget-app flow (delete children before parents).
     */
    private suspend fun expiryLoop(context: Context) {
        kotlinx.coroutines.delay(EXPIRY_INITIAL_DELAY_MS)
        while (true) {
            try {
                val db = AppDatabase.getInstance(context)
                val cutoff = System.currentTimeMillis() - EXPIRY_MAX_AGE_MS
                db.passiveEdgeDao().deleteOlderThan(cutoff)
                db.passiveScreenDao().deleteOlderThan(cutoff)
                db.passiveElementDao().deleteOrphaned()
                db.passiveEdgeDao().deleteOrphaned()
                PassiveConfidenceScorer.decayUnusedEdges(context)
            } catch (e: Exception) {
                android.util.Log.w("PassiveCaptureEngine", "expiry pass failed: ${e.message}")
            }
            kotlinx.coroutines.delay(EXPIRY_CHECK_INTERVAL_MS)
        }
    }

    private suspend fun flushLoop() {
        while (true) {
            kotlinx.coroutines.delay(FLUSH_INTERVAL_MS)
            val context = appContext ?: continue
            var batch: List<PendingOp> = emptyList()
            synchronized(writeQueue) {
                if (writeQueue.isNotEmpty()) {
                    batch = writeQueue.toList()
                    writeQueue.clear()
                }
            }
            if (batch.isEmpty()) continue
            try {
                flushBatch(context, batch)
            } catch (e: Exception) {
                // A flush failure must never crash the loop — the next
                // cycle just picks up whatever's queued since.
                android.util.Log.w("PassiveCaptureEngine", "flush failed: ${e.message}")
            }
        }
    }

    private suspend fun flushBatch(context: Context, batch: List<PendingOp>) {
        val db = AppDatabase.getInstance(context)
        val screenDao = db.passiveScreenDao()
        val elementDao = db.passiveElementDao()
        val edgeDao = db.passiveEdgeDao()

        for (op in batch) {
            when (op) {
                is PendingOp.ScreenSeen -> {
                    val existing = screenDao.get(op.screenId)
                    screenDao.upsert(
                        PassiveScreenEntity(
                            screenId = op.screenId,
                            packageName = op.packageName,
                            fingerprint = op.fingerprint,
                            firstSeenAt = existing?.firstSeenAt ?: op.at,
                            lastSeenAt = op.at,
                            observationCount = (existing?.observationCount ?: 0) + 1,
                            appVersion = if (op.appVersion != 0L) op.appVersion else (existing?.appVersion ?: 0L)
                        )
                    )
                    for (el in op.elements) {
                        val match = elementDao.findMatching(op.screenId, el.resourceId, el.text, el.className)
                        if (match != null) {
                            elementDao.update(match.copy(lastSeenAt = op.at, stateDescription = el.stateDescription))
                        } else {
                            elementDao.insert(
                                PassiveElementEntity(
                                    screenId = op.screenId,
                                    resourceId = el.resourceId,
                                    text = el.text,
                                    contentDescription = el.contentDescription,
                                    stateDescription = el.stateDescription,
                                    className = el.className,
                                    boundsLeft = el.bounds.left,
                                    boundsTop = el.bounds.top,
                                    boundsRight = el.bounds.right,
                                    boundsBottom = el.bounds.bottom,
                                    clickable = el.clickable,
                                    scrollable = el.scrollable,
                                    editable = el.editable,
                                    lastSeenAt = op.at
                                )
                            )
                        }
                    }
                }
                is PendingOp.EdgeObserved -> {
                    val existing = edgeDao.findResolved(op.fromScreenId, op.elementIdentifier, op.actionType, op.toScreenId)
                    if (existing != null) {
                        // Phase 3 — version-mismatch penalty: a layout update
                        // on the app doesn't wipe what was learned, but the
                        // edge shouldn't be blindly trusted at its old
                        // confidence until it's re-confirmed under the new
                        // version. The full scoring formula (+10/-12/decay)
                        // is Phase 4's job; this is just the one piece Phase
                        // 3's spec explicitly requires now.
                        val versionChanged = existing.appVersion != 0L && op.appVersion != 0L && existing.appVersion != op.appVersion
                        val newConfidence = if (versionChanged) (existing.confidence - 15).coerceAtLeast(10) else existing.confidence
                        val updated = existing.copy(
                            observedCount = existing.observedCount + 1,
                            lastObservedAt = op.at,
                            appVersion = if (op.appVersion != 0L) op.appVersion else existing.appVersion,
                            confidence = newConfidence
                        )
                        edgeDao.update(updated)
                        onEdgeResolved?.invoke(updated)
                    } else {
                        val newEdge = PassiveEdgeEntity(
                            fromScreenId = op.fromScreenId,
                            elementIdentifier = op.elementIdentifier,
                            actionType = op.actionType,
                            toScreenId = op.toScreenId,
                            observedCount = 1,
                            confidence = 50,
                            lastObservedAt = op.at,
                            appVersion = op.appVersion
                        )
                        val id = edgeDao.insert(newEdge)
                        onEdgeResolved?.invoke(newEdge.copy(id = id))
                    }
                }
            }
        }
    }
}
