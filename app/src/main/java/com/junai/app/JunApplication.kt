package com.junai.app

import android.app.Application
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter

/**
 * Registered in AndroidManifest.xml (android:name=".JunApplication").
 *
 * WHY THIS EXISTS: Pranav develops entirely from his phone via GitHub's
 * mobile UI and GitHub Actions builds — no Android Studio, no adb, no
 * logcat. When the app crashes there's normally no way to see WHY short
 * of guessing from a screenshot of the "app has stopped" dialog, which
 * shows nothing useful. This installs a global uncaught-exception
 * handler that writes the full stack trace to a plain text file in
 * app-private storage before the crash takes down the process. Whatever
 * screen launches next (SplashActivity) checks for that file on start
 * and shows it in a dialog — screenshot-able, no laptop needed.
 *
 * Deliberately does NOT try to swallow/recover from the crash itself —
 * it logs, then hands off to Android's normal default handler so the
 * app still closes the way it always did. This is a debugging aid, not
 * a crash-recovery mechanism; silently continuing after an uncaught
 * exception tends to leave the app in a half-broken state that's worse
 * than just closing.
 */
class JunApplication : Application() {

    companion object {
        const val CRASH_LOG_FILENAME = "last_crash.txt"
    }

    override fun onCreate() {
        super.onCreate()
        com.junai.app.ml.Breadcrumb.startNewSession(this)
        AppForegroundTracker.register()

        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                val sw = StringWriter()
                throwable.printStackTrace(PrintWriter(sw))
                val report = buildString {
                    append("Crashed at: ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(java.util.Date())}\n")
                    append("Thread: ${thread.name}\n\n")
                    append(sw.toString())
                }
                File(filesDir, CRASH_LOG_FILENAME).writeText(report)
            } catch (e: Exception) {
                // If even writing the crash log fails, there's nothing
                // more to do here — fall through to the default handler
                // below regardless.
            }
            defaultHandler?.uncaughtException(thread, throwable)
        }
    }
}
