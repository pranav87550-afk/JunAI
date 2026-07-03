package com.junai.app.agent.action

import android.content.Context
import android.view.accessibility.AccessibilityNodeInfo
import com.junai.app.AppDatabase
import com.junai.app.agent.screen.ScreenContextEngine
import com.junai.app.learning.RecordedMacroEntity
import com.junai.app.learning.RecordedStep
import org.json.JSONArray
import org.json.JSONObject

/**
 * Records a sequence of user gestures (taps + typed text) as an
 * identifier-based macro, so it can be replayed later for the same trigger
 * phrase. See RecordedStep for why identifiers (resourceId/text/
 * contentDescription) are used instead of raw coordinates.
 *
 * SECURITY: password/PIN/OTP fields are excluded at CAPTURE time, not
 * filtered afterward — isSensitive() runs before a step is ever added to
 * the in-memory list, so sensitive input never exists in this object, let
 * alone reaches the database. This is a hard rule, not a toggle.
 */
object RecordingEngine {

    private var recordingActive = false
    private var triggerPhrase: String = ""
    private var displayPhrase: String = ""
    private val steps = mutableListOf<RecordedStep>()

    val isRecording: Boolean get() = recordingActive

    fun start(triggerPhrase: String, displayPhrase: String) {
        steps.clear()
        this.triggerPhrase = triggerPhrase.lowercase().trim()
        this.displayPhrase = displayPhrase
        recordingActive = true
    }

    fun cancel() {
        recordingActive = false
        steps.clear()
    }

    fun stepCount(): Int = steps.size

    /** Called by JunAccessibilityService on TYPE_VIEW_CLICKED (or other click-adjacent events) while recording. */
    fun captureTap(node: AccessibilityNodeInfo) {
        if (!recordingActive) return
        if (isSensitive(node)) return
        val rect = android.graphics.Rect()
        node.getBoundsInScreen(rect)
        steps.add(
            RecordedStep(
                actionType = "TAP",
                packageName = node.packageName?.toString(),
                resourceId = node.viewIdResourceName,
                text = node.text?.toString()?.takeIf { it.isNotBlank() },
                contentDescription = node.contentDescription?.toString()?.takeIf { it.isNotBlank() },
                className = node.className?.toString(),
                boundsLeft = rect.left,
                boundsTop = rect.top,
                boundsRight = rect.right,
                boundsBottom = rect.bottom
            )
        )
    }

    /** Called by JunAccessibilityService on TYPE_VIEW_TEXT_CHANGED while recording. */
    fun captureType(node: AccessibilityNodeInfo, typedText: String) {
        if (!recordingActive) return
        if (isSensitive(node)) return
        if (typedText.isBlank()) return

        // Collapse to one TYPE step per field — without this, every single
        // keystroke would append a new step (text-changed fires per
        // character), producing dozens of near-duplicate steps for one
        // typed sentence.
        val last = steps.lastOrNull()
        if (last != null && last.actionType == "TYPE" && last.resourceId == node.viewIdResourceName) {
            steps[steps.lastIndex] = last.copy(typedText = typedText)
        } else {
            steps.add(
                RecordedStep(
                    actionType = "TYPE",
                    packageName = node.packageName?.toString(),
                    resourceId = node.viewIdResourceName,
                    text = null,
                    contentDescription = node.contentDescription?.toString(),
                    className = node.className?.toString(),
                    typedText = typedText
                )
            )
        }
    }

    private fun isSensitive(node: AccessibilityNodeInfo): Boolean {
        return node.isPassword ||
            ScreenContextEngine.looksLikeSensitiveLabel(node.hintText?.toString()) ||
            ScreenContextEngine.looksLikeSensitiveLabel(node.text?.toString()) ||
            ScreenContextEngine.looksLikeSensitiveLabel(node.contentDescription?.toString()) ||
            ScreenContextEngine.looksLikeSensitiveLabel(node.viewIdResourceName)
    }

    /**
     * Stops recording and persists whatever was captured. Returns null if
     * nothing usable was recorded (e.g. every tap happened to be on a
     * sensitive field, or the user pressed volume before doing anything).
     */
    suspend fun stopAndSave(context: Context): RecordedMacroEntity? {
        recordingActive = false
        if (steps.isEmpty()) return null

        val stepsJson = serializeSteps(steps)
        val entity = RecordedMacroEntity(
            triggerPhrase = triggerPhrase,
            displayPhrase = displayPhrase,
            stepsJson = stepsJson,
            stepCount = steps.size,
            createdAt = System.currentTimeMillis()
        )
        AppDatabase.getInstance(context).recordedMacroDao().insert(entity)
        steps.clear()
        return entity
    }

    fun serializeSteps(list: List<RecordedStep>): String {
        val arr = JSONArray()
        for (s in list) {
            arr.put(JSONObject().apply {
                put("actionType", s.actionType)
                put("packageName", s.packageName ?: JSONObject.NULL)
                put("resourceId", s.resourceId ?: JSONObject.NULL)
                put("text", s.text ?: JSONObject.NULL)
                put("contentDescription", s.contentDescription ?: JSONObject.NULL)
                put("className", s.className ?: JSONObject.NULL)
                put("typedText", s.typedText ?: JSONObject.NULL)
                put("boundsLeft", s.boundsLeft ?: JSONObject.NULL)
                put("boundsTop", s.boundsTop ?: JSONObject.NULL)
                put("boundsRight", s.boundsRight ?: JSONObject.NULL)
                put("boundsBottom", s.boundsBottom ?: JSONObject.NULL)
            })
        }
        return arr.toString()
    }

    fun parseSteps(json: String): List<RecordedStep> {
        return try {
            val arr = JSONArray(json)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                RecordedStep(
                    actionType = o.optString("actionType", "TAP"),
                    packageName = if (o.isNull("packageName")) null else o.optString("packageName"),
                    resourceId = if (o.isNull("resourceId")) null else o.optString("resourceId"),
                    text = if (o.isNull("text")) null else o.optString("text"),
                    contentDescription = if (o.isNull("contentDescription")) null else o.optString("contentDescription"),
                    className = if (o.isNull("className")) null else o.optString("className"),
                    typedText = if (o.isNull("typedText")) null else o.optString("typedText"),
                    boundsLeft = if (o.has("boundsLeft") && !o.isNull("boundsLeft")) o.optInt("boundsLeft") else null,
                    boundsTop = if (o.has("boundsTop") && !o.isNull("boundsTop")) o.optInt("boundsTop") else null,
                    boundsRight = if (o.has("boundsRight") && !o.isNull("boundsRight")) o.optInt("boundsRight") else null,
                    boundsBottom = if (o.has("boundsBottom") && !o.isNull("boundsBottom")) o.optInt("boundsBottom") else null
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }
}
