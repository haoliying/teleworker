package com.yuanhang.ssh

import android.app.Application
import android.content.Context
import android.os.Build
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class App : Application() {

    override fun onCreate() {
        super.onCreate()
        installCrashLogger()
    }

    companion object {

        private const val LOG_NAME = "crash.log"

        /** Cap the amount handed to the UI – a log that grew for weeks is not useful. */
        private const val MAX_CHARS = 32_000

        fun readCrashLog(context: Context): String = runCatching {
            val file = File(context.filesDir, LOG_NAME)
            if (!file.exists()) return@runCatching ""
            val text = file.readText()
            if (text.length <= MAX_CHARS) text else "…\n" + text.takeLast(MAX_CHARS)
        }.getOrDefault("")

        fun clearCrashLog(context: Context) {
            runCatching { File(context.filesDir, LOG_NAME).delete() }
        }
    }

    /**
     * Records uncaught exceptions to `filesDir/crash.log`.
     *
     * The app runs on foldables and tablets where a crash otherwise just looks like the
     * app silently closing; a persisted stack trace makes those reports actionable.
     * The default handler is always invoked afterwards, so behaviour is unchanged.
     */
    private fun installCrashLogger() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching {
                val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
                val device = "${Build.MANUFACTURER} ${Build.MODEL} (API ${Build.VERSION.SDK_INT})"
                val body = buildString {
                    appendLine("=== crash $stamp ===")
                    appendLine("device: $device")
                    appendLine("thread: ${thread.name}")
                    appendLine(throwable.toString())
                    throwable.stackTrace.take(40).forEach { appendLine("    at $it") }
                    appendLine()
                }
                File(filesDir, "crash.log").appendText(body)
            }
            previous?.uncaughtException(thread, throwable)
        }
    }
}
