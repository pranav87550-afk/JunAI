package com.junai.app.learning

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * A single recorded step within a macro. Deliberately identifier-based, not
 * coordinate-based — resourceId/text/contentDescription survive UI position
 * changes (new chats appearing above old ones, status rings shifting, app
 * updates reflowing a screen), where raw (x,y) taps would not.
 *
 * NEVER populated for password/PIN/OTP fields — RecordingEngine skips those
 * at capture time entirely, so there is nothing sensitive to ever persist
 * here in the first place.
 */
data class RecordedStep(
    val actionType: String,           // "TAP", "LONG_PRESS", "TYPE", or "SWIPE"
    val packageName: String?,         // app this step happened in, e.g. "com.whatsapp"
    val resourceId: String?,          // most stable identifier, when available
    val text: String?,                // visible label text, e.g. "Papa"
    val contentDescription: String?,  // for icon-only elements (e.g. avatar)
    val className: String?,           // last-resort fallback identifier
    val typedText: String? = null,    // only for TYPE steps — what was typed
    // BUGFIX: bounds used to be captured for TAP steps only. TYPE steps
    // (EditTexts with no resourceId/contentDescription — very common on
    // custom/compose UIs and third-party apps) had NO fallback at all, so
    // replay died with "no identifier saved" the moment resourceId and
    // contentDescription were both null. Now captured for every step type,
    // so there's always a last-resort spatial fallback — see
    // MacroReplayEngine / ActionEngine.typeStep / findNodeAtPosition.
    //
    // Screen position/size at record time (l,t,r,b in pixels), used ONLY as
    // a last-resort spatial fallback if resourceId/text/contentDescription
    // all fail to find the element during replay (e.g. custom launcher
    // icons with no accessible label). Coordinates can drift if the UI
    // reflows, so this is deliberately the LOWEST-priority identifier, not
    // the primary one — see MacroReplayEngine's lookup order.
    val boundsLeft: Int? = null,
    val boundsTop: Int? = null,
    val boundsRight: Int? = null,
    val boundsBottom: Int? = null,
    // Only for SWIPE steps — true = scrolled forward/down/right (content
    // moved up/left), false = backward/up/left. Derived from the scrollable
    // node's own AccessibilityEvent delta at capture time (see
    // RecordingEngine.captureScroll), so replay reproduces the same
    // direction via ACTION_SCROLL_FORWARD/BACKWARD rather than guessing.
    val scrollForward: Boolean? = null
)

@Entity(tableName = "recorded_macros")
data class RecordedMacroEntity(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val triggerPhrase: String,        // normalized (lowercase, trimmed) phrase that replays this
    val displayPhrase: String,        // original phrasing, for showing in the Execute tab
    val stepsJson: String,            // JSON array of RecordedStep
    val stepCount: Int,
    val createdAt: Long,
    val lastUsedAt: Long = 0L,
    val timesReplayed: Int = 0
)
