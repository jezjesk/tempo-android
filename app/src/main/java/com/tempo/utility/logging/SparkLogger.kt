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
   *  2. A daily text file saved to the app's private files directory.
   *     One file per calendar day; each new app session appends to the same file
   *     with a "=== Tempo log started ===" banner so restarts are clearly visible.
   *
   * Log file location: /data/data/com.tempo.utility/files/logs/tempo_2026-03-25.log
   *
   * Usage:
   *   SparkLogger.init(context)   // call once from Application
   *   SparkLogger.i("Tag", "message")
   *   SparkLogger.e("Tag", "message", exception)
   */
  object SparkLogger {

      private const val TAG = "Tempo"
      private const val MAX_ENTRIES_IN_MEMORY = 500

      private val dateFormat    = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault())
      private val lineFormat    = SimpleDateFormat("HH:mm:ss.SSS",            Locale.getDefault())
      private val fileDateFmt   = SimpleDateFormat("yyyy-MM-dd",              Locale.US)
      private val sessionTsFmt  = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss",    Locale.US)

      private var logFile: File? = null
      private val writeQueue = LinkedBlockingQueue<String>(2000)
      private val isRunning = AtomicBoolean(false)

      private val memoryLog = ArrayDeque<String>(MAX_ENTRIES_IN_MEMORY)

      // ---------------------------------------------------------------
      // Init
      // ---------------------------------------------------------------

      /**
       * Initialise the logger.  Call once from Application.onCreate().
       * The log file is named by today's date (tempo_yyyy-MM-dd.log) so all
       * sessions within a calendar day share one file.  A startup banner is
       * written each time so individual restarts are clearly visible.
       */
      fun init(context: Context) {
          val now        = Date()
          val dateStamp  = fileDateFmt.format(now)     // e.g. "2026-03-25"  → filename
          val sessionTs  = sessionTsFmt.format(now)    // e.g. "2026-03-25_21-08-15"  → banner only

          val logDir = File(context.filesDir, "logs")
          logDir.mkdirs()
          logFile = File(logDir, "tempo_$dateStamp.log")

          if (isRunning.compareAndSet(false, true)) {
              startWriterThread()
          }

          i("Logger", "=== Tempo log started === device=${android.os.Build.MODEL} " +
                  "android=${android.os.Build.VERSION.RELEASE} " +
                  "app=1.0 " +
                  "session=$sessionTs " +
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

      /** Returns the log File for today so it can be shared via an intent. */
      fun getLogFile(): File? = logFile

      /** Returns recent log lines for the in-app viewer. */
      fun getRecentLogs(): List<String> = synchronized(memoryLog) { memoryLog.toList() }

      /** Clears the in-memory buffer and empties today's log file. */
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
  