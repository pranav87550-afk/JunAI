package com.junai.app.passive

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Update

/**
 * Passive Learning — Phase 2 storage (built graph-shaped per the Phase 2
 * design check-in, rather than a flat capture log Phase 3 would later
 * convert). Phase 3's job is narrowed to refining HOW a fingerprint is
 * computed and matched, not to a data-migration.
 *
 * A [PassiveScreenEntity] is a node: one (packageName, structural
 * fingerprint) pair. [fingerprint] here is intentionally simple —
 * see [PassiveCaptureEngine.computeFingerprint] doc comment — and is
 * expected to get smarter in Phase 3 without a schema change, since it's
 * just a TEXT column either way.
 */
@Entity(tableName = "passive_screens")
data class PassiveScreenEntity(
    @PrimaryKey val screenId: String,   // "$packageName::$fingerprint"
    val packageName: String,
    val fingerprint: String,
    val firstSeenAt: Long,
    val lastSeenAt: Long,
    val observationCount: Int
)

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
    val lastObservedAt: Long
)

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

    @Query("DELETE FROM passive_elements WHERE screenId IN (SELECT screenId FROM passive_screens WHERE packageName = :packageName)")
    suspend fun deleteForApp(packageName: String)

    @Query("DELETE FROM passive_elements")
    suspend fun deleteAll()
}

@Dao
interface PassiveEdgeDao {
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

    @Query("DELETE FROM passive_edges WHERE fromScreenId IN (SELECT screenId FROM passive_screens WHERE packageName = :packageName)")
    suspend fun deleteForApp(packageName: String)

    @Query("DELETE FROM passive_edges")
    suspend fun deleteAll()
}
