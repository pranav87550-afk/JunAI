package com.junai.app.ml

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.TimeUnit

/**
 * Downloads ONE model file (see ModelCatalog) from Hugging Face into
 * filesDir/models/. Runs as a WorkManager CoroutineWorker rather than a
 * plain coroutine/Thread so it survives the app being backgrounded —
 * these are multi-hundred-MB files and can easily take a few minutes on
 * a normal connection, longer on weak signal.
 *
 * RESUME SUPPORT: writes into a "<file>.part" temp file first. If that
 * .part file already exists from a previous attempt (app killed,
 * network dropped, etc.), this resumes from where it left off using an
 * HTTP Range request instead of restarting the whole download — matters
 * a lot for a 500MB+ file on a shaky connection. Only renamed to the
 * final filename once the full download completes successfully, so a
 * half-downloaded file is never mistaken for a ready one (ChatEngine's
 * existing file-exists check would otherwise think a partial file is
 * a complete model).
 */
class ModelDownloadWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    companion object {
        const val KEY_MODEL_ID = "model_id"
        const val KEY_PROGRESS_PERCENT = "progress_percent"
        const val KEY_BYTES_DOWNLOADED = "bytes_downloaded"
        const val KEY_BYTES_TOTAL = "bytes_total"

        private const val CHANNEL_ID = "model_download_channel"
        private const val NOTIF_ID_BASE = 9000

        // Streamed in chunks rather than reading the whole body into
        // memory — these files are hundreds of MB, way too big to
        // buffer whole on a phone.
        private const val BUFFER_SIZE = 8 * 1024

        // How often (in bytes) to push a progress update — pushing on
        // every single 8KB chunk would spam setProgress()/the
        // notification hundreds of times a second for no visible
        // benefit. ~1% of a typical model size lands around here.
        private const val PROGRESS_UPDATE_INTERVAL_BYTES = 2 * 1024 * 1024L
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val modelIdName = inputData.getString(KEY_MODEL_ID)
            ?: return@withContext Result.failure()
        val modelId = try {
            ModelCatalog.ModelId.valueOf(modelIdName)
        } catch (e: IllegalArgumentException) {
            return@withContext Result.failure()
        }
        val model = ModelCatalog.byId(modelId)

        val dir = File(applicationContext.filesDir, ModelCatalog.localDirName())
        if (!dir.exists()) dir.mkdirs()
        val finalFile = File(dir, model.fileName)
        val partFile = File(dir, "${model.fileName}.part")

        // Already fully downloaded from a prior run — nothing to do.
        // (Size-only check, same pragmatic approach ChatEngine already
        // uses for its assets-copy — a proper checksum would be more
        // rigorous but Hugging Face doesn't hand us one cheaply here.)
        if (finalFile.exists() && finalFile.length() >= model.approxSizeBytes * 0.95) {
            return@withContext Result.success()
        }

        setForegroundSafely(model.displayName, 0)

        try {
            val resumeFrom = if (partFile.exists()) partFile.length() else 0L
            val requestBuilder = Request.Builder().url(model.downloadUrl)
            if (resumeFrom > 0) {
                requestBuilder.addHeader("Range", "bytes=$resumeFrom-")
            }

            client.newCall(requestBuilder.build()).execute().use { response ->
                // 416 = "range not satisfiable" — usually means our
                // .part file is already >= the server's file (stale/
                // corrupt leftover). Safest fix: drop it and restart
                // clean rather than looping on a request that will
                // keep failing the same way.
                if (response.code == 416) {
                    partFile.delete()
                    return@withContext Result.retry()
                }
                if (!response.isSuccessful) {
                    return@withContext Result.retry()
                }

                val body = response.body ?: return@withContext Result.retry()
                val isPartialResponse = response.code == 206
                val alreadyHave = if (isPartialResponse) resumeFrom else 0L
                if (!isPartialResponse && partFile.exists()) {
                    // Server ignored our Range request (some CDNs do on
                    // certain paths) — it's sending the full file from
                    // byte 0, so our old partial bytes would corrupt
                    // the result if kept. Start over.
                    partFile.delete()
                }

                val contentLength = body.contentLength()
                val totalBytes = if (contentLength > 0) alreadyHave + contentLength else model.approxSizeBytes

                RandomAccessFile(partFile, "rw").use { raf ->
                    raf.seek(alreadyHave)
                    body.byteStream().use { input ->
                        val buffer = ByteArray(BUFFER_SIZE)
                        var downloaded = alreadyHave
                        var sinceLastUpdate = 0L
                        while (true) {
                            val read = input.read(buffer)
                            if (read == -1) break
                            raf.write(buffer, 0, read)
                            downloaded += read
                            sinceLastUpdate += read
                            if (sinceLastUpdate >= PROGRESS_UPDATE_INTERVAL_BYTES) {
                                sinceLastUpdate = 0
                                val pct = ((downloaded * 100) / totalBytes).toInt().coerceIn(0, 100)
                                setProgress(
                                    workDataOf(
                                        KEY_PROGRESS_PERCENT to pct,
                                        KEY_BYTES_DOWNLOADED to downloaded,
                                        KEY_BYTES_TOTAL to totalBytes,
                                    )
                                )
                                setForegroundSafely(model.displayName, pct)
                            }
                        }
                    }
                }
            }

            // Full download landed in the .part file with no exception
            // — safe to promote it to the real filename now.
            if (!partFile.renameTo(finalFile)) {
                // rename can fail across some filesystems/edge cases —
                // fall back to copy+delete rather than leaving the
                // download "done" but stuck under the .part name.
                partFile.copyTo(finalFile, overwrite = true)
                partFile.delete()
            }
            setProgress(workDataOf(KEY_PROGRESS_PERCENT to 100))
            Result.success()
        } catch (e: Exception) {
            // Network hiccups, timeouts, etc. — .part file is left in
            // place on purpose so the next attempt (WorkManager's own
            // retry, or a user-tapped retry) resumes instead of
            // restarting from zero.
            Result.retry()
        }
    }

    /**
     * A 500MB+ download is exactly the kind of long-running work
     * Android's background limits are designed to kill — setForeground
     * with a visible notification is what lets WorkManager keep this
     * alive properly instead of getting silently cut off mid-download.
     * "Safely" because setForeground itself can throw if the app is in
     * a state that disallows starting a foreground service right now
     * (e.g. backgrounded on newer Android) — that's not worth failing
     * the whole download over, so it's swallowed here.
     */
    private suspend fun setForegroundSafely(modelName: String, percent: Int) {
        try {
            setForeground(buildForegroundInfo(modelName, percent))
        } catch (e: Exception) {
            // Non-fatal — download continues without a visible
            // notification in this edge case.
        }
    }

    private fun buildForegroundInfo(modelName: String, percent: Int): ForegroundInfo {
        val nm = applicationContext.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "Model downloads", NotificationManager.IMPORTANCE_LOW
            )
            nm.createNotificationChannel(channel)
        }
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setContentTitle("Downloading $modelName")
            .setContentText("$percent%")
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(true)
            .setProgress(100, percent, percent == 0)
            .build()
        val notifId = NOTIF_ID_BASE + modelName.hashCode() % 1000
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(notifId, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(notifId, notification)
        }
    }
}
