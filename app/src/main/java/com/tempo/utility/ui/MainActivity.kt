package com.tempo.utility.ui

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import com.tempo.utility.R
import com.tempo.utility.logging.CsvLogger
import com.tempo.utility.logging.SparkLogger
import com.tempo.utility.service.SparkAccessibilityService
import com.tempo.utility.ui.SettingsActivity
import com.google.android.material.button.MaterialButton

/**
 * MainActivity — control panel for Tempo.
 *
 * Layout order:
 *   1. Settings button
 *   2. Monitoring card (service status + pause/resume)
 *   3. Email Log button
 *   4. Email Offers (CSV) button
 *   5. How It Works card
 */
class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "MainActivity"
        private const val PREFS_NAME = "tempo_prefs"
        private const val PREF_MONITORING = "monitoring_enabled"
        private const val EMAIL_TO = "linuxelitist@gmail.com"
    }

    private lateinit var tvServiceStatus: TextView
    private lateinit var btnEnableService: Button
    private lateinit var btnOpenSettings: MaterialButton
    private lateinit var btnEmailLog: MaterialButton
    private lateinit var btnEmailCsv: MaterialButton
    private lateinit var btnMonitoringToggle: MaterialButton
    private lateinit var tvMonitoringStatus: TextView
    private lateinit var prefs: SharedPreferences

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        SparkLogger.i(TAG, "MainActivity.onCreate")

        prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        tvServiceStatus     = findViewById(R.id.tvServiceStatus)
        btnEnableService    = findViewById(R.id.btnEnableService)
        btnOpenSettings     = findViewById(R.id.btnOpenSettings)
        btnEmailLog         = findViewById(R.id.btnEmailLog)
        btnEmailCsv         = findViewById(R.id.btnEmailCsv)
        btnMonitoringToggle = findViewById(R.id.btnMonitoringToggle)
        tvMonitoringStatus  = findViewById(R.id.tvMonitoringStatus)

        btnEnableService.setOnClickListener {
            SparkLogger.i(TAG, "User tapped 'Enable Accessibility Service'")
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        btnOpenSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        btnEmailLog.setOnClickListener {
            SparkLogger.i(TAG, "User tapped 'Email Debug Log'")
            emailFile(
                file    = SparkLogger.getLogFile(),
                subject = "Tempo Debug Log",
                body    = "Debug log from Tempo — ${Build.MANUFACTURER} ${Build.MODEL} / Android ${Build.VERSION.RELEASE}",
                mime    = "text/plain"
            )
        }

        btnEmailCsv.setOnClickListener {
            SparkLogger.i(TAG, "User tapped 'Email Offer Records (CSV)'")
            emailFile(
                file    = CsvLogger.getFile(),
                subject = "Tempo Offer Records",
                body    = "Spark Driver offer records from Tempo — ${Build.MANUFACTURER} ${Build.MODEL} / Android ${Build.VERSION.RELEASE}",
                mime    = "text/csv"
            )
        }

        btnMonitoringToggle.setOnClickListener {
            val nowActive = !SparkAccessibilityService.isMonitoring
            SparkLogger.i(TAG, "User toggled monitoring: enabled=$nowActive")
            prefs.edit().putBoolean(PREF_MONITORING, nowActive).apply()
            sendMonitoringBroadcast(nowActive)
            applyMonitoringUi(nowActive)
        }

        val logFile = SparkLogger.getLogFile()
        SparkLogger.i(TAG, "Log file: ${logFile?.absolutePath}")
        SparkLogger.i(TAG, "CSV file: ${CsvLogger.getFile()?.absolutePath}")
    }

    override fun onResume() {
        super.onResume()
        SparkLogger.i(TAG, "MainActivity.onResume — checking permissions")

        val monitoring = prefs.getBoolean(PREF_MONITORING, true)
        if (SparkAccessibilityService.isMonitoring != monitoring) {
            sendMonitoringBroadcast(monitoring)
        }
        applyMonitoringUi(SparkAccessibilityService.isMonitoring)
        updatePermissionStatus()
    }

    override fun onPause() {
        super.onPause()
        SparkLogger.i(TAG, "MainActivity.onPause")
    }

    // ── Permission status ──────────────────────────────────────────────────────

    private fun updatePermissionStatus() {
        val serviceEnabled = isAccessibilityServiceEnabled()
        SparkLogger.i(TAG, "Permission check: accessibilityService=$serviceEnabled")

        if (serviceEnabled) {
            tvServiceStatus.text = getString(R.string.service_active)
            tvServiceStatus.setTextColor(getColor(R.color.spark_green))
            btnEnableService.text = "Service Enabled ✓"
        } else {
            tvServiceStatus.text = getString(R.string.service_inactive)
            tvServiceStatus.setTextColor(getColor(R.color.spark_red))
            btnEnableService.text = getString(R.string.enable_service)
        }
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        return try {
            val am = getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
            val enabled = am.getEnabledAccessibilityServiceList(
                AccessibilityServiceInfo.FEEDBACK_ALL_MASK
            )
            val found = enabled.any { info ->
                info.resolveInfo.serviceInfo.packageName == packageName &&
                        info.resolveInfo.serviceInfo.name ==
                        SparkAccessibilityService::class.java.name
            }
            SparkLogger.d(TAG, "isAccessibilityServiceEnabled: $found (checked ${enabled.size} services)")
            found
        } catch (e: Exception) {
            SparkLogger.e(TAG, "isAccessibilityServiceEnabled threw exception", e)
            false
        }
    }

    // ── Email file via Android share sheet ─────────────────────────────────────

    private fun emailFile(file: java.io.File?, subject: String, body: String, mime: String) {
        if (file == null || !file.exists()) {
            Toast.makeText(this, "File not found — use the app first", Toast.LENGTH_LONG).show()
            return
        }
        SparkLogger.i(TAG, "emailFile: ${file.absolutePath} (${file.length()} bytes)")
        try {
            val uri = FileProvider.getUriForFile(this, "${packageName}.fileprovider", file)
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = mime
                putExtra(Intent.EXTRA_EMAIL, arrayOf(EMAIL_TO))
                putExtra(Intent.EXTRA_SUBJECT, subject)
                putExtra(Intent.EXTRA_TEXT, body)
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(intent, "Send email"))
        } catch (e: Exception) {
            SparkLogger.e(TAG, "emailFile: exception", e)
            Toast.makeText(this, "Could not open email app: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    // ── Monitoring toggle UI ───────────────────────────────────────────────────

    private fun applyMonitoringUi(active: Boolean) {
        if (active) {
            tvMonitoringStatus.text = "● ACTIVE — watching for offers"
            tvMonitoringStatus.setTextColor(getColor(R.color.spark_green))
            btnMonitoringToggle.text = "⏸  Pause Monitoring"
            btnMonitoringToggle.setBackgroundColor(getColor(R.color.spark_red))
        } else {
            tvMonitoringStatus.text = "● PAUSED — not watching"
            tvMonitoringStatus.setTextColor(getColor(R.color.spark_yellow))
            btnMonitoringToggle.text = "▶  Resume Monitoring"
            btnMonitoringToggle.setBackgroundColor(getColor(R.color.spark_green))
        }
    }

    private fun sendMonitoringBroadcast(enabled: Boolean) {
        val intent = Intent(SparkAccessibilityService.ACTION_SET_MONITORING).apply {
            putExtra(SparkAccessibilityService.EXTRA_MONITORING_ENABLED, enabled)
            setPackage(packageName)
        }
        sendBroadcast(intent)
    }
}
