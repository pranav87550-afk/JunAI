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
 * Passive Learning — Phase 2: the capture engine.
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
 */
object PassiveCaptureEngine : PassiveEventListener {

    private const val MAX_EVENTS_PER_SECOND = 10
    private const val PERMISSION_REFRESH_MS = 30_000L
    private const val FLUSH_INTERVAL_MS = 3_000L

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
    }

    // ── Permission cache ──────────────────────────────────────────────

    @Volatile private var allowedSurfaces: Set<String> = emptySet()

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
        val editable: Boolean
    )

    private sealed class PendingOp {
        data class ScreenSeen(
            val screenId: String,
            val packageName: String,
            val fingerprint: String,
            val elements: List<CapturedElement>,
            val at: Long
        ) : PendingOp()

        data class EdgeObserved(
            val fromScreenId: String,
            val elementIdentifier: String,
            val actionType: String,
            val toScreenId: String,
            val at: Long
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

        writeQueue.add(PendingOp.ScreenSeen(screenId, surfaceKey, fingerprint, elements.toList(), now))

        val pending = pendingEdge
        if (pending != null) {
            val expired = now - pending.capturedAt > EDGE_RESOLUTION_WINDOW_MS
            if (expired) {
                pendingEdge = null
            } else if (screenId != pending.fromScreenId) {
                // A genuinely different screen appeared after the action — resolved.
                writeQueue.add(PendingOp.EdgeObserved(pending.fromScreenId, pending.elementIdentifier, pending.actionType, screenId, now))
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
                        editable = node.isEditable
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
     * Deliberately simple for Phase 2 — packageName plus a stable hash of
     * every interactive element's (resourceId ?: className) in traversal
     * order, plus a coarse element-count bucket so two screens with wildly
     * different content but coincidentally similar interactive elements
     * still don't collide. Phase 3's explicit job is deciding what "same
     * screen" should really mean (partial matches, dynamic lists, etc) —
     * this only needs to be good enough to start accumulating real data
     * without every screen colliding into one fingerprint.
     */
    private fun computeFingerprint(surfaceKey: String, elements: List<CapturedElement>): String {
        val interactiveKeys = elements
            .filter { it.clickable || it.scrollable || it.editable }
            .map { it.resourceId ?: it.className ?: "?" }
            .sorted()
        val countBucket = when (elements.size) {
            in 0..5 -> "xs"
            in 6..15 -> "s"
            in 16..40 -> "m"
            else -> "l"
        }
        val raw = surfaceKey + "|" + countBucket + "|" + interactiveKeys.joinToString(",")
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
                            observationCount = (existing?.observationCount ?: 0) + 1
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
                        edgeDao.update(existing.copy(observedCount = existing.observedCount + 1, lastObservedAt = op.at))
                    } else {
                        edgeDao.insert(
                            PassiveEdgeEntity(
                                fromScreenId = op.fromScreenId,
                                elementIdentifier = op.elementIdentifier,
                                actionType = op.actionType,
                                toScreenId = op.toScreenId,
                                observedCount = 1,
                                confidence = 50,
                                lastObservedAt = op.at
                            )
                        )
                    }
                }
            }
        }
    }
}
