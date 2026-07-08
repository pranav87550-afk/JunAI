package com.junai.app.passive

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query

/**
 * Passive Learning — Phase 1 (Permissions: the "Screen Reading" tab).
 *
 * This is the single gate every Phase 2 capture-path check must pass
 * through: if a package isn't in here with allowed = true, nothing about
 * it is ever observed, full stop (see the Improvement Prompt's Phase 1
 * "Storage" section).
 *
 * [packageName] doubles as the primary key for real apps (e.g.
 * "com.whatsapp") AND for the four pseudo-surfaces from Phase 1's
 * "System" section, which aren't PackageManager entries at all:
 *   "system:home"            — Home Screen / App Drawer
 *   "system:quick_settings"  — Quick Settings
 *   "system:notifications"   — Notifications
 *   "system:recents"         — Recents
 * This keeps Phase 2's capture-gate check to one query shape regardless
 * of whether the foreground surface is a real app or a system surface.
 *
 * JunAI's own package (com.junai.app) is never written here — ground rule
 * 1 makes that non-negotiable and non-togglable, so it's enforced by
 * simply never showing/inserting it, not by a flag on this row.
 */
@Entity(tableName = "app_learning_permissions")
data class AppLearningPermissionEntity(
    @PrimaryKey val packageName: String,
    val allowed: Boolean,
    val allowedAt: Long,
    val category: String?   // e.g. "FINANCE", "SYSTEM", or null for a normal app with no special category
)

@Dao
interface AppLearningPermissionDao {

    @Query("SELECT * FROM app_learning_permissions WHERE packageName = :packageName LIMIT 1")
    suspend fun get(packageName: String): AppLearningPermissionEntity?

    /**
     * The Phase 2 capture-gate check: every capture call starts here.
     * Returns false (deny) for anything never explicitly allowed, which
     * is exactly the "default is Deny, always" ground rule — a missing
     * row and an explicit allowed=false row behave identically.
     */
    @Query("SELECT allowed FROM app_learning_permissions WHERE packageName = :packageName LIMIT 1")
    suspend fun isAllowed(packageName: String): Boolean?

    @Query("SELECT * FROM app_learning_permissions ORDER BY allowedAt DESC")
    suspend fun getAll(): List<AppLearningPermissionEntity>

    @Query("SELECT * FROM app_learning_permissions WHERE allowed = 1")
    suspend fun getAllAllowed(): List<AppLearningPermissionEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: AppLearningPermissionEntity)

    @Query("DELETE FROM app_learning_permissions WHERE packageName = :packageName")
    suspend fun delete(packageName: String)
}
