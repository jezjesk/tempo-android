package com.tempo.utility

import android.app.Application
import com.tempo.utility.logging.CsvLogger
import com.tempo.utility.logging.SparkLogger
import com.tempo.utility.settings.AppSettings
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Application class — initialises SparkLogger and CsvLogger before anything else runs.
 * Registered in AndroidManifest via android:name=".TempoApplication".
 *
 * A single session timestamp (yyyy-MM-dd_HH-mm-ss) is generated at process start and
 * passed to both loggers so each run gets its own matched pair of files:
 *   tempo_2026-03-25_09-53-29.log
 *   spark_offers_2026-03-25_09-53-29.csv
 */
class TempoApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        val sessionTs = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date())
        SparkLogger.init(this, sessionTs)
        CsvLogger.initialize(this, sessionTs)
        AppSettings.init(this)
        SparkLogger.i("Application", "TempoApplication.onCreate — app process started")
    }
}
