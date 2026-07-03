package com.junai.app.learning

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Update

/**
 * A persistent, cross-macro knowledge base of every UI element Jun has
 * ever interacted with — separate from RecordedMacroEntity.
 *
 * Why this exists (the actual gap the "phone/Location" bugs kept exposing):
 * a macro's own steps only ever see ONE demonstration of an element. If
 * that one demonstration captured a poor identifier (a Quick Settings
 * tile's bare icon, no label anywhere nearby), the macro is stuck with
 * that poor identifier forever, and every future recording of a similar
 * element starts from zero again — no memory carries over between
 * recordings. This table is that memory: every tap/long-press, in every
 * recording, in every app, gets logged here by (packageName, resourceId,
 * className, approximate position) — building up a real profile per
 * element over time: its best-known label, its size, and how often it's
 * actually been interacted with. A resourceId/bounds combo seen 12 times
 * with a confidently-resolved label is a much stronger signal than a
 * single fresh observation with no label at all, and future matching
 * (recording OR replay) can fall back on this table when a fresh capture
 * comes up short on its own.
 */
@Entity(tableName = "learned_elements")
data class LearnedElementEntity(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val packageName: String,          // app this element belongs to, e.g. "com.android.systemui"
    val resourceId: String?,          // e.g. "com.android.systemui:id/tile"
    val className: String?,           // e.g. "android.widget.FrameLayout"
    val label: String?,               // best-known text or contentDescription, e.g. "Location"
    val boundsLeft: Int,
    val boundsTop: Int,
    val boundsRight: Int,
    val boundsBottom: Int,
    val width: Int,                   // derived, kept as its own column so queries don't recompute it
    val height: Int,
    val interactionCount: Int = 1,    // how many times this exact element has been tapped/held across ALL recordings
    val firstSeenAt: Long,
    val lastSeenAt: Long
)

@Dao
interface LearnedElementDao {

    /**
     * Finds an existing entry for the same element: same app + same
     * resourceId/className, at roughly the same position (±40px — enough
     * to absorb minor status-bar/banner-driven shifts without matching a
     * genuinely different element). Position is part of the match because
     * resourceId alone is often shared by many elements (every Quick
     * Settings tile, every RecyclerView row) — position is what tells them
     * apart.
     */
    @Query(
        """
        SELECT * FROM learned_elements
        WHERE packageName = :packageName
          AND (resourceId = :resourceId OR (resourceId IS NULL AND :resourceId IS NULL))
          AND ABS(boundsLeft - :left) < 40 AND ABS(boundsTop - :top) < 40
        LIMIT 1
        """
    )
    suspend fun findMatching(packageName: String, resourceId: String?, left: Int, top: Int): LearnedElementEntity?

    @Insert
    suspend fun insert(entity: LearnedElementEntity): Long

    @Update
    suspend fun update(entity: LearnedElementEntity)

    /** All known elements for an app — used for richer matching during replay/recording. */
    @Query("SELECT * FROM learned_elements WHERE packageName = :packageName ORDER BY interactionCount DESC")
    suspend fun forApp(packageName: String): List<LearnedElementEntity>

    @Query("SELECT * FROM learned_elements WHERE packageName = :packageName AND label IS NOT NULL ORDER BY interactionCount DESC")
    suspend fun labeledElementsForApp(packageName: String): List<LearnedElementEntity>

    @Query("SELECT COUNT(*) FROM learned_elements")
    suspend fun count(): Int
}
