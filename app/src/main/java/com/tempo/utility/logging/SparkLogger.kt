package com.tempo.utility.logging

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileWriter
import java.io.PrintWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean

/**
 * SparkLogger
 *
 * Dual-output logger:
 *  1. Android Logcat (visible in Android Studio or via `adb logcat -s Tempo`)
 *  2. A timestamped text file per app session saved to the app's private files directory
 *     so the user can share it without needing adb or Android Studio.
 *
 * Log file location: /data/data/com.tempo.utility/files/logs/tempo_<timestamp>.log
 * Each call to init() with a new sessionTimestamp creates a new file.
 *
 * Usage:
 *   SparkLogger.init(context, "2026-03-25_09-53-29")   // call once from Application
 *   SparkLogger.i("Tag", "message")
 *   SparkLogger.e("Tag", "message", exception)
 */
object SparkLogger {

    private const val TAG = "Tempo"
    private const val MAX_ENTRIES_IN_MEMORY = 500

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault())
    private val lineFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())

    private var logFile: File? = null
    private val writeQueue = LinkedBlockingQueue<String>(2000)
    private val isRunning = AtomicBoolean(false)

    private val memoryLog = ArrayDeque<String>(MAX_ENTRIES_IN_MEMORY)

    // ---------------------------------------------------------------
    // Init
    // ---------------------------------------------------------------

    /**
     * Initialise the logger for this app session.
     *
     * @param sessionTimestamp  Timestamp string used as the file name suffix, e.g.
     *                          "2026-03-25_09-53-29".  Both SparkLogger and CsvLogger
     *                          receive the same string so their files can be matched.
     */
    fun init(context: Context, sessionTimestamp: String) {
        val logDir = File(context.filesDir, "logs")
        logDir.mkdirs()
        logFile = File(logDir, "tempo_$sessionTimestamp.log")

        if (isRunning.compareAndSet(false, true)) {
            startWriterThread()
        }

        i("Logger", "=== Tempo log started === device=${android.os.Build.MODEL} " +
                "android=${android.os.Build.VERSION.RELEASE} " +
                "app=1.0 " +
                "session=$sessionTimestamp " +
                "file=${logFile?.absolutePath}")
    }

    // ---------------------------------------------------------------
    // Public log methods
    // ---------------------------------------------------------------

    fun v(tag: String, msg: String) = log("V", tag, msg, null)
    fun d(tag: String, msg: String) = log("D", tag, msg, null)
    fun i(tag: String, msg: String) = log("I", tag, msg, null)
    fun w(tag: String, msg: String, t: Throwable? = null) = log("W", tag, msg, t)
    fun e(tag: String, msg: String, t: Throwable? = null) = log("E", tag, msg, t)

    // ---------------------------------------------------------------
    // Accessors
    // ---------------------------------------------------------------

    /** Returns the log File for this session so it can be shared via an intent. */
    fun getLogFile(): File? = logFile

    /** Returns recent log lines for the in-app viewer. */
    fun getRecentLogs(): List<String> = synchronized(memoryLog) { memoryLog.toList() }

    /** Clears the in-memory buffer and empties the current session's log file. */
    fun clearLogs() {
        synchronized(memoryLog) { memoryLog.clear() }
        logFile?.writeText("")
        i("Logger", "Log cleared by user")
    }

    // ---------------------------------------------------------------
    // Internal
    // ---------------------------------------------------------------

    private fun log(level: String, tag: String, msg: String, t: Throwable?) {
        val timestamp = dateFormat.format(Date())
        val shortTime = lineFormat.format(Date())

        val fullLine = "$timestamp $level/$tag: $msg" +
                (t?.let { "\n${t.stackTraceToString()}" } ?: "")
        val shortLine = "$shortTime $level/$tag: $msg"

        when (level) {
            "V" -> Log.v(TAG, "[$tag] $msg", t)
            "D" -> Log.d(TAG, "[$tag] $msg", t)
            "I" -> Log.i(TAG, "[$tag] $msg", t)
            "W" -> Log.w(TAG, "[$tag] $msg", t)
            "E" -> Log.e(TAG, "[$tag] $msg", t)
        }

        synchronized(memoryLog) {
            if (memoryLog.size >= MAX_ENTRIES_IN_MEMORY) {
                memoryLog.removeFirstOrNull()
            }
            memoryLog.addLast(shortLine)
        }

        writeQueue.offer(fullLine)
    }

    private fun startWriterThread() {
        Thread({
            while (isRunning.get()) {
                try {
                    val line = writeQueue.take()
                    writeToFile(line)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    break
                }
            }
        }, "TempoLogWriter").apply {
            isDaemon = true
            start()
        }
    }

    private fun writeToFile(line: String) {
        val file = logFile ?: return
        try {
            FileWriter(file, true).use { fw ->
                PrintWriter(fw).use { pw ->
                    pw.println(line)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write to log file: ${e.message}")
        }
    }
}
