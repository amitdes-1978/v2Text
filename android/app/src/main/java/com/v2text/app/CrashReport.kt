package com.v2text.app

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.DateFormat
import java.util.Date

/**
 * Makes crashes reportable from the phone without adb:
 *  - Java/Kotlin exceptions (caught or uncaught) are appended to filesDir/crash.txt
 *  - on the next launch, Android's own record of why the last process died
 *    (native crash, ANR, out-of-memory, Java crash) is read via ApplicationExitInfo
 * Nothing is uploaded; the user copies the report and shares it if they want to.
 */
object CrashReport {
    private fun file(c: Context) = File(c.filesDir, "crash.txt")

    fun record(c: Context, where: String, t: Throwable) {
        try {
            val sw = StringWriter(); t.printStackTrace(PrintWriter(sw))
            val f = file(c)
            val old = if (f.exists()) f.readText().takeLast(30_000) else ""
            f.writeText(old + "\n=== ${DateFormat.getDateTimeInstance().format(Date())} · $where\n$sw")
        } catch (_: Throwable) { }
    }

    fun installUncaughtHandler(c: Context) {
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { th, e ->
            record(c, "uncaught on thread ${th.name}", e)
            prev?.uncaughtException(th, e)
        }
    }

    /** The newest abnormal exit not yet shown to the user, as readable text (or null). */
    fun unseenExit(c: Context): String? {
        val am = c.getSystemService(ActivityManager::class.java)
        val prefs = c.getSharedPreferences("crash", Context.MODE_PRIVATE)
        val seen = prefs.getLong("seen_ts", 0L)
        val bad = setOf(ApplicationExitInfo.REASON_CRASH, ApplicationExitInfo.REASON_CRASH_NATIVE,
            ApplicationExitInfo.REASON_ANR, ApplicationExitInfo.REASON_LOW_MEMORY, ApplicationExitInfo.REASON_INITIALIZATION_FAILURE)
        val e = am.getHistoricalProcessExitReasons(c.packageName, 0, 10)
            .firstOrNull { it.reason in bad && it.timestamp > seen } ?: return null
        return describe(c, e)
    }

    fun markSeen(c: Context) = c.getSharedPreferences("crash", Context.MODE_PRIVATE).edit()
        .putLong("seen_ts", System.currentTimeMillis()).apply()

    private fun describe(c: Context, e: ApplicationExitInfo): String = buildString {
        val reason = when (e.reason) {
            ApplicationExitInfo.REASON_CRASH -> "Java/Kotlin crash"
            ApplicationExitInfo.REASON_CRASH_NATIVE -> "native crash (speech engine / C++ code)"
            ApplicationExitInfo.REASON_ANR -> "app not responding (ANR)"
            ApplicationExitInfo.REASON_LOW_MEMORY -> "killed for low memory"
            ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "failed to start"
            else -> "reason ${e.reason}"
        }
        val v = try { c.packageManager.getPackageInfo(c.packageName, 0).versionName } catch (_: Exception) { "?" }
        append("v2Text $v · ${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})\n")
        append("When: ${DateFormat.getDateTimeInstance().format(Date(e.timestamp))}\n")
        append("What: $reason · status ${e.status} · importance ${e.importance} · pss ${e.pss / 1024} MB\n")
        e.description?.let { append("Description: $it\n") }
        val trace = try { e.traceInputStream?.use { it.readBytes() } } catch (_: Exception) { null }
        if (trace != null) {
            // ANR traces are text; native tombstones are protobuf, so keep their readable strings
            // (signal, abort message, library and function names in the backtrace).
            val text = if (e.reason == ApplicationExitInfo.REASON_CRASH_NATIVE) readableStrings(trace) else String(trace)
            append("\n--- trace ---\n").append(text.take(6000)).append('\n')
        }
        val log = file(c)
        if (log.exists()) append("\n--- app log ---\n").append(log.readText().takeLast(4000))
    }

    private fun readableStrings(b: ByteArray): String {
        val out = LinkedHashSet<String>(); val cur = StringBuilder()
        fun flush() { if (cur.length >= 5) out += cur.toString(); cur.clear() }
        for (x in b) { val ch = x.toInt() and 0xff; if (ch in 32..126) cur.append(ch.toChar()) else flush() }
        flush()
        return out.joinToString("\n")
    }
}
