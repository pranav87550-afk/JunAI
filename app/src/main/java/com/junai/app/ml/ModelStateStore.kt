package com.junai.app.ml

import android.content.Context
import androidx.lifecycle.MediatorLiveData
import androidx.lifecycle.LiveData
import androidx.work.WorkInfo

/**
 * Resolves what state a model is currently in for the Models screen to
 * show. Deliberately does NOT keep its own persisted status flag in
 * SharedPreferences — this app has already been bitten a few times
 * (see ChatEngine's init()/resetConversation() history) by a
 * hand-maintained "is it ready" flag drifting out of sync with the
 * actual underlying state. Instead this always derives status fresh
 * from the two things that are already the real ground truth:
 *   1. Does the model file exist on disk with the right size?
 *      (ModelDownloadManager.isDownloaded() — same check ChatEngine's
 *      own copy logic already trusts)
 *   2. What is WorkManager's current job state for that model's unique
 *      work name? (queued / running / failed / not present at all)
 * There's nothing to fall out of sync because there's nothing extra
 * being stored — every read re-derives from those two sources.
 */
object ModelStateStore {

    enum class Status { NOT_DOWNLOADED, QUEUED, DOWNLOADING, READY, FAILED }

    data class StatusInfo(
        val status: Status,
        val progressPercent: Int = 0,
        val bytesDownloaded: Long = 0,
        val bytesTotal: Long = 0,
    )

    /**
     * One-shot synchronous check — safe to call from a background
     * thread (e.g. right before an engine's init()) when you just need
     * a yes/no on "is this model usable right now", without caring
     * about live download progress. Cheap: just a file stat, no
     * WorkManager query.
     */
    fun isReady(context: Context, modelId: ModelCatalog.ModelId): Boolean =
        ModelDownloadManager.isDownloaded(context, modelId)

    /**
     * Live status for UI — updates automatically as a download
     * progresses. Combines the file check (for the READY / NOT_DOWNLOADED
     * resting states) with WorkManager's live WorkInfo (for QUEUED /
     * DOWNLOADING / FAILED while something is actively happening).
     */
    fun observe(context: Context, modelId: ModelCatalog.ModelId): LiveData<StatusInfo> {
        val result = MediatorLiveData<StatusInfo>()
        val appContext = context.applicationContext

        fun recompute(workInfos: List<WorkInfo>?) {
            // A finished (SUCCEEDED) or absent work entry doesn't
            // necessarily mean "ready" on its own — WorkManager's
            // history can be pruned by the system over time, so the
            // file check is still the deciding vote for the resting
            // states. Only the ACTIVE states (queued/running/failed)
            // come purely from WorkInfo, since there's no file-based
            // way to observe "in progress".
            val active = workInfos?.firstOrNull {
                it.state == WorkInfo.State.RUNNING || it.state == WorkInfo.State.ENQUEUED
            }
            val failed = workInfos?.any { it.state == WorkInfo.State.FAILED } == true

            result.value = when {
                active != null && active.state == WorkInfo.State.RUNNING -> {
                    val pct = active.progress.getInt(ModelDownloadWorker.KEY_PROGRESS_PERCENT, 0)
                    val downloaded = active.progress.getLong(ModelDownloadWorker.KEY_BYTES_DOWNLOADED, 0)
                    val total = active.progress.getLong(ModelDownloadWorker.KEY_BYTES_TOTAL, 0)
                    StatusInfo(Status.DOWNLOADING, pct, downloaded, total)
                }
                active != null && active.state == WorkInfo.State.ENQUEUED -> StatusInfo(Status.QUEUED)
                ModelDownloadManager.isDownloaded(appContext, modelId) -> StatusInfo(Status.READY, 100)
                failed -> StatusInfo(Status.FAILED)
                else -> StatusInfo(Status.NOT_DOWNLOADED)
            }
        }

        val workLiveData = ModelDownloadManager.observe(appContext, modelId)
        result.addSource(workLiveData) { recompute(it) }
        // Seed an initial value immediately (file-check only) so the UI
        // isn't blank before WorkManager's LiveData first emits.
        recompute(null)
        return result
    }
}
