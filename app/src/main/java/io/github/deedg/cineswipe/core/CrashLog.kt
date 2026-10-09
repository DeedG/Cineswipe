package io.github.deedg.cineswipe.core

import android.content.Context
import android.os.Build
import io.github.deedg.cineswipe.BuildConfig
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Enregistre la trace du dernier plantage pour pouvoir la copier depuis les réglages,
 * sans avoir besoin d'Android Studio ni d'un câble.
 */
class CrashLog(context: Context) {
    private val file = File(context.applicationContext.filesDir, "last_crash.txt")

    fun install() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching { file.writeText(report(thread, error)) }
            previous?.uncaughtException(thread, error)
        }
    }

    fun read(): String? = file.takeIf { it.exists() }?.readText()

    fun clear() {
        file.delete()
    }

    private fun report(thread: Thread, error: Throwable): String = buildString {
        appendLine("CineSwipe ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
        appendLine("Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}), ${Build.MANUFACTURER} ${Build.MODEL}")
        appendLine(SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.FRANCE).format(Date()))
        appendLine("Thread : ${thread.name}")
        appendLine()
        append(error.stackTraceToString())
    }
}
