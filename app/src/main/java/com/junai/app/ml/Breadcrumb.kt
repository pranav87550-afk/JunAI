package com.junai.app.ml

import android.content.Context
import java.io.File

/**
 * WHY THIS EXISTS: MediaPipe (EmbeddingEngine/FunctionCallEngine) and
 * LiteRT-LM (ChatEngine) are native C++ libraries under the hood. If
 * one of them crashes at the native level (bad model file, invalid
 * pointer, native OOM, etc.), that crash bypasses the JVM entirely —
 * Kotlin's try/catch and even Thread.setDefaultUncaughtExceptionHandler
 * (see JunApplication) never see it, because it's not a Java exception
 * at all, it's the OS killing the process directly. Normally you'd
 * diagnose this with adb logcat's tombstone output, which isn't
 * available developing purely from a phone.
 *
 * Breadcrumb sidesteps that: it writes a plain line to disk, flushed
 * immediately, right before and right after each risky native call. If
 * the process dies mid-call, the "before" line for that call is the
 * last thing on disk — whichever call never got its matching "after"
 * line is the one that killed the process. No exception needs to be
 * caught for this to work.
 */
object Breadcrumb {

    private const val FILENAME = "breadcrumbs.txt"
    private const val PREV_FILENAME = "breadcrumbs_prev.txt"

    @Volatile
    private var cachedFile: File? = null

    private fun file(context: Context): File {
        val existing = cachedFile
        if (existing != null) return existing
        val f = File(context.applicationContext.filesDir, FILENAME)
        cachedFile = f
        return f
    }

    /**
     * Called once from JunApplication.onCreate() — which runs before ANY
     * Activity, including SplashActivity — so each session starts with
     * a clean trail.
     *
     * BUGFIX: originally this just overwrote breadcrumbs.txt directly,
     * which meant by the time SplashActivity got a chance to read it a
     * few milliseconds later (in the SAME launch), the previous
     * session's trail — the one that actually matters, from right
     * before a crash — was already gone. Now the previous file is
     * preserved under a separate name first; readPreviousSession()
     * reads that one.
     */
    fun startNewSession(context: Context) {
        try {
            val current = file(context)
            if (current.exists()) {
                current.copyTo(File(context.applicationContext.filesDir, PREV_FILENAME), overwrite = true)
            }
            current.writeText("=== session start: ${now()} ===\n")
        } catch (e: Exception) { /* nothing more to do if even this fails */ }
    }

    /**
     * Appends one line, synchronously, flushed to disk immediately —
     * deliberately NOT batched/buffered/async, since the whole point is
     * that this line must already be safely on disk before the next
     * (potentially crashing) call happens a few lines later in the
     * caller's code.
     */
    fun log(context: Context, message: String) {
        try {
            file(context).appendText("${now()}  $message\n")
        } catch (e: Exception) { /* best-effort — never let logging itself crash the app */ }
    }

    fun readAll(context: Context): String? {
        val f = file(context)
        if (!f.exists()) return null
        return try { f.readText() } catch (e: Exception) { null }
    }

    /** The trail from the session that just ended, before startNewSession() was called for this launch. */
    fun readPreviousSession(context: Context): String? {
        val f = File(context.applicationContext.filesDir, PREV_FILENAME)
        if (!f.exists()) return null
        return try { f.readText() } catch (e: Exception) { null }
    }

    private fun now(): String =
        java.text.SimpleDateFormat("HH:mm:ss.SSS").format(java.util.Date())
}
