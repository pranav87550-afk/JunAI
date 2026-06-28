package com.junai.app.agent

import android.content.Context
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Update
import com.junai.app.AppDatabase
import com.junai.app.IntentDetector
import org.json.JSONArray
import org.json.JSONObject

enum class TaskStatus { RUNNING, PAUSED, COMPLETED, FAILED, CANCELLED }

/**
 * agent_tasks table — exact schema from spec: id, goalText, agentTaskParams
 * (JSON), steps (JSON), currentStep, status, timestamp. No extra columns
 * added, to match the spec precisely.
 */
@Entity(tableName = "agent_tasks")
data class AgentTaskEntity(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val goalText: String,
    val agentTaskParams: String,   // JSON-encoded IntentDetector.AgentTaskParams
    val steps: String,             // JSON-encoded List<AgentStep>
    val currentStep: Int,
    val status: String,            // TaskStatus.name
    val timestamp: Long = System.currentTimeMillis()
)

@Dao
interface AgentTaskDao {
    @Insert
    suspend fun insert(task: AgentTaskEntity): Long

    @Update
    suspend fun update(task: AgentTaskEntity)

    @Query("SELECT * FROM agent_tasks WHERE id = :id")
    suspend fun getById(id: Int): AgentTaskEntity?

    @Query("SELECT * FROM agent_tasks WHERE status = 'PAUSED' ORDER BY timestamp DESC LIMIT 1")
    suspend fun getMostRecentPaused(): AgentTaskEntity?

    @Query("SELECT * FROM agent_tasks ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getRecent(limit: Int): List<AgentTaskEntity>

    @Query("DELETE FROM agent_tasks WHERE id = :id")
    suspend fun delete(id: Int)

    @Query("DELETE FROM agent_tasks WHERE timestamp < :olderThan")
    suspend fun deleteOlderThan(olderThan: Long)
}

/** Returned to AgentEngine when the user says "resume" / "continue". */
data class ResumableTask(
    val taskId: Int,
    val params: IntentDetector.AgentTaskParams,
    val steps: List<AgentStep>,
    val currentStepIndex: Int
)

/**
 * MultiStepTaskManager — persists long-running agent workflows so they
 * survive interruption (app killed, user says "stop", phone call comes in)
 * and can be resumed exactly where they left off.
 *
 * AgentEngine is the only caller — it calls [startNewTask] right after
 * GoalPlanner produces a plan, [updateProgress] after every step, and
 * [pauseTask]/[cancelTask]/[completeTask]/[failTask] as the task's lifecycle
 * dictates. The FloatingBotService bubble (existing, wired in a later
 * integration pass) reads current step text from AgentEngine to show
 * real-time progress — this manager only owns persistence, not UI.
 */
class MultiStepTaskManager(context: Context) {

    private val dao = AppDatabase.getInstance(context).agentTaskDao()

    suspend fun startNewTask(params: IntentDetector.AgentTaskParams, steps: List<AgentStep>): Int {
        val entity = AgentTaskEntity(
            goalText = params.rawGoal,
            agentTaskParams = paramsToJson(params),
            steps = stepsToJson(steps),
            currentStep = 0,
            status = TaskStatus.RUNNING.name
        )
        return dao.insert(entity).toInt()
    }

    suspend fun updateProgress(taskId: Int, currentStepIndex: Int, updatedSteps: List<AgentStep>) {
        val existing = dao.getById(taskId) ?: return
        dao.update(
            existing.copy(
                steps = stepsToJson(updatedSteps),
                currentStep = currentStepIndex,
                timestamp = System.currentTimeMillis()
            )
        )
    }

    /** Called on "stop" / "cancel" mid-task, or when the app is about to die mid-workflow. */
    suspend fun pauseTask(taskId: Int) = setStatus(taskId, TaskStatus.PAUSED)
    suspend fun completeTask(taskId: Int) = setStatus(taskId, TaskStatus.COMPLETED)
    suspend fun cancelTask(taskId: Int) = setStatus(taskId, TaskStatus.CANCELLED)
    suspend fun failTask(taskId: Int) = setStatus(taskId, TaskStatus.FAILED)

    private suspend fun setStatus(taskId: Int, status: TaskStatus) {
        val existing = dao.getById(taskId) ?: return
        dao.update(existing.copy(status = status.name, timestamp = System.currentTimeMillis()))
    }

    /** Called when the user says "resume" / "continue" (IntentDetector.AgentActionType.RESUME). */
    suspend fun getResumableTask(): ResumableTask? {
        val entity = dao.getMostRecentPaused() ?: return null
        return ResumableTask(
            taskId = entity.id,
            params = paramsFromJson(entity.agentTaskParams),
            steps = stepsFromJson(entity.steps),
            currentStepIndex = entity.currentStep
        )
    }

    suspend fun hasResumableTask(): Boolean = dao.getMostRecentPaused() != null

    suspend fun getRecentTasks(limit: Int = 10): List<AgentTaskEntity> = dao.getRecent(limit)

    // ── JSON encode/decode — org.json is already used elsewhere in the
    // codebase (e.g. MainActivity), so no new dependency is introduced. ──

    private fun paramsToJson(params: IntentDetector.AgentTaskParams): String {
        val obj = JSONObject()
        obj.put("rawGoal", params.rawGoal)
        obj.put("actionType", params.actionType.name)
        obj.put("targetApp", params.targetApp ?: JSONObject.NULL)
        obj.put("targetSetting", params.targetSetting ?: JSONObject.NULL)
        obj.put("searchQuery", params.searchQuery ?: JSONObject.NULL)
        obj.put("targetContact", params.targetContact ?: JSONObject.NULL)
        return obj.toString()
    }

    private fun paramsFromJson(json: String): IntentDetector.AgentTaskParams {
        val obj = JSONObject(json)
        return IntentDetector.AgentTaskParams(
            rawGoal = obj.getString("rawGoal"),
            actionType = IntentDetector.AgentActionType.valueOf(obj.getString("actionType")),
            targetApp = obj.optStringOrNull("targetApp"),
            targetSetting = obj.optStringOrNull("targetSetting"),
            searchQuery = obj.optStringOrNull("searchQuery"),
            targetContact = obj.optStringOrNull("targetContact")
        )
    }

    private fun stepsToJson(steps: List<AgentStep>): String {
        val arr = JSONArray()
        for (step in steps) {
            val obj = JSONObject()
            obj.put("stepNumber", step.stepNumber)
            obj.put("type", step.type.name)
            obj.put("description", step.description)
            obj.put("target", step.target)
            obj.put("fallback", step.fallback ?: JSONObject.NULL)
            obj.put("status", step.status.name)
            arr.put(obj)
        }
        return arr.toString()
    }

    private fun stepsFromJson(json: String): List<AgentStep> {
        val arr = JSONArray(json)
        return (0 until arr.length()).map { i ->
            val obj = arr.getJSONObject(i)
            AgentStep(
                stepNumber = obj.getInt("stepNumber"),
                type = StepType.valueOf(obj.getString("type")),
                description = obj.getString("description"),
                target = obj.getString("target"),
                fallback = obj.optStringOrNull("fallback"),
                status = StepStatus.valueOf(obj.getString("status"))
            )
        }
    }

    private fun JSONObject.optStringOrNull(key: String): String? =
        if (!has(key) || isNull(key)) null else getString(key)
}
