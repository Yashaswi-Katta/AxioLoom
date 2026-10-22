package com.offline.inkmath

import android.app.Application
import android.content.Context
import android.util.Log

/**
 * Custom application class for InkMath.
 * Installs synchronous global crash logging to ensure any fatal crash or startup issue
 * is recorded to disk and can be inspected via DiagnosticsScreen rather than silently terminating.
 */
class InkMathApp : Application() {

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
                val prefs = context.getSharedPreferences("inkmath_crash_log", Context.MODE_PRIVATE)
                prefs.edit().putString("last_fatal_crash", report).commit()
            }
        }

        fun getSavedCrash(context: Context): String? {
            return lastCrash ?: runCatching {
                val prefs = context.getSharedPreferences("inkmath_crash_log", Context.MODE_PRIVATE)
                prefs.getString("last_fatal_crash", null)
            }.getOrNull()
        }

        fun clearSavedCrash(context: Context) {
            lastCrash = null
            runCatching {
                val prefs = context.getSharedPreferences("inkmath_crash_log", Context.MODE_PRIVATE)
                prefs.edit().remove("last_fatal_crash").commit()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            Log.e("InkMathApp", "FATAL CRASH on thread ${thread.name}: ${throwable.message}", throwable)
            recordCrash(this, throwable)
            defaultHandler?.uncaughtException(thread, throwable)
        }
    }
}
