package com.junai.app.ml

import android.content.Context
import androidx.lifecycle.LiveData
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Thin wrapper over WorkManager for the Models screen (ModelManagerActivity,
 * next piece) to call into — callers shouldn't need to touch WorkManager
 * APIs or ModelDownloadWorker directly.
 */
object ModelDownloadManager {

    private fun uniqueWorkName(modelId: ModelCatalog.ModelId) = "model_download_${modelId.name}"

    /**
     * Sidecar file recording the byte count ModelDownloadWorker actually
     * verified against the server's Content-Length right after a
     * download — NOT ModelCatalog's approxSizeBytes guess. Written only
     * once a download passes that integrity check; read by
     * isDownloaded() below to catch a truncated file that still exists
     * on disk under its final filename (e.g. one that was corrupted
     * before this verification existed, or survived some other way a
     * plain existence check wouldn't catch).
     */
    private fun verifiedSizeFile(context: Context, model: ModelCatalog.ModelInfo): File =
        File(File(context.filesDir, ModelCatalog.localDirName()), "${model.fileName}.verified_size")

    fun recordVerifiedSize(context: Context, modelId: ModelCatalog.ModelId, bytes: Long) {
        try {
            val model = ModelCatalog.byId(modelId)
            verifiedSizeFile(context, model).writeText(bytes.toString())
        } catch (e: Exception) { /* best-effort — worst case, isDownloaded() just falls back to plain existence */ }
    }

    /**
     * Starts (or resumes, if a .part file already exists) downloading
     * the given model. Safe to call again while already in progress —
     * ExistingWorkPolicy.KEEP means a second tap on "Download" while
     * one is already running just attaches to the existing work instead
     * of starting a duplicate.
     */
    fun download(context: Context, modelId: ModelCatalog.ModelId) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()
        val request = OneTimeWorkRequestBuilder<ModelDownloadWorker>()
            .setInputData(workDataOf(ModelDownloadWorker.KEY_MODEL_ID to modelId.name))
            .setConstraints(constraints)
            // Exponential backoff on transient failures (network drop
            // mid-download, server hiccup) rather than hammering
            // retries back-to-back — 30s initial delay, doubling each
            // retry, WorkManager's own cap after that.
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context)
            .enqueueUniqueWork(uniqueWorkName(modelId), ExistingWorkPolicy.KEEP, request)
    }

    fun cancel(context: Context, modelId: ModelCatalog.ModelId) {
        WorkManager.getInstance(context).cancelUniqueWork(uniqueWorkName(modelId))
    }

    /**
     * Retries a previously failed/cancelled download from scratch —
     * REPLACE instead of KEEP, since a failed/cancelled work item
     * sitting in WorkManager's queue wouldn't restart on its own.
     */
    fun retry(context: Context, modelId: ModelCatalog.ModelId) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()
        val request = OneTimeWorkRequestBuilder<ModelDownloadWorker>()
            .setInputData(workDataOf(ModelDownloadWorker.KEY_MODEL_ID to modelId.name))
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context)
            .enqueueUniqueWork(uniqueWorkName(modelId), ExistingWorkPolicy.REPLACE, request)
    }

    /** UI observes this (LiveData) to drive a progress bar / status badge for one model. */
    fun observe(context: Context, modelId: ModelCatalog.ModelId): LiveData<List<WorkInfo>> =
        WorkManager.getInstance(context).getWorkInfosForUniqueWorkLiveData(uniqueWorkName(modelId))

    /**
     * File-based ground truth for "is this model actually usable right
     * now" — independent of WorkManager's job state, since a completed
     * download's WorkInfo can be pruned/forgotten by the system over
     * time but the file on disk is what actually matters.
     *
     * BUGFIX (history): originally compared file.length() against a
     * percentage of ModelCatalog's approxSizeBytes — but those are
     * rough hand-guessed numbers (e.g. "~300 MB"), not the real file
     * size, so a fully downloaded model could fail this check forever
     * if the guess was too high. That was replaced with plain
     * existence, which is correct for a cleanly-finished download but
     * has no way to catch a genuinely truncated file that still landed
     * under its final filename (confirmed to happen: a dropped
     * connection mid-stream that exits the read loop the same way a
     * real completion does — see ModelDownloadWorker's integrity check
     * added alongside this).
     *
     * BUGFIX (this pass): rather than reintroducing the approxSizeBytes
     * guess, check against the byte count ModelDownloadWorker itself
     * verified against the server's real Content-Length right after
     * downloading (see recordVerifiedSize/verifiedSizeFile above). If
     * no such marker exists yet (model downloaded before this existed,
     * or the marker write itself failed), fall back to plain existence
     * exactly as before — never a false negative for an already-working
     * model.
     */
    fun isDownloaded(context: Context, modelId: ModelCatalog.ModelId): Boolean {
        val model = ModelCatalog.byId(modelId)
        val knowledgeFiles = model.knowledgeFiles
        if (knowledgeFiles != null) {
            // Multi-file bundle (KNOWLEDGE_PACK) — "downloaded" means
            // every domain file landed, not just one. Same plain
            // existence check as the single-file case below, just
            // ANDed across all of them.
            val dir = File(context.filesDir, ModelCatalog.localDirName())
            return knowledgeFiles.all { kf ->
                val f = File(dir, kf.remoteFileName)
                f.exists() && f.length() > 0
            }
        }
        val file = File(File(context.filesDir, ModelCatalog.localDirName()), model.fileName)
        if (!file.exists() || file.length() <= 0) return false

        val verifiedSize = try {
            verifiedSizeFile(context, model).takeIf { it.exists() }?.readText()?.trim()?.toLongOrNull()
        } catch (e: Exception) { null }
        if (verifiedSize != null && file.length() != verifiedSize) return false

        return true
    }

    /**
     * Deletes a model's file(s) so it can be cleanly re-downloaded — the
     * missing piece that made "just delete the corrupt file and
     * redownload" impossible from the Models screen before this: there
     * was no function anywhere that removed a model's file once it
     * existed. Also clears the .part (in-progress) file and the
     * verified-size marker so isDownloaded()/a fresh download both start
     * from a clean slate.
     */
    fun deleteModel(context: Context, modelId: ModelCatalog.ModelId) {
        val model = ModelCatalog.byId(modelId)
        val dir = File(context.filesDir, ModelCatalog.localDirName())
        val knowledgeFiles = model.knowledgeFiles
        if (knowledgeFiles != null) {
            knowledgeFiles.forEach { kf -> File(dir, kf.remoteFileName).delete() }
            return
        }
        File(dir, model.fileName).delete()
        File(dir, "${model.fileName}.part").delete()
        verifiedSizeFile(context, model).delete()
    }

    /** Absolute path engines (ChatEngine/EmbeddingEngine/FunctionCallEngine) should load from, once downloaded. */
    fun localPathFor(context: Context, modelId: ModelCatalog.ModelId): File {
        val model = ModelCatalog.byId(modelId)
        return File(File(context.filesDir, ModelCatalog.localDirName()), model.fileName)
    }

    /**
     * Directory containing every downloaded knowledge-pack JSON file
     * (filesDir/models/knowledge/) — what KnowledgeBase.kt reads from,
     * once ModelStateStore.isReady(KNOWLEDGE_PACK) is true.
     */
    fun localKnowledgeDir(context: Context): File =
        File(File(context.filesDir, ModelCatalog.localDirName()), "knowledge")
}
