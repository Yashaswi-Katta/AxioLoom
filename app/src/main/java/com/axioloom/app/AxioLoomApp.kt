package com.axioloom.app

import android.app.Application
import android.content.Context
import android.os.Environment
import android.util.Log
import com.axioloom.app.domain.cas.OfflineCasEngine
import java.io.File

/**
 * Custom application class for AxioLoom.
 * Installs synchronous global crash logging to ensure any fatal crash or startup issue
 * is recorded to disk and can be inspected via DiagnosticsScreen rather than silently terminating.
 */
class AxioLoomApp : Application() {

    companion object {
        @Volatile
        var lastCrash: String? = null
            private set

        fun recordCrash(context: Context, error: Throwable) {
            val report = buildString {
                appendLine("${error::class.java.name}: ${error.message ?: "No error message"}")
                for (element in error.stackTrace.take(30)) {
                    appendLine("  at $element")
                }
                var cause = error.cause
                var depth = 0
                while (cause != null && depth < 4) {
                    appendLine("Caused by ${cause::class.java.name}: ${cause.message ?: ""}")
                    for (element in cause.stackTrace.take(20)) {
                        appendLine("  at $element")
                    }
                    cause = cause.cause
                    depth++
                }
            }
            lastCrash = report
            runCatching {
                val prefs = context.getSharedPreferences("axioloom_crash_log", Context.MODE_PRIVATE)
                prefs.edit().putString("last_fatal_crash", report).commit()
            }
            runCatching {
                val extFile = File(context.getExternalFilesDir(null), "axioloom_crash.txt")
                extFile.writeText(report)
            }
            StartupLogger.logStage(context, "FATAL_JAVA_EXCEPTION: ${error::class.java.simpleName}: ${error.message}")
        }

        fun getSavedCrash(context: Context): String? {
            return lastCrash ?: runCatching {
                val prefs = context.getSharedPreferences("axioloom_crash_log", Context.MODE_PRIVATE)
                prefs.getString("last_fatal_crash", null)
            }.getOrNull()
        }

        fun clearSavedCrash(context: Context) {
            lastCrash = null
            runCatching {
                val prefs = context.getSharedPreferences("axioloom_crash_log", Context.MODE_PRIVATE)
                prefs.edit().remove("last_fatal_crash").commit()
            }
            runCatching {
                File(context.getExternalFilesDir(null), "axioloom_crash.txt").delete()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        StartupLogger.clear(this)
        StartupLogger.logStage(this, "STAGE_APP_ON_CREATE")
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            Log.e("AxioLoomApp", "FATAL CRASH on thread ${thread.name}: ${throwable.message}", throwable)
            recordCrash(this, throwable)
            defaultHandler?.uncaughtException(thread, throwable)
        }
    }
}
