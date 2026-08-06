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
    val actionType: String,           // "TAP", "LONG_PRESS", "TYPE", "SWIPE", or "SLIDE"
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
    val scrollForward: Boolean? = null,
    // BUGFIX (Phase 1h): only forward/backward was ever recorded — never
    // WHICH AXIS was swiped (vertical list vs a horizontal
    // carousel/tab-strip/gallery). ActionEngine.scrollStep()'s raw-gesture
    // fallback (used when the scrollable node can't be found anymore) had
    // no way to know this, so it always swiped vertically regardless of
    // what was actually recorded — a horizontal swipe replayed via that
    // fallback moved the wrong way entirely. Nullable + defaulted so old
    // macros recorded before this field existed still parse fine (see
    // RecordingEngine.parseSteps' .has() guard) and just fall back to the
    // old vertical-only behavior, same as before this fix.
    val scrollHorizontal: Boolean? = null,
    // PHASE B (matching recalibration — see architecture doc §3.1/§3.3):
    // computed once at capture time (RecordingEngine.idSiblingCount()) —
    // true if this step's resourceId was the ONLY element with that id
    // visible on screen at the moment it was tapped, false if 2+ other
    // elements shared it (QS tiles, list rows, grid icons — the classic
    // "always taps the wrong one" case), null for steps recorded before
    // this existed. Lets findBestMatchingNode() down-weight the id signal
    // for specifically THIS step at replay time instead of only ever
    // discovering the ambiguity generically, per-replay, too late.
    val idDistinctive: Boolean? = null,
    // PHASE D (slider/SeekBar capture — brightness, volume, and similar
    // drag-controlled UI). Only for actionType == "SLIDE". Captured from
    // the node's own AccessibilityNodeInfo.RangeInfo at the moment the
    // value settles (see RecordingEngine.captureSliderChange's debounce —
    // sliders fire many intermediate progress events while being
    // dragged; only the FINAL value is worth recording, same reasoning
    // as the Quick Settings tile-state debounce). Replayed via
    // ACTION_SET_PROGRESS, not by simulating a drag gesture — far more
    // reliable, and doesn't depend on the slider's on-screen size/track
    // length matching what it was at record time.
    val sliderValue: Float? = null,
    val sliderMin: Float? = null,
    val sliderMax: Float? = null
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
