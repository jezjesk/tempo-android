package com.tempo.utility

  import android.app.Application
  import com.tempo.utility.logging.CsvLogger
  import com.tempo.utility.logging.SparkLogger
  import com.tempo.utility.settings.AppSettings

  /**
   * Application class — initialises SparkLogger and CsvLogger before anything else runs.
   * Registered in AndroidManifest via android:name=".TempoApplication".
   *
   * Log files are daily: tempo_yyyy-MM-dd.log.  Each restart appends to the same file
   * with a "=== Tempo log started ===" banner so individual sessions are clearly visible.
   */
  class TempoApplication : Application() {
      override fun onCreate() {
          super.onCreate()
          SparkLogger.init(this)
          CsvLogger.initialize(this)
          AppSettings.init(this)
          SparkLogger.i("Application", "TempoApplication.onCreate — app process started")
      }
  }
  