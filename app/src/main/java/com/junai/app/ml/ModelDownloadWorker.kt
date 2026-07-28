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

        // KNOWLEDGE_PACK is a bundle of many small JSON files, not one
        // big file — handled entirely separately below since the
        // resume/Range-request logic further down only makes sense for
        // a single large file.
        if (model.knowledgeFiles != null) {
            return@withContext downloadKnowledgePack(model)
        }

        val dir = File(applicationContext.filesDir, ModelCatalog.localDirName())
        if (!dir.exists()) dir.mkdirs()
        val finalFile = File(dir, model.fileName)
        val partFile = File(dir, "${model.fileName}.part")

        // Already fully downloaded from a prior run — nothing to do.
        // (Plain existence check — see ModelDownloadManager.isDownloaded's
        // doc comment for why this is the correct check, not a size
        // comparison against ModelCatalog's rough size estimate.)
        if (ModelDownloadManager.isDownloaded(applicationContext, modelId)) {
            return@withContext Result.success()
        }

        setForegroundSafely(model.displayName, 0)

        try {
            val resumeFrom = if (partFile.exists()) partFile.length() else 0L
            val requestBuilder = Request.Builder().url(model.downloadUrl)
            if (resumeFrom > 0) {
                requestBuilder.addHeader("Range", "bytes=$resumeFrom-")
            }

            // Set inside the response lambda below, read after it — this
            // is what lets us verify the .part file actually reached its
            // expected size before trusting it, instead of just trusting
            // a clean loop exit (see the check right after the lambda).
            var expectedTotalBytes = -1L
            var contentLengthWasKnown = false

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
                expectedTotalBytes = totalBytes
                contentLengthWasKnown = contentLength > 0

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

            // The loop above exits the same way on two very different
            // outcomes: a genuinely complete download, AND a connection
            // that got silently cut mid-stream without OkHttp throwing
            // (seen in practice on slow/unstable mobile networks — the
            // socket just returns EOF early). Without this check, a
            // truncated .part file was getting promoted to the final
            // filename and reported as a successful download — this is
            // confirmed to be exactly what produced a corrupt, roughly
            // half-size EmbeddingGemma file that then failed to load
            // natively (MediaPipeException, buffer size mismatch) with
            // RAG silently never firing as a result. Only trust the
            // download when we actually knew the expected size AND the
            // file on disk matches it.
            val actualBytes = partFile.length()
            // Unconditional diagnostic line — regardless of outcome —
            // because right now it's genuinely unclear whether a
            // repeated truncated EmbeddingGemma download is happening
            // because contentLengthWasKnown comes back false for this
            // particular HuggingFace URL (Content-Length header missing/
            // stripped, e.g. chunked transfer through a CDN edge) or
            // some other reason. This makes that unambiguous from the
            // very next Breadcrumb trail instead of guessing further.
            com.junai.app.ml.Breadcrumb.log(
                applicationContext,
                "ModelDownloadWorker: ${model.fileName} finished loop — actualBytes=$actualBytes, " +
                    "expectedTotalBytes=$expectedTotalBytes, contentLengthWasKnown=$contentLengthWasKnown"
            )
            if (contentLengthWasKnown && actualBytes != expectedTotalBytes) {
                android.util.Log.w(
                    "ModelDownloadWorker",
                    "Truncated download for ${model.fileName}: got $actualBytes bytes, expected $expectedTotalBytes — retrying instead of accepting a corrupt file"
                )
                return@withContext Result.retry()
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
            if (contentLengthWasKnown) {
                ModelDownloadManager.recordVerifiedSize(applicationContext, modelId, expectedTotalBytes)
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
     * Downloads every domain JSON file in the knowledge pack, one at a
     * time. No byte-range resume logic here (unlike the single-file
     * path above) — each file is only a few KB, so a dropped connection
     * just means re-downloading that one small file, not a multi-minute
     * loss like it would be for a 300MB+ model. What IS resumed at this
     * level is PER-FILE: if the app was killed or the network dropped
     * partway through the pack (say 7 of 13 files landed), a retry
     * skips the 7 already-downloaded files and only fetches the
     * remaining 6 — the whole pack doesn't restart from zero.
     *
     * Notification title changes per file ("Downloading GK knowledge...",
     * "Downloading Tech knowledge...", etc. — Pranav's request, so the
     * notification panel shows real progress instead of a static bar)
     * while the notification ID stays fixed on "Knowledge Pack" so
     * Android updates the SAME notification in place rather than
     * spawning 13 separate ones.
     */
    private suspend fun downloadKnowledgePack(model: ModelCatalog.ModelInfo): Result {
        val files = model.knowledgeFiles ?: return Result.failure()
        val dir = File(applicationContext.filesDir, ModelCatalog.localDirName())
        if (!dir.exists()) dir.mkdirs()

        val total = files.size
        for ((index, kf) in files.withIndex()) {
            val finalFile = File(dir, kf.remoteFileName)
            finalFile.parentFile?.let { if (!it.exists()) it.mkdirs() }

            if (finalFile.exists() && finalFile.length() > 0) {
                // Already have this one from a previous attempt — skip
                // straight to the next file (per-file resume).
                continue
            }

            val pctBeforeThisFile = (index * 100) / total
            setForegroundSafelyKnowledge(
                title = "Downloading ${kf.displayLabel} knowledge...",
                text = "${index + 1} of $total",
                percent = pctBeforeThisFile,
            )
            setProgress(workDataOf(KEY_PROGRESS_PERCENT to pctBeforeThisFile))

            val partFile = File(dir, "${kf.remoteFileName}.part")
            try {
                val request = Request.Builder().url(kf.downloadUrl).build()
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return Result.retry()
                    val body = response.body ?: return Result.retry()
                    partFile.outputStream().use { out -> body.byteStream().copyTo(out) }
                }
                if (!partFile.renameTo(finalFile)) {
                    partFile.copyTo(finalFile, overwrite = true)
                    partFile.delete()
                }
            } catch (e: Exception) {
                // Leave any already-downloaded files in place — next
                // attempt (WorkManager retry or user tap) resumes from
                // here via the exists()-check above, doesn't restart
                // the whole 13-file pack.
                return Result.retry()
            }
        }

        setForegroundSafelyKnowledge(title = "Knowledge Pack downloaded", text = "All $total domains ready", percent = 100)
        setProgress(workDataOf(KEY_PROGRESS_PERCENT to 100))
        return Result.success()
    }

    private suspend fun setForegroundSafelyKnowledge(title: String, text: String, percent: Int) {
        try {
            setForeground(buildForegroundInfoKnowledge(title, text, percent))
        } catch (e: Exception) {
            // Non-fatal, same reasoning as setForegroundSafely() below.
        }
    }

    private fun buildForegroundInfoKnowledge(title: String, text: String, percent: Int): ForegroundInfo {
        val nm = applicationContext.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "Model downloads", NotificationManager.IMPORTANCE_LOW
            )
            nm.createNotificationChannel(channel)
        }
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(percent < 100)
            .setProgress(100, percent, false)
            .build()
        // Fixed key ("Knowledge Pack") — NOT the per-file title, which
        // changes every domain — so this stays ONE notification that
        // updates in place instead of spawning a new one per file.
        val notifId = NOTIF_ID_BASE + "Knowledge Pack".hashCode() % 1000
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(notifId, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(notifId, notification)
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
