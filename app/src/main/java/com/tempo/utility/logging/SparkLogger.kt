package com.tempo.utility.logging

import android.content.Context
import android.util.Log
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean

/**
 * SparkLogger
 *
 * Dual-output logger:
 *  1. Android Logcat (visible via `adb logcat -s Tempo`)
 *  2. A daily text file in the app's private files directory.
 *     One file per calendar day; each new session appends a startup banner.
 *
 * Design notes:
 *  - A single background writer thread owns the BufferedWriter, keeping the
 *    file handle open across all writes (no per-line open/close churn).
 *  - Day rollover is handled inside the writer thread: when the date changes,
 *    the old writer is closed, a new file is opened, and log rotation deletes
 *    files older than LOG_RETENTION_DAYS.
 *  - clearLogs() posts a sentinel through the queue so the writer thread owns
 *    the truncation — no race with in-flight writes.
 *  - DateTimeFormatter (thread-safe) replaces SimpleDateFormat.
 *  - shutdown() is idempotent and drains the queue before closing.
 *
 * Log file location: <filesDir>/logs/tempo_<yyyy-MM-dd>.log
 */
object SparkLogger {

    private const val TAG               = "Tempo"
    private const val MAX_MEMORY        = 500
    private const val LOG_RETENTION_DAYS = 7L
    private const val CLEAR_SENTINEL    = "\u0000CLEAR\u0000"

    // Thread-safe; both are effectively immutable once constructed.
    private val tsFull  = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")
    private val tsShort = DateTimeFormatter.ofPattern("HH:mm:ss.SSS")
    private val tsFile  = DateTimeFormatter.ofPattern("yyyy-MM-dd")
    private val tsSess  = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss")

    private var logDir:   File?           = null
    private var logFile:  File?           = null
    private var writer:   BufferedWriter? = null   // owned exclusively by writerThread
    private var writerDay = ""                     // date string of current open file

    private val writeQueue = LinkedBlockingQueue<String>(4000)
    private val isRunning  = AtomicBoolean(false)
    private var writerThread: Thread? = null

    private val memoryLog = ArrayDeque<String>(MAX_MEMORY)

    // ──────────────────────────────────────────────────────────────────────────
    // Lifecycle
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * Initialise (or re-initialise) the logger.  Safe to call multiple times —
     * shuts down any existing writer thread before starting a fresh one.
     * Call from Application.onCreate() or from the accessibility service.
     */
    fun init(context: Context) {
        // Always start clean so re-creation of the service gets a fresh thread
        // with the correct logFile reference.
        shutdown(drain = false)

        val now = LocalDateTime.now()
        val dir = File(context.filesDir, "logs").also { it.mkdirs() }
        logDir  = dir
        logFile = File(dir, "tempo_${now.format(tsFile)}.log")

        deleteOldLogs(dir)

        isRunning.set(true)
        writerThread = Thread({
            runWriterLoop()
        }, "TempoLogWriter").apply {
            isDaemon = true
            start()
        }

        i("Logger", "=== Tempo log started === device=${android.os.Build.MODEL} " +
                "android=${android.os.Build.VERSION.RELEASE} " +
                "app=1.0 " +
                "session=${now.format(tsSess)} " +
                "file=${logFile?.absolutePath}")
    }

    /**
     * Shuts down the writer thread gracefully.
     * @param drain  If true, remaining queue lines are flushed to disk before
     *               the writer closes.  Pass false when re-initialising (the
     *               thread will be interrupted immediately).
     */
    fun shutdown(drain: Boolean = true) {
        if (!isRunning.compareAndSet(true, false)) return
        writerThread?.interrupt()
        if (drain) {
            // Flush remaining lines directly on the calling thread.
            val remaining = mutableListOf<String>()
            writeQueue.drainTo(remaining)
            for (line in remaining) {
                if (line == CLEAR_SENTINEL) { writer?.let { it.flush(); }; logFile?.writeText(""); writer = null; writerDay = "" }
                else writeLineToWriter(line)
            }
        } else {
            writeQueue.clear()
        }
        writer?.flush()
        writer?.close()
        writer    = null
        writerDay = ""
        writerThread = null
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Public log methods
    // ──────────────────────────────────────────────────────────────────────────

    fun v(tag: String, msg: String)                          = log("V", tag, msg, null)
    fun d(tag: String, msg: String)                          = log("D", tag, msg, null)
    fun i(tag: String, msg: String)                          = log("I", tag, msg, null)
    fun w(tag: String, msg: String, t: Throwable? = null)    = log("W", tag, msg, t)
    fun e(tag: String, msg: String, t: Throwable? = null)    = log("E", tag, msg, t)

    // ──────────────────────────────────────────────────────────────────────────
    // Accessors
    // ──────────────────────────────────────────────────────────────────────────

    fun getLogFile(): File? = logFile

    fun getRecentLogs(): List<String> = synchronized(memoryLog) { memoryLog.toList() }

    /**
     * Clears the in-memory buffer and empties today's log file.
     * The truncation is posted through the queue so it does not race with
     * in-flight writes.
     */
    fun clearLogs() {
        synchronized(memoryLog) { memoryLog.clear() }
        val offered = writeQueue.offer(CLEAR_SENTINEL)
        if (!offered) {
            // Queue full — truncate directly as a fallback (best-effort).
            Log.w(TAG, "clearLogs: queue full, truncating directly")
            logFile?.writeText("")
        }
        i("Logger", "Log cleared by user")
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Internal — writer thread
    // ──────────────────────────────────────────────────────────────────────────

    private fun log(level: String, tag: String, msg: String, t: Throwable?) {
        // Capture one instant — both timestamps are consistent and only one
        // LocalDateTime object is allocated per call.
        val now       = LocalDateTime.now()
        val fullLine  = "${now.format(tsFull)} $level/$tag: $msg" +
                        (t?.let { "\n${it.stackTraceToString()}" } ?: "")
        val shortLine = "${now.format(tsShort)} $level/$tag: $msg"

        when (level) {
            "V" -> Log.v(TAG, "[$tag] $msg", t)
            "D" -> Log.d(TAG, "[$tag] $msg", t)
            "I" -> Log.i(TAG, "[$tag] $msg", t)
            "W" -> Log.w(TAG, "[$tag] $msg", t)
            "E" -> Log.e(TAG, "[$tag] $msg", t)
        }

        synchronized(memoryLog) {
            if (memoryLog.size >= MAX_MEMORY) memoryLog.removeFirstOrNull()
            memoryLog.addLast(shortLine)
        }

        if (!writeQueue.offer(fullLine)) {
            Log.w(TAG, "SparkLogger queue full — line dropped: $shortLine")
        }
    }

    private fun runWriterLoop() {
        while (isRunning.get()) {
            try {
                val line = writeQueue.take()
                if (line == CLEAR_SENTINEL) {
                    // Flush and truncate: close writer, wipe file, reopen.
                    writer?.flush()
                    logFile?.writeText("")
                    writer?.close()
                    writer    = null
                    writerDay = ""
                    // Reopen immediately so subsequent lines land in the cleared file.
                    ensureWriterOpen()
                } else {
                    ensureWriterOpen()
                    writeLineToWriter(line)
                }
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                break
            }
        }
        writer?.flush()
        writer?.close()
        writer    = null
        writerDay = ""
    }

    /**
     * Called only from the writer thread.
     * Opens (or reopens) the BufferedWriter for today's date.
     * On day rollover, closes the old writer, rotates old logs, and opens a new file.
     */
    private fun ensureWriterOpen() {
        val today = LocalDate.now().format(tsFile)
        if (writer != null && writerDay == today) return   // already open for today

        writer?.flush()
        writer?.close()
        writer = null

        val dir  = logDir ?: return
        val file = File(dir, "tempo_$today.log")
        logFile  = file
        writerDay = today

        if (writerDay.isNotEmpty()) deleteOldLogs(dir)   // rotate on day change

        writer = BufferedWriter(FileWriter(file, true))
    }

    /** Write one pre-formatted line to the open writer.  Call only from writer thread. */
    private fun writeLineToWriter(line: String) {
        try {
            val w = writer ?: return
            w.write(line)
            w.newLine()
            w.flush()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write to log file: ${e.message}")
        }
    }

    private fun deleteOldLogs(dir: File) {
        val cutoff = LocalDate.now().minusDays(LOG_RETENTION_DAYS)
        dir.listFiles { f -> f.name.startsWith("tempo_") && f.name.endsWith(".log") }
            ?.forEach { f ->
                try {
                    val dateStr = f.name.removePrefix("tempo_").removeSuffix(".log")
                    val fileDate = LocalDate.parse(dateStr, tsFile)
                    if (fileDate.isBefore(cutoff)) {
                        f.delete()
                        Log.i(TAG, "Rotated old log: ${f.name}")
                    }
                } catch (_: Exception) { /* non-matching filename — skip */ }
            }
    }
}
