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
     * time but the file on disk is what actually matters. Same
     * size-check approach ChatEngine's copy-check already uses.
     */
    fun isDownloaded(context: Context, modelId: ModelCatalog.ModelId): Boolean {
        val model = ModelCatalog.byId(modelId)
        val file = File(File(context.filesDir, ModelCatalog.localDirName()), model.fileName)
        return file.exists() && file.length() >= model.approxSizeBytes * 0.95
    }

    /** Absolute path engines (ChatEngine/EmbeddingEngine/FunctionCallEngine) should load from, once downloaded. */
    fun localPathFor(context: Context, modelId: ModelCatalog.ModelId): File {
        val model = ModelCatalog.byId(modelId)
        return File(File(context.filesDir, ModelCatalog.localDirName()), model.fileName)
    }
}
