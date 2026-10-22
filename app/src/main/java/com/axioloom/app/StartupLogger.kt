package com.axioloom.app

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileOutputStream

/**
 * Synchronous disk breadcrumb logger for recording startup milestones.
 * Flushes to disk with fsync so if a low-level native signal (SIGSEGV/SIGABRT) kills the process,
 * the preceding stage is reliably preserved and readable on the next launch.
 */
object StartupLogger {
    private const val FILE_NAME = "startup_stage.log"
    const val STAGE_READY = "STAGE_WORKSPACE_READY"

    @Synchronized
    fun logStage(context: Context, stage: String) {
        runCatching {
            val file = File(context.filesDir, FILE_NAME)
            FileOutputStream(file, true).use { out ->
                val entry = "${System.currentTimeMillis()}: $stage\n"
                out.write(entry.toByteArray(Charsets.UTF_8))
                out.flush()
                out.fd.sync()
            }
            Log.d("StartupLogger", "Stage: $stage")
        }
    }

    @Synchronized
    fun wasPreviousLaunchAborted(context: Context): Boolean {
        return runCatching {
            val file = File(context.filesDir, FILE_NAME)
            if (!file.exists()) return false
            val lines = file.readLines().filter { it.isNotBlank() }
            if (lines.isEmpty()) return false
            val lastStage = lines.last().substringAfter(": ").trim()
            lastStage != STAGE_READY
        }.getOrDefault(false)
    }

    @Synchronized
    fun getLastStage(context: Context): String? {
        return runCatching {
            val file = File(context.filesDir, FILE_NAME)
            if (!file.exists()) return null
            val lines = file.readLines().filter { it.isNotBlank() }
            lines.lastOrNull()?.substringAfter(": ")?.trim()
        }.getOrNull()
    }

    @Synchronized
    fun getFullLog(context: Context): String {
        return runCatching {
            val file = File(context.filesDir, FILE_NAME)
            if (file.exists()) file.readText() else "No startup log recorded."
        }.getOrDefault("Error reading startup log.")
    }

    @Synchronized
    fun clear(context: Context) {
        runCatching {
            File(context.filesDir, FILE_NAME).delete()
        }
    }
}
