package com.junai.app.passive

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Update

/**
 * Passive Learning — Phase 2/3 storage (built graph-shaped per the Phase 2
 * design check-in, rather than a flat capture log Phase 3 would later
 * convert). Phase 3 narrows to: refining the fingerprint (structural
 * shape, not text — see [PassiveCaptureEngine.computeFingerprint]),
 * tracking [PassiveScreenEntity.appVersion]/[PassiveEdgeEntity.appVersion]
 * so a layout update doesn't silently misapply stale learned data, and
 * 30-day auto-expiry (see the DAOs' deleteOlderThan).
 *
 * A [PassiveScreenEntity] is a node: one (packageName, structural
 * fingerprint) pair. Per the Phase 3 ground rule "one observation is not
 * enough to trust," a screen/edge only counts as CONFIRMED — usable by a
 * future path-finder — once [observationCount]/[PassiveEdgeEntity.observedCount]
 * reaches [CONFIRMATION_THRESHOLD]; see [PassiveScreenEntity.isConfirmed].
 */
const val CONFIRMATION_THRESHOLD = 3

@Entity(tableName = "passive_screens")
data class PassiveScreenEntity(
    @PrimaryKey val screenId: String,   // "$packageName::$fingerprint"
    val packageName: String,
    val fingerprint: String,
    val firstSeenAt: Long,
    val lastSeenAt: Long,
    val observationCount: Int,
    val appVersion: Long = 0L   // Phase 3: the app's versionCode when this screen was last (re)confirmed
) {
    /** Phase 3 ground rule: not routable/trustable until observed this many times. */
    val isConfirmed: Boolean get() = observationCount >= CONFIRMATION_THRESHOLD
}

/**
 * One interactive-or-visible element observed on a [PassiveScreenEntity].
 * Mirrors the fields JunAI's own capture path already uses elsewhere
 * (resourceId/text/contentDescription/stateDescription/className/bounds)
 * rather than inventing a parallel shape — see labelOf() and
 * collectNodes() in the existing recording/snapshot code.
 */
@Entity(tableName = "passive_elements")
data class PassiveElementEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val screenId: String,
    val resourceId: String?,
    val text: String?,
    val contentDescription: String?,
    val stateDescription: String?,
    val className: String?,
    val boundsLeft: Int,
    val boundsTop: Int,
    val boundsRight: Int,
    val boundsBottom: Int,
    val clickable: Boolean,
    val scrollable: Boolean,
    val editable: Boolean,
    val lastSeenAt: Long
)

/**
 * Phase 5 execution — MUST match PassiveCaptureEngine.elementIdentifierOf's
 * priority exactly (id > text > desc > position), since this is how a
 * PassiveEdgeEntity.elementIdentifier (built at capture time from a live
 * node) gets matched back to a stored PassiveElementEntity at execution
 * time. Any drift between the two would mean captured edges silently stop
 * resolving to a real element.
 */
fun PassiveElementEntity.identifier(): String {
    resourceId?.let { return "id:$it" }
    if (!text.isNullOrBlank()) return "text:$text"
    if (!contentDescription.isNullOrBlank()) return "desc:$contentDescription"
    return "pos:$className@$boundsLeft,$boundsTop"
}

/**
 * An observed transition: tapping/typing/scrolling [elementIdentifier] on
 * [fromScreenId] led to [toScreenId] (null until the next screen actually
 * resolves it — see PassiveCaptureEngine's pending-edge handling).
 *
 * [confidence] is seeded at 50 here (Phase 4's default) so Phase 4 can
 * start adjusting it immediately without another migration — Phase 2
 * itself never reads or changes this value beyond the initial seed.
 */
@Entity(tableName = "passive_edges")
data class PassiveEdgeEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val fromScreenId: String,
    val elementIdentifier: String,
    val actionType: String,   // "CLICK" | "LONG_CLICK" | "TYPE" | "SCROLL"
    val toScreenId: String?,
    val observedCount: Int,
    val confidence: Int,
    val lastObservedAt: Long,
    val appVersion: Long = 0L,  // Phase 3: version this edge was last confirmed against
    val consecutiveFailures: Int = 0  // Phase 4: resets to 0 on any success; 3 in a row triggers the steeper-drop + "sikhao" suggestion
) {
    val isConfirmed: Boolean get() = observedCount >= CONFIRMATION_THRESHOLD
}

@Dao
interface PassiveScreenDao {
    @Query("SELECT * FROM passive_screens WHERE screenId = :screenId LIMIT 1")
    suspend fun get(screenId: String): PassiveScreenEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: PassiveScreenEntity)

    @Query("SELECT COUNT(*) FROM passive_screens WHERE packageName = :packageName")
    suspend fun countForApp(packageName: String): Int

    @Query("DELETE FROM passive_screens WHERE packageName = :packageName")
    suspend fun deleteForApp(packageName: String)

    @Query("DELETE FROM passive_screens WHERE lastSeenAt < :cutoff")
    suspend fun deleteOlderThan(cutoff: Long)

    /** Phase 9 — Manage Learning tab: one row per app that has any learned data at all, regardless of current Allow/Deny state. */
    @Query("SELECT packageName, COUNT(*) as screenCount, MAX(lastSeenAt) as lastSeenAt FROM passive_screens GROUP BY packageName ORDER BY lastSeenAt DESC")
    suspend fun getAppSummaries(): List<PassiveAppSummary>

    /** Phase 8 — "usable" learned graph means confirmed (observationCount >= CONFIRMATION_THRESHOLD), not just observed-once. */
    @Query("SELECT COUNT(*) FROM passive_screens WHERE packageName = :packageName AND observationCount >= $CONFIRMATION_THRESHOLD")
    suspend fun countConfirmedForApp(packageName: String): Int

    @Query("DELETE FROM passive_screens")
    suspend fun deleteAll()
}

/** Aggregate row for Phase 9's per-app summary line ("12 screens seekhe, aakhri baar 2 din pehle use hua"). */
data class PassiveAppSummary(
    val packageName: String,
    val screenCount: Int,
    val lastSeenAt: Long
)

@Dao
interface PassiveElementDao {
    /** Used by the dedupe check before insert — see ground rule "No duplicates". */
    @Query("""
        SELECT * FROM passive_elements
        WHERE screenId = :screenId
          AND resourceId IS :resourceId
          AND text IS :text
          AND className IS :className
        LIMIT 1
    """)
    suspend fun findMatching(screenId: String, resourceId: String?, text: String?, className: String?): PassiveElementEntity?

    @Insert
    suspend fun insert(entity: PassiveElementEntity): Long

    @Update
    suspend fun update(entity: PassiveElementEntity)

    @Query("SELECT * FROM passive_elements WHERE screenId = :screenId")
    suspend fun forScreen(screenId: String): List<PassiveElementEntity>

    /** Phase 5 — every element learned anywhere in this app, for the intent-matcher to search across. */
    @Query("""
        SELECT pe.* FROM passive_elements pe
        INNER JOIN passive_screens ps ON pe.screenId = ps.screenId
        WHERE ps.packageName = :packageName
    """)
    suspend fun forApp(packageName: String): List<PassiveElementEntity>

    @Query("DELETE FROM passive_elements WHERE screenId IN (SELECT screenId FROM passive_screens WHERE packageName = :packageName)")
    suspend fun deleteForApp(packageName: String)

    @Query("DELETE FROM passive_elements")
    suspend fun deleteAll()

    /** Phase 3 — run right after PassiveScreenDao.deleteOlderThan, so elements never outlive the screen they belong to. */
    @Query("DELETE FROM passive_elements WHERE screenId NOT IN (SELECT screenId FROM passive_screens)")
    suspend fun deleteOrphaned()
}

@Dao
interface PassiveEdgeDao {
    @Query("SELECT * FROM passive_edges WHERE id = :id LIMIT 1")
    suspend fun getById(id: Long): PassiveEdgeEntity?

    @Query("""
        SELECT * FROM passive_edges
        WHERE fromScreenId = :fromScreenId AND elementIdentifier = :elementIdentifier AND actionType = :actionType
        LIMIT 1
    """)
    suspend fun findPendingMatch(fromScreenId: String, elementIdentifier: String, actionType: String): PassiveEdgeEntity?

    @Query("""
        SELECT * FROM passive_edges
        WHERE fromScreenId = :fromScreenId AND elementIdentifier = :elementIdentifier AND actionType = :actionType AND toScreenId = :toScreenId
        LIMIT 1
    """)
    suspend fun findResolved(fromScreenId: String, elementIdentifier: String, actionType: String, toScreenId: String): PassiveEdgeEntity?

    @Insert
    suspend fun insert(entity: PassiveEdgeEntity): Long

    @Update
    suspend fun update(entity: PassiveEdgeEntity)

    @Query("SELECT * FROM passive_edges WHERE fromScreenId = :fromScreenId")
    suspend fun fromScreen(fromScreenId: String): List<PassiveEdgeEntity>

    /** Phase 5 — every edge captured anywhere in this app, for the path-finder's graph search. */
    @Query("""
        SELECT pe.* FROM passive_edges pe
        INNER JOIN passive_screens ps ON pe.fromScreenId = ps.screenId
        WHERE ps.packageName = :packageName
    """)
    suspend fun forApp(packageName: String): List<PassiveEdgeEntity>

    /** Phase 3 — 30-day auto-expiry: an edge not re-observed recently is stale, not routable. */
    @Query("DELETE FROM passive_edges WHERE lastObservedAt < :cutoff")
    suspend fun deleteOlderThan(cutoff: Long)

    /**
     * Phase 4 — gentle decay for edges that just haven't come up recently
     * (separate from Phase 3's hard 30-day delete; this only ever floors
     * at [floor], it never zeroes an edge out — only explicit failures do
     * that). A single UPDATE rather than a per-row read-modify-write loop.
     */
    @Query("UPDATE passive_edges SET confidence = MAX(:floor, confidence - :amount) WHERE lastObservedAt < :cutoff AND confidence > :floor")
    suspend fun decayStale(cutoff: Long, amount: Int, floor: Int)

    @Query("DELETE FROM passive_edges WHERE fromScreenId IN (SELECT screenId FROM passive_screens WHERE packageName = :packageName)")
    suspend fun deleteForApp(packageName: String)

    @Query("DELETE FROM passive_edges")
    suspend fun deleteAll()

    @Query("DELETE FROM passive_edges WHERE fromScreenId NOT IN (SELECT screenId FROM passive_screens)")
    suspend fun deleteOrphaned()
}
