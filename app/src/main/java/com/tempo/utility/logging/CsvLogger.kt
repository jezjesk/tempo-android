package com.tempo.utility.logging

import android.content.Context
import com.tempo.utility.service.OfferDetails
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * CsvLogger — writes one row per offer to a timestamped CSV file per app session.
 *
 * File name: spark_offers_<sessionTimestamp>.csv
 * Location:  /storage/emulated/0/Android/data/com.tempo.utility/files/
 *
 * A single persistent file is used across all sessions. The file is only created
 * (with header) if it does not already exist; subsequent app starts append to it.
 *
 * Columns (19):
 *   Timestamp, EstimatedTotal, DeliveryPay, ExtraEarnings, TipPay,
 *   DistanceMi, TimeMin,
 *   RealMin   = TimeMin + DistanceMi×2
 *   CAMin     = 16.9×1.2/60×TimeMin + 0.3×DistanceMi  (CA Prop 22)
 *   SparkPay  = MAX(CAMin, EstimatedTotal−TipPay)
 *   TotalPay  = SparkPay + TipPay
 *   PayHourly = (TotalPay/TimeMin)×60
 *   TipHourly = (TipPay/TimeMin)×60
 *   DollarsPerMile = TotalPay/DistanceMi
 *   PickupType, OfferType, PickupStore,
 *   CriteriaResult (PASS|FAIL), ActionResult (PENDING→ACCEPTED|REJECTED|ACCEPT_UNAVAILABLE|ACCEPT_EXPIRED|ACCEPT_TIMEOUT|REJECT_EXPIRED|REJECT_TIMEOUT)
 */
object CsvLogger {

    private const val TAG = "CsvLogger"

    private val HEADER = "Timestamp,EstimatedTotal,DeliveryPay,ExtraEarnings,TipPay," +
          "DistanceMi,TimeMin,RealMin,CAMin,SparkPay,TotalPay," +
          "PayHourly,TipHourly,DollarsPerMile," +
          "PickupType,OfferType,PickupStore,CriteriaResult,ActionResult\n"

    private val ts = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)

    private var csvFile: File? = null

    /**
     * Initialise for this app session.
     *
     * @param sessionTimestamp  Timestamp suffix shared with SparkLogger, e.g.
     *                          "2026-03-25_09-53-29".
     */
    fun initialize(context: Context) {
        val dir  = context.getExternalFilesDir(null) ?: context.filesDir
        val file = File(dir, "spark_offers.csv")
        if (!file.exists()) {
            file.writeText(HEADER)
            SparkLogger.i(TAG, "Created new CSV at ${file.absolutePath}")
        } else {
            SparkLogger.i(TAG, "Appending to existing CSV at ${file.absolutePath}")
        }
        csvFile = file
    }

    fun append(details: OfferDetails, criteriaResult: String, actionResult: String = "PENDING") {
        val file = csvFile ?: run {
          SparkLogger.e(TAG, "append: CsvLogger not initialized")
          return
        }
        val row = buildString {
          append(ts.format(Date())); append(",")
          append(details.estimatedPayDollars?.let { String.format("%.2f", it) } ?: ""); append(",")
          append(details.deliveryPay?.let  { String.format("%.2f", it) } ?: ""); append(",")
          append(String.format("%.2f", details.extraEarnings ?: 0.0)); append(",")
          append(String.format("%.2f", details.tipPay)); append(",")
          append(details.distanceMiles?.let { String.format("%.2f", it) } ?: ""); append(",")
          append(String.format("%.1f", details.timeMinutes)); append(",")
          append(String.format("%.2f", details.realMin)); append(",")
          append(String.format("%.2f", details.caMin)); append(",")
          append(String.format("%.2f", details.sparkPay)); append(",")
          append(String.format("%.2f", details.totalPay)); append(",")
          append(String.format("%.2f", details.payHourly)); append(",")
          append(String.format("%.2f", details.tipHourly)); append(",")
          append(String.format("%.2f", details.dollarsPerMile)); append(",")
          append(csvQuote(details.pickupType ?: "")); append(",")
          append(details.offerType); append(",")
          append(csvQuote(details.pickupStore ?: "")); append(",")
          append(criteriaResult); append(",")
          append(actionResult)
          append("\n")
        }
        try {
          file.appendText(row)
          SparkLogger.i(TAG, "Recorded offer → $row".trimEnd())
        } catch (e: Exception) {
          SparkLogger.e(TAG, "append: failed to write row", e)
        }
    }

    /**
       * Updates the ActionResult field of the most recently written CSV row.
       * Called once the accept/reject outcome is confirmed so the row reflects
       * what actually happened rather than the initial PENDING placeholder.

    /**
     * Records a home-screen pre-rejected offer — one rejected before opening the detail
     * screen, so tip/store/type are unknown.  Blank CSV cells mark unknown fields.
     * Sets ActionResult to [actionResult] (defaults to "PENDING" so the subsequent
     * REJECTING-state cleanup can overwrite it with "REJECTED").
     */
    fun appendHomeCard(
        total: Double,
        distMi: Double?,
        timMin: Double,
        criteriaResult: String,
        actionResult: String = "PENDING"
    ) {
        val file = csvFile ?: run {
            SparkLogger.e(TAG, "appendHomeCard: CsvLogger not initialized")
            return
        }
        val miles     = distMi ?: 0.0
        val realMin   = timMin + miles * 2.0
        val caMin     = (16.9 * 1.2 / 60.0) * timMin + (0.3 * miles)
        val sparkPay  = maxOf(caMin, total)
        val payHourly = if (realMin > 0) (total / realMin) * 60.0 else 0.0
        val perMile   = if (miles > 0) total / miles else 0.0
        val row = buildString {
            append(ts.format(Date())); append(",")
            append(String.format("%.2f", total)); append(",")
            append(""); append(",")   // DeliveryPay — unknown
            append(""); append(",")   // ExtraEarnings — unknown
            append(""); append(",")   // TipPay — unknown
            append(distMi?.let { String.format("%.2f", it) } ?: ""); append(",")
            append(String.format("%.1f", timMin)); append(",")
            append(String.format("%.2f", realMin)); append(",")
            append(String.format("%.2f", caMin)); append(",")
            append(String.format("%.2f", sparkPay)); append(",")
            append(String.format("%.2f", total)); append(",")   // TotalPay ≈ total (no tip data)
            append(String.format("%.2f", payHourly)); append(",")
            append(""); append(",")   // TipHourly — unknown
            append(String.format("%.2f", perMile)); append(",")
            append(""); append(",")   // PickupType — unknown
            append(""); append(",")   // OfferType — unknown
            append(""); append(",")   // PickupStore — unknown
            append(criteriaResult); append(",")
            append(actionResult)
            append("\n")
        }
        try {
            file.appendText(row)
            SparkLogger.i(TAG, "Recorded home-card offer → $row".trimEnd())
        } catch (e: Exception) {
            SparkLogger.e(TAG, "appendHomeCard: failed to write row", e)
        }
    }
       */
    fun updateLastActionResult(result: String) {
        val file = csvFile ?: return
        try {
            val content = file.readText()
            // Trim trailing newlines, find the last row
            val trimmed   = content.trimEnd('\n')
            val lastNl    = trimmed.lastIndexOf('\n')
            if (lastNl < 0) return
            val lastRow   = trimmed.substring(lastNl + 1)
            if (lastRow.startsWith("Timestamp")) return  // header only — no data yet
            // Replace everything after the last comma (the ActionResult field)
            val lastComma = lastRow.lastIndexOf(',')
            if (lastComma < 0) return
            val updatedRow  = lastRow.substring(0, lastComma + 1) + result
            val newContent  = trimmed.substring(0, lastNl + 1) + updatedRow + "\n"
            file.writeText(newContent)
            SparkLogger.i(TAG, "updateLastActionResult: ActionResult=$result")
        } catch (e: Exception) {
            SparkLogger.e(TAG, "updateLastActionResult: failed", e)
        }
      }

    fun getFile(): File? = csvFile

    private fun csvQuote(value: String): String {
        if (value.isEmpty()) return value
        return if (value.contains(',') || value.contains('"') || value.contains('\n')) {
          "\"${value.replace("\"", "\"\"")}\""
        } else {
          value
        }
    }
}
